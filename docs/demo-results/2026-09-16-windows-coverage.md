# Windows JaCoCo Coverage Evidence — 2026-09-16

This record preserves the current measured coverage baseline for the deterministic default test suite after the summary-recovery redaction, typed outcome, and monitoring-contract changes. It is not a coverage gate, a quality score, or evidence for live Gemini, MySQL, Docker, Linux, latency, concurrency, or reliability.

## Environment

- Host: Windows 11, amd64 (`Microsoft Windows NT 10.0.22631.0`)
- Java: Oracle JDK 21.0.1
- Maven Wrapper: Maven 3.9.12
- JaCoCo Maven plugin: 0.8.15
- Docker-compatible runtime: unavailable (`docker`, `podman`, and `nerdctl` were not installed)

## Command and outcome

```powershell
.\mvnw.cmd clean verify
```

Outcome:

```text
Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
JaCoCo analyzed bundle 'SmartDoc-JP' with 80 classes
BUILD SUCCESS
```

The 49 tests came from 14 Surefire XML reports. No Failsafe execution is configured, so this command did not execute `*IT` classes. The optional MySQL Testcontainers and live Gemini smoke tests are outside this baseline.

## Report-level counters

Percentages use `covered / (covered + missed) * 100`, calculated from the report-level counters in `target/site/jacoco/jacoco.xml`.

| Counter | Covered | Missed | Total | Coverage |
|---|---:|---:|---:|---:|
| Instructions | 5,079 | 2,989 | 8,068 | 62.95% |
| Branches | 237 | 381 | 618 | 38.35% |
| Lines | 937 | 384 | 1,321 | 70.93% |
| Complexity | 389 | 431 | 820 | 47.44% |
| Methods | 340 | 171 | 511 | 66.54% |
| Classes | 72 | 8 | 80 | 90.00% |

Generated local artifacts:

- `target/jacoco.exec`
- `target/site/jacoco/index.html`
- `target/site/jacoco/jacoco.xml`
- `target/site/jacoco/jacoco.csv`

`target/` is intentionally ignored by Git. Regenerate before an interview rather than relying on an old local report; the raw XML contains session metadata, so its file hash is not expected to remain stable across equivalent runs.

## Highest-value gaps

The largest uncovered line counts were in:

| Class | Missed lines | Covered lines | Why it matters |
|---|---:|---:|---|
| `GoogleGenAiEmbeddingModel` | 52 | 0 | Provider adapter and embedding-response/error handling are not exercised by the deterministic suite |
| `GlobalExceptionHandler` | 32 | 11 | API error contracts have broad untested surface |
| `AuthService` | 30 | 2 | Registration/login behavior and role assignment need direct contract tests |
| `DocumentAgentTools` | 25 | 82 | Tool branches remain incomplete despite strong happy-path coverage |
| `JwtUtil` | 24 | 4 | Token creation/validation/expiry/error paths are weakly covered |
| `AiConfig` | 24 | 7 | Provider/proxy/model bean configuration is mostly untested |
| `RagService` | 23 | 132 | Important compensation, persistence, filtering, and failure branches remain |
| `AiMonitoringAspect` | 21 | 81 | Metrics branch coverage is incomplete |

Package-level line coverage is strongest in `aspect` (81.82%) and `service` (81.78%), while `config` is 25.00%, `exception` is 34.69%, and `security` is 54.63%. Branch coverage is only 38.35% overall, so increasing the number of shallow tests would not justify a 90% claim. The 90.00% class counter must not be presented as 90% overall coverage.

## Claim boundary

This baseline supports only the statement that the repository has a JaCoCo measurement for its deterministic Windows/JUnit 5 suite. It disproves the current resume wording `测试覆盖率 90%+`: line coverage is 70.93% and branch coverage is 38.35%, with no enforced threshold or Linux CI evidence.

The next coverage work should target security/error/provider-boundary behavior, not DTO/builders or generated boilerplate merely to raise a number. Any future threshold must be selected after those behavior tests exist and must remain explicit about excluded external tiers.
