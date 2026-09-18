# Deterministic Interview Demo Rehearsal — 2026-09-15

This is a retained command/result snapshot for the deterministic tier in `docs/INTERVIEW_DEMO.md`. It is not a performance benchmark or production-readiness report.

## Environment

- Host: Windows 11, amd64 (`10.0.22631.0`)
- Java: Oracle JDK 21.0.1
- Maven Wrapper distribution: Apache Maven 3.9.12
- Git branch: `main`
- Committed baseline: `d1b88b0`
- Repository state: working tree contains uncommitted changes; these results are not evidence for a fresh clone of `d1b88b0`
- External application dependencies: no MySQL, Docker, Gemini API, or proxy used by the tests

## Results

| Step | Command | Result | Rehearsal wall time |
|---|---|---|---:|
| Default suite | `.\mvnw.cmd test` | 45 passed; 0 failed/errors/skipped | 24.0 s |
| Deterministic vertical slice | `.\mvnw.cmd -Dtest=DeterministicVerticalSliceTest test` | 4 passed | 15.8 s |
| Authorization integration | `.\mvnw.cmd -Dtest=DocumentAuthorizationIntegrationTest test` | 6 passed | 15.1 s |
| Tool callback contract | `.\mvnw.cmd -Dtest=AgentToolCallingContractTest test` | 1 passed | 5.8 s |
| Tool/SSE failure contracts | `.\mvnw.cmd "-Dtest=DocumentAgentToolsTest,AgentStreamContractTest" test` | 10 passed | 6.0 s |

All five commands exited with code 0 and reported `BUILD SUCCESS`.

The wall times above were collected on one dependency-cached development machine only to plan the interview sequence. They are not repeat measurements, do not define a workload or concurrency level, and must not be quoted as application latency, throughput, speedup, or an SLO.

## Rehearsal notes

- Running the default suite and every focused suite in one interview is redundant. Use the full suite as the overall proof, then run only the vertical slice and Tool callback when time is limited.
- Maven output is verbose because failure-injection tests intentionally log stack traces. Pre-position the terminal at the final Surefire summary, but do not hide the fact that failures were injected.
- The Tool callback is most reliably demonstrated through `AgentToolCallingContractTest`; the current Web UI and Compose smoke do not expose a guaranteed live tool trace.
- The deterministic vertical slice is application-path evidence, not live Gemini or MySQL evidence.
- The summary recovery path can expose exception-derived fallback text through document status. Do not demonstrate it as a safe redaction path until fixed.

## Not verified by this rehearsal

- Docker Compose or Linux runtime
- MySQL/Testcontainers execution
- Live Gemini connectivity, PDF visual/OCR quality, or Tool selection
- Browser reconnect/cancellation behavior
- Concurrency, latency, throughput, or reliability targets
- Test coverage percentage
