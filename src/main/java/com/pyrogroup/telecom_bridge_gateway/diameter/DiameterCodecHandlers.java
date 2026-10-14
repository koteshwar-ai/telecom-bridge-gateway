package com.pyrogroup.telecom_bridge_gateway.diameter;

import com.pyrogroup.telecom_bridge_gateway.protocol.DiameterCodec;
import com.pyrogroup.telecom_bridge_gateway.protocol.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;

/** Netty glue: TCP byte stream <-> {@link DiameterMessage}. Used by both the client and the simulator. */
public final class DiameterCodecHandlers {

    private static final int MAX_MESSAGE_BYTES = 1 << 20;

    private DiameterCodecHandlers() {
    }

    public static void install(ChannelPipeline pipeline) {
        // Diameter length = 3 bytes at offset 1 and covers the WHOLE message (header included), so
        // adjustment = -(offset + lengthFieldLength) = -4 gives frame = length field value.
        pipeline.addLast("diameter-frame", new LengthFieldBasedFrameDecoder(MAX_MESSAGE_BYTES, 1, 3, -4, 0));
        pipeline.addLast("diameter-decoder", new Decoder());
        pipeline.addLast("diameter-encoder", new Encoder());
    }

    private static final class Decoder extends MessageToMessageDecoder<ByteBuf> {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
            byte[] raw = new byte[frame.readableBytes()];
            frame.readBytes(raw); // copy out so nothing keeps a reference to the pooled buffer
            out.add(DiameterCodec.decode(raw));
        }
    }

    private static final class Encoder extends MessageToByteEncoder<DiameterMessage> {
        @Override
        protected void encode(ChannelHandlerContext ctx, DiameterMessage message, ByteBuf out) {
            out.writeBytes(DiameterCodec.encode(message));
        }
    }
}
