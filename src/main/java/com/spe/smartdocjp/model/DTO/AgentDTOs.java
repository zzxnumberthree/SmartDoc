package com.spe.smartdocjp.model.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Data Transfer Objects for AI Agent intelligent conversation endpoints.
 */
public class AgentDTOs {

    public enum AgentToolResultCode {
        OK,
        INVALID_ARGUMENT,
        NOT_FOUND,
        AUTH_CONTEXT_MISSING,
        TOOL_EXECUTION_FAILED
    }

    /**
     * Stable tool callback result. Detailed causes are logged server-side and
     * never included in model-visible data.
     */
    public record AgentToolResult<T>(
            boolean success,
            AgentToolResultCode code,
            String message,
            T data,
            String errorId,
            boolean retryable
    ) {
        public static <T> AgentToolResult<T> success(T data) {
            return new AgentToolResult<>(true, AgentToolResultCode.OK, "工具执行成功", data, null, false);
        }

        public static <T> AgentToolResult<T> failure(
                AgentToolResultCode code,
                String message,
                String errorId,
                boolean retryable) {
            return new AgentToolResult<>(false, code, message, null, errorId, retryable);
        }
    }

    public enum AgentStreamEventType {
        TOKEN,
        COMPLETE,
        ERROR;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum AgentStreamErrorCode {
        INPUT_REJECTED,
        MODEL_TIMEOUT,
        MODEL_UNAVAILABLE
    }

    /** Typed payload carried by named Server-Sent Events. */
    public record AgentStreamEvent(
            AgentStreamEventType type,
            String conversationId,
            String delta,
            AgentStreamErrorCode errorCode,
            String message,
            Boolean retryable,
            String errorId,
            LocalDateTime timestamp
    ) {
        public static AgentStreamEvent token(String conversationId, String delta) {
            return new AgentStreamEvent(
                    AgentStreamEventType.TOKEN, conversationId, delta, null, null, null, null, LocalDateTime.now());
        }

        public static AgentStreamEvent complete(String conversationId) {
            return new AgentStreamEvent(
                    AgentStreamEventType.COMPLETE, conversationId, null, null, null, null, null, LocalDateTime.now());
        }

        public static AgentStreamEvent error(
                String conversationId,
                AgentStreamErrorCode errorCode,
                String message,
                boolean retryable,
                String errorId) {
            return new AgentStreamEvent(
                    AgentStreamEventType.ERROR,
                    conversationId,
                    null,
                    errorCode,
                    message,
                    retryable,
                    errorId,
                    LocalDateTime.now());
        }
    }

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
