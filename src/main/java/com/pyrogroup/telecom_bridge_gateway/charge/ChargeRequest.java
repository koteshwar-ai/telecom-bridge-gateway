package com.pyrogroup.telecom_bridge_gateway.charge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /api/v1/charge}.
 *
 * @param msisdn subscriber number in E.164 digits (no '+')
 * @param units  units requested from the online charging system (sent as CC-Total-Octets)
 */
public record ChargeRequest(
        @NotBlank @Pattern(regexp = "\\d{7,15}", message = "must be 7-15 digits (E.164 without '+')") String msisdn,
        @Positive long units) {
}
