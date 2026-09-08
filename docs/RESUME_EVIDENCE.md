# Resume-to-Code Alignment Audit

Audit date: 2026-09-05; evidence updates: 2026-09-06 and 2026-09-07 (Asia/Tokyo)  
Audited snapshot: branch `main`, commit `66fd6d3`, including the current working-tree changes  
Audit purpose: determine what can be truthfully demonstrated from source code, tests, and a runnable demo. This is not a production-readiness certification.

## Audit constraints and method

- `AGENTS.md` and `docs/RESUME_TARGET.md` were added after the original audit to define the evidence contract and truthful target capabilities. The exact Chinese resume text remains the claim set classified below.
- The working tree was already substantially dirty (95 entries when inspected). Evidence from modified or staged files may not exist in commit `66fd6d3` or on the remote repository until the owner deliberately commits it.
- Dependencies were not counted as implementation evidence. Findings below require an actual code path; tests and runtime checks are assessed separately.
- Phase 1 added a deterministic provider-boundary integration harness and the smallest production configuration changes needed for isolated storage/provider selection. It did not add Redis, an FSM, planning, robotics, or performance claims.
- `mvnw.cmd test` ran with Maven and Java 21.0.1 on 2026-09-07: **23 tests passed, 0 failed, 0 errors, 0 skipped**. The default suite used isolated H2 in MySQL compatibility mode and made no application-level calls to the host MySQL server, Docker, Gemini, or another external API; Maven dependency resolution may still require repository access on a clean machine.
- The focused deterministic vertical slice passed **2/2** scenarios. It exercised HTTP 202, transaction-after-commit scheduling, the real Spring `@Async` proxy (`DocAsync-*`), PDF media summary, chunk persistence, vector retrieval, grounded Q&A with source data, multi-frame SSE completion, Spring Retry, and a controlled failed state.
- The MySQL 8 Testcontainers command was invoked on 2026-09-06, but Docker was unavailable; **2 scenarios were skipped**. On 2026-09-07 the Docker CLI was still unavailable. Compose YAML and the Linux smoke script passed static syntax checks only. This is not MySQL, Docker Compose, or Linux runtime proof. The optional live Gemini smoke test was not enabled or executed.

Status meanings:

- **SUPPORTED**: a real implementation path exists and the wording does not materially exceed the evidence.
- **PARTIAL**: a real subset exists, but important parts of the wording, proof, or reliability are absent.
- **MISSING**: no substantive implementation was found.
- **MISLEADING**: the wording asserts completion, quality, or measured results that the repository cannot presently substantiate.

## Executive result

**Resume-Code Alignment Score: 50/100**

This score measures alignment of the supplied resume wording, not general code quality. The repository contains a substantive document application: Java 21/Spring Boot, Spring AI Gemini integration, PDF-as-media summarization, asynchronous background work, local RAG, cited Q&A, SSE output, and five read-only Spring AI tools. The score is held down by the resume's central claims of long-context/multi-source decision reasoning, measured 30x and sub-200 ms performance, Redis, a formal FSM, high-level task planning, high reliability/high concurrency, and 90%+ test coverage, none of which is currently demonstrated.

| Scoring area | Weight | Earned | Reason |
|---|---:|---:|---|
| Gemini, PDF summarization, RAG, Q&A | 25 | 20 | Real code paths plus deterministic end-to-end wiring evidence; no live quality evaluation |
| Long-context/multi-source reasoning and measured extraction | 20 | 3 | Basic chunking exists; claimed architecture and measurements do not |
| Async, streaming, cache, concurrency, latency | 20 | 10 | Real proxy execution and successful SSE are tested; Redis, load evidence, and latency proof do not exist |
| Agent, Tool Calling, FSM, planning | 20 | 8 | Tool Calling exists; formal workflow/planner does not |
| Containers, Linux reproducibility, tests, coverage | 15 | 9 | Health/secrets/volumes/non-root image and smoke workflow improved; Docker/MySQL/Linux runtime and coverage remain unproved |
| **Total** | **100** | **50** | |

## Claim-by-claim evidence

### Claim 1

1. **Exact resume claim:** `基于多模态大模型的智能决策与 Agent 规划系统。`
2. **Status:** **MISLEADING**.
3. **Relevant source files/classes/methods:** `AiAnalysisService.generateSummaryFromPdf` sends a PDF as `Media(application/pdf)`; `AgentService.chat/chatStream` registers `DocumentAgentTools`; `RagService.ask` performs retrieve-then-generate Q&A. No decision model, planning data structure, planner, or workflow engine was found.
4. **Existing tests:** `AgentServiceTest` tests only input guardrails; `DocumentAgentToolsTest` directly tests three tool methods with mocks; no planning or decision test exists.
5. **Interview demonstration:** Demonstrate PDF upload, summary, RAG Q&A, and a read-only tool call. Explicitly describe it as an AI document assistant with model-selected tools, not an autonomous planning system.
6. **Missing implementation:** Explicit task representation, planning algorithm, plan validation, step execution, state persistence, recovery/termination rules, and a decision-quality evaluation.
7. **Technical risks:** “智能决策” and “Agent 规划系统” invite architecture questions that the current linear chat/tool flow cannot answer. They may also imply autonomous-control or business-decision authority that does not exist.
8. **Recommendation:** **Simplify the resume wording now** to `基于 Gemini 多模态 PDF、RAG 与 Tool Calling 的智能文档助手`; implement planning only if it is genuinely relevant to the target role.

### Claim 2

1. **Exact resume claim:** `针对复杂长文档与多模态多源信息`
2. **Status:** **PARTIAL**.
3. **Relevant source files/classes/methods:** `RagService.embedAndStoreDocument` reads PDF pages or text, applies the default `TokenTextSplitter`, and stores chunks. `AiAnalysisService.generateSummaryFromPdf` sends one whole PDF to Gemini; `generateSummaryFromText` sends one whole text string. There is no multi-source aggregation pipeline.
4. **Existing tests:** No long-document fixture, image/OCR fixture, multi-document reasoning test, token-limit test, or quality evaluation. `RagServiceTest` mocks vector search and only tests DTO mapping.
5. **Interview demonstration:** Upload one text PDF, show page/text chunks and semantic retrieval. Do not call this multi-source fusion or proven long-document reasoning.
6. **Missing implementation:** Hierarchical/map-reduce summarization, context-budget management, source normalization, cross-source deduplication/conflict handling, OCR/image extraction validation, and long-document evaluation fixtures.
7. **Technical risks:** Text input is read fully into memory; the summary path does not reuse the RAG chunks; upload limit is 10 MB; provider context/size limits are not preflighted.
8. **Recommendation:** **Simplify** to `PDF/TXT 文档解析与分块检索`. Implement and evaluate a hierarchical multi-source pipeline before restoring “复杂长文档/多源信息”.

### Claim 3

1. **Exact resume claim:** `集成 Google Gemini 视觉多模态大模型`
2. **Status:** **SUPPORTED** as a code-level, PDF-specific integration; broader vision/OCR capability is not proved.
3. **Relevant source files/classes/methods:** `pom.xml` uses Spring AI BOM 1.1.2 and `spring-ai-starter-model-google-genai`; `application.properties` selects `gemini-2.5-flash`; `AiAnalysisService.generateSummaryFromPdf` constructs a `UserMessage` with PDF media; `PdfDocumentParser.parseAndAnalyze` invokes it; `AiConfig.googleGenAiClient` reads `GOOGLE_API_KEY` and applies a 60-second timeout.
4. **Existing tests:** `DeterministicVerticalSliceTest` verifies that the summary request reaches the provider boundary with PDF media through the real application path. `GeminiLiveSmokeIT` provides an opt-in one-request chat smoke test, but it was not executed and does not exercise PDF media.
5. **Interview demonstration:** With a valid API key and working network/proxy settings, upload a PDF containing text plus a chart/image and show the generated structured summary. Retain a fixed fixture and expected observations.
6. **Missing implementation:** A successful live Gemini PDF fixture test and evaluated examples proving chart/image/OCR handling.
7. **Technical risks:** Production proxy defaults can break the API call in Docker/Linux; API/model availability and quotas are external; deterministic provider tests do not prove Gemini behavior or quality.
8. **Recommendation:** **Keep with narrower wording:** `通过 Spring AI 接入 Gemini 2.5 Flash，将 PDF 作为多模态输入生成摘要`.

### Claim 4

1. **Exact resume claim:** `设计高效 Prompt 工程与结构化分块解析管线`
2. **Status:** **PARTIAL**.
3. **Relevant source files/classes/methods:** `AiAnalysisService.generateSummaryFromText/generateSummaryFromPdf` use fixed prompts with four requested sections; `RagService.embedAndStoreDocument` uses `PagePdfDocumentReader`, `TextReader`, and the default `TokenTextSplitter`; metadata and chunks are persisted.
4. **Existing tests:** `DeterministicVerticalSliceTest` verifies PDF ingestion creates persisted chunks containing the fixture marker and makes them retrievable. There is still no prompt regression/evaluation, splitter-boundary, or structured-output schema test.
5. **Interview demonstration:** Show the four-section prompt, resulting summary, stored chunk count, metadata, and a retrieval result with citation.
6. **Missing implementation:** Prompt versioning, explicit delimiter/escaping, structured output schema/validation, tuned splitter parameters, table/image-aware parsing, chunk-quality metrics, and prompt-injection tests for document content.
7. **Technical risks:** “高效” is unmeasured. The summary prompt concatenates untrusted document text directly and returns free-form text. Default splitting is token-based, not semantic or document-structure-aware.
8. **Recommendation:** **Simplify** to `设计摘要 Prompt，并使用 PDF reader + TokenTextSplitter 构建分块嵌入管线`.

### Claim 5

1. **Exact resume claim:** `实现长上下文的精准语义提取与决策推理`
2. **Status:** **MISLEADING**.
3. **Relevant source files/classes/methods:** `RagService.search/ask` provides ordinary similarity retrieval and grounded Q&A. No long-context orchestration, decision schema, reasoning trace, evaluator, confidence threshold, or decision policy exists.
4. **Existing tests:** No accuracy, faithfulness, citation correctness, long-context recall, decision outcome, or hallucination evaluation.
5. **Interview demonstration:** Only demonstrate semantic retrieval and cited Q&A; do not claim precision or decision reasoning.
6. **Missing implementation:** Gold datasets, retrieval/generation metrics, context selection/compression, structured decision outputs with evidence, abstention/confidence policy, and deterministic business rules/HITL.
7. **Technical risks:** `RagService.ask` searches with threshold `0.0`, so irrelevant chunks may be included. “精准” is an empirical claim without an evaluation baseline.
8. **Recommendation:** **Remove this wording now**. Consider `基于向量检索的文档语义问答与来源引用`.

### Claim 6

1. **Exact resume claim:** `将传统人工信息抽取耗时从 5 分钟压缩至 10 秒以内（30倍提速）`
2. **Status:** **MISLEADING**.
3. **Relevant source files/classes/methods:** No benchmark source, script, report, dataset, recorded timings, or metric instrumentation establishes these numbers. Logging/AOP timing is not a controlled comparison.
4. **Existing tests:** No performance or user-task benchmark.
5. **Interview demonstration:** This claim cannot currently be demonstrated. A one-off fast response is not evidence of an average or a 30x improvement.
6. **Missing implementation:** Defined task and baseline, representative corpus, warm/cold runs, sample count, percentile statistics, hardware/network/model version, raw results, and reproducible benchmark command.
7. **Technical risks:** Gemini network inference has variable latency and a configured 60-second timeout. A universal “10 seconds” promise is likely brittle.
8. **Recommendation:** **Remove the numbers now**. Reintroduce only measured wording such as `在固定 N 份测试集上将人工抽取中位耗时从 X 降至 Y` with the report in-repo.

### Claim 7

1. **Exact resume claim:** `采用非阻塞异步流式传输与 Redis 动态缓存层`
2. **Status:** **PARTIAL**: asynchronous processing and streaming exist; Redis does not.
3. **Relevant source files/classes/methods:** `AsyncConfig.documentTaskExecutor` configures a 4/8-thread, queue-100 executor; `DocumentAsyncService.processAiAndRagAsync` uses `@Async`; upload returns HTTP 202 and schedules work after commit; `AgentService.chatStream` returns `ChatClient.stream().content()`; `AgentController` produces SSE; `index.html` consumes the stream. No Redis dependency, bean, configuration, Compose service, or call site exists.
4. **Existing tests:** `DeterministicVerticalSliceTest` blocks the provider boundary, proves upload returns while background work is blocked, verifies execution on `DocAsync-*`, and checks a successful three-chunk SSE response. It does not prove Redis, saturation behavior, backpressure, browser behavior, or concurrency under load.
5. **Interview demonstration:** Show upload returning 202 while status is polled, then show token chunks arriving in the Web UI. State explicitly that caching is not implemented.
6. **Missing implementation:** Redis selection and cache semantics, key design/TTL/invalidation, serialization, failure fallback, metrics, stampede protection, and integration/load tests.
7. **Technical risks:** `CallerRunsPolicy` makes the request thread execute work when saturated, so upload is not guaranteed non-blocking under load. The app also includes both MVC and WebFlux; the overall runtime is not demonstrated as end-to-end reactive.
8. **Recommendation:** **Split and simplify** to `采用线程池异步处理文档，并通过 SSE 流式展示 Agent 响应`. Remove Redis until implemented for a measured need.

### Claim 8

1. **Exact resume claim:** `将平均端到端推理与查询延迟优化至 <200ms`
2. **Status:** **MISLEADING**.
3. **Relevant source files/classes/methods:** No latency SLO, benchmark, telemetry query, cache implementation, or report exists. The Google client timeout is 60 seconds, which is a failure bound rather than latency proof.
4. **Existing tests:** No timing assertions, load tests, percentiles, or performance environment.
5. **Interview demonstration:** Cannot be truthfully demonstrated now. Separate future metrics into upload acknowledgement, vector-only search, time-to-first-token, and full-answer completion.
6. **Missing implementation:** Instrumentation and trace boundaries, workload/corpus, warmup, concurrency levels, percentile reporting, repeated runs, and raw benchmark results.
7. **Technical risks:** “End-to-end inference <200 ms” is implausibly strong for a remote Gemini call unless it means time-to-first-token or a cache hit; the current wording does not make that distinction.
8. **Recommendation:** **Remove the claim** until measured. Never mix cached retrieval latency with full remote-model completion latency.

### Claim 9

1. **Exact resume claim:** `通过 Web 端实时流式呈现，保障复杂推理决策的高可靠与高响应性`
2. **Status:** **PARTIAL**.
3. **Relevant source files/classes/methods:** `AgentController.chatStreamPost/chatStreamGet`, `AgentService.chatStream`, and `index.html` implement a Web SSE display. The service maps stream errors into user-visible chunks.
4. **Existing tests:** `DeterministicVerticalSliceTest` exercises the controller-level SSE success path, asserts at least three `data:` frames in order, and observes async request completion. There is no explicit completion-event protocol, disconnect/cancellation, reconnect, browser, or reliability/load test.
5. **Interview demonstration:** Ask a document question in stream mode and show chunks appended live. Describe it as an SSE UX feature, not proof of complex decision reliability.
6. **Missing implementation:** Heartbeats, client reconnection/resumption, cancellation propagation, server/client timeouts, bounded buffering/backpressure policy, structured SSE events, and SLO/error-rate evidence.
7. **Technical risks:** GET places prompts in URLs and logs; frontend parsing can mishandle SSE frames split across network reads; stream errors are converted to text while the HTTP response can remain successful.
8. **Recommendation:** **Keep only** `Web 端通过 SSE 实时呈现模型输出`; remove “保障复杂推理决策的高可靠”.

### Claim 10

1. **Exact resume claim:** `规范化设计外部工具动态调用（Tool/Function Calling）接口与状态机`
2. **Status:** **PARTIAL**: Tool Calling is real; the state machine is missing.
3. **Relevant source files/classes/methods:** `DocumentAgentTools` defines five `@Tool` methods (`searchDocuments`, `getDocumentById`, `listRecentDocuments`, `getDocumentStats`, `compareDocuments`); `AgentService.getOrCreateChatClient` registers them through `defaultTools`. Document status enums and direct assignments are lifecycle flags, not an FSM.
4. **Existing tests:** `DocumentAgentToolsTest` directly tests search, stats, and compare formatting with mocks. No test proves that Gemini selects/invokes a tool. No transition-table or invalid-transition test exists.
5. **Interview demonstration:** Demonstrate a prompt that causes a visible read-only document tool call and explain tool schemas. Do not draw or describe an FSM as implemented.
6. **Missing implementation:** Tool-call integration trace/test, per-user authorization in tools, typed tool results/error contracts, idempotency/timeout policies, explicit states/events/guards, persisted transitions, and invalid-transition enforcement.
7. **Technical risks:** Tool methods query global repositories without visible per-user filtering, creating a cross-user disclosure risk. Model-controlled dispatch is not equivalent to deterministic workflow control.
8. **Recommendation:** **Keep Tool Calling, remove FSM**: `使用 Spring AI @Tool 设计 5 个只读文档检索/统计函数，并注册到 Agent`.

### Claim 11

1. **Exact resume claim:** `验证自律系统高层任务规划（Task Planning）可行性`
2. **Status:** **MISSING**.
3. **Relevant source files/classes/methods:** `agent-system.st` contains tool-use instructions and `AgentService` performs a single prompt/call or prompt/stream operation. No planner/executor loop, plan object, goal decomposition, checkpoint, step budget, or planning evaluation exists.
4. **Existing tests:** None for task decomposition, multi-step execution, recovery, convergence, plan quality, or autonomous control.
5. **Interview demonstration:** None is currently honest. Tool selection may be shown only as Tool Calling.
6. **Missing implementation:** Goal/task/step model, planner and executor, observable plan, success criteria, bounded iteration, durable state/checkpoints, recovery/HITL, and a scenario-based evaluation suite.
7. **Technical risks:** Describing one LLM tool-selection turn as “high-level task planning” is a common but readily exposed exaggeration. “自律系统” can also imply robotics/control safety properties absent here.
8. **Recommendation:** **Remove the claim** unless a real planning workflow is implemented and evaluated.

### Claim 12

1. **Exact resume claim:** `完成 Docker 容器化部署`
2. **Status:** **PARTIAL**.
3. **Relevant source files/classes/methods:** `Dockerfile` is a two-stage Maven/Temurin 21 build that runs the default tests and uses a non-root runtime user plus healthcheck; `compose.yaml` defines pinned MySQL 8.0.44, health dependencies, required environment secrets, and database/application data volumes; `scripts/compose-smoke.sh` defines the intended health/auth/upload/RAG/Q&A/SSE/restart workflow.
4. **Existing tests:** `AdminControllerTest.healthProbeIsPublicButMetricsRemainProtected` verifies anonymous health access without detailed components while metrics remains protected. Compose YAML parsed and the Bash script passed syntax checking. Docker was unavailable, so the image, stack, MySQL and smoke workflow were not run.
5. **Interview demonstration:** On a clean Linux/Docker host, export the `.env` values and run `bash scripts/compose-smoke.sh`. Retain the successful command output before using “完成”.
6. **Missing implementation:** An executed clean-host record or CI container smoke, confirmed Linux volume permissions, and optional digest pinning/update automation.
7. **Technical risks:** The runtime workflow remains unverified; the non-Compose application configuration still has fallback database/JWT secrets and enables a known demo admin through `data.sql`; an old Compose database volume may retain that seeded account; existing manually altered application volumes could have incompatible permissions; upstream Maven/Temurin images are tag-pinned rather than digest-pinned. New Compose databases disable SQL seed data.
8. **Recommendation:** Until verified, say **`提供 Dockerfile 与 Docker Compose（应用 + MySQL）`**. After clean Linux smoke evidence, “容器化部署” is reasonable for a demo environment.

### Claim 13

1. **Exact resume claim:** `严格单元测试（测试覆盖率 90%+）`
2. **Status:** **MISLEADING**.
3. **Relevant source files/classes/methods:** `pom.xml` includes Spring Boot test support, H2, Awaitility, and optional Testcontainers; 10 default JUnit 5 test classes contain 23 tests. There is no JaCoCo/Cobertura plugin, coverage rule, report, or CI gate.
4. **Existing tests:** All 23 default tests passed locally. `DeterministicVerticalSliceTest` adds two Spring-managed integration scenarios for success and failure; the actuator security test verifies the container health contract; other tests are primarily Mockito/unit or context tests. MySQL Testcontainers compiled but its two scenarios were skipped because Docker was unavailable.
5. **Interview demonstration:** Run `./mvnw test` and show 23/23 passing, then run the focused vertical slice. Do not quote a coverage percentage. Explain that H2 isolates the default suite but does not prove MySQL compatibility.
6. **Missing implementation:** Executed MySQL Testcontainers evidence, JaCoCo report, meaningful branch/line threshold, Linux CI, and broader security/Tool Calling/provider integration tests.
7. **Technical risks:** Passing deterministic provider tests do not prove external integrations. H2 MySQL mode can differ from real MySQL. No measured coverage percentage exists.
8. **Recommendation:** **Replace now** with `使用 JUnit 5/Mockito 与 Spring Boot 集成测试验证核心服务及 PDF-RAG-SSE 主链路（22 个测试）`. Claim 90%+ only after a retained report and enforced threshold exist.

## Requested technology cross-check

| Area | Status | What is demonstrable now | Main limitation / risk | Current interview wording |
|---|---|---|---|---|
| Java version consistency | **SUPPORTED** | README, Maven compiler/property, Docker build and runtime all use Java 21; tests ran on Java 21.0.1 | `GEMINI.md` says Java 17+, which is broad rather than exact | “Java 21” |
| Spring Boot / Spring AI | **SUPPORTED** | Boot 3.4.1, Spring AI 1.1.2 BOM, Google GenAI starter and real ChatClient paths | No provider integration test; early `AiConfig` bean warning during test startup | “Spring Boot 3.4.1 + Spring AI” |
| Gemini API integration | **SUPPORTED** | Chat and embedding code use Google GenAI; model config is Gemini 2.5 Flash; an opt-in chat smoke exists | Live smoke was not run; API key/network/quota still required | Narrow code-level integration claim |
| Multimodal PDF processing | **PARTIAL** | PDF is attached as `application/pdf`; deterministic integration verifies the PDF-media path and RAG page extraction | No successful live Gemini PDF or evaluated image/chart/OCR behavior | “PDF 多模态输入” only |
| Automatic summarization | **SUPPORTED** | Upload triggers TXT/MD/Java or PDF summary automatically in background | Whole-input summary; free-form output; no quality tests | “自动生成文档摘要” |
| Interactive Q&A | **SUPPORTED** | `/api/search/ask` is integration-tested with retrieved source data and a deterministic grounded answer | AI quality is stubbed; authorization risk remains in Agent tools | “基于 RAG 的交互式问答” |
| Asynchronous processing | **SUPPORTED** at feature level | 202 upload, after-commit scheduling, `DocAsync-*` proxy execution, lifecycle flags | Saturation can run work on caller; no load test | “线程池后台异步处理” |
| Streaming responses | **SUPPORTED** at feature level | ChatClient Flux, SSE controller, Web stream reader, successful multi-frame controller test | No explicit completion event, reconnect, cancellation, or browser test | “SSE 流式呈现” |
| High concurrency / low latency | **MISSING** | Executor sizes are configuration only | No load test, backpressure evidence, SLO, or measurements | Do not claim |
| Redis | **MISSING** | Nothing | No dependency/service/config/tests | Do not claim |
| RAG | **SUPPORTED** for a local demo | PDF/text ingestion, token splitting, Gemini embeddings, `SimpleVectorStore`, retrieval, cited generation | JSON file store is local, non-distributed, and vulnerable to concurrent save races | “本地 SimpleVectorStore RAG” |
| Tool Calling | **SUPPORTED** at code level | Five `@Tool` methods registered with ChatClient | No real model-selected tool integration test; global data scope | “Spring AI Tool Calling” with scope |
| FSM / state transitions | **MISSING** as FSM | Document/embedding enums and assignments exist | No formal events, guards, transition table, persistence, or validation | Call them status lifecycle flags, not FSM |
| Agent workflow | **PARTIAL** | System prompt, memory advisor, tools, sync/SSE calls | Linear single-agent call; memory is in-process and volatile | “带记忆和只读工具的文档 Agent” |
| Task Planning | **MISSING** | Nothing beyond model tool selection instructions | No decomposition, plan/execution loop, checkpoint, evaluation | Do not claim |
| Docker Compose | **PARTIAL** | App/MySQL healthchecks, required Compose secrets, non-root runtime, app/DB volumes, and a full smoke script exist | YAML/script only statically checked; Docker/Linux/MySQL runtime not executed | “提供可验证的容器化配置与 smoke 脚本” |
| JUnit 5 | **SUPPORTED** | 23/23 default tests pass, including two vertical-slice scenarios and the health security contract | No coverage report; Docker/MySQL scenarios skipped | Quote test count, not coverage |
| Linux reproducibility | **PARTIAL** | Default tests no longer require host MySQL; Maven wrapper and Linux container images exist | Only Windows was executed; no Linux CI; proxy default can break containers | Do not say reproducible until clean-host proof |
| Timeout/retry/error handling | **PARTIAL** | 60 s Google HTTP timeout; 2 attempts with backoff and recovery; proxy-level retry and failed state are tested | Comment says 3 attempts; retries all exceptions; RAG swallows embedding failures; generic error response includes exception message | “basic timeout/retry/fallback” |
| Robotics/autonomous control | **MISSING** | No implementation | No device/simulator/control loop/safety state/telemetry/test | Remove entirely |

## Critical inconsistencies and technical risks

1. **Resume versus implementation:** Redis, formal FSM, high-level planning, autonomous/robotic control, 30x speedup, sub-200 ms end-to-end latency, high-concurrency reliability, and 90%+ coverage are not evidenced.
2. **README drift:** README calls RAG a future roadmap item even though `RagService` is implemented; it does not describe the Agent/SSE/async features now present. Conversely, README says timeout/response hardening is complete without tests demonstrating it.
3. **Java is consistent; runtime portability is only partly proved:** Java 21 aligns across README, Maven, and Docker. The default suite now uses an isolated deterministic H2 profile, but H2 is not MySQL proof and no Linux CI or clean-host Docker run exists.
4. **The working tree is not a stable interview artifact:** Many implementation files are modified and many files are staged/untracked. A remote clone of commit `66fd6d3` may not match this audit.
5. **No Redis at any layer:** no dependency, configuration, Compose service, cache abstraction, implementation, or test.
6. **Status flags are not an FSM:** enum values plus `if/else` assignments do not enforce transitions, invalid states, idempotency, or recovery.
7. **“Agent planning” is currently model-directed tool selection:** there is no observable or persisted plan/executor workflow.
8. **RAG durability/concurrency is demo-grade:** `SimpleVectorStore` is saved to one JSON file from asynchronous work without an explicit concurrency or atomic-write strategy; it is not shared across replicas.
9. **Authorization risk:** Agent tool methods query repository-wide data without visible user scoping, despite the main document service enforcing ownership.
10. **Async saturation can become synchronous:** `CallerRunsPolicy` can execute document work on the upload request thread when the pool and queue are full.
11. **Failure semantics are inconsistent:** summary status may be `completed` while embedding later fails; `RagService` suppresses embedding exceptions and the caller can log the full pipeline as complete.
12. **Retry scope remains broad:** configuration uses `maxAttempts=2` while one comment says three, and every exception is retried. The new failure scenario does verify the Spring Retry proxy and recovery path.
13. **Error leakage:** `GlobalExceptionHandler` says internal detail is hidden but appends `exc.getMessage()` to the 500 response. Stream and tool errors also return exception messages.
14. **Container demo is prepared but not executed:** Compose now requires secrets, disables the proxy by default, mounts application data, and defines health/smoke behavior. Docker/Linux permissions, MySQL startup, real Gemini calls, and restart persistence still require a successful clean-host run.

## Prioritized implementation plan

Scope estimates are engineering estimates for one developer after the current working tree is stabilized. They exclude product discovery and external API approval time.

### P0 — resume-critical truth and demonstrability

| Task | Affected modules | Scope | Dependencies | Tests required | Resume claim validated |
|---|---|---:|---|---|---|
| **Completed on this host:** deterministic vertical-slice integration harness: upload fixture -> 202 -> background summary -> chunks -> search/ask -> SSE | `src/test`, test profiles, AI/vector configuration | **Done; MySQL runtime tier pending Docker** | H2; provider-boundary doubles; Awaitility; optional Testcontainers | 2/2 deterministic scenarios passed; MySQL scenarios skipped; opt-in Gemini chat smoke not run | Automatic summarization, interactive Q&A, async, SSE, and RAG wiring |
| Define and run honest benchmarks before using any number | Benchmark module/scripts and `docs/benchmarks` | **M, 2–4 days** | k6/Gatling or equivalent; Micrometer traces; fixed fixtures | Repeatable load run with concurrency matrix; median/p95/p99 for upload ACK, search, TTFT, full completion; raw output committed | Any future speedup, high-concurrency, or latency wording |
| **Prepared; runtime verification pending:** one-command Docker/Linux demo | `Dockerfile`, `compose.yaml`, `.env.example`, security/config, README, `scripts/compose-smoke.sh` | **Static/config work done; clean Linux execution pending** | Docker Compose; Gemini key; `curl`; `jq` | Health security test passed; YAML/Bash syntax passed; full Docker smoke not executed | Docker configuration only until a clean-host run succeeds |
| Align public wording immediately | `docs/RESUME_TARGET.md`, README, interview runbook | **S, 0.5–1 day** | Findings in this audit | Link checker and command verification | Prevents unsupported Redis/FSM/planning/performance/coverage claims |

### P1 — engineering quality and reliability

| Task | Affected modules | Scope | Dependencies | Tests required | Resume claim validated |
|---|---|---:|---|---|---|
| Add JaCoCo and CI, then raise coverage through behavior-focused tests | `pom.xml`, `.github/workflows` or chosen CI, `src/test` | **M, 2–4 days** | JaCoCo; Linux CI; Testcontainers | Line and branch report; enforce an honest threshold only after gaps are covered | JUnit 5 and any future coverage percentage |
| Correct timeout/retry/failure contracts | `AiConfig`, `AiAnalysisService`, `RagService`, `DocumentAsyncService`, exception handling | **M, 2–4 days** | Spring Retry; optional Resilience4j; Micrometer | Proxy-level retry count/backoff/recovery; retryable vs non-retryable errors; timeout; redacted errors; consistent final statuses | High reliability, timeout/retry/error handling |
| Make async execution bounded and observable | `AsyncConfig`, upload/async services, metrics | **M, 2–4 days** | Micrometer; explicit rejection strategy or durable queue | Saturation/rejection test, non-blocking ACK test, cancellation/idempotency, concurrent document processing | Async/high-concurrency claim, if benchmarks later support it |
| Enforce user authorization inside retrieval and tools | `RagService`, `DocumentAgentTools`, repositories, vector metadata/filtering | **M, 2–4 days** | Security context or explicit principal; metadata filters | Cross-user isolation tests for search, ask, and every tool | Safe interactive Q&A and Tool Calling |
| Fix SSE protocol and privacy behavior | `AgentController`, `AgentService`, `index.html` | **M, 1–3 days** | Structured `ServerSentEvent`; Reactor Test | Frame-boundary parsing, error events, disconnect/cancel, timeout/heartbeat, POST streaming, no prompt logging | Reliable Web streaming |

### P2 — advanced AI functionality

| Task | Affected modules | Scope | Dependencies | Tests required | Resume claim validated |
|---|---|---:|---|---|---|
| Implement hierarchical long-document and multi-source summarization with structured outputs | Parser pipeline, summary orchestration, prompt resources, DTOs, persistence | **L, 7–12 days** | JSON schema/structured output; OCR only if required; evaluation fixtures | Token-boundary, map/reduce, tables/images, conflicting sources, schema validation, quality/golden-set evaluation | Long-context, multi-source semantic extraction; not performance until benchmarked |
| Replace file-backed `SimpleVectorStore` with a durable concurrent vector store | AI config, RAG persistence/search, Compose, migrations | **L, 4–8 days** | pgvector, Redis Vector, or another justified store | Restart durability, concurrent ingestion, deletion consistency, filtered retrieval, recall evaluation | Production-capable RAG and concurrency |
| Add Redis only for a defined measured cache/state use case | cache/config/service layer, Compose, metrics | **M/L, 4–7 days** | Spring Data Redis; Redis container | TTL/invalidation, tenant-safe keys, cache hit/miss, outage fallback, stampede/concurrency, benchmark before/after | Redis cache claim and only the measured latency component |
| Implement an explicit bounded planner/executor workflow and FSM | New workflow domain/service/persistence/controller modules; Agent tools | **L/XL, 10–20 days** | A Java workflow/state-machine library or a small explicit transition engine; durable checkpoint store | Transition table, invalid transitions, restart/resume, max-step convergence, tool failure/retry, HITL, scenario/golden-plan evaluation | FSM, Agent workflow, high-level Task Planning |
| Add real model-selected Tool Calling contract tests | Agent service/tests and provider adapter | **M, 2–4 days** | Deterministic ChatModel stub plus optional live Gemini suite | Tool selection, argument schema, multiple calls, tool error, authorization, response grounding | Tool/Function Calling |

### P3 — optional showcase/demo functionality

| Task | Affected modules | Scope | Dependencies | Tests required | Resume claim validated |
|---|---|---:|---|---|---|
| Build an interview demo dashboard/runbook showing status, citations, tool trace, timings, and failures | Web UI, Actuator/Micrometer, docs/demo assets | **M, 3–6 days** | Existing UI plus metrics/tracing | Playwright/browser smoke; deterministic demo fixtures | Showcase of supported capabilities without changing their truth level |
| Add a robotics/autonomous-control simulator only if target jobs require it | Separate bounded module/service, simulator adapter, safety FSM | **XL, 15–30+ days** | Simulator/protocol chosen from actual job need; telemetry; safety interlocks | Deterministic simulation, command limits, emergency stop, timing/fault injection, replay | Robotics/autonomous-control claims |

Do not bolt robotics terminology onto the document application. If robotics is a target competency, a separate simulator-backed project with explicit safety and control semantics will be more credible.

## Recommended implementation order

1. Preserve and review the completed deterministic vertical-slice baseline; deliberately commit it only after the owner separates unrelated dirty-tree changes.
2. Make the Docker/Linux demo run on a clean Docker-capable host, execute the MySQL tier, and record the exact command/output.
3. Instrument and benchmark before choosing any performance wording or deciding whether Redis is justified.
4. Close reliability/security gaps: provider retries, state consistency, async saturation, SSE protocol, and tenant isolation.
5. Add JaCoCo and Linux CI, then earn an enforceable coverage number through meaningful tests.
6. Improve long-document/multi-source processing and evaluation.
7. Add Redis/durable vector infrastructure only when benchmarks and deployment topology require it.
8. Implement a formal planner/FSM only if the resume and target roles genuinely require that architecture.
9. Treat robotics/autonomous control as a separate project, not a relabeling of this one.

## Claims that should currently NOT be used in an interview

- The project title `智能决策与 Agent 规划系统` as written.
- Long-context “precise” semantic extraction or decision reasoning.
- Multi-source/multimodal fusion beyond sending a PDF as Gemini media.
- 5 minutes to under 10 seconds / 30x speedup.
- Redis dynamic caching.
- Average end-to-end inference and query latency below 200 ms.
- Proven high concurrency or high reliability.
- A formal FSM/state-machine implementation.
- High-level autonomous Task Planning or any self-governing system claim.
- 90%+ test coverage.
- Clean Linux reproducibility or completed Docker deployment until a clean-host run is recorded.
- Robotics or autonomous-control capability.

The following narrower claims are currently reasonable: Java 21 + Spring Boot 3.4.1; Spring AI/Gemini code integration; PDF-as-multimodal-input and text summarization; Spring-proxied asynchronous upload processing; local `SimpleVectorStore` RAG with cited Q&A; Web SSE streaming; five read-only Spring AI tools; Docker/Compose configuration plus an unexecuted smoke script; and 23 passing default JUnit 5 tests. Each should be described with the limitations documented above.

## First concrete coding task recommended

Correct the summary/embedding failure-state contract. Add an embedding-provider failure scenario to the Spring-managed vertical slice, ensure the overall document lifecycle cannot report a misleading full success when indexing fails, and make the final state and logs explicit.

This is the next coding task because deterministic success and summary-provider failure are covered, while an embedding failure can still leave `status=completed` with `embeddingStatus=failed` and a misleading “full pipeline finished” log. Separately, execute the prepared Compose smoke on a Docker-capable Linux host before changing the Docker claim.
