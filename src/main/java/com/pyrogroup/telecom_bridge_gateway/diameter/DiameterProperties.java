package com.pyrogroup.telecom_bridge_gateway.diameter;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Everything tunable about the Diameter connection, bound from {@code diameter.*}. */
@Validated
@ConfigurationProperties(prefix = "diameter")
public record DiameterProperties(
        @DefaultValue("localhost") @NotBlank String host,
        @DefaultValue("3868") @Min(1) int port,
        @DefaultValue("gateway.example.com") @NotBlank String originHost,
        @DefaultValue("example.com") @NotBlank String originRealm,
        @DefaultValue("example.com") @NotBlank String destinationRealm,
        @DefaultValue("127.0.0.1") @NotBlank String hostIp,
        @DefaultValue("32251@3gpp.org") @NotBlank String serviceContextId,
        /** Max time to wait for a CCA before failing the REST call with 504. */
        @DefaultValue("2s") Duration requestTimeout,
        @DefaultValue("5s") Duration connectTimeout,
        /** Max time to wait for the CEA after sending the CER. */
        @DefaultValue("5s") Duration capabilitiesTimeout,
        /** Idle time before a DWR is sent. */
        @DefaultValue("30s") Duration watchdogInterval,
        @DefaultValue("1s") Duration reconnectInitialDelay,
        @DefaultValue("30s") Duration reconnectMaxDelay,
        /** Bound on the Hop-by-Hop map - beyond this requests are rejected instead of queued. */
        @DefaultValue("10000") @Min(1) int maxPending) {
}
