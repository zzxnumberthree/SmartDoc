package com.spe.smartdocjp.model.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Data Transfer Objects for AI Agent intelligent conversation endpoints.
 */
public class AgentDTOs {

    /**
     * Request DTO for Agent multi-turn chat.
     * @param message The user's input message.
     * @param conversationId Optional conversation ID for multi-turn memory. Defaults to 'default-session'.
     */
    public record AgentChatRequest(
            @NotBlank(message = "对话内容 message 不能为空")
            @Size(max = 2000, message = "单次对话内容不能超过 2000 字")
            String message,

            String conversationId
    ) {
        public String getEffectiveConversationId() {
            return (conversationId == null || conversationId.trim().isEmpty()) ? "default-session" : conversationId.trim();
        }
    }

    /**
     * Response DTO returned by the AI Agent after processing.
     * @param conversationId The active conversation ID.
     * @param reply The AI Agent's response in Japanese.
     * @param timestamp The response timestamp.
     */
    public record AgentChatResponse(
            String conversationId,
            String reply,
            LocalDateTime timestamp
    ) {
        public static AgentChatResponse of(String conversationId, String reply) {
            return new AgentChatResponse(conversationId, reply, LocalDateTime.now());
        }
    }
}
