package com.pyrogroup.telecom_bridge_gateway.protocol;

import java.util.List;
import java.util.Optional;

/**
 * A Diameter message (RFC 6733 section 3). Immutable.
 *
 * @param request       R bit - true for a request, false for an answer
 * @param proxiable     P bit
 * @param error         E bit - answer carries a protocol error
 * @param commandCode   24-bit command code
 * @param applicationId 32-bit Application-Id (held in a long to avoid sign issues)
 * @param hopByHop      Hop-by-Hop Identifier - matches an answer to its request on one connection
 * @param endToEnd      End-to-End Identifier - duplicate detection across the whole path
 */
public record DiameterMessage(
        boolean request,
        boolean proxiable,
        boolean error,
        int commandCode,
        long applicationId,
        int hopByHop,
        int endToEnd,
        List<Avp> avps) {

    public DiameterMessage {
        avps = List.copyOf(avps);
    }

    public DiameterMessage withIdentifiers(int newHopByHop, int newEndToEnd) {
        return new DiameterMessage(request, proxiable, error, commandCode, applicationId, newHopByHop, newEndToEnd,
                avps);
    }

    public Optional<Avp> find(int avpCode) {
        for (Avp avp : avps) {
            if (avp.code() == avpCode) {
                return Optional.of(avp);
            }
        }
        return Optional.empty();
    }

    /** Looks one level down inside a Grouped AVP. */
    public Optional<Avp> findNested(int groupCode, int avpCode) {
        return find(groupCode).flatMap(group -> group.asGrouped().stream()
                .filter(child -> child.code() == avpCode)
                .findFirst());
    }

    public Optional<Long> resultCode() {
        return find(Diameter.AVP_RESULT_CODE).map(Avp::asUnsigned32);
    }

    public Optional<String> sessionId() {
        return find(Diameter.AVP_SESSION_ID).map(Avp::asUtf8);
    }

    @Override
    public String toString() {
        return "DiameterMessage[" + (request ? "REQ" : "ANS") + " cmd=" + commandCode + " app=" + applicationId
                + " hbh=" + Integer.toUnsignedString(hopByHop) + " e2e=" + Integer.toUnsignedString(endToEnd)
                + " avps=" + avps.size() + "]";
    }
}
