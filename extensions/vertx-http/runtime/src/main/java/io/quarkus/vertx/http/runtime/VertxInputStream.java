package io.quarkus.vertx.http.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Deque;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.quarkus.runtime.BlockingOperationNotAllowedException;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.impl.ConnectionBase;
import io.vertx.ext.web.RoutingContext;

public class VertxInputStream extends InputStream {

    public static final String CONTINUE = "100-continue";
    public final byte[] oneByte = new byte[1];
    private final VertxBlockingInput exchange;

    private boolean closed;
    private boolean finished;
    private ByteBuf pooled;
    private final long limit;
    private ContinueState continueState = ContinueState.NONE;

    public VertxInputStream(RoutingContext request, long timeout) {
        this.exchange = new VertxBlockingInput(request.request(), timeout);
        Long limitObj = request.get(VertxHttpRecorder.MAX_REQUEST_SIZE_KEY);
        if (limitObj == null) {
            limit = -1;
        } else {
            limit = limitObj;
        }
        String expect = request.request().getHeader(HttpHeaderNames.EXPECT);
        if (expect != null && expect.equalsIgnoreCase(CONTINUE)) {
            continueState = ContinueState.REQUIRED;
        }
    }

    public VertxInputStream(RoutingContext request, long timeout, ByteBuf existing) {
        this.exchange = new VertxBlockingInput(request.request(), timeout);
        Long limitObj = request.get(VertxHttpRecorder.MAX_REQUEST_SIZE_KEY);
        if (limitObj == null) {
            limit = -1;
        } else {
            limit = limitObj;
        }
        this.pooled = existing;
    }

    @Override
    public int read() throws IOException {
        int read = read(oneByte);
        if (read == -1) {
            return -1;
        }
        return oneByte[0] & 0xff;
    }

    @Override
    public int read(final byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
        if (b == null || b.length < off + len) {
            throw new IOException("Incompatible Buffer size");
        }
        if (continueState == ContinueState.REQUIRED) {
            continueState = ContinueState.SENT;
            exchange.request.response().writeContinue();
        }
        readIntoBuffer();
        if (limit > 0 && exchange.request.bytesRead() > limit) {
            HttpServerResponse response = exchange.request.response();
            if (response.headWritten()) {
                //the response has been written, not much we can do
                exchange.request.connection().close();
                throw new IOException("Request too large");
            } else {
                response.setStatusCode(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.code());
                response.headers().add(HttpHeaderNames.CONNECTION, "close");
                response.endHandler(new Handler<Void>() {
                    @Override
                    public void handle(Void event) {
                        exchange.request.connection().close();
                    }
                });
                response.end();
                throw new IOException("Request too large");
            }
        }
        if (finished) {
            return -1;
        }
        if (len == 0) {
            return 0;
        }
        ByteBuf buffer = pooled;
        int copied = Math.min(len, buffer.readableBytes());
        buffer.readBytes(b, off, copied);
        if (!buffer.isReadable()) {
            pooled.release();
            pooled = null;
        }
        return copied;
    }

    private void readIntoBuffer() throws IOException {
        if (pooled == null && !finished) {
            pooled = exchange.readBlocking();
            if (pooled == null) {
                finished = true;
                pooled = null;
            }
        }
    }

    @Override
    public int available() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
        if (finished) {
            return 0;
        }
        if (pooled != null && pooled.isReadable()) {
            return pooled.readableBytes() + exchange.readBytesAvailable();
        }

        return exchange.readBytesAvailable();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            while (!finished) {
                readIntoBuffer();
                if (pooled != null) {
                    pooled.release();
                    pooled = null;
                }
            }
        } catch (IOException | RuntimeException e) {
            //our exchange is all broken, just end it
            throw e;
        } finally {
            if (pooled != null) {
                pooled.release();
                pooled = null;
            }
            finished = true;
        }
    }

    public static class VertxBlockingInput implements Handler<Buffer> {
        protected final HttpServerRequest request;
        protected Buffer input1;
        protected Deque<Buffer> inputOverflow;
        protected boolean waiting = false;
        protected boolean eof = false;
        protected Throwable readException;
        private final long timeout;
        private final int headerLen;

        public VertxBlockingInput(HttpServerRequest request, long timeout) {
            this.request = request;
            this.headerLen = getLengthFromHeader();
            this.timeout = timeout;
            final ConnectionBase connection = (ConnectionBase) request.connection();
            synchronized (connection) {
                if (!connection.channel().isOpen()) {
                    readException = new ClosedChannelException();
                } else if (!request.isEnded()) {
                    request.pause();
                    request.handler(this);
                    request.endHandler(new Handler<Void>() {
                        @Override
                        public void handle(Void event) {
                            synchronized (connection) {
                                eof = true;
                                if (waiting) {
                                    connection.notifyAll();
                                }
                            }
                        }
                    });
                    request.exceptionHandler(new Handler<Throwable>() {
                        @Override
                        public void handle(Throwable event) {
                            synchronized (connection) {
                                readException = new IOException(event);
                                if (input1 != null) {
                                    input1.getByteBuf().release();
                                    input1 = null;
                                }
                                if (inputOverflow != null) {
                                    Buffer d = inputOverflow.poll();
                                    while (d != null) {
                                        d.getByteBuf().release();
                                        d = inputOverflow.poll();
                                    }
                                }
                                if (waiting) {
                                    connection.notifyAll();
                                }
                            }
                        }

                    });
                    request.fetch(1);
                } else {
                    eof = true;
                }
            }
        }

        protected ByteBuf readBlocking() throws IOException {
            long expire = System.currentTimeMillis() + timeout;
            synchronized (request.connection()) {
                while (input1 == null && !eof && readException == null) {
                    long rem = expire - System.currentTimeMillis();
                    if (rem <= 0) {
                        //everything is broken, if read has timed out we can assume that the underling connection
                        //is wrecked, so just close it
                        request.connection().close();
                        IOException throwable = new IOException("Read timed out");
                        readException = throwable;
                        throw throwable;
                    }

                    try {
                        if (Context.isOnEventLoopThread()) {
                            throw new BlockingOperationNotAllowedException("Attempting a blocking read on io thread");
                        }
                        waiting = true;
                        request.connection().wait(rem);
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException(e.getMessage());
                    } finally {
                        waiting = false;
                    }
                }
                if (readException != null) {
                    throw new IOException(readException);
                }
                Buffer ret = input1;
                input1 = null;
                if (inputOverflow != null) {
                    input1 = inputOverflow.poll();
                    if (input1 == null) {
                        request.fetch(1);
                    }
                } else if (!eof) {
                    request.fetch(1);
                }
                return ret == null ? null : ret.getByteBuf();
            }
        }

        @Override
        public void handle(Buffer event) {
            synchronized (request.connection()) {
                if (event.length() == 0 && request.version() == HttpVersion.HTTP_2) {
                    // When using HTTP/2 H2, this indicates that we won't receive anymore data.
                    eof = true;
                    if (waiting) {
                        request.connection().notifyAll();
                    }
                    return;
                }
                if (input1 == null) {
                    input1 = event;
                } else {
                    if (inputOverflow == null) {
                        inputOverflow = new ArrayDeque<>();
                    }
                    inputOverflow.add(event);
                }
                if (waiting) {
                    request.connection().notifyAll();
                }
            }
        }

        public int readBytesAvailable() {
            if (input1 != null) {
                return input1.getByteBuf().readableBytes();
            }
            return headerLen;
        }

        private int getLengthFromHeader() {
            String length = request.getHeader(HttpHeaders.CONTENT_LENGTH);
            if (length == null) {
                return 0;
            }
            try {
                return Integer.parseInt(length);
            } catch (NumberFormatException e) {
                Long.parseLong(length); // ignore the value as can only return an int anyway
                return Integer.MAX_VALUE;
            }
        }
    }

    enum ContinueState {
        NONE,
        REQUIRED,
        SENT;
    }
}
