# Testing SmartDoc-JP

The default application test workflow is deterministic and does not require a local MySQL server, Docker, `GOOGLE_API_KEY`, Gemini quota, or a proxy. It makes no application-level external API calls; Maven may still need repository access the first time it resolves the wrapper, plugins, or dependencies.

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

## Focused vertical-slice integration test

```powershell
.\mvnw.cmd -Dtest=DeterministicVerticalSliceTest test
```

The scenario exercises the Spring-managed path from PDF upload and HTTP 202 through transaction-after-commit scheduling, the real `@Async` proxy, summary and RAG ingestion, search, grounded Q&A, and successful multi-frame SSE completion. It also verifies retry recovery and a controlled failed summary state.

Only the external AI boundary is deterministic. The fixed responses and embeddings do not prove Gemini response quality, visual/OCR quality, semantic relevance, latency, concurrency, or provider availability.

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
