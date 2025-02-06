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

package org.eclipse.jetty.websocket.core;

import java.util.List;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

public class TestWebSocketNegotiator extends WebSocketNegotiator.AbstractNegotiator
{
    private final FrameHandler frameHandler;

    public TestWebSocketNegotiator(FrameHandler frameHandler)
    {
        this(frameHandler, null);
    }

    public TestWebSocketNegotiator(FrameHandler frameHandler, Configuration.Customizer customizer)
    {
        super(customizer);
        this.frameHandler = frameHandler;
    }

    @Override
    public FrameHandler negotiate(ServerUpgradeRequest request, ServerUpgradeResponse response, Callback callback)
    {
        List<String> offeredSubprotocols = request.getSubProtocols();
        if (!offeredSubprotocols.isEmpty())
            response.setAcceptedSubProtocol(offeredSubprotocols.get(0));
        return frameHandler;
    }
}
