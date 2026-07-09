package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.model.DTO.AgentDTOs.*;
import com.spe.smartdocjp.service.agent.AgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
