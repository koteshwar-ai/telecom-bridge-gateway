package com.pyrogroup.telecom_bridge_gateway.diameter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Exposes connection health and the Hop-by-Hop map size to Prometheus / Grafana. */
@Component
public class DiameterMetrics {

    public DiameterMetrics(MeterRegistry registry, DiameterClient client) {
        Gauge.builder("diameter.pending.requests", client, DiameterClient::pendingCount)
                .description("In-flight Diameter requests (size of the Hop-by-Hop map)")
                .register(registry);
        Gauge.builder("diameter.connection.open", client, c -> c.isOpen() ? 1 : 0)
                .description("1 when CER/CEA completed and the connection is usable")
                .register(registry);
    }
}
