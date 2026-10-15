package com.pyrogroup.telecom_bridge_gateway.web;

import com.pyrogroup.telecom_bridge_gateway.diameter.DiameterErrorAnswerException;
import com.pyrogroup.telecom_bridge_gateway.diameter.DiameterOverloadedException;
import com.pyrogroup.telecom_bridge_gateway.diameter.DiameterUnavailableException;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates Diameter-side failures into HTTP semantics (RFC 7807 problem details):
 * <ul>
 *   <li>peer down / connection lost -> 503 Service Unavailable</li>
 *   <li>too many in-flight requests -> 503 + Retry-After</li>
 *   <li>no CCA within the timeout -> 504 Gateway Timeout</li>
 *   <li>peer answered with 3xxx/5xxx -> 502 Bad Gateway</li>
 * </ul>
 * Bean-validation failures (400) come from the {@link ResponseEntityExceptionHandler} base class.
 */
@Slf4j
@RestControllerAdvice
public class GatewayExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(DiameterUnavailableException.class)
    ProblemDetail unavailable(DiameterUnavailableException ex) {
        log.warn("503: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(DiameterOverloadedException.class)
    ResponseEntity<ProblemDetail> overloaded(DiameterOverloadedException ex) {
        log.warn("503 (overloaded): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    @ExceptionHandler(TimeoutException.class)
    ProblemDetail timeout(TimeoutException ex) {
        log.warn("504: no Diameter answer within the configured timeout");
        return problem(HttpStatus.GATEWAY_TIMEOUT, "No Credit-Control-Answer received in time");
    }

    @ExceptionHandler(DiameterErrorAnswerException.class)
    ProblemDetail badGateway(DiameterErrorAnswerException ex) {
        log.warn("502: {}", ex.getMessage());
        ProblemDetail detail = problem(HttpStatus.BAD_GATEWAY, ex.getMessage());
        detail.setProperty("resultCode", ex.getResultCode());
        return detail;
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
