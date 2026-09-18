package com.spe.smartdocjp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
@Slf4j
public class AiAnalysisService {

    static final String SUMMARY_UNAVAILABLE_MESSAGE = "AI 服务暂时不可用，请稍后重试。";

    public static final class SummaryResult {
        private final boolean successful;
        private final String content;

        private SummaryResult(boolean successful, String content) {
            this.successful = successful;
            this.content = content;
        }

        public static SummaryResult success(String content) {
            if (content == null || content.isBlank()) {
                return unavailable();
            }
            return new SummaryResult(true, content);
        }

        public static SummaryResult unavailable() {
            return new SummaryResult(false, SUMMARY_UNAVAILABLE_MESSAGE);
        }

        public static SummaryResult unsupportedFormat() {
            return new SummaryResult(false, "不支持的文档格式。");
        }

        public boolean successful() {
            return successful;
        }

        public String content() {
            return content;
        }
    }

    private final ChatClient chatClient;

    /**
     Constructs the AI analysis service with a configured chat client.
     @param builder The builder for the chat client.
     */
    public AiAnalysisService(ChatClient.Builder builder) {
        // spring ai 提供了ChatClient.Builder 我们再通过 clone 隔离后再 build() 来生成当前服务的 chatClient
        ChatClient.Builder builderToUse = null;
        try {
            builderToUse = builder.clone();
        } catch (Exception ignored) {}
        if (builderToUse == null) {
            builderToUse = builder;
        }
        this.chatClient = builderToUse
                // 基本的System Prompt
                .defaultSystem("你是一家大型日本IT企业里优秀的管理文档助手，所有回答用日语输出。")
                .build();
    }

    // test
    public String testConnect() {
        log.info("Executing testConnect() - verifying AI service connectivity");
        return chatClient.prompt("hello,who are you?").call().content();
    }

    /**
     * Analyzes document content with retry mechanism (max 2 attempts, exponential backoff).
     * @param targetLocation Path to the stored document file on disk.
     * @param originalFilename The original name of the uploaded document.
     * @return A typed result containing either the generated summary or a safe fallback.
     * @throws Exception if all retry attempts fail and recover method is not triggered.
     */
    @Retryable(retryFor = {Exception.class}, maxAttempts = 2, backoff = @Backoff(delay = 1000, multiplier = 1.5))
    public SummaryResult analyzeDocumentWithRetry(com.spe.smartdocjp.service.parser.DocumentParser parser, Path targetLocation, String originalFilename) throws Exception {
        log.info("Executing AI analysis with retry for file: {}", originalFilename);
        return SummaryResult.success(parser.parseAndAnalyze(targetLocation));
    }

    /**
     * Recovery method called when analyzeDocumentWithRetry exhausts both retry attempts.
     * @param e The final exception thrown after retries.
     * @param targetLocation Path to the document.
     * @param originalFilename Original filename.
     * @return A failed result with a fixed safe fallback message.
     */
    @Recover
    public SummaryResult recoverAnalyzeDocument(Exception e, com.spe.smartdocjp.service.parser.DocumentParser parser, Path targetLocation, String originalFilename) {
        log.error("AI analysis exhausted all retries for file '{}'; returning a safe fallback",
                originalFilename, e);
        return SummaryResult.unavailable();
    }

    /**
     Generates a structured AI summary from plain text.
     @param documentText The text content of the document.
     @return The AI-generated summary as a formatted string.
     */
    public String generateSummaryFromText(String documentText) {
        // 设置提示词模板 Message Template
        String messageTemplate =
                    "请分析一下文本，按照以下格式输出：" +
                    "1. 【文档概要】(100字程度)" +
                    "2. 【重要要点】(项目符号3点)" +
                    "3. 【技术栈/关键词】(如有)" +
                    "4. 【关注点/注意事项】(如有)" + documentText;

        // 使用 Fluent API 链式调用
        return chatClient
                .prompt(messageTemplate)
                .call()
                .content();
    }

    /**
     Generates a structured AI summary from a PDF file.
     @param pdfResource The PDF file as a Spring Resource.
     @return The AI-generated summary as a formatted string.
     */
    public String generateSummaryFromPdf(Resource pdfResource) {
        // 设置提示词模板 Message Template
        String messageTemplate = """
                    请分析附加的PDF文档，按照以下格式输出：
                    1. 【文档概要】(100字程度)
                    2. 【重要要点】(项目符号3点)
                    3. 【技术栈/关键词】(如有)
                    4. 【关注点/注意事项】(如有)
                """;

        UserMessage userMessage = new UserMessage(messageTemplate);
        // media 表示输入材料，mime自动匹配文件类型
        userMessage.getMedia().add(new Media(
                        MimeTypeUtils.parseMimeType("application/pdf"),
                        pdfResource
        ));

        return chatClient.prompt(new Prompt(userMessage))
                .call()
                .content();
    }


}
