package com.pyrogroup.telecom_bridge_gateway.protocol;

import static com.pyrogroup.telecom_bridge_gateway.protocol.Diameter.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builders for the messages used in this project. Shared by the gateway client and the simulator so
 * both sides speak exactly the same dialect. Hop-by-Hop / End-to-End are left at 0 on requests; the
 * connection owner stamps them with {@link DiameterMessage#withIdentifiers(int, int)}.
 */
public final class DiameterMessages {

    private DiameterMessages() {
    }

    // ------------------------------------------------------------------ base protocol

    /** Capabilities-Exchange-Request (RFC 6733 5.3.1). Application-Id 0, P bit clear. */
    public static DiameterMessage cer(DiameterIdentity id) {
        return new DiameterMessage(true, false, false, CMD_CAPABILITIES_EXCHANGE, APP_BASE, 0, 0, List.of(
                Avp.utf8(AVP_ORIGIN_HOST, id.host()),
                Avp.utf8(AVP_ORIGIN_REALM, id.realm()),
                Avp.address(AVP_HOST_IP_ADDRESS, id.ip()),
                Avp.unsigned32(AVP_VENDOR_ID, 0),
                Avp.utf8(AVP_PRODUCT_NAME, id.productName()).nonMandatory(),
                Avp.unsigned32(AVP_AUTH_APPLICATION_ID, APP_CREDIT_CONTROL)));
    }

    public static DiameterMessage cea(DiameterIdentity id, DiameterMessage cer, long resultCode) {
        List<Avp> avps = answerAvps(id, cer, resultCode);
        avps.add(Avp.address(AVP_HOST_IP_ADDRESS, id.ip()));
        avps.add(Avp.unsigned32(AVP_VENDOR_ID, 0));
        avps.add(Avp.utf8(AVP_PRODUCT_NAME, id.productName()).nonMandatory());
        avps.add(Avp.unsigned32(AVP_AUTH_APPLICATION_ID, APP_CREDIT_CONTROL));
        return answer(cer, false, avps);
    }

    /** Device-Watchdog-Request (RFC 6733 5.5.1). */
    public static DiameterMessage dwr(DiameterIdentity id) {
        return new DiameterMessage(true, false, false, CMD_DEVICE_WATCHDOG, APP_BASE, 0, 0, List.of(
                Avp.utf8(AVP_ORIGIN_HOST, id.host()),
                Avp.utf8(AVP_ORIGIN_REALM, id.realm())));
    }

    public static DiameterMessage dwa(DiameterIdentity id, DiameterMessage dwr, long resultCode) {
        return answer(dwr, false, answerAvps(id, dwr, resultCode));
    }

    /** Disconnect-Peer-Request (RFC 6733 5.4.1). */
    public static DiameterMessage dpr(DiameterIdentity id) {
        return new DiameterMessage(true, false, false, CMD_DISCONNECT_PEER, APP_BASE, 0, 0, List.of(
                Avp.utf8(AVP_ORIGIN_HOST, id.host()),
                Avp.utf8(AVP_ORIGIN_REALM, id.realm()),
                Avp.integer32(AVP_DISCONNECT_CAUSE, DISCONNECT_REBOOTING)));
    }

    public static DiameterMessage dpa(DiameterIdentity id, DiameterMessage dpr, long resultCode) {
        return answer(dpr, false, answerAvps(id, dpr, resultCode));
    }

    /** Generic protocol-error answer (E bit set), e.g. 3001 DIAMETER_COMMAND_UNSUPPORTED. */
    public static DiameterMessage errorAnswer(DiameterIdentity id, DiameterMessage request, long resultCode) {
        return answer(request, true, answerAvps(id, request, resultCode));
    }

    // ------------------------------------------------------------------ credit control (RFC 4006)

    /**
     * Credit-Control-Request. P bit set, Application-Id 4.
     *
     * @param units requested units, sent as Requested-Service-Unit / CC-Total-Octets
     */
    public static DiameterMessage ccr(DiameterIdentity id, String destinationRealm, String serviceContextId,
            String sessionId, int requestType, long requestNumber, String msisdn, long units) {
        return new DiameterMessage(true, true, false, CMD_CREDIT_CONTROL, APP_CREDIT_CONTROL, 0, 0, List.of(
                Avp.utf8(AVP_SESSION_ID, sessionId),
                Avp.utf8(AVP_ORIGIN_HOST, id.host()),
                Avp.utf8(AVP_ORIGIN_REALM, id.realm()),
                Avp.utf8(AVP_DESTINATION_REALM, destinationRealm),
                Avp.unsigned32(AVP_AUTH_APPLICATION_ID, APP_CREDIT_CONTROL),
                Avp.utf8(AVP_SERVICE_CONTEXT_ID, serviceContextId),
                Avp.integer32(AVP_CC_REQUEST_TYPE, requestType),
                Avp.unsigned32(AVP_CC_REQUEST_NUMBER, requestNumber),
                Avp.time(AVP_EVENT_TIMESTAMP, Instant.now()),
                Avp.grouped(AVP_SUBSCRIPTION_ID,
                        Avp.integer32(AVP_SUBSCRIPTION_ID_TYPE, SUBSCRIPTION_ID_END_USER_E164),
                        Avp.utf8(AVP_SUBSCRIPTION_ID_DATA, msisdn)),
                Avp.grouped(AVP_REQUESTED_SERVICE_UNIT, Avp.unsigned64(AVP_CC_TOTAL_OCTETS, units))));
    }

    /** Credit-Control-Answer echoing the request's session / CC fields and granting {@code grantedUnits}. */
    public static DiameterMessage cca(DiameterIdentity id, DiameterMessage ccr, long resultCode, long grantedUnits) {
        List<Avp> avps = answerAvps(id, ccr, resultCode);
        avps.add(Avp.unsigned32(AVP_AUTH_APPLICATION_ID, APP_CREDIT_CONTROL));
        copy(ccr, AVP_CC_REQUEST_TYPE, avps);
        copy(ccr, AVP_CC_REQUEST_NUMBER, avps);
        if (resultCode == RESULT_SUCCESS) {
            avps.add(Avp.grouped(AVP_GRANTED_SERVICE_UNIT, Avp.unsigned64(AVP_CC_TOTAL_OCTETS, grantedUnits)));
        }
        return answer(ccr, false, avps);
    }

    // ------------------------------------------------------------------ helpers

    /** Session-Id (when present in the request) first, as RFC 6733 requires, then Result-Code and origin. */
    private static List<Avp> answerAvps(DiameterIdentity id, DiameterMessage request, long resultCode) {
        List<Avp> avps = new ArrayList<>();
        Optional<Avp> session = request.find(AVP_SESSION_ID);
        session.ifPresent(avps::add);
        avps.add(Avp.unsigned32(AVP_RESULT_CODE, resultCode));
        avps.add(Avp.utf8(AVP_ORIGIN_HOST, id.host()));
        avps.add(Avp.utf8(AVP_ORIGIN_REALM, id.realm()));
        return avps;
    }

    private static DiameterMessage answer(DiameterMessage request, boolean error, List<Avp> avps) {
        return new DiameterMessage(false, request.proxiable(), error, request.commandCode(),
                request.applicationId(), request.hopByHop(), request.endToEnd(), avps);
    }

    private static void copy(DiameterMessage from, int avpCode, List<Avp> to) {
        from.find(avpCode).ifPresent(to::add);
    }
}
