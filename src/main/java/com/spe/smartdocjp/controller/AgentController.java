package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.model.DTO.AgentDTOs.*;
import com.spe.smartdocjp.service.agent.AgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * REST controller exposing multi-turn intelligent chat endpoints for the AI Agent document assistant.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentService agentService;

    /**
     * Handles multi-turn chat interaction with the AI Agent.
     * @param request The chat request containing user message and conversationId.
     * @return AgentChatResponse with the AI Agent's reply and timestamp.
     */
    @PostMapping("/chat")
    public ResponseEntity<AgentChatResponse> chat(@Valid @RequestBody AgentChatRequest request) {
        log.info("REST request for AI Agent chat in conversation '{}': '{}'", request.getEffectiveConversationId(), request.message());
        AgentChatResponse response = agentService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Handles multi-turn chat interaction returning Server-Sent Events (SSE) data stream via POST.
     * @param request The chat request.
     * @return Flux of String tokens.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamPost(@Valid @RequestBody AgentChatRequest request) {
        log.info("REST POST request for AI Agent SSE chat stream in conversation '{}'", request.getEffectiveConversationId());
        return agentService.chatStream(request);
    }

    /**
     * Handles multi-turn chat interaction returning Server-Sent Events (SSE) data stream via GET (for native EventSource).
     * @param message The user prompt.
     * @param conversationId Optional conversation ID.
     * @return Flux of String tokens.
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamGet(@RequestParam("message") String message,
                                      @RequestParam(value = "conversationId", required = false) String conversationId) {
        log.info("REST GET request for AI Agent SSE chat stream in conversation '{}'", conversationId);
        AgentChatRequest request = new AgentChatRequest(message, conversationId);
        return agentService.chatStream(request);
    }
}
