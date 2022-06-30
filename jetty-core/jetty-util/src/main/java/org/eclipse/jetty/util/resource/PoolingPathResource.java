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

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java NIO Path Resource with file system pooling. {@link FileSystem} implementations that must be closed
 * must use this class, for instance the one handling the `jar` scheme.
 */
public class PoolingPathResource extends PathResource
{
    private static final Logger LOG = LoggerFactory.getLogger(PoolingPathResource.class);

    private static final Map<FileSystem, Metadata> POOL = new HashMap<>();
    private static final AutoLock POOL_LOCK = new AutoLock();

    PoolingPathResource(URI uri) throws IOException
    {
        super(uri, true);
    }

    public static Mount mount(URI uri) throws IOException
    {
        if (!uri.isAbsolute())
            throw new IllegalArgumentException("not an absolute uri: " + uri);
        if (PathResource.ALLOWED_SCHEMES.contains(uri.getScheme()))
            throw new IllegalArgumentException("not an allowed scheme: " + uri);

        try (AutoLock ignore = POOL_LOCK.lock())
        {
            FileSystem fileSystem;
            try
            {
                fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
            }
            catch (FileSystemAlreadyExistsException fsaee)
            {
                fileSystem = Paths.get(uri).getFileSystem();
            }
            return retain(fileSystem, uri).mount;
        }
    }

    public static Collection<Mount> mounts()
    {
        try (AutoLock ignore = POOL_LOCK.lock())
        {
            return POOL.values().stream().map(m -> m.mount).toList();
        }
    }

    public static void sweep()
    {
        Set<Map.Entry<FileSystem, Metadata>> entries;
        try (AutoLock ignore = POOL_LOCK.lock())
        {
            entries = POOL.entrySet();
        }

        for (Map.Entry<FileSystem, Metadata> entry : entries)
        {
            FileSystem fileSystem = entry.getKey();
            Metadata metadata = entry.getValue();

            if (metadata.path == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Filesystem {} not backed by a file", fileSystem);
                return;
            }

            try (AutoLock ignore = POOL_LOCK.lock())
            {
                // We must check if the FS is still open under the lock as a concurrent thread may have closed it.
                if (fileSystem.isOpen() &&
                    !Files.isReadable(metadata.path) ||
                    !Files.getLastModifiedTime(metadata.path).equals(metadata.lastModifiedTime) ||
                    Files.size(metadata.path) != metadata.size)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("File {} backing filesystem {} has been removed or changed, closing it", metadata.path, fileSystem);
                    POOL.remove(fileSystem);
                    IO.close(fileSystem);
                }
            }
            catch (IOException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Cannot read last access time or size of file {} backing filesystem {}", metadata.path, fileSystem);
            }
        }
    }

    private static Metadata retain(FileSystem fileSystem, URI uri)
    {
        assert POOL_LOCK.isHeldByCurrentThread();

        Metadata metadata = POOL.get(fileSystem);
        if (metadata == null)
        {
            LOG.debug("Pooling new FS {}", fileSystem);
            metadata = new Metadata(uri);
            POOL.put(fileSystem, metadata);
        }
        else
        {
            int count = metadata.counter.incrementAndGet();
            LOG.debug("Incremented ref counter to {} for FS {}", count, fileSystem);
        }
        return metadata;
    }

    private static void release(FileSystem fileSystem)
    {
        assert POOL_LOCK.isHeldByCurrentThread();

        Metadata metadata = POOL.get(fileSystem);
        int count = metadata.counter.decrementAndGet();
        if (count == 0)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ref counter reached 0, closing pooled FS {}", fileSystem);
            IO.close(fileSystem);
            POOL.remove(fileSystem);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Decremented ref counter to {} for FS {}", count, fileSystem);
        }
    }

    private static class Metadata
    {
        private final AtomicInteger counter;
        private final FileTime lastModifiedTime;
        private final long size;
        private final Path path;
        private final Mount mount;

        private Metadata(URI uri)
        {
            Path path = uriToPath(uri);

            long size = -1L;
            FileTime lastModifiedTime = null;
            if (path != null)
            {
                try
                {
                    size = Files.size(path);
                    lastModifiedTime = Files.getLastModifiedTime(path);
                }
                catch (IOException ioe)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Cannot read size or last modified time from {} backing filesystem at {}", path, uri);
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Filesystem at {} is not backed by a file", uri);
            }
            this.counter = new AtomicInteger(1);
            this.path = path;
            this.size = size;
            this.lastModifiedTime = lastModifiedTime;
            this.mount = new Mount(uri);
        }

        private static Path uriToPath(URI uri)
        {
            String scheme = uri.getScheme();
            if ((scheme == null) || !scheme.equalsIgnoreCase("jar"))
                return null;

            String spec = uri.getRawSchemeSpecificPart();
            int sep = spec.indexOf("!/");
            if (sep != -1)
                spec = spec.substring(0, sep);
            return Paths.get(URI.create(spec)).toAbsolutePath();
        }
    }

    public static class Mount implements Closeable
    {
        private final URI uri;

        private Mount(URI uri)
        {
            this.uri = uri;
        }

        public Resource newResource() throws IOException
        {
            return Resource.newResource(uri);
        }

        @Override
        public void close() throws IOException
        {
            try (AutoLock ignore = POOL_LOCK.lock())
            {
                FileSystem fileSystem = Paths.get(uri).getFileSystem();
                release(fileSystem);
            }
            catch (FileSystemNotFoundException fsnfe)
            {
                // The FS has already been released by a sweep.
            }
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + " uri=" + uri;
        }
    }
}
