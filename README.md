# Telecom-Bridge Gateway

REST -> Diameter Credit-Control (Ro/Gy, RFC 4006) gateway. `POST /api/v1/charge` becomes an
asynchronous Diameter **CCR**; the **CCA** is mapped back to the HTTP response. Includes a standalone
Diameter simulator, tests, Docker setup and a Locust load test.

```
 HTTP client --> [Spring MVC, async CompletableFuture]
                       |
                  ChargeService  --builds CCR-->  DiameterClient (Netty, 1 persistent TCP connection)
                       ^                                |  ConcurrentHashMap<HopByHop, CompletableFuture>
                       +------- completes future <------+  CCA matched by Hop-by-Hop id
                                                        |
                                              DiameterSimulator :3868 (CCA after 50-100 ms)
```

## Requirements
Java 21, Maven (or the included `./mvnw`). Docker optional. Wireshark/tcpdump for the PCAP, Locust for load.

## Run locally (IntelliJ or terminal)
1. **Simulator** - run `SimulatorMain` (`com.pyrogroup.telecom_bridge_gateway.simulator`) from IntelliJ, or
   `./mvnw -q compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.pyrogroup.telecom_bridge_gateway.simulator.SimulatorMain`.
   Options: `--port=3868 --min-delay-ms=50 --max-delay-ms=100`.
2. **Gateway** - run `TelecomBridgeGatewayApplication`, or `./mvnw spring-boot:run`.
3. Try it:
```bash
curl -s -X POST localhost:8080/api/v1/charge -H 'Content-Type: application/json' \
     -d '{"msisdn":"919876543210","units":1048576}'
# {"sessionId":"gateway.example.com;...;1","status":"APPROVED","resultCode":2001,"grantedUnits":1048576}
```

### Docker
`docker compose up --build` starts simulator + gateway (gateway reaches it via `DIAMETER_HOST=simulator`).

## API
| Case | HTTP |
|---|---|
| CCA 2xxx | 200 `APPROVED` |
| CCA 4xxx (e.g. 4012 credit limit) | 200 `REJECTED` |
| CCA 3xxx/5xxx | 502 |
| Invalid body | 400 |
| Diameter peer down / connection lost / back-pressure | 503 (`Retry-After` on back-pressure) |
| No CCA within `diameter.request-timeout` (2 s) | 504 |

Errors use RFC 7807 problem details.

## Design notes (mapped to the evaluation criteria)
* **Concurrency** - `ConcurrentHashMap<Integer, CompletableFuture<DiameterMessage>>` keyed by Hop-by-Hop id.
  Controller returns a `CompletableFuture`, so no servlet thread waits. Netty I/O threads complete futures.
* **Protocol accuracy** - `protocol/DiameterCodec`: 20-byte header, 24-bit length/command code, AVP length excludes
  padding but AVPs are zero-padded to 4 bytes, grouped AVPs, Address and Time types, M-bit rules.
  CER/CEA (app 0, Auth-Application-Id 4), DWR/DWA, DPR/DPA, CCR/CCA (app 4, P bit). Session-Id per RFC 6733 8.8,
  End-to-End id initialised per RFC 6733 3.
* **Resource management** - every path removes the pending entry (answer, timeout, write failure, disconnect,
  shutdown); pending map is bounded (`diameter.max-pending`) and writability is checked; `@PreDestroy` sends DPR,
  closes the channel and shuts the event loop down; decoder copies bytes out of Netty buffers.
* **Error handling** - see API table. Auto-reconnect with exponential back-off + jitter; watchdog closes a
  connection whose DWR goes unanswered.
* **Observability** - `/actuator/prometheus` exposes `gateway.charge` (latency histogram + p50/p95/p99, tagged by
  outcome), `diameter.pending.requests`, `diameter.connection.open`.

## Tests
`./mvnw verify` - codec/AVP encode-decode tests, exception-to-HTTP mapping, and integration tests over real TCP
(500 concurrent charges matched to their own answers, timeout without leaks, peer down, reconnect).

## PCAP (`transaction_flow.pcap`) - Windows
Prerequisite: Wireshark (bundles dumpcap + Npcap with loopback support).

**One command** (PowerShell, as Administrator if the capture fails to start):
```powershell
powershell -ExecutionPolicy Bypass -File scripts\capture-pcap.ps1
```
It builds the jar, starts the simulator, starts the loopback capture, starts the gateway (so CER/CEA is
recorded), sends 10 charge requests (10 CCR/CCA) and writes `transaction_flow.pcap` (libpcap format) in the
project root. Logs go to `logs\`. Options: `-Requests 10 -CaptureSeconds 45`.

**Manual alternative (Wireshark GUI):** start the simulator, capture on *Adapter for loopback traffic capture*
with filter `tcp port 3868 or tcp port 8080`, start the gateway, send requests, then
File > Save As > type **Wireshark/tcpdump - pcap** > `transaction_flow.pcap`.

Open in Wireshark with display filter `diameter || http`. Expected: CER, CEA (Result-Code 2001), then HTTP POST
followed by a CCR/CCA pair for each request. (Linux/macOS: `sudo tcpdump -i lo -w transaction_flow.pcap 'tcp port 3868 or tcp port 8080'`, `lo0` on macOS.)

The load-test capture is large; GitHub rejects files > 100 MB, so split it with dumpcap
(`-b filesize:51200 -b files:200`), zip it and link it instead of committing it.

## Load test (100 TPS, 500,000 transactions)
```bash
pip install locust
locust -f loadtest/locustfile.py --headless -u 100 -r 100 --host http://localhost:8080 --run-time 90m --csv loadtest/results
```
Stops itself after 500,000 requests (~83 min). Watch heap via `/actuator/metrics/jvm.memory.used` or
`jcmd <pid> GC.heap_info`, and latency via `/actuator/prometheus`.

**p95 note:** the simulator delay is uniform 50-100 ms, so its own p95 is ~97.5 ms before any gateway overhead.
Report the gateway overhead (end-to-end p95 minus simulated delay) or run with `--max-delay-ms=90` and say so.

## Assumptions
* The challenge PDF's JSON sample did not survive text extraction; the payload used is
  `{"msisdn": "<digits>", "units": <long>}`. Adjust `ChargeRequest` if the original differs.
* TCP transport (SCTP optional per the brief).
