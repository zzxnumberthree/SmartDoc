# Windows JaCoCo Coverage Evidence — 2026-09-18

This record preserves the deterministic default-suite baseline after the Spring Retry and AI monitoring advisor order was made explicit. It is not a coverage gate, a quality score, or evidence for live Gemini, MySQL, Docker, Linux, latency, concurrency, or reliability.

## Environment

- Host: Windows 11, amd64
- Java: Oracle JDK 21.0.1
- Maven Wrapper: Maven 3.9.12
- JaCoCo Maven plugin: 0.8.15
- Docker-compatible runtime: not exercised

## Command and outcome

```powershell
.\mvnw.cmd -q clean verify
```

```text
Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
Surefire XML reports: 15
JaCoCo analyzed bundle 'SmartDoc-JP' with 80 classes
BUILD SUCCESS
```

The default lifecycle did not execute optional `*IT` classes. The added Spring integration test proves that two failed retry attempts followed by `@Recover` produce one logical SUMMARY error metric, one duration sample, no success metric, and no successful usage recording.

## Report-level counters

Percentages use `covered / (covered + missed) * 100` from `target/site/jacoco/jacoco.xml`.

| Counter | Covered | Missed | Total | Coverage |
|---|---:|---:|---:|---:|
| Instructions | 5,052 | 3,016 | 8,068 | 62.62% |
| Branches | 237 | 381 | 618 | 38.35% |
| Lines | 933 | 388 | 1,321 | 70.63% |
| Complexity | 389 | 431 | 820 | 47.44% |
| Methods | 340 | 171 | 511 | 66.54% |
| Classes | 72 | 8 | 80 | 90.00% |

Generated local artifacts are under `target/site/jacoco/` and are ignored by Git. Regenerate them before an interview or release-evidence update.

## Claim boundary

This baseline supports deterministic Windows/JUnit 5 testing and the real Spring Retry/monitoring advisor contract. It still disproves the resume wording `测试覆盖率 90%+`: line coverage is 70.63% and branch coverage is 38.35%, with no enforced threshold or Linux CI evidence. The 90.00% class counter is not overall coverage.

Highest-value remaining gaps remain security, centralized API error handling, provider configuration/boundaries, and real Linux/MySQL/Docker execution. Adding shallow DTO tests merely to raise the percentage is not recommended.
