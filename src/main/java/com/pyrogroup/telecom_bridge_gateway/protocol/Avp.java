package com.pyrogroup.telecom_bridge_gateway.protocol;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A single Attribute-Value Pair (RFC 6733 section 4.1).
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           AVP Code                            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V M P r r r r r|                  AVP Length                   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        Vendor-ID (opt)                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Data ... (padded with zeros to a 4-byte boundary)
 * </pre>
 *
 * The AVP Length field does <b>not</b> include the trailing padding; the next AVP always starts on a
 * 4-byte boundary. {@code vendorId == 0} means the V bit is not set.
 */
public record Avp(int code, long vendorId, boolean mandatory, byte[] data) {

    /** Seconds between the NTP epoch (1900-01-01) and the Unix epoch, used by the Time AVP type. */
    private static final long NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L;

    public Avp {
        Objects.requireNonNull(data, "data");
    }

    // ---------------------------------------------------------------- factories

    public static Avp utf8(int code, String value) {
        return new Avp(code, 0, true, value.getBytes(StandardCharsets.UTF_8));
    }

    public static Avp unsigned32(int code, long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("Unsigned32 out of range: " + value);
        }
        return new Avp(code, 0, true, ByteBuffer.allocate(4).putInt((int) value).array());
    }

    public static Avp integer32(int code, int value) {
        return new Avp(code, 0, true, ByteBuffer.allocate(4).putInt(value).array());
    }

    public static Avp unsigned64(int code, long value) {
        return new Avp(code, 0, true, ByteBuffer.allocate(8).putLong(value).array());
    }

    /** Address type: 2-byte address family (1 = IPv4, 2 = IPv6) followed by the raw address. */
    public static Avp address(int code, InetAddress address) {
        byte[] raw = address.getAddress();
        int family = address instanceof Inet4Address ? 1 : 2;
        return new Avp(code, 0, true, ByteBuffer.allocate(2 + raw.length).putShort((short) family).put(raw).array());
    }

    /** Time type: seconds since 1900-01-01 as an Unsigned32. */
    public static Avp time(int code, Instant instant) {
        long ntpSeconds = instant.getEpochSecond() + NTP_EPOCH_OFFSET_SECONDS;
        return new Avp(code, 0, false, ByteBuffer.allocate(4).putInt((int) ntpSeconds).array());
    }

    public static Avp grouped(int code, Avp... children) {
        return new Avp(code, 0, true, DiameterCodec.encodeAvps(List.of(children)));
    }

    /** Same AVP with the M (Mandatory) bit cleared, for AVPs that the RFC says MUST NOT carry it. */
    public Avp nonMandatory() {
        return new Avp(code, vendorId, false, data);
    }

    // ---------------------------------------------------------------- sizes

    public int headerLength() {
        return vendorId != 0 ? 12 : 8;
    }

    /** Value of the AVP Length field: header + data, excluding padding. */
    public int length() {
        return headerLength() + data.length;
    }

    /** Bytes the AVP occupies on the wire, including padding to a 4-byte boundary. */
    public int paddedLength() {
        return (length() + 3) & ~3;
    }

    // ---------------------------------------------------------------- accessors

    public String asUtf8() {
        return new String(data, StandardCharsets.UTF_8);
    }

    public long asUnsigned32() {
        requireLength(4);
        return Integer.toUnsignedLong(ByteBuffer.wrap(data).getInt());
    }

    public int asInteger32() {
        requireLength(4);
        return ByteBuffer.wrap(data).getInt();
    }

    public long asUnsigned64() {
        requireLength(8);
        return ByteBuffer.wrap(data).getLong();
    }

    public List<Avp> asGrouped() {
        return DiameterCodec.decodeAvps(ByteBuffer.wrap(data));
    }

    private void requireLength(int expected) {
        if (data.length != expected) {
            throw new DiameterProtocolException(
                    "AVP " + code + " expected " + expected + " data bytes but has " + data.length);
        }
    }

    @Override
    public String toString() {
        return "Avp[code=" + code + ", vendorId=" + vendorId + ", mandatory=" + mandatory + ", dataLength="
                + data.length + "]";
    }
}
