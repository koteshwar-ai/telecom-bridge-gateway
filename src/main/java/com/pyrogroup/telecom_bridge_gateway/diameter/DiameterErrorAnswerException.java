package com.pyrogroup.telecom_bridge_gateway.diameter;

/** The peer answered with a protocol-level failure (3xxx / 5xxx Result-Code) - maps to HTTP 502. */
public class DiameterErrorAnswerException extends RuntimeException {

    private final long resultCode;

    public DiameterErrorAnswerException(long resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public long getResultCode() {
        return resultCode;
    }
}
