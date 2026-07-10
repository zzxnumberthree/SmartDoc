package com.spe.smartdocjp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AiAnalysisService retry and fallback mechanics.
 */
@ExtendWith(MockitoExtension.class)
class AiAnalysisServiceTest {

    @Mock
    private ChatClient.Builder builder;

    @Mock
    private ChatClient chatClient;

    private AiAnalysisService aiAnalysisService;

    @BeforeEach
    void setUp() {
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        aiAnalysisService = new AiAnalysisService(builder);
    }

    @Test
    @DisplayName("测试重试耗尽后 @Recover 降级返回友好信息")
    void testRecoverAnalyzeDocument() {
        Exception mockException = new RuntimeException("API Request Timed out");
        Path mockPath = Paths.get("./dummy.txt");
        
        String fallbackResponse = aiAnalysisService.recoverAnalyzeDocument(mockException, mockPath, "dummy.txt");
        
        assertTrue(fallbackResponse.contains("AI 服务暂时不可用，请稍后重试"));
        assertTrue(fallbackResponse.contains("API Request Timed out"));
    }

    @Test
    @DisplayName("测试不支持的文件后缀直接返回提示")
    void testAnalyzeDocumentWithRetry_UnsupportedFormat() throws Exception {
        Path mockPath = Paths.get("./dummy.xyz");
        String result = aiAnalysisService.analyzeDocumentWithRetry(mockPath, "dummy.xyz");
        assertEquals("Unsupported format: dummy.xyz", result);
    }
}
