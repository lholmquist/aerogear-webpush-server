/**
 * JBoss, Home of Professional Open Source
 * Copyright Red Hat, Inc., and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.aerogear.webpush;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2InboundFlowController;
import io.netty.handler.codec.http2.DefaultHttp2OutboundFlowController;
import io.netty.handler.codec.http2.DelegatingDecompressorFrameListener;
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2InboundFrameLogger;
import io.netty.handler.codec.http2.Http2OutboundFrameLogger;
import io.netty.handler.codec.http2.Http2ToHttpConnectionHandler;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.ssl.SslContext;
import io.netty.util.internal.logging.InternalLoggerFactory;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.util.internal.logging.InternalLogLevel.INFO;

public class WebPushClientInitializer extends ChannelInitializer<SocketChannel> {

    private static final Http2FrameLogger LOGGER =
            new Http2FrameLogger(INFO, InternalLoggerFactory.getInstance(WebPushClientInitializer.class));
    private final SslContext sslCtx;
    private final int maxContentLength;
    private Http2ToHttpConnectionHandler connectionHandler;
    private HttpResponseHandler responseHandler;
    private Http2SettingsHandler settingsHandler;


    public WebPushClientInitializer(SslContext sslCtx, int maxContentLength) {
        this.sslCtx = sslCtx;
        this.maxContentLength = maxContentLength;
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        final Http2Connection connection = new DefaultHttp2Connection(false);
        final Http2FrameWriter frameWriter = frameWriter();
        connectionHandler = new Http2ToHttpConnectionHandler(connection,
                frameReader(),
                frameWriter,
                new DefaultHttp2InboundFlowController(connection, frameWriter),
                new DefaultHttp2OutboundFlowController(connection, frameWriter),
                new DelegatingDecompressorFrameListener(connection,
                        InboundHttp2ToHttpAdapter.newInstance(connection, maxContentLength)));
        responseHandler = new HttpResponseHandler();
        settingsHandler = new Http2SettingsHandler(ch.newPromise());
        if (sslCtx != null) {
            configureSsl(ch);
        } else {
            configureClearText(ch);
        }
    }

    public HttpResponseHandler responseHandler() {
        return responseHandler;
    }

    public Http2SettingsHandler settingsHandler() {
        return settingsHandler;
    }

    protected void configureEndOfPipeline(ChannelPipeline pipeline) {
        pipeline.addLast("Http2SettingsHandler", settingsHandler);
        pipeline.addLast("HttpResponseHandler", responseHandler);
    }

    /**
     * Configure the pipeline for TLS NPN negotiation to HTTP/2.
     */
    private void configureSsl(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("SslHandler", sslCtx.newHandler(ch.alloc()));
        pipeline.addLast("Http2Handler", connectionHandler);
        configureEndOfPipeline(pipeline);
    }

    /**
     * Configure the pipeline for a cleartext upgrade from HTTP to HTTP/2.
     */
    private void configureClearText(SocketChannel ch) {
        HttpClientCodec sourceCodec = new HttpClientCodec();
        Http2ClientUpgradeCodec upgradeCodec = new Http2ClientUpgradeCodec(connectionHandler);
        HttpClientUpgradeHandler upgradeHandler = new HttpClientUpgradeHandler(sourceCodec, upgradeCodec, 65536);
        ch.pipeline().addLast("Http2SourceCodec", sourceCodec);
        ch.pipeline().addLast("Http2UpgradeHandler", upgradeHandler);
        ch.pipeline().addLast("Http2UpgradeRequestHandler", new UpgradeRequestHandler());
        ch.pipeline().addLast("Logger", new UserEventLogger());
    }

    /**
     * A handler that triggers the cleartext upgrade to HTTP/2 by sending an initial HTTP request.
     */
    private final class UpgradeRequestHandler extends ChannelHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            DefaultFullHttpRequest upgradeRequest = new DefaultFullHttpRequest(HTTP_1_1, GET, "/");
            ctx.writeAndFlush(upgradeRequest);
            super.channelActive(ctx);
            // Done with this handler, remove it from the pipeline.
            ctx.pipeline().remove(this);
            WebPushClientInitializer.this.configureEndOfPipeline(ctx.pipeline());
        }
    }

    /**
     * Class that logs any User Events triggered on this channel.
     */
    private static class UserEventLogger extends ChannelHandlerAdapter {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            System.out.println("User Event Triggered: " + evt);
            super.userEventTriggered(ctx, evt);
        }
    }

    private static Http2FrameReader frameReader() {
        return new Http2InboundFrameLogger(new DefaultHttp2FrameReader(), LOGGER);
    }

    private static Http2FrameWriter frameWriter() {
        return new Http2OutboundFrameLogger(new DefaultHttp2FrameWriter(), LOGGER);
    }
}
