package com.pyrogroup.telecom_bridge_gateway.charge;

/**
 * Result of a charge attempt, derived from the Diameter CCA.
 *
 * @param sessionId    Diameter Session-Id used for the CCR
 * @param status       APPROVED for 2xxx, REJECTED for 4xxx (e.g. 4012 credit limit reached)
 * @param resultCode   Result-Code AVP from the CCA
 * @param grantedUnits Granted-Service-Unit / CC-Total-Octets, null when nothing was granted
 */
public record ChargeResponse(String sessionId, Status status, long resultCode, Long grantedUnits) {

    public enum Status { APPROVED, REJECTED }
}
