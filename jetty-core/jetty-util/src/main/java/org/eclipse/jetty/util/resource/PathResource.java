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
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java NIO Path Resource.
 */
public class PathResource extends Resource
{
    private static final Logger LOG = LoggerFactory.getLogger(PathResource.class);
    private static final LinkOption[] NO_FOLLOW_LINKS = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
    private static final LinkOption[] FOLLOW_LINKS = new LinkOption[]{};

    public static Set<String> ALLOWED_SCHEMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("file", "jrt")));

    private final Path path;
    private final Path alias;
    private final URI uri;

    private Path checkAliasPath()
    {
        Path abs = path;

        /* Catch situation where the Path class has already normalized
         * the URI eg. input path "aa./foo.txt"
         * from an #addPath(String) is normalized away during
         * the creation of a Path object reference.
         * If the URI is different then the Path.toUri() then
         * we will just use the original URI to construct the
         * alias reference Path.
         */
        if (!URIUtil.equalsIgnoreEncodings(uri, path.toUri()))
        {
            try
            {
                return Paths.get(uri).toRealPath(FOLLOW_LINKS);
            }
            catch (IOException ignored)
            {
                // If the toRealPath() call fails, then let
                // the alias checking routines continue on
                // to other techniques.
                LOG.trace("IGNORED", ignored);
            }
        }

        if (!abs.isAbsolute())
            abs = path.toAbsolutePath();

        // Any normalization difference means it's an alias,
        // and we don't want to bother further to follow
        // symlinks as it's an alias anyway.
        Path normal = path.normalize();
        if (!isSameName(abs, normal))
            return normal;

        try
        {
            if (Files.isSymbolicLink(path))
                return path.getParent().resolve(Files.readSymbolicLink(path));
            if (Files.exists(path))
            {
                Path real = abs.toRealPath(FOLLOW_LINKS);
                if (!isSameName(abs, real))
                    return real;
            }
        }
        catch (IOException e)
        {
            LOG.trace("IGNORED", e);
        }
        catch (Exception e)
        {
            LOG.warn("bad alias ({} {}) for {}", e.getClass().getName(), e.getMessage(), path);
        }
        return null;
    }

    /**
     * Test if the paths are the same name.
     *
     * <p>
     * If the real path is not the same as the absolute path
     * then we know that the real path is the alias for the
     * provided path.
     * </p>
     *
     * <p>
     * For OS's that are case insensitive, this should
     * return the real (on-disk / case correct) version
     * of the path.
     * </p>
     *
     * <p>
     * We have to be careful on Windows and OSX.
     * </p>
     *
     * <p>
     * Assume we have the following scenario:
     * </p>
     *
     * <pre>
     *   Path a = new File("foo").toPath();
     *   Files.createFile(a);
     *   Path b = new File("FOO").toPath();
     * </pre>
     *
     * <p>
     * There now exists a file called {@code foo} on disk.
     * Using Windows or OSX, with a Path reference of
     * {@code FOO}, {@code Foo}, {@code fOO}, etc.. means the following
     * </p>
     *
     * <pre>
     *                        |  OSX    |  Windows   |  Linux
     * -----------------------+---------+------------+---------
     * Files.exists(a)        |  True   |  True      |  True
     * Files.exists(b)        |  True   |  True      |  False
     * Files.isSameFile(a,b)  |  True   |  True      |  False
     * a.equals(b)            |  False  |  True      |  False
     * </pre>
     *
     * <p>
     * See the javadoc for Path.equals() for details about this FileSystem
     * behavior difference
     * </p>
     *
     * <p>
     * We also cannot rely on a.compareTo(b) as this is roughly equivalent
     * in implementation to a.equals(b)
     * </p>
     */
    public static boolean isSameName(Path pathA, Path pathB)
    {
        int aCount = pathA.getNameCount();
        int bCount = pathB.getNameCount();
        if (aCount != bCount)
        {
            // different number of segments
            return false;
        }

        // compare each segment of path, backwards
        for (int i = bCount; i-- > 0; )
        {
            if (!pathA.getName(i).toString().equals(pathB.getName(i).toString()))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Construct a new PathResource from a URI object.
     * <p>
     * Must be an absolute URI using the <code>file</code> scheme.
     *
     * @param uri the URI to build this PathResource from.
     * @throws IOException if unable to construct the PathResource from the URI.
     */
    PathResource(URI uri) throws IOException
    {
        this(uri, false);
    }

    PathResource(URI uri, boolean bypassAllowedSchemeCheck) throws IOException
    {
        if (!uri.isAbsolute())
            throw new IllegalArgumentException("not an absolute uri: " + uri);
        if (!bypassAllowedSchemeCheck && !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("not an allowed scheme: " + uri);

        Path path;
        try
        {
            path = Paths.get(uri);
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
            throw new IOException("Unable to build Path from: " + uri, e);
        }

        this.path = path.toAbsolutePath();
        this.uri = uri;
        this.alias = checkAliasPath();
    }

    @Override
    public boolean isSame(Resource resource)
    {
        try
        {
            if (resource instanceof PathResource)
            {
                Path path = resource.getPath();
                return Files.isSameFile(getPath(), path);
            }
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ignored", e);
        }
        return false;
    }

    @Override
    public void close()
    {
    }

    @Override
    public boolean delete() throws SecurityException
    {
        try
        {
            return Files.deleteIfExists(path);
        }
        catch (IOException e)
        {
            LOG.trace("IGNORED", e);
            return false;
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        PathResource other = (PathResource)obj;
        if (path == null)
        {
            if (other.path != null)
            {
                return false;
            }
        }
        else if (!path.equals(other.path))
        {
            return false;
        }
        return true;
    }

    @Override
    public boolean exists()
    {
        return Files.exists(path, NO_FOLLOW_LINKS);
    }

    /**
     * @return the {@link Path} of the resource
     */
    public Path getPath()
    {
        return path;
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        return Files.newInputStream(path, StandardOpenOption.READ);
    }

    @Override
    public String getName()
    {
        return path.toAbsolutePath().toString();
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return newSeekableByteChannel();
    }

    public SeekableByteChannel newSeekableByteChannel() throws IOException
    {
        return Files.newByteChannel(path, StandardOpenOption.READ);
    }

    @Override
    public URI getURI()
    {
        return this.uri;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((path == null) ? 0 : path.hashCode());
        return result;
    }

    @Override
    public boolean isContainedIn(Resource r)
    {
        try
        {
            PathResource pr = PathResource.class.cast(r);
            return (path.startsWith(pr.getPath()));
        }
        catch (ClassCastException e)
        {
            return false;
        }
    }

    @Override
    public boolean isDirectory()
    {
        return Files.isDirectory(path, FOLLOW_LINKS);
    }

    @Override
    public long lastModified()
    {
        try
        {
            FileTime ft = Files.getLastModifiedTime(path, FOLLOW_LINKS);
            return ft.toMillis();
        }
        catch (IOException e)
        {
            LOG.trace("IGNORED", e);
            return 0;
        }
    }

    @Override
    public long length()
    {
        try
        {
            return Files.size(path);
        }
        catch (IOException e)
        {
            // in case of error, use File.length logic of 0L
            return 0L;
        }
    }

    @Override
    public boolean isAlias()
    {
        return this.alias != null;
    }

    /**
     * The Alias as a Path.
     * <p>
     * Note: this cannot return the alias as a DIFFERENT path in 100% of situations,
     * due to Java's internal Path/File normalization.
     * </p>
     *
     * @return the alias as a path.
     */
    public Path getAliasPath()
    {
        return this.alias;
    }

    @Override
    public URI getAlias()
    {
        return this.alias == null ? null : this.alias.toUri();
    }

    @Override
    public String[] list()
    {
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(path))
        {
            List<String> entries = new ArrayList<>();
            for (Path entry : dir)
            {
                String name = entry.getFileName().toString();

                if (Files.isDirectory(entry))
                {
                    name += "/";
                }

                entries.add(name);
            }
            int size = entries.size();
            return entries.toArray(new String[size]);
        }
        catch (DirectoryIteratorException e)
        {
            LOG.debug("Directory list failure", e);
        }
        catch (IOException e)
        {
            LOG.debug("Directory list access failure", e);
        }
        return null;
    }

    @Override
    public boolean renameTo(Resource dest) throws SecurityException
    {
        if (dest instanceof PathResource)
        {
            PathResource destRes = (PathResource)dest;
            try
            {
                Path result = Files.move(path, destRes.path);
                return Files.exists(result, NO_FOLLOW_LINKS);
            }
            catch (IOException e)
            {
                LOG.trace("IGNORED", e);
                return false;
            }
        }
        else
        {
            return false;
        }
    }

    @Override
    public void copyTo(Path destination) throws IOException
    {
        if (isDirectory())
            Files.walkFileTree(this.path, new TreeCopyFileVisitor(destination));
        else
            Files.copy(this.path, destination);
    }

    @Override
    public String toString()
    {
        return this.uri.toASCIIString();
    }

    private static class TreeCopyFileVisitor extends SimpleFileVisitor<Path>
    {
        private final Path target;

        public TreeCopyFileVisitor(Path target)
        {
            this.target = target;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
        {
            Path resolve = target.resolve(dir);
            if (Files.notExists(resolve))
                Files.createDirectories(resolve);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
        {
            Path resolvedTarget = target.resolve(file);
            Files.copy(file, resolvedTarget, StandardCopyOption.REPLACE_EXISTING);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc)
        {
            return FileVisitResult.CONTINUE;
        }
    }
}
