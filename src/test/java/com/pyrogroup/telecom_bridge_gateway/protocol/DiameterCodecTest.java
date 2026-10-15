package com.pyrogroup.telecom_bridge_gateway.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiameterCodecTest {

    private static final DiameterIdentity ID = identity();

    private static DiameterIdentity identity() {
        try {
            return new DiameterIdentity("gw.example.com", "example.com", InetAddress.getByName("10.0.0.1"), "test");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void avpLengthExcludesPaddingButWireSizeIncludesIt() {
        Avp originHost = Avp.utf8(Diameter.AVP_ORIGIN_HOST, "abc"); // 3 data bytes
        assertThat(originHost.length()).isEqualTo(11);              // 8 header + 3 data
        assertThat(originHost.paddedLength()).isEqualTo(12);        // padded to 4-byte boundary

        DiameterMessage msg = new DiameterMessage(true, false, false, 280, 0, 1, 2, List.of(originHost));
        byte[] wire = DiameterCodec.encode(msg);

        assertThat(wire.length).isEqualTo(20 + 12);
        assertThat(wire.length % 4).isZero();
        // AVP length field (bytes 25..27 = header 20 + code 4 + flags 1) holds 11, not 12
        assertThat(ByteBuffer.wrap(wire, 24, 4).getInt() & 0xFFFFFF).isEqualTo(11);
        // the single padding byte is zero
        assertThat(wire[wire.length - 1]).isZero();
    }

    @Test
    void headerFieldsAreEncodedPerRfc6733() {
        byte[] wire = DiameterCodec.encode(DiameterMessages.cer(ID).withIdentifiers(0x11223344, 0x55667788));

        assertThat(wire[0]).isEqualTo((byte) 1);                                    // version
        assertThat(((wire[1] & 0xFF) << 16 | (wire[2] & 0xFF) << 8 | (wire[3] & 0xFF))).isEqualTo(wire.length);
        assertThat(wire[4] & 0xFF).isEqualTo(0x80);                                 // R bit only (CER is not proxiable)
        assertThat(((wire[5] & 0xFF) << 16 | (wire[6] & 0xFF) << 8 | (wire[7] & 0xFF))).isEqualTo(257);
        assertThat(ByteBuffer.wrap(wire, 8, 4).getInt()).isZero();                  // Application-Id 0
        assertThat(ByteBuffer.wrap(wire, 12, 4).getInt()).isEqualTo(0x11223344);    // Hop-by-Hop
        assertThat(ByteBuffer.wrap(wire, 16, 4).getInt()).isEqualTo(0x55667788);    // End-to-End
    }

    @Test
    void ccrRoundTripsIncludingGroupedAvps() {
        DiameterMessage ccr = DiameterMessages.ccr(ID, "example.com", "32251@3gpp.org", "gw;1;2",
                Diameter.CC_INITIAL_REQUEST, 0, "919876543210", 1_048_576L).withIdentifiers(7, 9);

        DiameterMessage decoded = DiameterCodec.decode(DiameterCodec.encode(ccr));

        assertThat(decoded.request()).isTrue();
        assertThat(decoded.proxiable()).isTrue();
        assertThat(decoded.commandCode()).isEqualTo(Diameter.CMD_CREDIT_CONTROL);
        assertThat(decoded.applicationId()).isEqualTo(Diameter.APP_CREDIT_CONTROL);
        assertThat(decoded.hopByHop()).isEqualTo(7);
        assertThat(decoded.endToEnd()).isEqualTo(9);
        assertThat(decoded.sessionId()).contains("gw;1;2");
        assertThat(decoded.find(Diameter.AVP_CC_REQUEST_TYPE).orElseThrow().asInteger32())
                .isEqualTo(Diameter.CC_INITIAL_REQUEST);
        assertThat(decoded.findNested(Diameter.AVP_SUBSCRIPTION_ID, Diameter.AVP_SUBSCRIPTION_ID_DATA)
                .orElseThrow().asUtf8()).isEqualTo("919876543210");
        assertThat(decoded.findNested(Diameter.AVP_SUBSCRIPTION_ID, Diameter.AVP_SUBSCRIPTION_ID_TYPE)
                .orElseThrow().asInteger32()).isEqualTo(Diameter.SUBSCRIPTION_ID_END_USER_E164);
        assertThat(decoded.findNested(Diameter.AVP_REQUESTED_SERVICE_UNIT, Diameter.AVP_CC_TOTAL_OCTETS)
                .orElseThrow().asUnsigned64()).isEqualTo(1_048_576L);
    }

    @Test
    void ccaCopiesIdentifiersAndSessionFromRequest() {
        DiameterMessage ccr = DiameterMessages.ccr(ID, "example.com", "ctx", "gw;1;2",
                Diameter.CC_INITIAL_REQUEST, 0, "1234567", 500).withIdentifiers(42, 43);

        DiameterMessage cca = DiameterCodec.decode(DiameterCodec.encode(
                DiameterMessages.cca(ID, ccr, Diameter.RESULT_SUCCESS, 500)));

        assertThat(cca.request()).isFalse();
        assertThat(cca.hopByHop()).isEqualTo(42);
        assertThat(cca.endToEnd()).isEqualTo(43);
        assertThat(cca.sessionId()).contains("gw;1;2");
        assertThat(cca.resultCode()).contains(2001L);
        assertThat(cca.findNested(Diameter.AVP_GRANTED_SERVICE_UNIT, Diameter.AVP_CC_TOTAL_OCTETS)
                .orElseThrow().asUnsigned64()).isEqualTo(500);
    }

    @Test
    void unsigned32AboveIntMaxSurvivesRoundTrip() {
        Avp avp = Avp.unsigned32(Diameter.AVP_RESULT_CODE, 4_000_000_000L);
        assertThat(avp.asUnsigned32()).isEqualTo(4_000_000_000L);
    }

    @Test
    void timeAvpUsesNtpEpoch() {
        Avp avp = Avp.time(Diameter.AVP_EVENT_TIMESTAMP, Instant.parse("1970-01-01T00:00:00Z"));
        assertThat(avp.asUnsigned32()).isEqualTo(2_208_988_800L);
    }

    @Test
    void vendorSpecificAvpCarriesVendorIdAndExtendedHeader() {
        Avp vendorAvp = new Avp(1000, 10415, false, new byte[] {1, 2, 3, 4});
        assertThat(vendorAvp.length()).isEqualTo(16);

        DiameterMessage msg = new DiameterMessage(true, false, false, 272, 4, 1, 1, List.of(vendorAvp));
        DiameterMessage decoded = DiameterCodec.decode(DiameterCodec.encode(msg));

        assertThat(decoded.avps()).hasSize(1);
        assertThat(decoded.avps().get(0).vendorId()).isEqualTo(10415);
        assertThat(decoded.avps().get(0).data()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void rejectsTruncatedAndInconsistentMessages() {
        byte[] wire = DiameterCodec.encode(DiameterMessages.dwr(ID));

        assertThatThrownBy(() -> DiameterCodec.decode(Arrays.copyOf(wire, 10)))
                .isInstanceOf(DiameterProtocolException.class);
        assertThatThrownBy(() -> DiameterCodec.decode(Arrays.copyOf(wire, wire.length - 1)))
                .isInstanceOf(DiameterProtocolException.class)
                .hasMessageContaining("length");

        byte[] badVersion = wire.clone();
        badVersion[0] = 2;
        assertThatThrownBy(() -> DiameterCodec.decode(badVersion)).isInstanceOf(DiameterProtocolException.class);
    }

    @Test
    void rejectsAvpWhoseLengthOverrunsTheMessage() {
        byte[] wire = DiameterCodec.encode(DiameterMessages.dwr(ID));
        wire[25] = 0x7F; // corrupt the first AVP's length field (bytes 25..27) to something huge
        assertThatThrownBy(() -> DiameterCodec.decode(wire)).isInstanceOf(DiameterProtocolException.class);
    }
}
