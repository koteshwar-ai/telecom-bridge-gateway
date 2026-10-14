package com.pyrogroup.telecom_bridge_gateway.diameter;

/** Too many in-flight requests or the socket is not writable - back-pressure, maps to HTTP 503. */
public class DiameterOverloadedException extends RuntimeException {

    public DiameterOverloadedException(String message) {
        super(message);
    }
}
