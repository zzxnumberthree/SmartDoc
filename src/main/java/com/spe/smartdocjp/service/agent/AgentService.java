package com.spe.smartdocjp.service.agent;

import com.spe.smartdocjp.model.DTO.AgentDTOs.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Service for the AI Agent intelligent document assistant, integrating Function Calling tools, ChatMemory, and Security Guardrails.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private final ChatClient.Builder chatClientBuilder;
    private final ChatMemory chatMemory;
    private final DocumentAgentTools documentAgentTools;

    @Value("classpath:prompts/agent-system.st")
    private Resource systemPromptResource;

    private static final Pattern DANGEROUS_INPUT_PATTERN = Pattern.compile(
            "(?i).*(rm\\s+-rf|drop\\s+table|delete\\s+from|truncate\\s+table|<script>|exec\\().*",
            Pattern.DOTALL
    );

    private static final Pattern SENSITIVE_OUTPUT_PATTERN = Pattern.compile(
            "(?i).*(GOOGLE_API_KEY|jdbc:mysql:|C:\\\\Users\\\\a|NullPointerException|StackOverflowError).*",
            Pattern.DOTALL
    );

    /**
     * Processes a user chat request through the AI Agent with guardrails and function calling.
     * @param request The chat request with message and conversationId.
     * @return AgentChatResponse containing the AI reply.
     */
    public AgentChatResponse chat(AgentChatRequest request) {
        String conversationId = request.getEffectiveConversationId();
        String rawMessage = request.message();
        log.info("Processing Agent chat for conversationId '{}': '{}'", conversationId, rawMessage);

        // 1. Guardrail Input Validation
        validateInputGuardrail(rawMessage);

        try {
            // 2. Load and render System Prompt
            String systemTemplate = new String(systemPromptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String renderedSystemPrompt = systemTemplate.replace("{conversationId}", conversationId);

            // 3. Invoke ChatClient with memory advisor and function tools
            ChatClient chatClient = chatClientBuilder
                    .defaultTools(documentAgentTools)
                    .build();

            String aiOutput = chatClient.prompt()
                    .system(renderedSystemPrompt)
                    .user(rawMessage)
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(a -> a.param("chat_memory_conversation_id", conversationId)
                                    .param("chat_memory_response_size", 30))
                    .call()
                    .content();

            // 4. Guardrail Output Validation
            String sanitizedOutput = validateOutputGuardrail(aiOutput);

            log.info("Agent chat completed for conversationId '{}'. Output length: {}", conversationId, sanitizedOutput != null ? sanitizedOutput.length() : 0);
            return AgentChatResponse.of(conversationId, sanitizedOutput);

        } catch (Exception e) {
            log.error("Error executing Agent chat pipeline for conversationId: " + conversationId, e);
            throw new RuntimeException("AI Agent 处理异常: " + e.getMessage(), e);
        }
    }

    /**
     * Validates user input against dangerous injection patterns or length violations.
     * @param message The user's input string.
     */
    private void validateInputGuardrail(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("对话输入内容不能为空。");
        }
        if (message.length() > 2000) {
            throw new IllegalArgumentException("输入字数超过上限 (2000字)，已被安全策略拦截。");
        }
        if (DANGEROUS_INPUT_PATTERN.matcher(message).matches()) {
            log.warn("Guardrail intercepted dangerous input attempt: {}", message);
            throw new IllegalArgumentException("［安全护栏拦截］检测到潜在的不安全指令或高危系统操作词汇（例如删除表、执行脚本）。Agent 助手仅支持只读探查，请修改您的询问后重试。");
        }
    }

    /**
     * Checks output against sensitive internal diagnostics and sanitizes if necessary.
     * @param output The AI output string.
     * @return Sanitized output string.
     */
    private String validateOutputGuardrail(String output) {
        if (output == null) {
            return "（响应内容为空）";
        }
        if (SENSITIVE_OUTPUT_PATTERN.matcher(output).matches()) {
            log.warn("Guardrail detected sensitive keywords in AI output, sanitizing response.");
            String cleaned = output
                    .replaceAll("(?i)GOOGLE_API_KEY=[^\\s]+", "GOOGLE_API_KEY=***")
                    .replaceAll("(?i)jdbc:mysql:[^\\s]+", "jdbc:mysql://***")
                    .replaceAll("(?i)C:\\\\Users\\\\[^\\s\\\\]+", "C:\\\\Users\\\\***");
            return "［安全护栏提醒］检测到模型输出中包含内部配置或系统路径调试信息，已进行自动安全脱敏处理。回答如下：\n\n" + cleaned;
        }
        return output;
    }
}
