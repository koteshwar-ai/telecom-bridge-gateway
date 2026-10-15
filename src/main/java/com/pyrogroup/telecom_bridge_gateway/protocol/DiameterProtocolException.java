package com.pyrogroup.telecom_bridge_gateway.protocol;

/** Raised when bytes on the wire are not a well-formed Diameter message / AVP. */
public class DiameterProtocolException extends RuntimeException {

    public DiameterProtocolException(String message) {
        super(message);
    }
}
