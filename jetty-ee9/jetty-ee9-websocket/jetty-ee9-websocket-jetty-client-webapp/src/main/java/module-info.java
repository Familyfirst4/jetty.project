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

import org.eclipse.jetty.ee9.websocket.client.config.JettyWebSocketClientConfiguration;

module org.eclipse.jetty.ee9.websocket.jetty.client.webapp
{
    requires org.slf4j;
    requires transitive org.eclipse.jetty.ee9.webapp;

    exports org.eclipse.jetty.ee9.websocket.client.config;

    provides org.eclipse.jetty.ee9.webapp.Configuration with JettyWebSocketClientConfiguration;
}
