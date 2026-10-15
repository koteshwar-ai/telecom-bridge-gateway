package com.pyrogroup.telecom_bridge_gateway.diameter;

import static com.pyrogroup.telecom_bridge_gateway.protocol.Diameter.*;

import com.pyrogroup.telecom_bridge_gateway.protocol.DiameterIdentity;
import com.pyrogroup.telecom_bridge_gateway.protocol.DiameterMessage;
import com.pyrogroup.telecom_bridge_gateway.protocol.DiameterMessages;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Asynchronous Diameter client over a single persistent TCP connection.
 *
 * <ul>
 *   <li>CER/CEA on connect, DWR/DWA watchdog while idle, DPR/DPA on shutdown, auto-reconnect with
 *       exponential back-off.</li>
 *   <li>{@link #sendRequest} never blocks: it registers a {@link CompletableFuture} under the request's
 *       Hop-by-Hop id in a {@link ConcurrentHashMap}; the Netty I/O thread completes it when the answer
 *       with the same id arrives.</li>
 *   <li>Every path removes the map entry (answer, timeout, write failure, disconnect, shutdown), so the
 *       map cannot grow without bound.</li>
 * </ul>
 */
@Slf4j
@Component
public class DiameterClient {

    public enum State { DISCONNECTED, CONNECTING, WAITING_FOR_CEA, OPEN, STOPPED }

    private final DiameterProperties props;
    private final DiameterIdentity identity;

    /** Hop-by-Hop id -> caller waiting for the answer. */
    private final ConcurrentHashMap<Integer, CompletableFuture<DiameterMessage>> pending = new ConcurrentHashMap<>();

    private final AtomicInteger hopByHop = new AtomicInteger(ThreadLocalRandom.current().nextInt());
    private final AtomicInteger endToEnd;
    private final AtomicLong sessionSeq;

    private volatile State state = State.DISCONNECTED;
    private volatile Channel channel;
    private volatile int reconnectAttempt;
    private EventLoopGroup group;
    private Bootstrap bootstrap;

    public DiameterClient(DiameterProperties props) {
        this.props = props;
        InetAddress ip;
        try {
            ip = InetAddress.getByName(props.hostIp());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid diameter.host-ip: " + props.hostIp(), e);
        }
        this.identity = new DiameterIdentity(props.originHost(), props.originRealm(), ip, "telecom-bridge-gateway");
        long nowSeconds = Instant.now().getEpochSecond();
        // RFC 6733 4.3 / 3: End-to-End = 12 high bits of time + 20 random bits, then incremented.
        this.endToEnd = new AtomicInteger(
                (int) (((nowSeconds & 0xFFF) << 20) | (ThreadLocalRandom.current().nextInt() & 0xFFFFF)));
        // Session-Id suffix: unique 64-bit value initialised from the time, as RFC 6733 8.8 suggests.
        this.sessionSeq = new AtomicLong(nowSeconds << 32);
    }

    // ================================================================== lifecycle

    @PostConstruct
    public void start() {
        group = new NioEventLoopGroup(2, new DefaultThreadFactory("diameter-io"));
        bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) props.connectTimeout().toMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new IdleStateHandler(0, 0, props.watchdogInterval().toMillis(),
                                TimeUnit.MILLISECONDS));
                        DiameterCodecHandlers.install(ch.pipeline());
                        ch.pipeline().addLast(new ClientHandler());
                    }
                });
        connect();
    }

    @PreDestroy
    public void stop() {
        State previous = state;
        state = State.STOPPED;
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            try {
                if (previous == State.OPEN) {
                    ch.writeAndFlush(DiameterMessages.dpr(identity).withIdentifiers(
                            hopByHop.incrementAndGet(), endToEnd.incrementAndGet())).await(1, TimeUnit.SECONDS);
                }
                ch.close().await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        failAllPending(new DiameterUnavailableException("Gateway is shutting down"));
        if (group != null) {
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
        log.info("Diameter client stopped");
    }

    // ================================================================== public API

    /**
     * Sends a request and returns a future for the answer. Completes exceptionally with
     * {@link DiameterUnavailableException}, {@link DiameterOverloadedException} or
     * {@link java.util.concurrent.TimeoutException}.
     */
    public CompletableFuture<DiameterMessage> sendRequest(DiameterMessage request) {
        Channel ch = channel;
        if (state != State.OPEN || ch == null || !ch.isActive()) {
            return CompletableFuture.failedFuture(
                    new DiameterUnavailableException("Diameter peer not connected (state=" + state + ")"));
        }
        if (pending.size() >= props.maxPending() || !ch.isWritable()) {
            return CompletableFuture.failedFuture(new DiameterOverloadedException(
                    "Too many in-flight Diameter requests (" + pending.size() + ")"));
        }

        CompletableFuture<DiameterMessage> future = new CompletableFuture<>();
        int hbh = hopByHop.incrementAndGet();
        while (pending.putIfAbsent(hbh, future) != null) { // extremely unlikely wrap-around collision
            hbh = hopByHop.incrementAndGet();
        }
        final int key = hbh;
        // Whatever the outcome (answer / timeout / error), drop the entry: no leaks.
        future.orTimeout(props.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((answer, error) -> pending.remove(key, future));

        DiameterMessage wire = request.withIdentifiers(key, endToEnd.incrementAndGet());
        ch.writeAndFlush(wire).addListener(written -> {
            if (!written.isSuccess()) {
                future.completeExceptionally(
                        new DiameterUnavailableException("Failed to write to Diameter peer", written.cause()));
            }
        });
        return future;
    }

    /** RFC 6733 8.8: {@code <DiameterIdentity>;<high 32 bits>;<low 32 bits>}. */
    public String newSessionId() {
        long v = sessionSeq.incrementAndGet();
        return identity.host() + ";" + (v >>> 32) + ";" + (v & 0xFFFFFFFFL);
    }

    public DiameterIdentity identity() {
        return identity;
    }

    public boolean isOpen() {
        return state == State.OPEN;
    }

    public State state() {
        return state;
    }

    public int pendingCount() {
        return pending.size();
    }

    // ================================================================== connection management

    private void connect() {
        if (state == State.STOPPED) {
            return;
        }
        state = State.CONNECTING;
        log.info("Connecting to Diameter peer {}:{}", props.host(), props.port());
        bootstrap.connect(props.host(), props.port()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warn("Diameter connect to {}:{} failed: {}", props.host(), props.port(), f.cause().toString());
                if (state != State.STOPPED) {
                    state = State.DISCONNECTED;
                }
                scheduleReconnect();
            }
            // on success the handler's channelActive() sends the CER
        });
    }

    private void scheduleReconnect() {
        if (state == State.STOPPED) {
            return;
        }
        long initial = props.reconnectInitialDelay().toMillis();
        long max = props.reconnectMaxDelay().toMillis();
        long delay = Math.min(max, initial << Math.min(reconnectAttempt, 20));
        delay += ThreadLocalRandom.current().nextLong(Math.max(1, delay / 5)); // jitter
        reconnectAttempt++;
        log.info("Reconnecting to Diameter peer in {} ms (attempt {})", delay, reconnectAttempt);
        try {
            group.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Reconnect not scheduled, event loop is shut down");
        }
    }

    private void onDisconnected(Channel ch) {
        if (channel == ch) {
            channel = null;
        }
        if (state != State.STOPPED) {
            state = State.DISCONNECTED;
        }
        failAllPending(new DiameterUnavailableException("Diameter connection lost"));
        scheduleReconnect();
    }

    private void failAllPending(Throwable cause) {
        Iterator<Map.Entry<Integer, CompletableFuture<DiameterMessage>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, CompletableFuture<DiameterMessage>> entry = it.next();
            if (pending.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().completeExceptionally(cause);
            }
        }
    }

    // ================================================================== Netty handler (one per connection)

    private final class ClientHandler extends SimpleChannelInboundHandler<DiameterMessage> {

        private boolean watchdogOutstanding;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            channel = ctx.channel();
            state = State.WAITING_FOR_CEA;
            log.info("TCP connected to {}, sending CER", ctx.channel().remoteAddress());
            ctx.writeAndFlush(DiameterMessages.cer(identity)
                    .withIdentifiers(hopByHop.incrementAndGet(), endToEnd.incrementAndGet()));
            ctx.executor().schedule(() -> {
                if (state == State.WAITING_FOR_CEA && ctx.channel().isActive()) {
                    log.warn("No CEA within {}, closing connection", props.capabilitiesTimeout());
                    ctx.close();
                }
            }, props.capabilitiesTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DiameterMessage msg) {
            watchdogOutstanding = false; // any traffic proves the peer is alive
            if (msg.request()) {
                handleRequest(ctx, msg);
            } else {
                handleAnswer(ctx, msg);
            }
        }

        private void handleRequest(ChannelHandlerContext ctx, DiameterMessage req) {
            switch (req.commandCode()) {
                case CMD_DEVICE_WATCHDOG -> ctx.writeAndFlush(DiameterMessages.dwa(identity, req, RESULT_SUCCESS));
                case CMD_DISCONNECT_PEER -> {
                    log.info("Peer sent DPR, disconnecting");
                    ctx.writeAndFlush(DiameterMessages.dpa(identity, req, RESULT_SUCCESS))
                            .addListener(ChannelFutureListener.CLOSE);
                }
                default -> {
                    log.warn("Unsupported request from peer: {}", req);
                    ctx.writeAndFlush(DiameterMessages.errorAnswer(identity, req, RESULT_COMMAND_UNSUPPORTED));
                }
            }
        }

        private void handleAnswer(ChannelHandlerContext ctx, DiameterMessage ans) {
            switch (ans.commandCode()) {
                case CMD_CAPABILITIES_EXCHANGE -> {
                    long rc = ans.resultCode().orElse(0L);
                    if (rc == RESULT_SUCCESS) {
                        state = State.OPEN;
                        reconnectAttempt = 0;
                        log.info("Diameter connection OPEN (CEA Result-Code {})", rc);
                    } else {
                        log.error("CEA rejected with Result-Code {}, closing", rc);
                        ctx.close();
                    }
                }
                case CMD_DEVICE_WATCHDOG -> log.debug("DWA received");
                case CMD_DISCONNECT_PEER -> log.debug("DPA received");
                default -> {
                    CompletableFuture<DiameterMessage> waiter = pending.remove(ans.hopByHop());
                    if (waiter == null) {
                        log.debug("Answer with unknown/expired hop-by-hop {} dropped", ans.hopByHop());
                    } else {
                        waiter.complete(ans);
                    }
                }
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.ALL_IDLE && state == State.OPEN) {
                if (watchdogOutstanding) {
                    log.warn("No answer to DWR, closing connection");
                    ctx.close();
                } else {
                    watchdogOutstanding = true;
                    log.debug("Idle, sending DWR");
                    ctx.writeAndFlush(DiameterMessages.dwr(identity)
                            .withIdentifiers(hopByHop.incrementAndGet(), endToEnd.incrementAndGet()));
                }
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.warn("Diameter connection closed");
            onDisconnected(ctx.channel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Diameter channel error, closing: {}", cause.toString());
            ctx.close();
        }
    }
}
