package com.pyrogroup.telecom_bridge_gateway.diameter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pyrogroup.telecom_bridge_gateway.charge.ChargeRequest;
import com.pyrogroup.telecom_bridge_gateway.charge.ChargeResponse;
import com.pyrogroup.telecom_bridge_gateway.charge.ChargeService;
import com.pyrogroup.telecom_bridge_gateway.simulator.DiameterSimulator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Real TCP round trips between the gateway client and the in-process simulator. */
class DiameterClientIntegrationTest {

    private DiameterSimulator simulator;
    private DiameterClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.stop();
        }
        if (simulator != null) {
            simulator.close();
        }
    }

    @Test
    void manyConcurrentChargesAreMatchedToTheirOwnAnswers() throws Exception {
        simulator = new DiameterSimulator(0, 5, 15);
        int port = simulator.start();
        ChargeService service = startClientAndService(port, Duration.ofSeconds(2));
        awaitOpen(client);

        List<CompletableFuture<ChargeResponse>> calls = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            calls.add(service.charge(new ChargeRequest("91987654" + String.format("%04d", i), i)));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

        for (int i = 0; i < calls.size(); i++) {
            ChargeResponse response = calls.get(i).get();
            assertThat(response.status()).isEqualTo(ChargeResponse.Status.APPROVED);
            assertThat(response.resultCode()).isEqualTo(2001);
            // the simulator echoes requested units: proves answer N went to caller N
            assertThat(response.grantedUnits()).isEqualTo(i + 1L);
        }
        assertThat(client.pendingCount()).isZero();
    }

    @Test
    void slowPeerCausesTimeoutAndDoesNotLeakPendingEntries() throws Exception {
        simulator = new DiameterSimulator(0, 600, 600);
        int port = simulator.start();
        ChargeService service = startClientAndService(port, Duration.ofMillis(150));
        awaitOpen(client);

        CompletableFuture<ChargeResponse> call = service.charge(new ChargeRequest("919876543210", 10));

        assertThatThrownBy(() -> call.get(3, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
        assertThat(client.pendingCount()).isZero();
    }

    @Test
    void peerDownFailsFastWithUnavailable() throws Exception {
        int closedPort = freePort();
        ChargeService service = startClientAndService(closedPort, Duration.ofSeconds(1));

        CompletableFuture<ChargeResponse> call = service.charge(new ChargeRequest("919876543210", 10));

        assertThat(client.isOpen()).isFalse();
        assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DiameterUnavailableException.class);
    }

    @Test
    void clientReconnectsOnceThePeerComesUp() throws Exception {
        int port = freePort();
        startClientAndService(port, Duration.ofSeconds(1)); // peer not there yet
        assertThat(client.isOpen()).isFalse();

        simulator = new DiameterSimulator(port, 1, 2);
        simulator.start();

        awaitOpen(client); // retries with back-off, then CER/CEA completes
        assertThat(client.isOpen()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private ChargeService startClientAndService(int port, Duration requestTimeout) {
        DiameterProperties props = new DiameterProperties("localhost", port, "gw.test", "test", "test",
                "127.0.0.1", "32251@3gpp.org", requestTimeout, Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(30), Duration.ofMillis(100), Duration.ofMillis(500), 1000);
        client = new DiameterClient(props);
        client.start();
        return new ChargeService(client, props, new SimpleMeterRegistry());
    }

    private static void awaitOpen(DiameterClient client) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!client.isOpen() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(client.isOpen()).as("CER/CEA handshake completed").isTrue();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
