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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;

public class ChannelTest
{
    Server server;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        server = new Server();
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testSimpleGET() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        server.setHandler(helloHandler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel);
        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));
    }

    @Test
    public void testAsyncGET() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        server.setHandler(helloHandler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);
        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        AtomicReference<Callback> sendCB = new AtomicReference<>();
        MockStream stream = new MockStream(channel)
        {
            @Override
            public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
            {
                sendCB.set(callback);
                super.send(response, last, Callback.NOOP, content);
            }
        };
        Runnable task = channel.onRequest(request, stream);
        task.run();
        assertThat(stream.isComplete(), is(false));

        sendCB.getAndSet(null).succeeded(); // succeed the original send
        sendCB.getAndSet(null).succeeded(); // succeed the completion send
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));
    }

    @Test
    public void testEchoPOST() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        server.setHandler(echoHandler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);

        String message = "ECHO Echo echo";
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel, body);

        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));
    }

    @Test
    public void testMultiEchoPOST() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        server.setHandler(echoHandler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);

        String[] parts = new String[] {"ECHO ", "Echo ", "echo"};
        String message = String.join("", parts);
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel, false)
        {
            int i = 0;
            @Override
            public Content readContent()
            {
                if (i < parts.length)
                    return Content.from(BufferUtil.toBuffer(parts[i++]), false);
                return Content.EOF;
            }
        };

        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));
    }

    @Test
    public void testAsyncEchoPOST() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        server.setHandler(echoHandler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);

        String[] parts = new String[] {"ECHO ", "Echo ", "echo"};
        String message = String.join("", parts);
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        AtomicReference<Callback> sendCB = new AtomicReference<>();
        MockStream stream = new MockStream(channel, false)
        {
            @Override
            public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
            {
                sendCB.set(callback);
                super.send(response, last, Callback.NOOP, content);
            }
        };

        Runnable task = channel.onRequest(request, stream);
        assertThat(stream.isComplete(), is(false));
        assertThat(stream.isDemanding(), is(false));
        assertThat(sendCB.get(), nullValue());

        task.run();
        assertThat(stream.isComplete(), is(false));
        assertThat(stream.isDemanding(), is(true));
        assertThat(sendCB.get(), nullValue());

        for (int i = 0; i < parts.length; i++)
        {
            String part = parts[i];
            boolean last = i == (parts.length - 1);
            task = stream.addContent(BufferUtil.toBuffer(part), last);
            assertThat(task, notNullValue());

            assertThat(stream.isComplete(), is(false));
            assertThat(stream.isDemanding(), is(false));

            task.run();
            assertThat(stream.isComplete(), is(false));
            assertThat(stream.isDemanding(), is(false));

            Callback callback = sendCB.getAndSet(null);
            assertThat(callback, notNullValue());

            callback.succeeded();
            assertThat(stream.isComplete(), is(false));
            assertThat(stream.isDemanding(), is(!last));
        }

        Callback callback = sendCB.getAndSet(null);
        assertThat(callback, notNullValue());
        callback.succeeded();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));
    }

    @Test
    public void testNoop() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response)
            {
                return false;
            }
        };
        server.setHandler(handler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);
        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel);
        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(404));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), nullValue());
        assertThat(stream.getResponse().getFields().getLongField(HttpHeader.CONTENT_LENGTH), equalTo(0L));
    }

    @Test
    public void testThrow() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response)
            {
                throw new UnsupportedOperationException("testing");
            }
        };
        server.setHandler(handler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);
        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel);
        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(500));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), nullValue());
        assertThat(stream.getResponse().getFields().getLongField(HttpHeader.CONTENT_LENGTH), equalTo(0L));
    }

    @Test
    public void testThrowCommitted() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response)
            {
                response.setStatus(200);
                response.setContentLength(10);
                response.write(false, Callback.from(() ->
                {
                    throw new UnsupportedOperationException("testing");
                }));
                return true;
            }
        };
        server.setHandler(handler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);
        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel);
        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
    }

    @Test
    public void testAutoContentLength() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response)
            {
                response.write(true, request, BufferUtil.toBuffer("12345"));
                return true;
            }
        };
        server.setHandler(handler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);
        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel);
        Runnable task = channel.onRequest(request, stream);

        task.run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().getLongField(HttpHeader.CONTENT_LENGTH), equalTo(5L));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo("12345"));
    }

    @Test
    public void testInsufficientContentWritten1() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response)
            {
                response.setContentLength(10);
                response.write(true, request, BufferUtil.toBuffer("12345"));
                return true;
            }
        };
        server.setHandler(handler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);
        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel);
        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("content-length 10 != 5"));
        assertThat(stream.getResponse(), nullValue());
    }

    @Test
    public void testInsufficientContentWritten2() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response)
            {
                response.setContentLength(10);
                response.write(false, Callback.from(() -> response.write(true, request)), BufferUtil.toBuffer("12345"));
                return true;
            }
        };
        server.setHandler(handler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);
        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel);
        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("content-length 10 > 5"));
        assertThat(stream.getResponse(), notNullValue());
    }

    @Test
    public void testExcessContentWritten1() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response)
            {
                response.setContentLength(5);
                response.write(true, request, BufferUtil.toBuffer("1234567890"));
                return true;
            }
        };
        server.setHandler(handler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);
        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel);
        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("content-length 5 != 10"));
        assertThat(stream.getResponse(), nullValue());
    }

    @Test
    public void testExcessContentWritten2() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response)
            {
                response.setContentLength(5);
                response.write(false, Callback.from(() -> response.write(true, request, BufferUtil.toBuffer("567890"))), BufferUtil.toBuffer("1234"));
                return true;
            }
        };
        server.setHandler(handler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);
        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel);
        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("content-length 5 < 10"));
        assertThat(stream.getResponse(), notNullValue());
    }

    @Test
    public void testUnconsumedContentAvailable() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        server.setHandler(helloHandler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);

        String message = "ECHO Echo echo";
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel, body);

        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));

        assertThat(stream.getResponse().getFields().get(HttpHeader.CONNECTION), nullValue());
        assertThat(stream.readContent(), sameInstance(Content.EOF));
    }

    @Test
    public void testUnconsumedContentUnavailable() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response)
            {
                response.setStatus(200);
                response.setContentType(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                response.setContentLength(5);
                response.write(false, Callback.from(() -> response.write(true, request, BufferUtil.toBuffer("12345"))));
                return true;
            }
        };
        server.setHandler(handler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);

        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, 10)
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel, false);

        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("Content not consumed"));
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo("12345"));

        assertThat(stream.getResponse().getFields().get(HttpHeader.CONNECTION), nullValue());
        assertThat(stream.readContent(), nullValue());
    }

    @Test
    public void testUnconsumedContentUnavailableClosed() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response)
            {
                response.setStatus(200);
                response.addHeader(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
                response.setContentType(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                response.setContentLength(5);
                response.write(false, Callback.from(() -> response.write(true, request, BufferUtil.toBuffer("12345"))));
                return true;
            }
        };
        server.setHandler(handler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);

        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, 10)
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel, false);

        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo("12345"));

        assertThat(stream.getResponse().getFields().get(HttpHeader.CONNECTION), equalTo(HttpHeaderValue.CLOSE.asString()));
        assertThat(stream.readContent(), nullValue());
    }

    @Test
    public void testPersistent() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        server.setHandler(echoHandler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);

        String message = "ECHO Echo echo";
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel, body);

        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));

        // 2nd request
        fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        stream = new MockStream(channel);
        task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), nullValue());
        assertThat(stream.getResponse().getFields().getLongField(HttpHeader.CONTENT_LENGTH), equalTo(0L));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(""));
    }

    @Test
    public void testWhenStreamEvent() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        server.setHandler(echoHandler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);

        String[] parts = new String[] {"ECHO ", "Echo ", "echo"};
        String message = String.join("", parts);
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        AtomicReference<Callback> sendCB = new AtomicReference<>();
        MockStream stream = new MockStream(channel, false)
        {
            @Override
            public Content readContent()
            {
                return super.readContent();
            }

            @Override
            public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
            {
                sendCB.set(callback);
                super.send(response, last, Callback.NOOP, content);
            }
        };

        assertThat(stream.addContent(BufferUtil.toBuffer(parts[0]), false), nullValue());

        Runnable task = channel.onRequest(request, stream);

        List<String> history = new ArrayList<>();
        channel.whenStreamEvent(s ->
            new Stream.Wrapper(s)
            {
                @Override
                public Content readContent()
                {
                    Content content = super.readContent();
                    history.add("readContent: " + content);
                    return content;
                }

                @Override
                public void demandContent()
                {
                    history.add("demandContent");
                    super.demandContent();
                }

                @Override
                public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
                {
                    history.add(String.format("send %d l=%b %d %s",
                        response == null ? 0 : response.getStatus(),
                        last,
                        content.length,
                        content.length == 0 ? null : BufferUtil.toDetailString(content[0])));
                    super.send(response, last, callback, content);
                }

                @Override
                public void push(MetaData.Request request)
                {
                    history.add("push");
                    super.push(request);
                }

                @Override
                public void upgrade(Connection connection)
                {
                    history.add("upgrade");
                    super.upgrade(connection);
                }

                @Override
                public void succeeded()
                {
                    history.add("succeeded");
                    super.succeeded();
                }

                @Override
                public void failed(Throwable x)
                {
                    history.add("failed " + x);
                    super.failed(x);
                }
            });

        task.run();
        Callback callback = sendCB.getAndSet(null);
        callback.succeeded();

        for (int i = 1; i < parts.length; i++)
        {
            String part = parts[i];
            boolean last = i == (parts.length - 1);
            task = stream.addContent(BufferUtil.toBuffer(part), last);
            task.run();
            callback = sendCB.getAndSet(null);
            callback.succeeded();
        }

        callback = sendCB.getAndSet(null);
        callback.succeeded();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));

        Iterator<String> timeline = history.iterator();

        // Read of the first part that has already arrived
        assertThat(timeline.next(), allOf(startsWith("readContent: "),containsString("<<<ECHO >>>")));
        // send the first part with a commit
        assertThat(timeline.next(), allOf(startsWith("send 200 l=false"),containsString("<<<ECHO >>>")));
        // no more content available
        assertThat(timeline.next(), allOf(startsWith("readContent: null")));
        // demand content
        assertThat(timeline.next(), allOf(startsWith("demandContent")));
        // read the next part when it arrives
        assertThat(timeline.next(), allOf(startsWith("readContent: "), containsString("<<<Echo >>>")));
        // send the next part not commit and not last
        assertThat(timeline.next(), allOf(startsWith("send 0 l=false "), containsString("<<<Echo >>>")));
        // no more content available
        assertThat(timeline.next(), allOf(startsWith("readContent: null")));
        // demand content
        assertThat(timeline.next(), allOf(startsWith("demandContent")));
        // read the last part when it arrives
        assertThat(timeline.next(), allOf(startsWith("readContent: "), containsString("<<<echo>>>"), containsString("l=true")));
        // send the last part
        assertThat(timeline.next(), allOf(startsWith("send 0 l=true "), containsString("<<<echo>>>")));
        // ensure all data is consumed
        assertThat(timeline.next(), allOf(startsWith("readContent: EOF")));
        // ensure all data is flushed
        assertThat(timeline.next(), allOf(startsWith("send 0 l=true 0 null")));
        // succeed the stream
        assertThat(timeline.next(), allOf(startsWith("succeeded")));
        // End of history
        assertThat(timeline.hasNext(), is(false));
    }

    @Test
    public void testTrailers() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        server.setHandler(echoHandler);
        server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        Channel channel = new Channel(server, connectionMetaData);

        String[] parts = new String[] {"ECHO ", "Echo ", "echo"};
        String message = String.join("", parts);
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields trailers = HttpFields.build().add("Trailer", "value").asImmutable();
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        MockStream stream = new MockStream(channel, false)
        {
            int i = 0;
            @Override
            public Content readContent()
            {
                if (i < parts.length)
                    return Content.from(BufferUtil.toBuffer(parts[i++]), false);

                if (i++ == parts.length)
                    return new Content.Trailers(trailers);

                return Content.EOF;
            }
        };

        Runnable task = channel.onRequest(request, stream);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));

        HttpFields trailersRcv = stream.getResponse().getTrailerSupplier().get();
        assertThat(trailersRcv, notNullValue());
        assertThat(trailersRcv.get("Echo"), equalTo("Trailers"));
        assertThat(trailersRcv.get("Trailer"), equalTo("value"));
    }
}