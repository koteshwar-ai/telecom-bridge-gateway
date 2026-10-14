package com.pyrogroup.telecom_bridge_gateway.simulator;

import lombok.extern.slf4j.Slf4j;

/**
 * Entry point for the standalone simulator.
 *
 * <pre>
 *   java -cp target/classes:... SimulatorMain [--port=3868] [--min-delay-ms=50] [--max-delay-ms=100]
 * </pre>
 * Also settable via system properties {@code sim.port}, {@code sim.minDelayMs}, {@code sim.maxDelayMs}.
 */
@Slf4j
public final class SimulatorMain {

    private SimulatorMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("sim.port", 3868);
        int min = Integer.getInteger("sim.minDelayMs", 50);
        int max = Integer.getInteger("sim.maxDelayMs", 100);
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.startsWith("--min-delay-ms=")) {
                min = Integer.parseInt(arg.substring("--min-delay-ms=".length()));
            } else if (arg.startsWith("--max-delay-ms=")) {
                max = Integer.parseInt(arg.substring("--max-delay-ms=".length()));
            }
        }
        DiameterSimulator simulator = new DiameterSimulator(port, min, max);
        Runtime.getRuntime().addShutdownHook(new Thread(simulator::close, "sim-shutdown"));
        simulator.start();
        Thread.currentThread().join(); // run until killed
    }
}
