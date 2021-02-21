//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http.client;

public enum Transport
{
    HTTP, HTTPS, H2C, H2, FCGI, UNIX_SOCKET;

    public boolean isHttp1Based()
    {
        return this == HTTP || this == HTTPS;
    }

    public boolean isHttp2Based()
    {
        return this == H2C || this == H2;
    }

    public boolean isTlsBased()
    {
        return this == HTTPS || this == H2;
    }
}
