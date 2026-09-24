# Testing SmartDoc-JP

The default application test workflow is deterministic and does not require a local MySQL server, Docker, `GOOGLE_API_KEY`, Gemini quota, or a proxy. It makes no application-level external API calls; Maven may still need repository access the first time it resolves the wrapper, plugins, or dependencies.

## Current evidence snapshot

On 2026-09-24, after the document restore, Web client, and original-download changes, this Windows host ran `clean verify` with Java 21.0.1: **86 tests passed, 0 failed, 0 errors, 0 skipped** across 18 Surefire XML reports. The generated JaCoCo report measured **75.22% line coverage** and **44.81% branch coverage**. `node scripts/test-document-web-client.mjs` also passed using Node 24.15.0. These are deterministic local results, not MySQL, live Gemini, or browser proof.

On 2026-09-18, after implementing the structured API validation contract and aligning the registration OpenAPI description, the Windows host ran `clean verify` with Maven 3.9.12, Java 21.0.1, and JaCoCo 0.8.15: **61 tests passed, 0 failed, 0 errors, 0 skipped** across 17 default test classes (17 Surefire XML reports). The report measured **72.99% line coverage** and **38.59% branch coverage**. See `docs/demo-results/2026-09-18-api-contract-coverage.md` for the report-level counters and scope (earlier snapshots are retained in `docs/demo-results/2026-09-18-windows-coverage.md` and `docs/demo-results/2026-09-16-windows-coverage.md`).

The focused commands used by the interview runbook also passed in a timed rehearsal. See `docs/demo-results/2026-09-15-windows-deterministic.md`; its elapsed times are demo-planning notes, not performance evidence.

This number excludes two optional MySQL Testcontainers scenarios previously skipped because Docker was unavailable. The live Gemini smoke and Docker Compose/Linux smoke were not executed. There is no coverage gate, and the measured values do not support a 90%+ claim. Do not combine passed and skipped reports into a “47/47 passed” claim.

## Default deterministic suite

Windows PowerShell:

```powershell
.\mvnw.cmd test
```

Linux/macOS equivalent (not executed on the current audit host):

```bash
./mvnw test
```

This suite uses the `deterministic-test` profile, an in-memory H2 database in MySQL compatibility mode, and stable `ChatModel`/`EmbeddingModel` test doubles. It proves Spring application wiring, controller behavior, persistence, and unit-test behavior. H2 compatibility mode is not proof that the application works against MySQL.

## JaCoCo coverage baseline

Run the deterministic default suite and generate HTML, XML, and CSV reports:

```powershell
.\mvnw.cmd clean verify
```

Generated local files:

- `target/site/jacoco/index.html`
- `target/site/jacoco/jacoco.xml`
- `target/site/jacoco/jacoco.csv`

JaCoCo is configured without production-class exclusions and without a coverage threshold. The `report` goal runs during `verify`; `mvn test` still runs the tests but does not generate the report. No Failsafe execution is configured, so `clean verify` covers the current default `*Test` suite and does not execute the optional `*IT` classes.

The current retained 2026-09-18 baseline is 72.99% lines, 38.59% branches, 64.22% instructions, 48.60% complexity, 68.43% methods, and 91.25% classes. Coverage shows which bytecode was exercised; it does not prove assertion quality, provider behavior, portability, reliability, or performance. The class counter reaching 91.25% does not mean overall “90%+ coverage”; line and branch coverage remain substantially lower.

## Focused vertical-slice integration test

```powershell
.\mvnw.cmd -Dtest=DeterministicVerticalSliceTest test
```

The scenarios exercise the Spring-managed path from PDF upload and HTTP 202 through transaction-after-commit scheduling, the real `@Async` proxy, summary and RAG ingestion, search, grounded Q&A, and successful multi-frame SSE completion. They also verify retry recovery, a controlled failed-summary state, and an embedding failure that preserves the successful summary while marking both the overall document lifecycle and embedding lifecycle as failed. A two-user scenario uses the real H2 repositories and `SimpleVectorStore` semantics to verify that vector filtering occurs before `topK`, search/Q&A sources contain only the authenticated owner's data, and direct calls to all five Agent tools enforce a test-supplied owner `ToolContext`. The embedding-failure fixture fails before any vector or chunk is stored.

The retry-exhaustion scenario injects an API-key marker, database URL, and absolute path into the provider exception. It verifies that the persisted summary and authenticated status API contain only the fixed safe fallback while the document remains failed and successfully indexed RAG data is retained. `AiAnalysisServiceTest` and `DocumentAsyncServiceTest` separately enforce the same redaction at the recovery and pre-index failure boundaries.

The user scope is stored in new vector metadata. Under the current `SimpleVectorStore` metadata-filter semantics, older entries without `userId` are expected to be excluded and must be re-indexed before they can be searched again. Loading a legacy JSON vector file is not covered by the current tests, so treat this as a migration requirement rather than executed compatibility evidence.

`RagServiceTest` separately verifies best-effort vector compensation when a flushed database chunk write fails after a vector add, and verifies that file-backed vector-store persistence failure is propagated instead of being reported as success. These are unit-level failure-injection tests, not a transaction-commit fault test. Because the vector store and relational database do not share one transaction, a commit-time failure or failed compensation can still leave data requiring reconciliation; this is not an atomic guarantee.

Only the external AI boundary is deterministic. The fixed responses and embeddings do not prove Gemini response quality, visual/OCR quality, semantic relevance, latency, concurrency, or provider availability.

## Focused Spring Retry and monitoring integration test

```powershell
.\mvnw.cmd -Dtest=SpringRetryMonitoringIntegrationTest test
```

This Spring context test calls the proxied `AiAnalysisService`, injects two parser failures, and verifies that Spring Retry reaches `@Recover`. It also locks the advisor-order contract: two physical attempts produce one logical SUMMARY failure counter, one duration sample, no success counter, and no successful usage record. The monitoring aspect is explicitly ordered outside Spring Retry so future framework or configuration changes cannot silently return to per-attempt business metrics.

## Focused document authorization integration test

```powershell
.\mvnw.cmd -Dtest=DocumentAuthorizationIntegrationTest test
```

This eleven-scenario H2/MockMvc suite uses real repositories and `CustomUserDetails` principals for ordinary users plus an administrator. It verifies that foreign and unknown document IDs return the same 404 contract; status/detail/update/re-analysis/delete reject cross-user access without changing state; owners and administrators can perform the permitted mutations; every unknown single-document operation returns 404; active and deleted lists are owner-scoped while administrators can view all; the anonymous home page contains no document data; both upload endpoints derive ownership only from authentication even when a spoofed `userId` parameter is supplied; the REST upload acknowledgement excludes user credentials and storage fields; and protected RAG endpoints fail closed when an authenticated principal cannot be mapped to an application user. Restore scenarios verify owner/admin scope, safe 404/409 responses, source-path checks, processing state, and exactly one scheduled async rebuild. Download scenarios verify owner/admin byte-for-byte attachment responses and safe 404 responses for unauthorized, deleted, missing, or escaping sources. `DocumentAsyncService` is mocked in this suite to prevent unrelated background AI work; authorization, controllers, services, security filters, and persistence remain real.

## Scripted document Web client flow

```powershell
node scripts/test-document-web-client.mjs
```

This dependency-free Node test executes the page's inline script with a small DOM and API double. It checks authenticated list loading, filename text rendering, original-file download, delete and restore requests, list refresh, and disabled deletion while processing. It is not a real browser or network test.

## Focused scripted provider-selection Tool Calling contract test

```powershell
.\mvnw.cmd -Dtest=AgentToolCallingContractTest test
```

This deterministic contract drives the real `AgentService` and `ChatClient` configuration. Its scripted local `ChatModel` double mirrors the two-round internal tool loop used by `GoogleGenAiChatModel`: after verifying the forwarded system/user prompt, offered callbacks, and internal-execution option, the first provider round emits a call for the registered `getDocumentStats` tool; Spring AI's real `ToolCallingManager` executes the callback; and the second round observes the resulting `ToolResponseMessage` before producing a grounded final response. The test verifies that the callback receives the server-created `smartdoc.userId`, the authenticated owner's repository scope is queried, a spoofed user scope is never queried, and the trace records the call ID/name, two provider rounds, tool result, and final marker.

This proves the application/framework callback contract with a scripted deterministic provider selection. It does not prove that live Gemini will choose the desired tool reliably, and it does not test multi-tool sequences, streaming tool calls, or provider-driven recovery from tool errors.

## Focused Agent failure-contract tests

```powershell
.\mvnw.cmd -Dtest=DocumentAgentToolsTest,AgentStreamContractTest test
```

These ten tests cover all five read-only tool boundaries, fail-closed authorization context, a dependency exception returned as a typed/redacted tool result, and the SSE terminal protocol. The stream scenarios verify token(s) followed by exactly one `complete` event on success; a safe `MODEL_UNAVAILABLE` error before the first token; a safe `MODEL_TIMEOUT` error after partial output; and an `INPUT_REJECTED` event without provider invocation. Failure events contain correlation IDs where server-side diagnostics exist, never expose the injected connection string, API-key marker, path, or exception message, and never append `complete` after an error.

`DeterministicVerticalSliceTest` separately verifies the controller wire format contains named `token` and `complete` SSE frames and no `error` frame on success. `AgentControllerStreamTest` verifies comment heartbeat mapping, termination, and downstream cancellation with Reactor subscriptions. `AiMonitoringAspectTest` verifies that terminal stream errors and typed summary failures increment failure metrics without recording successful usage, while successful streams and summaries record usage estimates. The Web page buffers partial network reads and requires a single terminal event; there is no Playwright/browser, proxy, reconnect, or load test yet.

## Focused API validation and OpenAPI contract tests

```powershell
.\mvnw.cmd '-Dtest=GlobalExceptionHandlerTest,AuthControllerTest' test
```

These 11 tests across two test classes verify the centralized validation error contract and authentication controller behavior:
- `GlobalExceptionHandlerTest` (7 tests) verifies that `MethodArgumentNotValidException` returns HTTP 400 RFC 7807 Problem Details with type `https://api.spe.smartdoc.com/errors/validation-failed`, title `Validation Failed`, safe generic detail `请求参数验证失败`, a `timestamp`, and structured `fieldErrors` without echoing rejected user values. It also verifies typed status/Problem Detail mappings for `DocumentNotFoundException` (404), `AccessDeniedException` (403), `MaxUploadSizeExceededException` (413), `IllegalArgumentException` (400), `AuthenticationException` (401), and safe redacted 500 handling for general unexpected exceptions.
- `AuthControllerTest` (4 tests) uses MockMvc to verify that `/api/auth/register` and `/api/auth/login` produce the structured HTTP 400 Problem Detail when request validation fails, ensuring rejected input values and internal exception class names are not leaked to clients. It also verifies via reflection that the registration OpenAPI `@Operation` description documents assigning the standard `USER` role and does not advertise username-prefix administrator assignment.

These tests prove the contract for audited controller validation and exception-handling paths. They do not prove application-wide error de-identification across all endpoints, full OpenAPI schema conformance, end-to-end authentication against a real MySQL instance, or live provider behavior.

## Optional MySQL 8 Testcontainers tier

Requires a working Docker-compatible environment:

```powershell
.\mvnw.cmd -Dtest=MySqlContainerVerticalSliceIT test
```

This reuses the same vertical-slice scenarios against a real MySQL 8 container. The test is skipped when Docker is unavailable. A skipped run is not MySQL verification and does not prove Docker Compose or Linux reproducibility.

## Optional live Gemini smoke test

The smoke test is disabled by default and performs one small real chat request only when both the system property and API key are present:

```powershell
$env:GOOGLE_API_KEY="your-key"
.\mvnw.cmd -Dtest=GeminiLiveSmokeIT -Dlive.gemini=true test
```

Linux/macOS equivalent:

```bash
export GOOGLE_API_KEY="your-key"
./mvnw -Dtest=GeminiLiveSmokeIT -Dlive.gemini=true test
```

A successful result proves only that the configured Gemini chat model accepted one request and returned non-empty text at that time. It does not prove PDF multimodal behavior, embeddings, RAG, Tool Calling, streaming, quality, reliability, or performance.

## Docker Compose smoke

Copy `.env.example` to `.env`, replace all required placeholder secrets, and export the values into the current shell. On Linux with Docker Compose, `curl`, and `jq` installed:

```bash
set -a
source .env
set +a
bash scripts/compose-smoke.sh
```

The script uses an isolated Compose project and fresh named volumes. It validates the Compose model, builds the image while running the default Maven tests, waits for MySQL and application health, registers a temporary user, uploads a fixed text fixture, awaits asynchronous summary/indexing, verifies the expected marker in retrieval and the grounded answer/source, requires multiple non-error SSE frames, restarts the app container, and checks that document/vector data remain available. It removes its temporary stack and volumes after success; pass `--keep` only when the isolated stack should remain for inspection.

The current Compose configuration disables `data.sql`. An existing `db_data` volume created by an older configuration may still contain the previously seeded `admin_user_1`; audit and rotate or remove that account during an upgrade. The application deliberately does not delete existing database users automatically.

On the current audit host, Docker was unavailable. Only YAML parsing, shell syntax, the public-health security test, and the normal Maven suite were executed. The image build, Compose startup, real Gemini calls, MySQL container, Linux permissions, and restart-persistence path remain unverified until this script succeeds on a Docker-capable Linux host.

## Test artifacts

Deterministic uploads are written below `target/deterministic-test/uploads`. The deterministic vector store is reset in memory before each vertical-slice scenario and is not persisted to the production JSON file.
