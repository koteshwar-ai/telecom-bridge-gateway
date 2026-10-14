package com.pyrogroup.telecom_bridge_gateway.charge;

import static com.pyrogroup.telecom_bridge_gateway.protocol.Diameter.*;

import com.pyrogroup.telecom_bridge_gateway.diameter.DiameterClient;
import com.pyrogroup.telecom_bridge_gateway.diameter.DiameterErrorAnswerException;
import com.pyrogroup.telecom_bridge_gateway.diameter.DiameterProperties;
import com.pyrogroup.telecom_bridge_gateway.protocol.Avp;
import com.pyrogroup.telecom_bridge_gateway.protocol.DiameterMessage;
import com.pyrogroup.telecom_bridge_gateway.protocol.DiameterMessages;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Maps a REST charge request onto a Diameter CCR and the CCA back onto a REST response. */
@Slf4j
@Service
public class ChargeService {

    private final DiameterClient client;
    private final DiameterProperties props;
    private final MeterRegistry registry;

    public ChargeService(DiameterClient client, DiameterProperties props, MeterRegistry registry) {
        this.client = client;
        this.props = props;
        this.registry = registry;
    }

    public CompletableFuture<ChargeResponse> charge(ChargeRequest request) {
        Timer.Sample sample = Timer.start(registry);
        String sessionId = client.newSessionId();
        DiameterMessage ccr = DiameterMessages.ccr(client.identity(), props.destinationRealm(),
                props.serviceContextId(), sessionId, CC_INITIAL_REQUEST, 0, request.msisdn(), request.units());

        return client.sendRequest(ccr)
                .thenApply(cca -> toResponse(sessionId, cca))
                .whenComplete((response, error) -> {
                    String outcome = outcome(response, error);
                    sample.stop(Timer.builder("gateway.charge").tag("outcome", outcome).register(registry));
                    log.debug("charge session={} outcome={}", sessionId, outcome);
                });
    }

    private ChargeResponse toResponse(String sessionId, DiameterMessage cca) {
        if (cca.commandCode() != CMD_CREDIT_CONTROL) {
            throw new DiameterErrorAnswerException(0, "Unexpected answer command code " + cca.commandCode());
        }
        long resultCode = cca.resultCode().orElseThrow(
                () -> new DiameterErrorAnswerException(0, "CCA without Result-Code"));
        if (resultCode / 1000 == 2) {
            Long granted = cca.findNested(AVP_GRANTED_SERVICE_UNIT, AVP_CC_TOTAL_OCTETS)
                    .map(Avp::asUnsigned64).orElse(null);
            return new ChargeResponse(sessionId, ChargeResponse.Status.APPROVED, resultCode, granted);
        }
        if (resultCode / 1000 == 4) { // transient failure = business rejection (credit limit, denied...)
            return new ChargeResponse(sessionId, ChargeResponse.Status.REJECTED, resultCode, null);
        }
        throw new DiameterErrorAnswerException(resultCode, "Diameter peer answered with Result-Code " + resultCode);
    }

    private static String outcome(ChargeResponse response, Throwable error) {
        if (error == null) {
            return response.status().name().toLowerCase();
        }
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof TimeoutException ? "timeout" : "error";
    }
}
