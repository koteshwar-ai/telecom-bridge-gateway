package com.pyrogroup.telecom_bridge_gateway.diameter;

/** The Diameter peer is not connected (or the connection dropped) - maps to HTTP 503. */
public class DiameterUnavailableException extends RuntimeException {

    public DiameterUnavailableException(String message) {
        super(message);
    }

    public DiameterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
