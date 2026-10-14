package com.pyrogroup.telecom_bridge_gateway.simulator;

import static com.pyrogroup.telecom_bridge_gateway.protocol.Diameter.*;

import com.pyrogroup.telecom_bridge_gateway.diameter.DiameterCodecHandlers;
import com.pyrogroup.telecom_bridge_gateway.protocol.Avp;
import com.pyrogroup.telecom_bridge_gateway.protocol.DiameterIdentity;
import com.pyrogroup.telecom_bridge_gateway.protocol.DiameterMessage;
import com.pyrogroup.telecom_bridge_gateway.protocol.DiameterMessages;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal Diameter credit-control server. Answers CER, DWR, DPR immediately and every CCR with a CCA
 * (Result-Code 2001) after a random delay in [minDelayMs, maxDelayMs]. The delay is scheduled on the
 * connection's event loop, so a slow answer never blocks other requests (no Thread.sleep).
 */
@Slf4j
public final class DiameterSimulator implements AutoCloseable {

    private final int port;
    private final int minDelayMs;
    private final int maxDelayMs;
    private final DiameterIdentity identity;
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel serverChannel;

    public DiameterSimulator(int port, int minDelayMs, int maxDelayMs) {
        if (minDelayMs < 0 || maxDelayMs < minDelayMs) {
            throw new IllegalArgumentException("Invalid delay range " + minDelayMs + ".." + maxDelayMs);
        }
        this.port = port;
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.identity = new DiameterIdentity("simulator.example.com", "example.com",
                InetAddress.getLoopbackAddress(), "diameter-simulator");
    }

    /** Binds and returns the actual port (useful when constructed with port 0). */
    public int start() throws InterruptedException {
        boss = new NioEventLoopGroup(1, new DefaultThreadFactory("sim-boss"));
        workers = new NioEventLoopGroup(2, new DefaultThreadFactory("sim-worker"));
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        DiameterCodecHandlers.install(ch.pipeline());
                        ch.pipeline().addLast(new ServerHandler());
                    }
                });
        serverChannel = bootstrap.bind(port).sync().channel();
        int bound = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        log.info("Diameter simulator listening on port {} (CCA delay {}-{} ms)", bound, minDelayMs, maxDelayMs);
        return bound;
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (workers != null) {
            workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @ChannelHandler.Sharable
    private final class ServerHandler extends SimpleChannelInboundHandler<DiameterMessage> {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            log.info("Peer connected: {}", ctx.channel().remoteAddress());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.info("Peer disconnected: {}", ctx.channel().remoteAddress());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DiameterMessage msg) {
            if (!msg.request()) {
                return; // we never send requests, ignore stray answers
            }
            switch (msg.commandCode()) {
                case CMD_CAPABILITIES_EXCHANGE -> {
                    log.info("CER from {} -> CEA 2001",
                            msg.find(AVP_ORIGIN_HOST).map(Avp::asUtf8).orElse("?"));
                    ctx.writeAndFlush(DiameterMessages.cea(identity, msg, RESULT_SUCCESS));
                }
                case CMD_DEVICE_WATCHDOG -> ctx.writeAndFlush(DiameterMessages.dwa(identity, msg, RESULT_SUCCESS));
                case CMD_DISCONNECT_PEER -> ctx.writeAndFlush(DiameterMessages.dpa(identity, msg, RESULT_SUCCESS))
                        .addListener(ChannelFutureListener.CLOSE);
                case CMD_CREDIT_CONTROL -> answerCcrLater(ctx, msg);
                default -> ctx.writeAndFlush(DiameterMessages.errorAnswer(identity, msg, RESULT_COMMAND_UNSUPPORTED));
            }
        }

        private void answerCcrLater(ChannelHandlerContext ctx, DiameterMessage ccr) {
            long units = ccr.findNested(AVP_REQUESTED_SERVICE_UNIT, AVP_CC_TOTAL_OCTETS)
                    .map(Avp::asUnsigned64).orElse(0L);
            DiameterMessage cca = DiameterMessages.cca(identity, ccr, RESULT_SUCCESS, units);
            long delay = ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs + 1L);
            log.debug("CCR {} -> CCA in {} ms", ccr.sessionId().orElse("?"), delay);
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(cca);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("Closing connection after error: {}", cause.toString());
            ctx.close();
        }
    }
}
