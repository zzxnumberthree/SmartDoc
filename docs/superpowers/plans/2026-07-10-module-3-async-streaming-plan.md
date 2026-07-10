# SmartDoc 模块三：异步处理与流式输出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 SmartDoc 系统实现生产级高并发异步 AI 处理架构，解耦文件上传与耗时 AI 摘要及 RAG 嵌入流程，并为 Agent 智能助手提供 SSE 流式响应能力与前端实时动态问答 UI。

**Architecture:** 
1. **异步解耦架构**：文件上传后在 100ms 内立即返回 HTTP 202 Accepted + 文档 ID；后台由自定义异步线程池 (`documentTaskExecutor`) 独立处理 AI 摘要与 RAG 切分向量化，配置指数退避重试机制 (`@Retryable`) 与优雅降级方案 (`@Recover`)。
2. **SSE 流式响应架构**：引入 `spring-boot-starter-webflux` 提供 `Flux<String>` 响应式数据流支持。Agent 对话服务端结合 Spring AI `ChatClient.stream()` 与 `MessageChatMemoryAdvisor`，流式推送 Token 块；前端利用 `EventSource` / Fetch Stream 读取流数据，实现实时逐字打字输出效果。

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring AI 1.1.2 (Google GenAI Gemini 2.5-flash), Spring Retry, Spring WebFlux (`Flux`), Thymeleaf + Bootstrap 5 + Native EventSource/Fetch API.

## Global Constraints
1. 严格遵守分层架构设计：Controller -> Service -> Repository -> Model。
2. 全局异常处理必须经由 `GlobalExceptionHandler` 输出标准 RFC 7807 (Problem Detail) 响应格式。
3. 必须使用 Lombok (`@RequiredArgsConstructor`, `@Slf4j`) 进行构造器注入与日志打印，严禁使用 `System.out.println()`。
4. 所有 AI 调用需具备超时控制与失败降级策略，绝不可因 AI 接口异常导致主业务崩溃。

---

## Task Structure

### Task 1: 引入 WebFlux 与创建异步配置类 `AsyncConfig`

**Files:**
- Modify: `d:/Special_CODE/SmartDoc/pom.xml`
- Create: `d:/Special_CODE/SmartDoc/src/main/java/com/spe/smartdocjp/config/AsyncConfig.java`

- [ ] **Step 1: 在 pom.xml 添加 `spring-boot-starter-webflux` 和 `spring-retry` 依赖（如需）并重新构建**
- [ ] **Step 2: 创建 `AsyncConfig.java`，配置 `@EnableAsync`, `@EnableRetry` 及核心数为 4，最大数为 8 的线程池 `documentTaskExecutor`**
- [ ] **Step 3: 运行 `.\mvnw.cmd test-compile` 验证依赖冲突与编译正确性**

---

### Task 2: 创建 `DocumentStatusDTO` 与重构 `AiAnalysisService` 重试机制

**Files:**
- Create: `d:/Special_CODE/SmartDoc/src/main/java/com/spe/smartdocjp/model/DTO/DocumentStatusDTO.java`
- Modify: `d:/Special_CODE/SmartDoc/src/main/java/com/spe/smartdocjp/service/AiAnalysisService.java`

- [ ] **Step 1: 创建 `DocumentStatusDTO.java` 传输对象**
- [ ] **Step 2: 为 `AiAnalysisService.java` 添加 `@Retryable` 逻辑支持与 `@Recover` 降级逻辑**
- [ ] **Step 3: 编写单测验证重试与降级机制生效**

---

### Task 3: 拆分与构建 `DocumentAsyncService` 后台异步引擎

**Files:**
- Create: `d:/Special_CODE/SmartDoc/src/main/java/com/spe/smartdocjp/service/DocumentAsyncService.java`
- Create: `d:/Special_CODE/SmartDoc/src/test/java/com/spe/smartdocjp/service/DocumentAsyncServiceTest.java`

- [ ] **Step 1: 创建 `DocumentAsyncService` 类，提供 `@Async("documentTaskExecutor") public void processAiAndRagAsync(Long documentId, Path targetLocation)`**
- [ ] **Step 2: 编写 `DocumentAsyncServiceTest` 验证异步执行成功及失败时的 `Document.status` 状态流转 (`processing` -> `completed`/`failed`)**
- [ ] **Step 3: 运行 `.\mvnw.cmd test -Dtest=DocumentAsyncServiceTest` 确保测试通过**

---

### Task 4: 改造 `DocumentService` 与 `DocumentController` 实现 HTTP 202 响应与状态查询端点

**Files:**
- Modify: `d:/Special_CODE/SmartDoc/src/main/java/com/spe/smartdocjp/service/DocumentService.java`
- Modify: `d:/Special_CODE/SmartDoc/src/main/java/com/spe/smartdocjp/controller/DocumentController.java`
- Modify: `d:/Special_CODE/SmartDoc/src/test/java/com/spe/smartdocjp/service/DocumentServiceTest.java`

- [ ] **Step 1: 修改 `DocumentService.uploadDocument` 为快速落库并调用 `documentAsyncService.processAiAndRagAsync()`**
- [ ] **Step 2: 在 `DocumentController` 中修改 `/api/documents/upload` 返回 HTTP 202 Accepted，并添加 `GET /api/documents/{id}/status` 和 `GET /api/documents/status` 轮询接口**
- [ ] **Step 3: 更新并运行 `DocumentServiceTest` 验证快速响应与状态查询**

---

### Task 5: 为 Agent 对话服务端实现 SSE (`Flux<String>`) 流式端点

**Files:**
- Modify: `d:/Special_CODE/SmartDoc/src/main/java/com/spe/smartdocjp/service/agent/AgentService.java`
- Modify: `d:/Special_CODE/SmartDoc/src/main/java/com/spe/smartdocjp/controller/AgentController.java`
- Modify: `d:/Special_CODE/SmartDoc/src/test/java/com/spe/smartdocjp/service/agent/AgentServiceTest.java`

- [ ] **Step 1: 在 `AgentService` 中新增 `public Flux<String> chatStream(AgentChatRequest request)` 方法**
- [ ] **Step 2: 在 `AgentController` 中新增支持 `MediaType.TEXT_EVENT_STREAM_VALUE` 的 `POST /api/agent/chat/stream` 与 `GET /api/agent/chat/stream` 端点**
- [ ] **Step 3: 编写并运行 `AgentServiceTest` 测试 `chatStream` 数据流**

---

### Task 6: 前端 `index.html` UI 升级支持动态打字问答与表格自动状态轮询

**Files:**
- Modify: `d:/Special_CODE/SmartDoc/src/main/resources/templates/index.html`

- [ ] **Step 1: 在 `index.html` 中新增 **AI Agent 智能问答助手 (SSE 实时流式交互)** UI 面板及对应 JS 前端逻辑**
- [ ] **Step 2: 为文档列表的 `processing` 行编写 JS 轮询更新逻辑（每 3 秒检测直至状态完结）**
- [ ] **Step 3: 整体运行单元测试并启动服务测试完整连通性**
