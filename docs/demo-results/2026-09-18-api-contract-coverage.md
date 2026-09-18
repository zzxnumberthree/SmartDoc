# Windows JaCoCo Coverage Evidence (API Contract Baseline) — 2026-09-18

This record preserves the deterministic default-suite baseline after implementing structured RFC 7807 Problem Details validation error handling and aligning the `AuthController.register` OpenAPI description. It supersedes the earlier same-day 50-test snapshot (`docs/demo-results/2026-09-18-windows-coverage.md`) only as the latest local deterministic baseline; the earlier snapshot is retained as historical evidence. It is not a coverage gate, a quality score, or evidence for live Gemini, MySQL, Docker, Linux, latency, concurrency, or reliability.

## Environment

- Host: Windows 11, amd64
- Java: Oracle JDK 21.0.1
- Maven Wrapper: Maven 3.9.12
- JaCoCo Maven plugin: 0.8.15
- Docker-compatible runtime: not exercised

## Commands and outcomes

### Focused API validation and OpenAPI contract test

Windows PowerShell:

```powershell
.\mvnw.cmd '-Dtest=GlobalExceptionHandlerTest,AuthControllerTest' test
```

```text
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

This verifies that:
- `MethodArgumentNotValidException` returns HTTP 400 RFC 7807 Problem Details with type `https://api.spe.smartdoc.com/errors/validation-failed`, title `Validation Failed`, detail `请求参数验证失败`, `timestamp`, and structured `fieldErrors` without echoing rejected input.
- `AuthController.register` OpenAPI `@Operation` description documents assigning the standard `USER` role and does not claim administrator privileges based on username prefix.
- `/api/auth/register` and `/api/auth/login` produce structured HTTP 400 Problem Details on invalid input without leaking rejected values or internal exception classes.
- Additional typed status and Problem Detail mappings in `GlobalExceptionHandler` (`DocumentNotFoundException` -> 404, `AccessDeniedException` -> 403, `MaxUploadSizeExceededException` -> 413, `IllegalArgumentException` -> 400, `AuthenticationException` -> 401, general unexpected `Exception` -> redacted 500).

### Full deterministic verification

Windows PowerShell:

```powershell
.\mvnw.cmd clean verify
```

```text
Tests run: 61, Failures: 0, Errors: 0, Skipped: 0
Surefire XML reports: 17
JaCoCo analyzed bundle 'SmartDoc-JP' with 80 classes
BUILD SUCCESS
```

The default lifecycle did not execute optional `*IT` classes (`MySqlContainerVerticalSliceIT`, `GeminiLiveSmokeIT`).

## Report-level counters

Percentages use `covered / (covered + missed) * 100` from `target/site/jacoco/jacoco.xml`.

| Counter | Covered | Missed | Total | Coverage |
|---|---:|---:|---:|---:|
| Instructions | 5,203 | 2,899 | 8,102 | 64.22% |
| Branches | 240 | 382 | 622 | 38.59% |
| Lines | 970 | 359 | 1,329 | 72.99% |
| Complexity | 399 | 422 | 821 | 48.60% |
| Methods | 349 | 161 | 510 | 68.43% |
| Classes | 73 | 7 | 80 | 91.25% |

Generated local artifacts are under `target/site/jacoco/` and are ignored by Git. Regenerate them before an interview or release-evidence update.

## Claim boundary and limitations

- This baseline supports deterministic Windows/JUnit 5 testing and the audited API validation/exception handling contracts using H2 in MySQL compatibility mode and deterministic AI provider doubles.
- It continues to disprove an overall `测试覆盖率 90%+` claim: line coverage is 72.99% and branch coverage is 38.59%. The 91.25% class counter indicates only that 73 of 80 classes had at least one instruction exercised; it does not indicate overall 90%+ coverage.
- There is still no coverage gate enforced in the build.
- Optional tiers—MySQL 8 Testcontainers (`MySqlContainerVerticalSliceIT`), live Gemini API smoke (`GeminiLiveSmokeIT`), and Linux / Docker Compose runtime workflows—were not executed.
- Does not prove complete API error de-identification across all application endpoints, full OpenAPI schema conformance, production reliability, or performance.
