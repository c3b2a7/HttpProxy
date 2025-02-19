package com.arloor.forwardproxy.handler;

import com.arloor.forwardproxy.monitor.ChannelTrafficMonitor;
import com.arloor.forwardproxy.monitor.GlobalTrafficMonitor;
import com.arloor.forwardproxy.ssl.SslContextFactory;
import com.arloor.forwardproxy.trace.TraceConstant;
import com.arloor.forwardproxy.trace.Tracer;
import com.arloor.forwardproxy.vo.SslConfig;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.concurrent.TimeUnit;

public class HttpsProxyServerInitializer extends ChannelInitializer<SocketChannel> {
    private static final Logger log = LoggerFactory.getLogger(HttpsProxyServerInitializer.class);

    private final SslConfig sslConfig;

    private SslContext sslCtx;

    public HttpsProxyServerInitializer(SslConfig sslConfig) {
        this.sslConfig = sslConfig;
        loadSslContext();
    }

    @Override
    public void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        p.addLast(GlobalTrafficMonitor.getInstance());
        p.addLast(new IdleStateHandler(0, 0, 15, TimeUnit.SECONDS));
        Span streamSpan = Tracer.spanBuilder(TraceConstant.stream.name())
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(TraceConstant.client.name(), ch.remoteAddress().getHostName())
                .startSpan();
        p.addLast(new ChannelTrafficMonitor(1000, streamSpan));
        if (sslCtx != null) {
            p.addLast(sslCtx.newHandler(ch.alloc()));
        }
        p.addLast(new HttpRequestDecoder());
        p.addLast(new HttpResponseEncoder());
        p.addLast(new HttpServerExpectContinueHandler());
        p.addLast(SessionHandShakeHandler.NAME, new SessionHandShakeHandler(sslConfig.getAuthMap(), streamSpan, new HashSet<>()));

    }

    public void loadSslContext() {
        try {
            this.sslCtx = SslContextFactory.getSSLContext(sslConfig.getFullchain(), sslConfig.getPrivkey());
        } catch (Throwable e) {
            log.error("init ssl context error!", e);
        }
    }
}
