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
package org.jboss.aerogear.webpush.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.AsciiString;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.EmptyHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import org.jboss.aerogear.webpush.AggregateChannel;
import org.jboss.aerogear.webpush.Channel;
import org.jboss.aerogear.webpush.Registration;
import org.jboss.aerogear.webpush.Registration.WebLink;
import org.jboss.aerogear.webpush.WebPushServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS;
import static io.netty.handler.codec.http.HttpHeaderNames.CACHE_CONTROL;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static io.netty.handler.codec.http.HttpResponseStatus.ACCEPTED;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.NO_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.CREATED;
import static io.netty.util.CharsetUtil.UTF_8;
import static org.jboss.aerogear.webpush.JsonMapper.fromJson;

public class WebPushFrameListener extends Http2FrameAdapter {

    public static final AsciiString LINK = new AsciiString("link");
    public static final AsciiString ANY_ORIGIN = new AsciiString("*");
    public static final AsciiString AGGREGATION_JSON = new AsciiString("application/push-aggregation+json");
    private static final Logger LOGGER = LoggerFactory.getLogger(WebPushNettyServer.class);
    private static final ConcurrentHashMap<String, Optional<Client>> monitoredStreams = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> notificationStreams = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AggregateChannel> aggregateChannels = new ConcurrentHashMap<>();
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String PUT = "PUT";
    private static final String DELETE = "DELETE";

    private static final String PATH_KEY = "webpush.path";
    private static final String METHOD_KEY = "webpush.method";
    private final WebPushServer webpushServer;
    private Http2ConnectionEncoder encoder;

    public WebPushFrameListener(final WebPushServer webpushServer) {
        Objects.requireNonNull(webpushServer, "webpushServer must not be null");
        this.webpushServer = webpushServer;
    }

    public void encoder(Http2ConnectionEncoder encoder) {
        this.encoder = encoder;
    }


    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx,
                              final int streamId,
                              final Http2Headers headers,
                              final int streamDependency,
                              final short weight,
                              final boolean exclusive,
                              final int padding,
                              final boolean endStream) throws Http2Exception {
        final String path = headers.path().toString();
        LOGGER.info("onHeadersRead. streamId={}, method={}, path={}, endstream={}", streamId, headers.method(), path, endStream);

        setPathAndMethod(encoder.connection().stream((streamId)), path, headers.method());
        switch (headers.method().toString()) {
            case GET:
                if (path.contains("monitor")) {
                    handleMonitor(ctx, path, streamId);
                } else {
                    handleChannelStatus(ctx, path, streamId, padding);
                }
                break;
            case POST:
                if (path.contains("aggregate")) {
                    verifyAggregateMimeType(headers);
                } // register/channel/notificaitons are handled by onDataRead
                break;
            case DELETE:
                handleChannelRemoval(ctx, path, streamId);
                break;
            case PUT:
                break;
        }
    }

    private static void setPathAndMethod(final Http2Stream stream, final String path, final AsciiString method) {
        stream.setProperty(PATH_KEY, path);
        stream.setProperty(METHOD_KEY, method);
    }

    private void verifyAggregateMimeType(final Http2Headers headers) {
        if (!AGGREGATION_JSON.equals(headers.get(CONTENT_TYPE))) {
            // TODO: handle a stream error. Needs to be investigate what the proper handling is.
        }
    }

    @Override
    public int onDataRead(final ChannelHandlerContext ctx,
                          final int streamId,
                          final ByteBuf data,
                          final int padding,
                          final boolean endOfStream) throws Http2Exception {
        final Http2Stream stream = encoder.connection().stream(streamId);
        final String path = stream.getProperty(PATH_KEY);
        LOGGER.info("onDataRead. streamId={}, path={}, endstream={}", streamId, path, endOfStream);
        if (path.contains("/register")) {
            handleDeviceRegistration(ctx, streamId);
        } else if (path.contains("channel")) {
            handleChannelCreation(ctx, path, streamId);
        } else if (path.contains("aggregate")) {
            handleAggregateChannelCreation(ctx, path,streamId, data);
        } else {
            handleNotification(ctx, streamId, data, padding, path);
        }
        return super.onDataRead(ctx, streamId, data, padding, endOfStream);
    }

    private void handleNotification(final ChannelHandlerContext ctx,
                                    final int streamId,
                                    final ByteBuf data,
                                    final int padding,
                                    final String path) {
        final String endpointToken = extractEndpointToken(path);
        handleNotify(endpointToken, data, padding, e ->
            encoder.writeHeaders(ctx, streamId, acceptedHeaders(), 0, true, ctx.newPromise())
        );
        Optional.ofNullable(aggregateChannels.get(endpointToken)).ifPresent(agg ->
            agg.channels().stream().forEach(entry ->  handleNotify(entry.endpoint(), data, padding, e -> {} ))
        );
    }

    private void handleNotify(final String endpoint,
                              final ByteBuf data,
                              final int padding,
                              final Consumer<Http2ConnectionEncoder> consumer) {
        final Optional<String> optionalRegId = channelRegIdForEndpoint(extractEndpointToken(endpoint));
        if (optionalRegId.isPresent()) {
            final Optional<Client> optionalClient = clientForRegId(optionalRegId.get());
            if (optionalClient.isPresent()) {
                final Client client = optionalClient.get();
                LOGGER.info("Handle notification {} payload {}", client, data.toString(UTF_8));
                if (!client.isHeadersSent()) {
                    client.encoder.writeHeaders(client.ctx, client.streamId, EmptyHttp2Headers.INSTANCE, 0, false, client.ctx.newPromise())
                            .addListener(WebPushFrameListener::logFutureError);
                    client.headersSent();
                }
                client.encoder.writeData(client.ctx, client.streamId, data.retain(), padding, false, client.ctx.newPromise())
                        .addListener(WebPushFrameListener::logFutureError);
            } else {
                webpushServer.setMessage(endpoint, Optional.of(data.toString(CharsetUtil.UTF_8)));
                consumer.accept(encoder);
            }
        }
    }

    private Optional<String> channelRegIdForEndpoint(final String endpointToken) {
        return Optional.ofNullable(notificationStreams.get(endpointToken));
    }

    private Optional<Client> clientForRegId(final String regId) {
        return Optional.ofNullable(monitoredStreams.get(regId)).orElse(Optional.empty());

    }

    private static void logFutureError(final Future future) {
        if (!future.isSuccess()) {
            LOGGER.error("ChannelFuture failed. Cause: {}", future.cause());
        }
    }

    private void handleDeviceRegistration(final ChannelHandlerContext ctx, final int streamId) {
        final Registration registration = webpushServer.register();
        encoder.writeHeaders(ctx, streamId, registrationHeaders(registration), 0, true, ctx.newPromise());
        LOGGER.info("Registered {} " + registration);
    }

    private static AsciiString asLink(final URI contextUri, final String relationType) {
        return new AsciiString("<" + contextUri + ">;rel=\"" +  relationType + "\"");
    }

    private void handleChannelCreation(final ChannelHandlerContext ctx, final String path, final int streamId) {
        final String registrationId = extractRegistrationId(path, "channel");
        final Optional<Channel> channel = webpushServer.newChannel(registrationId);
        channel.ifPresent(ch -> {
            LOGGER.info("Created channel {} " + ch);
            notificationStreams.put(ch.endpointToken(), registrationId);
            encoder.writeHeaders(ctx, streamId, createdHeaders(ch), 0, true, ctx.newPromise());
        });
    }

    private Http2Headers registrationHeaders(final Registration registration) {
        return new DefaultHttp2Headers(false)
                .status(OK.codeAsText())
                .set(LOCATION, new AsciiString(registration.monitorUri().toString()))
                .set(ACCESS_CONTROL_ALLOW_ORIGIN, ANY_ORIGIN)
                .set(ACCESS_CONTROL_EXPOSE_HEADERS, new AsciiString("Link, Cache-Control, Location"))
                .set(LINK, asLink(registration.monitorUri(), WebLink.MONITOR.toString()),
                        asLink(registration.channelUri(), WebLink.CHANNEL.toString()),
                        asLink(registration.aggregateUri(), WebLink.AGGREGATE.toString()))
                .set(CACHE_CONTROL, privateCacheWithMaxAge(webpushServer.config().registrationMaxAge()));
    }

    private Http2Headers acceptedHeaders() {
        return new DefaultHttp2Headers(false)
                .status(ACCEPTED.codeAsText())
                .set(ACCESS_CONTROL_ALLOW_ORIGIN, ANY_ORIGIN)
                .set(CACHE_CONTROL, privateCacheWithMaxAge(webpushServer.config().registrationMaxAge()));
    }

    private void handleAggregateChannelCreation(final ChannelHandlerContext ctx,
                                                final String path,
                                                final int streamId,
                                                final ByteBuf data) {
        LOGGER.info("Aggregate payload={}", data.toString(UTF_8));
        final String registrationId = extractRegistrationId(path, "aggregate");
        final AggregateChannel aggregateChannel = fromJson(data.toString(UTF_8), AggregateChannel.class);
        final Optional<Channel> channel = webpushServer.newChannel(registrationId);
        channel.ifPresent(ch -> {
            LOGGER.info("Created aggregate channel {} " + ch);
            aggregateChannels.put(ch.endpointToken(), aggregateChannel);
            encoder.writeHeaders(ctx, streamId, createdHeaders(ch), 0, true, ctx.newPromise());
        });
    }

    private Http2Headers createdHeaders(final Channel channel) {
        return new DefaultHttp2Headers(false)
                .status(CREATED.codeAsText())
                .set(LOCATION, new AsciiString("webpush/" + channel.endpointToken()))
                .set(ACCESS_CONTROL_ALLOW_ORIGIN, ANY_ORIGIN)
                .set(ACCESS_CONTROL_EXPOSE_HEADERS, new AsciiString("Location"))
                .set(CACHE_CONTROL, privateCacheWithMaxAge(webpushServer.config().channelMaxAge()));
    }

    /**
     * Returns a cache-control value with this private and has the specified maxAge.
     *
     * @param maxAge the max age in seconds.
     * @return {@link AsciiString} the value for a cache-control header.
     */
    private static AsciiString privateCacheWithMaxAge(final long maxAge) {
        return new AsciiString("private, max-age=" + maxAge);
    }

    private void handleChannelRemoval(final ChannelHandlerContext ctx, final String path, final int streamId) {
        final String endpointToken = extractEndpointToken(path);
        final Optional<Channel> channel = webpushServer.getChannel(endpointToken);
        if (channel.isPresent()) {
            webpushServer.removeChannel(channel.get());
            notificationStreams.remove(endpointToken);
            encoder.writeHeaders(ctx, streamId, okHeaders(), 0, true, ctx.newPromise());
        } else {
            encoder.writeHeaders(ctx, streamId, notFoundHeaders(), 0, true, ctx.newPromise());
        }
    }

    /*
      A monitor request is responded to with a push promise. A push promise is associated with a
      previous client-initiated request (the monitor request)
     */
    private void handleMonitor(final ChannelHandlerContext ctx, final String path, final int streamId) {
        final String registrationId = extractRegistrationId(path, "monitor");
        final int pushStreamId = encoder.connection().local().nextStreamId();
        monitoredStreams.put(registrationId, Optional.of(new Client(ctx, pushStreamId, encoder)));
        LOGGER.info("Monitor ctx={}, registrationId={}, pushPromiseStreamId={}", ctx, registrationId, pushStreamId);
        encoder.writePushPromise(ctx, streamId, pushStreamId, okHeaders(), 0, ctx.newPromise());
    }

    private void handleChannelStatus(final ChannelHandlerContext ctx,
                                     final String path,
                                     final int streamId,
                                     final int padding) {
        final String endpointToken = extractEndpointToken(path);
        final Optional<Channel> channel = webpushServer.getChannel(endpointToken);
        if (channel.isPresent()) {
            LOGGER.info("Channel {}", channel);
            final Optional<String> message = channel.get().message();
            if (message.isPresent()) {
                encoder.writeHeaders(ctx, streamId, okHeaders(), 0, false, ctx.newPromise());
                encoder.writeData(ctx, streamId, copiedBuffer(message.get(), UTF_8), padding, false, ctx.newPromise());
                webpushServer.setMessage(endpointToken, Optional.empty());
            } else {
                encoder.writeHeaders(ctx, streamId, noContentHeaders(), 0, true, ctx.newPromise());
            }
        } else {
            encoder.writeHeaders(ctx, streamId, notFoundHeaders(), 0, true, ctx.newPromise());
        }
    }
    private static Http2Headers noContentHeaders() {
        return new DefaultHttp2Headers(false)
                .status(NO_CONTENT.codeAsText())
                .set(ACCESS_CONTROL_ALLOW_ORIGIN, ANY_ORIGIN);
    }

    private static Http2Headers notFoundHeaders() {
        return new DefaultHttp2Headers(false)
                .status(NOT_FOUND.codeAsText())
                .set(ACCESS_CONTROL_ALLOW_ORIGIN, ANY_ORIGIN);
    }

    private static Http2Headers okHeaders() {
        return new DefaultHttp2Headers(false)
                .status(OK.codeAsText())
                .set(ACCESS_CONTROL_ALLOW_ORIGIN, ANY_ORIGIN)
                .set(ACCESS_CONTROL_EXPOSE_HEADERS, CONTENT_TYPE);
    }


    private static String extractRegistrationId(final String path, final String segment) {
        final String subpath = path.substring(0, path.indexOf(segment) - 1);
        return subpath.subSequence(subpath.lastIndexOf('/') + 1, subpath.length()).toString();
    }

    private static String extractEndpointToken(final String path) {
        return path.substring(path.lastIndexOf("/") + 1);
    }

    private static class Client {

        private final ChannelHandlerContext ctx;
        private final Http2ConnectionEncoder encoder;
        private final int streamId;
        private final AtomicBoolean headersSent = new AtomicBoolean(false);

        Client(final ChannelHandlerContext ctx, final int streamId, final Http2ConnectionEncoder encoder) {
            this.ctx = ctx;
            this.streamId = streamId;
            this.encoder = encoder;
        }

        boolean isHeadersSent() {
            return headersSent.get();
        }

        void headersSent() {
            headersSent.set(true);
        }

        @Override
        public String toString() {
            return "Client[streamid=" + streamId + ", ctx=" + ctx + ", headersSent=" + headersSent.get() + "]";
        }

    }

}
