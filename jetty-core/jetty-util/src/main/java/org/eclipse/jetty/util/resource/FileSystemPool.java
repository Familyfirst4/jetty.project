//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO figure out if this should be a LifeCycle or not, how many instances of this class can reside in a JVM, who can call sweep and when.
 */
@ManagedObject("Pool of FileSystems used to mount Resources")
public class FileSystemPool implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(FileSystemPool.class);
    public static final FileSystemPool INSTANCE = new FileSystemPool();

    private static final Map<String, String> ENV_MULTIRELEASE_RUNTIME;

    static
    {
        Map<String, String> env = new HashMap<>();
        // Key and Value documented at https://docs.oracle.com/en/java/javase/17/docs/api/jdk.zipfs/module-summary.html
        env.put("releaseVersion", "runtime");
        ENV_MULTIRELEASE_RUNTIME = env;
    }

    private final Map<URI, Bucket> pool = new HashMap<>();
    private final AutoLock poolLock = new AutoLock();

    private FileSystemPool()
    {
    }

    public Resource.Mount mount(URI uri) throws IOException
    {
        if (!uri.isAbsolute())
            throw new IllegalArgumentException("not an absolute uri: " + uri);
        if (PathResource.ALLOWED_SCHEMES.contains(uri.getScheme()))
            throw new IllegalArgumentException("not an allowed scheme: " + uri);

        FileSystem fileSystem = null;
        try (AutoLock ignore = poolLock.lock())
        {
            try
            {
                fileSystem = FileSystems.newFileSystem(uri, ENV_MULTIRELEASE_RUNTIME);
                if (LOG.isDebugEnabled())
                    LOG.debug("Mounted new FS {}", uri);
            }
            catch (FileSystemAlreadyExistsException fsaee)
            {
                fileSystem = Paths.get(uri).getFileSystem();
                if (LOG.isDebugEnabled())
                    LOG.debug("Using existing FS {}", uri);
            }
            catch (ProviderNotFoundException pnfe)
            {
                throw new IllegalArgumentException("Unable to mount FileSystem from unsupported URI: " + uri, pnfe);
            }
            // use root FS URI so that pool key/release/sweep is sane
            URI rootURI = fileSystem.getPath("/").toUri();
            Mount mount = new Mount(rootURI, Resource.newResource(uri));
            retain(rootURI, fileSystem, mount);
            return mount;
        }
        catch (Exception e)
        {
            IO.close(fileSystem);
            throw e;
        }
    }

    private void release(URI fsUri)
    {
        try (AutoLock ignore = poolLock.lock())
        {
            // use base URI so that pool key/release/sweep is sane
            Bucket bucket = pool.get(fsUri);
            if (bucket == null)
            {
                LOG.warn("Unable to release Mount (not in pool): {}", fsUri);
                return;
            }

            int count = bucket.counter.decrementAndGet();
            if (count == 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Ref counter reached 0, closing pooled FS {}", bucket);
                IO.close(bucket.fileSystem);
                pool.remove(fsUri);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Decremented ref counter to {} for FS {}", count, bucket);
            }
        }
        catch (FileSystemNotFoundException fsnfe)
        {
            // The FS has already been released by a sweep.
        }
    }

    @ManagedAttribute("The mounted FileSystems")
    public Collection<Resource.Mount> mounts()
    {
        try (AutoLock ignore = poolLock.lock())
        {
            return pool.values().stream().map(m -> m.mount).toList();
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            new DumpableCollection("mounts", mounts()));
    }

    @ManagedOperation(value = "Sweep the pool for deleted mount points", impact = "ACTION")
    public void sweep()
    {
        Set<Map.Entry<URI, Bucket>> entries;
        try (AutoLock ignore = poolLock.lock())
        {
            entries = pool.entrySet();
        }

        for (Map.Entry<URI, Bucket> entry : entries)
        {
            URI fsUri = entry.getKey();
            Bucket bucket = entry.getValue();
            FileSystem fileSystem = bucket.fileSystem;

            if (bucket.path == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Filesystem {} not backed by a file", bucket);
                return;
            }

            try (AutoLock ignore = poolLock.lock())
            {
                // We must check if the FS is still open under the lock as a concurrent thread may have closed it.
                if (fileSystem.isOpen() &&
                    !Files.isReadable(bucket.path) ||
                    !Files.getLastModifiedTime(bucket.path).equals(bucket.lastModifiedTime) ||
                    Files.size(bucket.path) != bucket.size)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("File {} backing filesystem {} has been removed or changed, closing it", bucket.path, fileSystem);
                    IO.close(fileSystem);
                    pool.remove(fsUri);
                }
            }
            catch (IOException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Cannot read last access time or size of file {} backing filesystem {}", bucket.path, fileSystem);
            }
        }
    }

    private void retain(URI fsUri, FileSystem fileSystem, Resource.Mount mount)
    {
        assert poolLock.isHeldByCurrentThread();

        Bucket bucket = pool.get(fsUri);
        if (bucket == null)
        {
            LOG.debug("Pooling new FS {}", fileSystem);
            bucket = new Bucket(fsUri, fileSystem, mount);
            pool.put(fsUri, bucket);
        }
        else
        {
            int count = bucket.counter.incrementAndGet();
            LOG.debug("Incremented ref counter to {} for FS {}", count, fileSystem);
        }
    }

    private static class Bucket
    {
        private final AtomicInteger counter;
        private final FileSystem fileSystem;
        private final FileTime lastModifiedTime;
        private final long size;
        private final Path path;
        private final Resource.Mount mount;

        private Bucket(URI fsUri, FileSystem fileSystem, Resource.Mount mount)
        {
            URI containerUri = Resource.unwrapContainer(fsUri);
            Path path = Paths.get(containerUri);

            long size = -1L;
            FileTime lastModifiedTime = null;
            try
            {
                size = Files.size(path);
                lastModifiedTime = Files.getLastModifiedTime(path);
            }
            catch (IOException ioe)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Cannot read size or last modified time from {} backing filesystem at {}", path, fsUri);
            }

            this.counter = new AtomicInteger(1);
            this.fileSystem = fileSystem;
            this.path = path;
            this.size = size;
            this.lastModifiedTime = lastModifiedTime;
            this.mount = mount;
        }

        @Override
        public String toString()
        {
            return fileSystem.toString();
        }
    }

    private static class Mount implements Resource.Mount
    {
        private final URI fsUri;
        private final Resource root;

        private Mount(URI fsUri, Resource resource)
        {
            this.fsUri = fsUri;
            this.root = resource;
        }

        public Resource root()
        {
            return root;
        }

        @Override
        public void close() throws IOException
        {
            FileSystemPool.INSTANCE.release(fsUri);
        }

        @Override
        public String toString()
        {
            return String.format("%s[uri=%s,root=%s]", getClass().getSimpleName(), fsUri, root);
        }
    }
}