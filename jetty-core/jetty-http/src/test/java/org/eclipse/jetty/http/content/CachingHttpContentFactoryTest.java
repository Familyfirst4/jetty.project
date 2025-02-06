//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http.content;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class CachingHttpContentFactoryTest
{
    public WorkDir workDir;
    private ArrayByteBufferPool.Tracking trackingPool;
    private ByteBufferPool.Sized sizedPool;

    @BeforeEach
    public void setUp()
    {
        trackingPool = new ArrayByteBufferPool.Tracking();
        sizedPool = new ByteBufferPool.Sized(trackingPool);
    }

    @AfterEach
    public void tearDown()
    {
        assertThat("Leaks: " + trackingPool.dumpLeaks(), trackingPool.getLeaks().size(), is(0));
    }

    @Test
    public void testCannotRetainEvictedContent() throws Exception
    {
        Path file = Files.writeString(workDir.getEmptyPathDir().resolve("file.txt"), "0123456789abcdefghijABCDEFGHIJ");
        ResourceHttpContentFactory resourceHttpContentFactory = new ResourceHttpContentFactory(ResourceFactory.root().newResource(file.getParent()), MimeTypes.DEFAULTS);
        CachingHttpContentFactory cachingHttpContentFactory = new CachingHttpContentFactory(resourceHttpContentFactory, sizedPool);

        CachingHttpContentFactory.CachingHttpContent content = (CachingHttpContentFactory.CachingHttpContent)cachingHttpContentFactory.getContent("file.txt");

        // Empty the cache so 'content' gets released.
        cachingHttpContentFactory.flushCache();

        assertFalse(content.retain());

        // Even if the content cannot be retained, whatever we got from cachingHttpContentFactory must be released.
        content.release();
    }

    @Test
    public void testRetainThenEvictContent() throws Exception
    {
        Path file = Files.writeString(workDir.getEmptyPathDir().resolve("file.txt"), "0123456789abcdefghijABCDEFGHIJ");
        ResourceHttpContentFactory resourceHttpContentFactory = new ResourceHttpContentFactory(ResourceFactory.root().newResource(file.getParent()), MimeTypes.DEFAULTS);
        CachingHttpContentFactory cachingHttpContentFactory = new CachingHttpContentFactory(resourceHttpContentFactory, sizedPool);

        CachingHttpContentFactory.CachingHttpContent content = (CachingHttpContentFactory.CachingHttpContent)cachingHttpContentFactory.getContent("file.txt");

        assertTrue(content.retain());

        // Empty the cache so 'content' gets released.
        cachingHttpContentFactory.flushCache();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BufferUtil.writeTo(content.getByteBuffer(), baos);
        assertThat(baos.toString(StandardCharsets.UTF_8), is("0123456789abcdefghijABCDEFGHIJ"));
        // Pair the explicit retain that succeeded.
        content.release();

        // Even if the content cannot be retained, whatever we got from cachingHttpContentFactory must be released.
        content.release();
    }
}
