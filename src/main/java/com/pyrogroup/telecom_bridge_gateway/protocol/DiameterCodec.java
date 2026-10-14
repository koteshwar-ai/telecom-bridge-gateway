package com.pyrogroup.telecom_bridge_gateway.protocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java binary codec for Diameter messages (no Netty dependency, so it is trivially unit-testable).
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Version    |                 Message Length                |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | command flags |                  Command-Code                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Application-ID                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      Hop-by-Hop Identifier                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      End-to-End Identifier                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  AVPs ...
 * </pre>
 */
public final class DiameterCodec {

    public static final int HEADER_LENGTH = 20;
    private static final int VERSION = 1;

    private static final int FLAG_REQUEST = 0x80;
    private static final int FLAG_PROXIABLE = 0x40;
    private static final int FLAG_ERROR = 0x20;

    private static final int AVP_FLAG_VENDOR = 0x80;
    private static final int AVP_FLAG_MANDATORY = 0x40;

    private DiameterCodec() {
    }

    // ------------------------------------------------------------------ encode

    public static byte[] encode(DiameterMessage message) {
        int length = HEADER_LENGTH;
        for (Avp avp : message.avps()) {
            length += avp.paddedLength();
        }
        if (length > 0xFFFFFF) {
            throw new IllegalArgumentException("Diameter message too large: " + length);
        }
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.put((byte) VERSION);
        put24(buf, length);
        int flags = (message.request() ? FLAG_REQUEST : 0)
                | (message.proxiable() ? FLAG_PROXIABLE : 0)
                | (message.error() ? FLAG_ERROR : 0);
        buf.put((byte) flags);
        put24(buf, message.commandCode());
        buf.putInt((int) message.applicationId());
        buf.putInt(message.hopByHop());
        buf.putInt(message.endToEnd());
        for (Avp avp : message.avps()) {
            writeAvp(buf, avp);
        }
        return buf.array();
    }

    static byte[] encodeAvps(List<Avp> avps) {
        int length = 0;
        for (Avp avp : avps) {
            length += avp.paddedLength();
        }
        ByteBuffer buf = ByteBuffer.allocate(length);
        for (Avp avp : avps) {
            writeAvp(buf, avp);
        }
        return buf.array();
    }

    private static void writeAvp(ByteBuffer buf, Avp avp) {
        buf.putInt(avp.code());
        boolean vendor = avp.vendorId() != 0;
        buf.put((byte) ((vendor ? AVP_FLAG_VENDOR : 0) | (avp.mandatory() ? AVP_FLAG_MANDATORY : 0)));
        put24(buf, avp.length()); // length EXCLUDES padding
        if (vendor) {
            buf.putInt((int) avp.vendorId());
        }
        buf.put(avp.data());
        for (int i = avp.length(); i < avp.paddedLength(); i++) {
            buf.put((byte) 0); // padding to a 4-byte boundary
        }
    }

    // ------------------------------------------------------------------ decode

    public static DiameterMessage decode(byte[] raw) {
        if (raw.length < HEADER_LENGTH) {
            throw new DiameterProtocolException("Message shorter than header: " + raw.length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int version = buf.get() & 0xFF;
        if (version != VERSION) {
            throw new DiameterProtocolException("Unsupported Diameter version " + version);
        }
        int length = get24(buf);
        if (length != raw.length) {
            throw new DiameterProtocolException(
                    "Message length field " + length + " does not match frame size " + raw.length);
        }
        int flags = buf.get() & 0xFF;
        int commandCode = get24(buf);
        long applicationId = Integer.toUnsignedLong(buf.getInt());
        int hopByHop = buf.getInt();
        int endToEnd = buf.getInt();
        List<Avp> avps = decodeAvps(buf);
        return new DiameterMessage((flags & FLAG_REQUEST) != 0, (flags & FLAG_PROXIABLE) != 0,
                (flags & FLAG_ERROR) != 0, commandCode, applicationId, hopByHop, endToEnd, avps);
    }

    static List<Avp> decodeAvps(ByteBuffer buf) {
        List<Avp> avps = new ArrayList<>();
        while (buf.hasRemaining()) {
            if (buf.remaining() < 8) {
                throw new DiameterProtocolException("Truncated AVP header");
            }
            int code = buf.getInt();
            int flags = buf.get() & 0xFF;
            int length = get24(buf);
            boolean vendor = (flags & AVP_FLAG_VENDOR) != 0;
            int headerLength = vendor ? 12 : 8;
            if (length < headerLength) {
                throw new DiameterProtocolException("AVP " + code + " has invalid length " + length);
            }
            long vendorId = 0;
            if (vendor) {
                if (buf.remaining() < 4) {
                    throw new DiameterProtocolException("Truncated vendor id in AVP " + code);
                }
                vendorId = Integer.toUnsignedLong(buf.getInt());
            }
            int dataLength = length - headerLength;
            if (dataLength > buf.remaining()) {
                throw new DiameterProtocolException("AVP " + code + " length " + length + " exceeds message");
            }
            byte[] data = new byte[dataLength];
            buf.get(data);
            int padding = ((length + 3) & ~3) - length;
            buf.position(buf.position() + Math.min(padding, buf.remaining()));
            avps.add(new Avp(code, vendorId, (flags & AVP_FLAG_MANDATORY) != 0, data));
        }
        return avps;
    }

    // ------------------------------------------------------------------ 24-bit helpers

    private static void put24(ByteBuffer buf, int value) {
        buf.put((byte) (value >>> 16));
        buf.put((byte) (value >>> 8));
        buf.put((byte) value);
    }

    private static int get24(ByteBuffer buf) {
        return ((buf.get() & 0xFF) << 16) | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
    }
}
