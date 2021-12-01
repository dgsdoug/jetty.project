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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.HttpStatus.INTERNAL_SERVER_ERROR_500;

/**
 * <p>A {@link Connection} that handles the HTTP protocol.</p>
 */
public class HttpConnection extends AbstractConnection implements Runnable, WriteFlusher.Listener, Connection.UpgradeFrom, Connection.UpgradeTo, ConnectionMetaData
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnection.class);
    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<>();

    private final HttpConfiguration _configuration;
    private final Connector _connector;
    private final ByteBufferPool _bufferPool;
    private final RetainableByteBufferPool _retainableByteBufferPool;
    private final HttpGenerator _generator;
    private final Http1Channel _channel;
    private final AtomicReference<Http1Stream> _stream = new AtomicReference<>();
    private final HttpParser _parser;
    private final AttributesMap _attributes = new AttributesMap();
    private volatile RetainableByteBuffer _retainableByteBuffer;
    private final DemandContentCallback _demandContentCallback = new DemandContentCallback();
    private final SendCallback _sendCallback = new SendCallback();
    private final boolean _recordHttpComplianceViolations;
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private boolean _useInputDirectByteBuffers;
    private boolean _useOutputDirectByteBuffers;

    /**
     * Get the current connection that this thread is dispatched to.
     * Note that a thread may be processing a request asynchronously and
     * thus not be dispatched to the connection.
     *
     * @return the current HttpConnection or null
     * @see Request#getAttribute(String) for a more general way to access the HttpConnection
     */
    public static HttpConnection getCurrentConnection()
    {
        return __currentConnection.get();
    }

    protected static HttpConnection setCurrentConnection(HttpConnection connection)
    {
        HttpConnection last = __currentConnection.get();
        __currentConnection.set(connection);
        return last;
    }

    public HttpConnection(HttpConfiguration configuration, Connector connector, EndPoint endPoint, boolean recordComplianceViolations)
    {
        super(endPoint, connector.getExecutor(), Invocable.InvocationType.EITHER);
        _configuration = configuration;
        _connector = connector;
        _bufferPool = _connector.getByteBufferPool();
        _retainableByteBufferPool = RetainableByteBufferPool.findOrAdapt(connector, _bufferPool);
        _generator = newHttpGenerator();
        _channel = new Http1Channel(connector.getServer(), configuration);
        _parser = newHttpParser(configuration.getHttpCompliance());
        _recordHttpComplianceViolations = recordComplianceViolations;
        if (LOG.isDebugEnabled())
            LOG.debug("New HTTP Connection {}", this);
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _configuration;
    }

    public boolean isRecordHttpComplianceViolations()
    {
        return _recordHttpComplianceViolations;
    }

    protected HttpGenerator newHttpGenerator()
    {
        return new HttpGenerator(_configuration.getSendServerVersion(), _configuration.getSendXPoweredBy());
    }

    protected HttpParser newHttpParser(HttpCompliance compliance)
    {
        HttpParser parser = new HttpParser(newRequestHandler(), getHttpConfiguration().getRequestHeaderSize(), compliance);
        parser.setHeaderCacheSize(getHttpConfiguration().getHeaderCacheSize());
        parser.setHeaderCacheCaseSensitive(getHttpConfiguration().isHeaderCacheCaseSensitive());
        return parser;
    }

    protected HttpParser.RequestHandler newRequestHandler()
    {
        return _channel;
    }

    public Server getServer()
    {
        return _connector.getServer();
    }

    public Connector getConnector()
    {
        return _connector;
    }

    public Channel getChannel()
    {
        return _channel;
    }

    public HttpParser getParser()
    {
        return _parser;
    }

    public HttpGenerator getGenerator()
    {
        return _generator;
    }

    @Override
    public String getId()
    {
        // TODO
        return null;
    }

    @Override
    public HttpVersion getVersion()
    {
        Http1Stream stream = _stream.get();
        return (stream != null) ? stream._version : HttpVersion.HTTP_1_1;
    }

    @Override
    public String getProtocol()
    {
        return getVersion().asString();
    }

    @Override
    public Connection getConnection()
    {
        return this;
    }

    @Override
    public boolean isPersistent()
    {
        return _generator.isPersistent();
    }

    @Override
    public boolean isSecure()
    {
        return getEndPoint() instanceof SslConnection.DecryptedEndPoint;
    }

    @Override
    public SocketAddress getRemote()
    {
        return getEndPoint().getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocal()
    {
        return getEndPoint().getLocalSocketAddress();
    }

    @Override
    public Object removeAttribute(String name)
    {
        return _attributes.removeAttribute(name);
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return _attributes.setAttribute(name, attribute);
    }

    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNames()
    {
        return _attributes.getAttributeNames();
    }

    public Set<Map.Entry<String, Object>> getAttributeEntrySet()
    {
        return _attributes.getAttributeEntrySet();
    }

    @Override
    public void clearAttributes()
    {
        _attributes.clearAttributes();
    }

    @Override
    public long getMessagesIn()
    {
        return 0; // TODO
    }

    @Override
    public long getMessagesOut()
    {
        return 0; // TODO
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return _useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        _useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return _useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        _useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    @Override
    public ByteBuffer onUpgradeFrom()
    {
        if (!isRequestBufferEmpty())
        {
            ByteBuffer unconsumed = ByteBuffer.allocateDirect(_retainableByteBuffer.remaining());
            unconsumed.put(_retainableByteBuffer.getBuffer());
            unconsumed.flip();
            releaseRequestBuffer();
            return unconsumed;
        }
        return null;
    }

    @Override
    public void onUpgradeTo(ByteBuffer buffer)
    {
        BufferUtil.append(getRequestBuffer(), buffer);
    }

    @Override
    public void onFlushed(long bytes) throws IOException
    {
        // TODO is this callback still needed?   Couldn't we wrap send callback instead?
        //      Either way, the dat rate calculations from HttpOutput.onFlushed should be moved to Channel.
    }

    void releaseRequestBuffer()
    {
        if (_retainableByteBuffer != null && !_retainableByteBuffer.hasRemaining())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("releaseRequestBuffer {}", this);
            if (_retainableByteBuffer.release())
                _retainableByteBuffer = null;
            else
                throw new IllegalStateException("unreleased buffer " + _retainableByteBuffer);
        }
    }

    private ByteBuffer getRequestBuffer()
    {
        if (_retainableByteBuffer == null)
            _retainableByteBuffer = _retainableByteBufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
        return _retainableByteBuffer.getBuffer();
    }

    public boolean isRequestBufferEmpty()
    {
        return _retainableByteBuffer == null || _retainableByteBuffer.isEmpty();
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onFillable enter {} {}", this, _channel, _retainableByteBuffer);

        HttpConnection last = setCurrentConnection(this);
        try
        {
            while (getEndPoint().isOpen())
            {
                // Fill the request buffer (if needed).
                int filled = fillRequestBuffer();
                if (filled < 0 && getEndPoint().isOutputShutdown())
                    close();

                // Parse the request buffer.
                boolean handle = parseRequestBuffer();

                // There could be a connection upgrade before handling
                // the HTTP/1.1 request, for example PRI * HTTP/2.
                // If there was a connection upgrade, the other
                // connection took over, nothing more to do here.
                if (getEndPoint().getConnection() != this)
                    break;

                // Handle channel event. This will only be true when the headers of a request have been received.
                if (handle)
                {
                    Request request = _channel.getRequest();
                    _channel.onRequest();
                    if (!request.isComplete())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("request !complete {} {}", request, this);
                        break;
                    }
                    if (!request.isComplete() || getEndPoint().getConnection() != this)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("upgraded {} -> {}", this, getEndPoint().getConnection());
                        break;
                    }
                }

                if (filled < 0)
                {
                    getEndPoint().shutdownOutput();
                    break;
                }
                if (filled == 0)
                {
                    fillInterested();
                    break;
                }

            }
        }
        catch (Throwable x)
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("caught exception {} {}", this, _channel, x);
                if (_retainableByteBuffer != null)
                {
                    _retainableByteBuffer.clear();
                    releaseRequestBuffer();
                }
            }
            finally
            {
                getEndPoint().close(x);
            }
        }
        finally
        {
            setCurrentConnection(last);
            if (LOG.isDebugEnabled())
                LOG.debug("onFillable exit {} {} {}", this, _channel, _retainableByteBuffer);
        }
    }

    /**
     * Parse and fill data, looking for content.
     * We do parse first, and only fill if we're out of bytes to avoid unnecessary system calls.
     */
    void parseAndFillForContent()
    {
        // Defensive check to avoid an infinite select/wakeup/fillAndParseForContent/wait loop
        // in case the parser was mistakenly closed and the connection was not aborted.
        if (_parser.isTerminated())
            throw new IllegalStateException("Parser is terminated: " + _parser);

        // When fillRequestBuffer() is called, it must always be followed by a parseRequestBuffer() call otherwise this method
        // doesn't trigger EOF/earlyEOF which breaks AsyncRequestReadTest.testPartialReadThenShutdown().

        // This loop was designed by a committee and voted by a majority.
        while (_parser.inContentState())
        {
            if (parseRequestBuffer())
                break;
            // Re-check the parser state after parsing to avoid filling,
            // otherwise fillRequestBuffer() would acquire a ByteBuffer
            // that may be leaked.
            if (_parser.inContentState() && fillRequestBuffer() <= 0)
                break;
        }
    }

    private int fillRequestBuffer()
    {
        if (_retainableByteBuffer != null && _retainableByteBuffer.isRetained())
            throw new IllegalStateException("fill with unconsumed content on " + this);

        if (isRequestBufferEmpty())
        {
            // Get a buffer
            // We are not in a race here for the request buffer as we have not yet received a request,
            // so there are not an possible legal threads calling #parseContent or #completed.
            ByteBuffer requestBuffer = getRequestBuffer();

            // fill
            try
            {
                int filled = getEndPoint().fill(requestBuffer);
                if (filled == 0) // Do a retry on fill 0 (optimization for SSL connections)
                    filled = getEndPoint().fill(requestBuffer);

                if (filled > 0)
                    bytesIn.add(filled);
                else if (filled < 0)
                    _parser.atEOF();

                if (LOG.isDebugEnabled())
                    LOG.debug("{} filled {} {}", this, filled, _retainableByteBuffer);

                return filled;
            }
            catch (IOException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to fill from endpoint {}", getEndPoint(), e);
                _parser.atEOF();
                return -1;
            }
        }
        return 0;
    }

    private boolean parseRequestBuffer()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} parse {}", this, _retainableByteBuffer);

        boolean handle = _parser.parseNext(_retainableByteBuffer == null ? BufferUtil.EMPTY_BUFFER : _retainableByteBuffer.getBuffer());

        if (LOG.isDebugEnabled())
            LOG.debug("{} parsed {} {}", this, handle, _parser);

        // recycle buffer ?
        if (_retainableByteBuffer != null && !_retainableByteBuffer.isRetained())
            releaseRequestBuffer();

        return handle;
    }

    private boolean upgrade()
    {
        /**
         * TODO deal with upgrade later
        Connection connection = (Connection)_channel.getRequest().getAttribute(UPGRADE_CONNECTION_ATTRIBUTE);
        if (connection == null)
            return false;

        if (LOG.isDebugEnabled())
            LOG.debug("Upgrade from {} to {}", this, connection);
        _channel.getState().upgrade();
        getEndPoint().upgrade(connection);
        _channel.recycle();
        _parser.reset();
        _generator.reset();
        if (_retainableByteBuffer != null)
        {
            if (!_retainableByteBuffer.isRetained())
            {
                releaseRequestBuffer();
            }
            else
            {
                LOG.warn("{} lingering content references?!?!", this);
                _retainableByteBuffer = null; // Not returned to pool!
            }
        }
        return true;
         */
        return false;
    }

    @Override
    protected boolean onReadTimeout(Throwable timeout)
    {
        // TODO
        throw new UnsupportedOperationException();
        // TODO return _channel.onIdleTimeout(timeout);
    }

    @Override
    protected void onFillInterestedFailed(Throwable cause)
    {
        _parser.close();
        super.onFillInterestedFailed(cause);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        if (isRequestBufferEmpty())
            fillInterested();
        else
            getExecutor().execute(this);
    }

    @Override
    public void onClose(Throwable cause)
    {
        if (cause == null)
            _sendCallback.close();
        else
            _sendCallback.failed(cause);
        super.onClose(cause);
    }

    @Override
    public void run()
    {
        onFillable();
    }

    public void asyncReadFillInterested()
    {
        getEndPoint().tryFillInterested(_demandContentCallback);
    }

    @Override
    public long getBytesIn()
    {
        return bytesIn.longValue();
    }

    @Override
    public long getBytesOut()
    {
        return bytesOut.longValue();
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s@%x[p=%s,g=%s]=>%s",
            getClass().getSimpleName(),
            hashCode(),
            _parser,
            _generator,
            _channel);
    }

    private class DemandContentCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            Runnable task = _channel.onContentAvailable();
            if (task != null)
                task.run();
        }

        @Override
        public void failed(Throwable x)
        {
            Runnable task = _channel.onConnectionClose(x);
            if (task != null)
                // Execute error path as invocation type is probably wrong.
                getConnector().getExecutor().execute(task);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _channel.getOnContentAvailableInvocationType();
        }
    }

    private class SendCallback extends IteratingCallback
    {
        private MetaData.Response _info;
        private boolean _head;
        private ByteBuffer _content;
        private boolean _lastContent;
        private Callback _callback;
        private ByteBuffer _header;
        private ByteBuffer _chunk;
        private boolean _shutdownOut;

        private SendCallback()
        {
            super(true);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _callback.getInvocationType();
        }

        private boolean reset(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean last, Callback callback)
        {
            if (reset())
            {
                _info = response;
                _head = request != null && HttpMethod.HEAD.is(request.getMethod());
                _content = content;
                _lastContent = last;
                _callback = callback;
                _header = null;
                _shutdownOut = false;

                if (getConnector().isShutdown())
                    _generator.setPersistent(false);

                return true;
            }

            if (isClosed() && response == null && last && content == null)
            {
                callback.succeeded();
                return false;
            }

            LOG.warn("reset failed {}", this);

            if (isClosed())
                callback.failed(new EofException());
            else
                callback.failed(new WritePendingException());
            return false;
        }

        @Override
        public Action process() throws Exception
        {
            if (_callback == null)
                throw new IllegalStateException();

            boolean useDirectByteBuffers = isUseOutputDirectByteBuffers();
            while (true)
            {
                HttpGenerator.Result result = _generator.generateResponse(_info, _head, _header, _chunk, _content, _lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("generate: {} for {} ({},{},{})@{}",
                        result,
                        this,
                        BufferUtil.toSummaryString(_header),
                        BufferUtil.toSummaryString(_content),
                        _lastContent,
                        _generator.getState());

                switch (result)
                {
                    case NEED_INFO:
                        throw new EofException("request lifecycle violation");

                    case NEED_HEADER:
                    {
                        _header = _bufferPool.acquire(Math.min(_configuration.getResponseHeaderSize(), _configuration.getOutputBufferSize()), useDirectByteBuffers);
                        continue;
                    }
                    case HEADER_OVERFLOW:
                    {
                        if (_header.capacity() >= _configuration.getResponseHeaderSize())
                            throw new BadMessageException(INTERNAL_SERVER_ERROR_500, "Response header too large");
                        releaseHeader();
                        _header = _bufferPool.acquire(_configuration.getResponseHeaderSize(), useDirectByteBuffers);
                        continue;
                    }
                    case NEED_CHUNK:
                    {
                        _chunk = _bufferPool.acquire(HttpGenerator.CHUNK_SIZE, useDirectByteBuffers);
                        continue;
                    }
                    case NEED_CHUNK_TRAILER:
                    {
                        releaseChunk();
                        _chunk = _bufferPool.acquire(_configuration.getResponseHeaderSize(), useDirectByteBuffers);
                        continue;
                    }
                    case FLUSH:
                    {
                        // Don't write the chunk or the content if this is a HEAD response, or any other type of response that should have no content
                        if (_head || _generator.isNoContent())
                        {
                            BufferUtil.clear(_chunk);
                            BufferUtil.clear(_content);
                        }

                        byte gatherWrite = 0;
                        long bytes = 0;
                        if (BufferUtil.hasContent(_header))
                        {
                            gatherWrite += 4;
                            bytes += _header.remaining();
                        }
                        if (BufferUtil.hasContent(_chunk))
                        {
                            gatherWrite += 2;
                            bytes += _chunk.remaining();
                        }
                        if (BufferUtil.hasContent(_content))
                        {
                            gatherWrite += 1;
                            bytes += _content.remaining();
                        }
                        HttpConnection.this.bytesOut.add(bytes);
                        switch (gatherWrite)
                        {
                            case 7:
                                getEndPoint().write(this, _header, _chunk, _content);
                                break;
                            case 6:
                                getEndPoint().write(this, _header, _chunk);
                                break;
                            case 5:
                                getEndPoint().write(this, _header, _content);
                                break;
                            case 4:
                                getEndPoint().write(this, _header);
                                break;
                            case 3:
                                getEndPoint().write(this, _chunk, _content);
                                break;
                            case 2:
                                getEndPoint().write(this, _chunk);
                                break;
                            case 1:
                                getEndPoint().write(this, _content);
                                break;
                            default:
                                succeeded();
                        }

                        return Action.SCHEDULED;
                    }
                    case SHUTDOWN_OUT:
                    {
                        _shutdownOut = true;
                        continue;
                    }
                    case DONE:
                    {
                        // If this is the end of the response and the connector was shutdown after response was committed,
                        // we can't add the Connection:close header, but we are still allowed to close the connection
                        // by shutting down the output.
                        if (getConnector().isShutdown() && _generator.isEnd() && _generator.isPersistent())
                            _shutdownOut = true;

                        return Action.SUCCEEDED;
                    }
                    case CONTINUE:
                    {
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException("generateResponse=" + result);
                    }
                }
            }
        }

        private Callback release()
        {
            Callback complete = _callback;
            _callback = null;
            _info = null;
            _content = null;
            releaseHeader();
            releaseChunk();
            return complete;
        }

        private void releaseHeader()
        {
            if (_header != null)
                _bufferPool.release(_header);
            _header = null;
        }

        private void releaseChunk()
        {
            if (_chunk != null)
                _bufferPool.release(_chunk);
            _chunk = null;
        }

        @Override
        protected void onCompleteSuccess()
        {
            // TODO is this too late to get the request?
            boolean upgrading = _channel.getRequest() != null && _channel.getRequest().getAttribute(Channel.UPGRADE_CONNECTION_ATTRIBUTE) != null;
            release().succeeded();
            // If successfully upgraded it is responsibility of the next protocol to close the connection.
            if (_shutdownOut && !upgrading)
                getEndPoint().shutdownOutput();
        }

        @Override
        public void onCompleteFailure(final Throwable x)
        {
            failedCallback(release(), x);
            if (_shutdownOut)
                getEndPoint().shutdownOutput();
        }

        @Override
        public String toString()
        {
            return String.format("%s[i=%s,cb=%s]", super.toString(), _info, _callback);
        }
    }

    private class Http1Channel extends Channel implements HttpParser.RequestHandler
    {
        private final HttpFields.Mutable _headers = HttpFields.build();
        private HttpFields.Mutable _trailers;
        Runnable _onRequest;

        public Http1Channel(Server server, HttpConfiguration configuration)
        {
            super(server, HttpConnection.this, configuration);
        }

        @Override
        public void startRequest(String method, String uri, HttpVersion version)
        {
            Http1Stream stream = new Http1Stream(_headers, method, uri, version);
            if (!_stream.compareAndSet(null, stream))
                throw new IllegalStateException("Stream pending");
            _channel.setStream(stream);
        }

        @Override
        public void parsedHeader(HttpField field)
        {
            _stream.get().parsedHeader(field);
        }

        @Override
        public boolean headerComplete()
        {
            _onRequest = _stream.get().headerComplete();
            return true;
        }

        private void onRequest()
        {
            Runnable onRequest = _onRequest;
            _onRequest = null;
            onRequest.run();
        }

        @Override
        public boolean content(ByteBuffer buffer)
        {
            if (_stream.get()._content != null)
                throw new IllegalStateException();
            _stream.get()._content = Content.from(buffer, false);
            return true;
        }

        @Override
        public boolean contentComplete()
        {
            // Do nothing at this point.
            // Wait for messageComplete so any trailers can be sent as special content
            return false;
        }

        @Override
        public boolean messageComplete()
        {
            Http1Stream stream = _stream.get();
            if (_trailers == null)
            {
                stream._content = stream._content == null ? Content.EOF : Content.from(stream._content, Content.EOF);
            }
            else
            {
                Content trailers = new Content.Trailers(_trailers.asImmutable());
                stream._content = stream._content == null ? trailers : Content.from(stream._content, trailers);
            }
            return false;
        }

        @Override
        public void parsedTrailer(HttpField field)
        {
            if (_trailers == null)
                _trailers = HttpFields.build();
            _trailers.add(field);
        }

        @Override
        public void badMessage(BadMessageException failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("badMessage {} {}", HttpConnection.this, failure);
            _generator.setPersistent(false);

            Http1Stream stream = _stream.get();
            if (stream == null)
            {
                stream = new Http1Stream(_headers, "BAD", "/", HttpVersion.HTTP_1_1);
                _stream.set(stream);
                _channel.setStream(stream);
            }
            Runnable todo = _channel.onError(failure);
            if (todo != null)
                getServer().getThreadPool().execute(todo);
        }

        @Override
        public void earlyEOF()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("early EOF {}", HttpConnection.this);
            _generator.setPersistent(false);
            Runnable todo = _channel.onError(new BadMessageException("Early EOF"));
            if (todo != null)
                getServer().getThreadPool().execute(todo);
        }
    }

    private static final HttpField PREAMBLE_UPGRADE_H2C = new HttpField(HttpHeader.UPGRADE, "h2c");

    private class Http1Stream implements Stream
    {
        private final long _nanoTimestamp = System.nanoTime();
        private final HttpFields.Mutable _headerBuilder;
        private final String _method;
        private final HttpURI.Mutable _uri;
        private final HttpVersion _version;
        private long _contentLength = -1;
        private HostPortHttpField _authority;
        private MetaData.Request _request;
        private HttpField _upgrade = null;

        Content _content;
        private boolean _connectionClose = false;
        private boolean _connectionKeepAlive = false;
        private boolean _unknownExpectation = false;
        private boolean _expect100Continue = false;
        private boolean _expect102Processing = false;
        private List<String> _complianceViolations;
        private HttpFields.Mutable _trailers;

        public Http1Stream(HttpFields.Mutable headerBuilder, String method, String uri, HttpVersion version)
        {
            _headerBuilder = headerBuilder;
            _method = method;
            _uri = uri == null ? null : HttpURI.build(uri);
            _version = version;

            if (_uri != null && _uri.getPath() == null && _uri.getScheme() != null && _uri.hasAuthority())
                _uri.path("/");
        }

        public void parsedHeader(HttpField field)
        {
            HttpHeader header = field.getHeader();
            String value = field.getValue();
            if (header != null)
            {
                switch (header)
                {
                    case CONNECTION:
                        _connectionClose |= field.contains(HttpHeaderValue.CLOSE.asString());
                        if (HttpVersion.HTTP_1_0.equals(_version))
                            _connectionKeepAlive |= field.contains(HttpHeader.KEEP_ALIVE.asString());
                        break;

                    case HOST:
                        if (field instanceof HostPortHttpField)
                            _authority = (HostPortHttpField)field;
                        else if (StringUtil.isNotBlank(value))
                            field = _authority = new HostPortHttpField(value);
                        break;

                    case EXPECT:
                    {
                        if (!HttpHeaderValue.parseCsvIndex(value, t ->
                        {
                            switch (t)
                            {
                                case CONTINUE:
                                    _expect100Continue = true;
                                    return true;
                                case PROCESSING:
                                    _expect102Processing = true;
                                    return true;
                                default:
                                    return false;
                            }
                        }, s -> false))
                        {
                            _unknownExpectation = true;
                            _expect100Continue = false;
                            _expect102Processing = false;
                        }
                        break;
                    }

                    case UPGRADE:
                        _upgrade = field;
                        break;

                    case CONTENT_LENGTH:
                        _contentLength = field.getLongValue();
                        break;

                    default:
                        break;
                }
            }
            _headerBuilder.add(field);
        }

        public Runnable headerComplete()
        {
            UriCompliance compliance;
            if (_uri.hasViolations())
            {
                compliance = _configuration.getUriCompliance();
                String badMessage = UriCompliance.checkUriCompliance(compliance, _uri);
                if (badMessage != null)
                    throw new BadMessageException(badMessage);
            }

            _uri.scheme(getEndPoint() instanceof SslConnection.DecryptedEndPoint ? HttpScheme.HTTPS : HttpScheme.HTTP);

            if (!HttpMethod.CONNECT.is(_method))
            {
                if (_authority != null)
                    _uri.authority(HostPort.normalizeHost(_authority.getHost()), _authority.getPort());
                else
                {
                    SocketAddress addr = getConnection().getEndPoint().getLocalSocketAddress();
                    if (addr instanceof InetSocketAddress)
                    {
                        InetSocketAddress inet = (InetSocketAddress)addr;
                        _uri.authority(HostPort.normalizeHost(inet.getHostString()), inet.getPort());
                    }
                }
            }

            _request = new MetaData.Request(_method, _uri.asImmutable(), _version, _headerBuilder, _contentLength);

            Runnable handle = _channel.onRequest(_request);

            if (_complianceViolations != null && !_complianceViolations.isEmpty())
            {
                _channel.getRequest().setAttribute(HttpCompliance.VIOLATIONS_ATTR, _complianceViolations);
                _complianceViolations = null;
            }

            boolean persistent;

            switch (_request.getHttpVersion())
            {
                case HTTP_0_9:
                {
                    persistent = false;
                    break;
                }
                case HTTP_1_0:
                {
                    persistent = getHttpConfiguration().isPersistentConnectionsEnabled() &&
                        _connectionKeepAlive &&
                        !_connectionClose ||
                        HttpMethod.CONNECT.is(_method);
                    if (persistent)
                        // TODO remove the need to set this header here
                        _channel.getResponse().getHeaders().add(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE);
                    else
                        _generator.setPersistent(false);

                    break;
                }

                case HTTP_1_1:
                {
                    if (_unknownExpectation)
                    {
                        _channel.badMessage(new BadMessageException(HttpStatus.EXPECTATION_FAILED_417));
                        return null; // TODO ???
                    }

                    persistent = getHttpConfiguration().isPersistentConnectionsEnabled() &&
                        !_connectionClose ||
                        HttpMethod.CONNECT.is(_method);

                    if (!persistent)
                        _generator.setPersistent(false);

                    if (_upgrade != null && HttpConnection.this.upgrade())
                        return null; // TODO ???

                    break;
                }

                case HTTP_2:
                {
                    // Allow direct "upgrade" to HTTP_2_0 only if the connector supports h2c.
                    _upgrade = PREAMBLE_UPGRADE_H2C;

                    if (HttpMethod.PRI.is(_method) &&
                        "*".equals(_uri.getPath()) &&
                        _headerBuilder.size() == 0 &&
                        HttpConnection.this.upgrade())
                        return null; // TODO ?

                    // TODO?
                    _parser.close();
                    throw new BadMessageException(HttpStatus.UPGRADE_REQUIRED_426);
                }

                default:
                {
                    throw new IllegalStateException("unsupported version " + _version);
                }
            }

            if (!persistent)
                _generator.setPersistent(false);

            return handle;
        }

        @Override
        public String getId()
        {
            // TODO
            return null;
        }

        @Override
        public long getNanoTimeStamp()
        {
            return _nanoTimestamp;
        }

        @Override
        public Content readContent()
        {
            if (_content == null)
                parseAndFillForContent();

            Content content = _content;
            _content = content == null ? null : content.next();
            return content;
        }

        @Override
        public void demandContent()
        {
            if (_content != null)
            {
                Runnable onContentAvailable = _channel.onContentAvailable();
                if (onContentAvailable != null)
                    onContentAvailable.run();
                return;
            }
            parseAndFillForContent();
            if (_content != null)
            {
                Runnable onContentAvailable = _channel.onContentAvailable();
                if (onContentAvailable != null)
                    onContentAvailable.run();
                return;
            }

            if (_expect100Continue)
            {
                _expect100Continue = false;
                send(HttpGenerator.CONTINUE_100_INFO, false, Callback.NOOP);
            }

            tryFillInterested(_demandContentCallback);
        }

        @Override
        public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
        {
            if (response == null)
            {
                if (!last && BufferUtil.isEmpty(content))
                {
                    callback.succeeded();
                    return;
                }
            }
            else if (_generator.isCommitted())
            {
                callback.failed(new IllegalStateException("Committed"));
            }
            else
            {
                // If we are still expecting a 100 continues when we commit
                if (_expect100Continue)
                    // then we can't be persistent
                    _generator.setPersistent(false);
            }

            // TODO support gather write
            if (content.length > 1)
                throw new UnsupportedOperationException("Gather write!");
            if (_sendCallback.reset(_request, response, content.length == 0 ? null : content[0], last, callback))
                _sendCallback.iterate();
        }

        @Override
        public boolean isPushSupported()
        {
            return false;
        }

        @Override
        public void push(MetaData.Request request)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isCommitted()
        {
            return _stream.get() != this || _generator.isCommitted();
        }

        @Override
        public boolean isComplete()
        {
            return _stream.get() != this;
        }

        @Override
        public void upgrade(Connection connection)
        {
            // TODO
            throw new UnsupportedOperationException();
        }

        @Override
        public void succeeded()
        {
            Http1Stream stream = _stream.getAndSet(null);
            if (stream == null)
                return; // TODO log

            if (LOG.isDebugEnabled())
                LOG.debug("succeeded {}", HttpConnection.this);
            // If we are fill interested, then a read is pending and we must abort
            if (isFillInterested())
            {
                LOG.warn("Read pending {} {}", this, getEndPoint());
                failed(new IOException("Pending read in onCompleted"));
                return;
            }

            if (HttpConnection.this.upgrade())
                return;

            // Finish consuming the request
            // If we are still expecting
            if (_expect100Continue)
            {
                // close to seek EOF
                _parser.close();
            }

            // Reset the channel, parsers and generator
            if (!_parser.isClosed())
            {
                if (_generator.isPersistent())
                    _parser.reset();
                else
                    _parser.close();
            }

            _generator.reset();

            // if we are not called from the onfillable thread, schedule completion
            if (getCurrentConnection() != HttpConnection.this)
            {
                // If we are looking for the next request
                if (_parser.isStart())
                {
                    // if the buffer is empty
                    if (isRequestBufferEmpty())
                    {
                        // look for more data
                        fillInterested();
                    }
                    // else if we are still running
                    else if (getConnector().isRunning())
                    {
                        // Dispatched to handle a pipelined request
                        try
                        {
                            getExecutor().execute(HttpConnection.this);
                        }
                        catch (RejectedExecutionException e)
                        {
                            if (getConnector().isRunning())
                                LOG.warn("Failed dispatch of {}", this, e);
                            else
                                LOG.trace("IGNORED", e);
                            getEndPoint().close();
                        }
                    }
                    else
                    {
                        getEndPoint().close();
                    }
                }
                // else the parser must be closed, so seek the EOF if we are still open
                else if (getEndPoint().isOpen())
                    fillInterested();
            }
        }

        @Override
        public void failed(Throwable x)
        {
            Http1Stream stream = _stream.getAndSet(null);
            if (stream == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("ignored", x);
                return;
            }

            getEndPoint().close();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return Stream.super.getInvocationType();
        }
    }
}
