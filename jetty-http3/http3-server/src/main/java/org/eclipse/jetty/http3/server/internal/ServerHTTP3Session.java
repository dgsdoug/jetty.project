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

package org.eclipse.jetty.http3.server.internal;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.ControlFlusher;
import org.eclipse.jetty.http3.internal.DecoderStreamConnection;
import org.eclipse.jetty.http3.internal.EncoderStreamConnection;
import org.eclipse.jetty.http3.internal.InstructionFlusher;
import org.eclipse.jetty.http3.internal.InstructionHandler;
import org.eclipse.jetty.http3.internal.MessageFlusher;
import org.eclipse.jetty.http3.internal.UnidirectionalStreamConnection;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.common.StreamType;
import org.eclipse.jetty.quic.server.ServerProtocolSession;
import org.eclipse.jetty.quic.server.ServerQuicSession;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerHTTP3Session extends ServerProtocolSession
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerHTTP3Session.class);

    private final QpackEncoder encoder;
    private final QpackDecoder decoder;
    private final HTTP3SessionServer session;
    private final ControlFlusher controlFlusher;
    private final MessageFlusher messageFlusher;

    public ServerHTTP3Session(HTTP3Configuration configuration, ServerQuicSession quicSession, Session.Server.Listener listener)
    {
        super(quicSession);
        this.session = new HTTP3SessionServer(this, listener);
        addBean(session);
        session.setStreamIdleTimeout(configuration.getStreamIdleTimeout());

        if (LOG.isDebugEnabled())
            LOG.debug("initializing HTTP/3 streams");

        long encoderStreamId = getQuicSession().newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint encoderEndPoint = configureInstructionEndPoint(encoderStreamId);
        InstructionFlusher encoderInstructionFlusher = new InstructionFlusher(quicSession, encoderEndPoint, EncoderStreamConnection.STREAM_TYPE);
        this.encoder = new QpackEncoder(new InstructionHandler(encoderInstructionFlusher), configuration.getMaxBlockedStreams());
        addBean(encoder);
        if (LOG.isDebugEnabled())
            LOG.debug("created encoder stream #{} on {}", encoderStreamId, encoderEndPoint);

        long decoderStreamId = getQuicSession().newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint decoderEndPoint = configureInstructionEndPoint(decoderStreamId);
        InstructionFlusher decoderInstructionFlusher = new InstructionFlusher(quicSession, decoderEndPoint, DecoderStreamConnection.STREAM_TYPE);
        this.decoder = new QpackDecoder(new InstructionHandler(decoderInstructionFlusher), configuration.getMaxRequestHeadersSize());
        addBean(decoder);
        if (LOG.isDebugEnabled())
            LOG.debug("created decoder stream #{} on {}", decoderStreamId, decoderEndPoint);

        long controlStreamId = getQuicSession().newStreamId(StreamType.SERVER_UNIDIRECTIONAL);
        QuicStreamEndPoint controlEndPoint = configureControlEndPoint(controlStreamId);
        this.controlFlusher = new ControlFlusher(quicSession, controlEndPoint, configuration.isUseOutputDirectByteBuffers());
        addBean(controlFlusher);
        if (LOG.isDebugEnabled())
            LOG.debug("created control stream #{} on {}", controlStreamId, controlEndPoint);

        this.messageFlusher = new MessageFlusher(quicSession.getByteBufferPool(), encoder, configuration.getMaxResponseHeadersSize(), configuration.isUseOutputDirectByteBuffers());
        addBean(messageFlusher);
    }

    public QpackDecoder getQpackDecoder()
    {
        return decoder;
    }

    public HTTP3SessionServer getSessionServer()
    {
        return session;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        // Queue the mandatory SETTINGS frame.
        Map<Long, Long> settings = session.onPreface();
        if (settings == null)
            settings = Map.of();
        // TODO: add default settings.
        SettingsFrame frame = new SettingsFrame(settings);
        controlFlusher.offer(frame, Callback.from(Invocable.InvocationType.NON_BLOCKING, session::onOpen, this::fail));
        controlFlusher.iterate();
    }

    private void fail(Throwable failure)
    {
        // TODO: must close the connection.
    }

    private QuicStreamEndPoint configureInstructionEndPoint(long streamId)
    {
        // This is a write-only stream, so no need to link a Connection.
        return getOrCreateStreamEndPoint(streamId, QuicStreamEndPoint::onOpen);
    }

    private QuicStreamEndPoint configureControlEndPoint(long streamId)
    {
        // This is a write-only stream, so no need to link a Connection.
        return getOrCreateStreamEndPoint(streamId, QuicStreamEndPoint::onOpen);
    }

    @Override
    protected boolean onReadable(long readableStreamId)
    {
        StreamType streamType = StreamType.from(readableStreamId);
        if (streamType == StreamType.CLIENT_BIDIRECTIONAL)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("bidirectional stream #{} selected for read", readableStreamId);
            return super.onReadable(readableStreamId);
        }
        else
        {
            QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(readableStreamId, this::configureUnidirectionalStreamEndPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("unidirectional stream #{} selected for read: {}", readableStreamId, streamEndPoint);
            return streamEndPoint.onReadable();
        }
    }

    @Override
    protected boolean onIdleTimeout()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout {} ms expired for {}", getQuicSession().getIdleTimeout(), this);
        return session.onIdleTimeout();
    }

    @Override
    public void inwardClose(long error, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("inward closing 0x{}/{} on {}", Long.toHexString(error), reason, this);
        session.disconnect(reason);
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        return session.shutdown();
    }

    @Override
    protected void onClose(long error, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed remotely 0x{}/{} {}", Long.toHexString(error), reason, this);
        session.onClose(error, reason);
    }

    private void configureUnidirectionalStreamEndPoint(QuicStreamEndPoint endPoint)
    {
        UnidirectionalStreamConnection connection = new UnidirectionalStreamConnection(endPoint, getQuicSession().getExecutor(), getQuicSession().getByteBufferPool(), encoder, decoder, session);
        endPoint.setConnection(connection);
        endPoint.onOpen();
        connection.onOpen();
    }

    void writeControlFrame(Frame frame, Callback callback)
    {
        controlFlusher.offer(frame, callback);
        controlFlusher.iterate();
    }

    void writeMessageFrame(long streamId, Frame frame, Callback callback)
    {
        QuicStreamEndPoint endPoint = getOrCreateStreamEndPoint(streamId, this::configureProtocolEndPoint);
        messageFlusher.offer(endPoint, frame, callback);
        messageFlusher.iterate();
    }

    public void onDataAvailable(long streamId)
    {
        session.onDataAvailable(streamId);
    }
}