package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.common.ApiResponse;
import com.spe.smartdocjp.model.DTO.AgentDTOs.*;
import com.spe.smartdocjp.service.agent.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * REST controller exposing multi-turn intelligent chat endpoints for the AI Agent document assistant.
 */
@Tag(name = "Agent 助手", description = "AI 智能文档助手的对话接口，支持普通返回与 SSE 流式返回")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final AgentService agentService;

    @Value("${smartdoc.agent.stream-heartbeat-interval:15s}")
    private Duration heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;

    /**
     * Handles multi-turn chat interaction with the AI Agent.
     * @param request The chat request containing user message and conversationId.
     * @return AgentChatResponse with the AI Agent's reply and timestamp.
     */
    @Operation(summary = "多轮对话 (同步返回)", description = "与 AI Agent 进行多轮对话，等待完整回答后返回")
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<AgentChatResponse>> chat(@Valid @RequestBody AgentChatRequest request) {
        log.info("REST request for AI Agent chat in conversation '{}' (inputLength={})",
                request.getEffectiveConversationId(), request.message() != null ? request.message().length() : 0);
        AgentChatResponse response = agentService.chat(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Handles multi-turn chat interaction returning Server-Sent Events (SSE) data stream via POST.
     * @param request The chat request.
     * @return Named token, complete, or error SSE events.
     */
    @Operation(summary = "多轮对话 (SSE 流式 POST)", description = "通过 POST 请求获取 SSE 流式回答")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentStreamEvent>> chatStreamPost(@Valid @RequestBody AgentChatRequest request) {
        log.info("REST POST request for AI Agent SSE chat stream in conversation '{}'", request.getEffectiveConversationId());
        return toServerSentEvents(agentService.chatStream(request));
    }

    /**
     * Handles multi-turn chat interaction returning Server-Sent Events (SSE) data stream via GET (for native EventSource).
     * @param message The user prompt.
     * @param conversationId Optional conversation ID.
     * @return Named token, complete, or error SSE events.
     */
    @Operation(summary = "多轮对话 (SSE 流式 GET)", description = "通过 GET 请求获取 SSE 流式回答，适合浏览器原生 EventSource API")
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentStreamEvent>> chatStreamGet(
            @RequestParam("message") String message,
            @RequestParam(value = "conversationId", required = false) String conversationId) {
        log.info("REST GET request for AI Agent SSE chat stream in conversation '{}'", conversationId);
        AgentChatRequest request = new AgentChatRequest(message, conversationId);
        return toServerSentEvents(agentService.chatStream(request));
    }

    private Flux<ServerSentEvent<AgentStreamEvent>> toServerSentEvents(Flux<AgentStreamEvent> events) {
        Flux<ServerSentEvent<AgentStreamEvent>> dataFrames = events.map(event ->
                ServerSentEvent.<AgentStreamEvent>builder(event)
                        .event(event.type().wireName())
                        .build());
        Flux<ServerSentEvent<AgentStreamEvent>> heartbeatFrames = Flux.interval(resolveHeartbeatInterval())
                .map(tick -> ServerSentEvent.<AgentStreamEvent>builder()
                        .comment("heartbeat")
                        .build());

        // Terminal data events stop both subscriptions; downstream cancellation does the same.
        return Flux.merge(dataFrames, heartbeatFrames)
                .takeUntil(frame -> frame.data() != null
                        && (frame.data().type() == AgentStreamEventType.COMPLETE
                        || frame.data().type() == AgentStreamEventType.ERROR));
    }

    private Duration resolveHeartbeatInterval() {
        return (heartbeatInterval != null && !heartbeatInterval.isNegative() && !heartbeatInterval.isZero())
                ? heartbeatInterval
                : DEFAULT_HEARTBEAT_INTERVAL;
    }

    void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    Duration getHeartbeatInterval() {
        return resolveHeartbeatInterval();
    }
}
