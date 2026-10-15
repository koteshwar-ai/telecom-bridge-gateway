package com.pyrogroup.telecom_bridge_gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.pyrogroup.telecom_bridge_gateway.diameter.DiameterErrorAnswerException;
import com.pyrogroup.telecom_bridge_gateway.diameter.DiameterOverloadedException;
import com.pyrogroup.telecom_bridge_gateway.diameter.DiameterUnavailableException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    void peerDownIs503() {
        assertThat(handler.unavailable(new DiameterUnavailableException("down")).getStatus()).isEqualTo(503);
    }

    @Test
    void overloadIs503WithRetryAfter() {
        var response = handler.overloaded(new DiameterOverloadedException("busy"));
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void noAnswerInTimeIs504() {
        assertThat(handler.timeout(new TimeoutException()).getStatus()).isEqualTo(504);
    }

    @Test
    void protocolErrorFromPeerIs502() {
        var detail = handler.badGateway(new DiameterErrorAnswerException(5012, "unable to comply"));
        assertThat(detail.getStatus()).isEqualTo(502);
        assertThat(detail.getProperties()).containsEntry("resultCode", 5012L);
    }
}
