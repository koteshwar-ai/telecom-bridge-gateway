package com.pyrogroup.telecom_bridge_gateway.protocol;

/** Protocol constants from RFC 6733 (base) and RFC 4006 (Credit Control). */
public final class Diameter {

    private Diameter() {
    }

    // ---- Application IDs ----
    public static final long APP_BASE = 0L;
    /** Diameter Credit-Control Application (Ro/Gy). */
    public static final long APP_CREDIT_CONTROL = 4L;

    // ---- Command codes ----
    public static final int CMD_CAPABILITIES_EXCHANGE = 257;
    public static final int CMD_CREDIT_CONTROL = 272;
    public static final int CMD_DEVICE_WATCHDOG = 280;
    public static final int CMD_DISCONNECT_PEER = 282;

    // ---- AVP codes ----
    public static final int AVP_EVENT_TIMESTAMP = 55;
    public static final int AVP_HOST_IP_ADDRESS = 257;
    public static final int AVP_AUTH_APPLICATION_ID = 258;
    public static final int AVP_SESSION_ID = 263;
    public static final int AVP_ORIGIN_HOST = 264;
    public static final int AVP_VENDOR_ID = 266;
    public static final int AVP_RESULT_CODE = 268;
    public static final int AVP_PRODUCT_NAME = 269;
    public static final int AVP_DISCONNECT_CAUSE = 273;
    public static final int AVP_DESTINATION_REALM = 283;
    public static final int AVP_ORIGIN_REALM = 296;
    public static final int AVP_CC_TIME = 420;
    public static final int AVP_CC_TOTAL_OCTETS = 421;
    public static final int AVP_GRANTED_SERVICE_UNIT = 431;
    public static final int AVP_REQUESTED_SERVICE_UNIT = 437;
    public static final int AVP_SUBSCRIPTION_ID = 443;
    public static final int AVP_SUBSCRIPTION_ID_DATA = 444;
    public static final int AVP_SUBSCRIPTION_ID_TYPE = 450;
    public static final int AVP_CC_REQUEST_NUMBER = 415;
    public static final int AVP_CC_REQUEST_TYPE = 416;
    public static final int AVP_SERVICE_CONTEXT_ID = 461;

    // ---- CC-Request-Type values ----
    public static final int CC_INITIAL_REQUEST = 1;
    public static final int CC_UPDATE_REQUEST = 2;
    public static final int CC_TERMINATION_REQUEST = 3;
    public static final int CC_EVENT_REQUEST = 4;

    // ---- Subscription-Id-Type values ----
    public static final int SUBSCRIPTION_ID_END_USER_E164 = 0;

    // ---- Disconnect-Cause values ----
    public static final int DISCONNECT_REBOOTING = 0;

    // ---- Result codes ----
    public static final long RESULT_SUCCESS = 2001L;
    public static final long RESULT_COMMAND_UNSUPPORTED = 3001L;
    public static final long RESULT_END_USER_SERVICE_DENIED = 4010L;
    public static final long RESULT_CREDIT_LIMIT_REACHED = 4012L;
}
