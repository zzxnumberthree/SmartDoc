package com.spe.smartdocjp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import com.spe.smartdocjp.service.AiAnalysisService.SummaryResult;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AiAnalysisService retry and fallback mechanics.
 */
@ExtendWith(MockitoExtension.class)
class AiAnalysisServiceTest {

    private static final String RAW_SECRET =
            "AIza-SENTINEL jdbc:mysql://db:3306/smartdoc C:\\private\\uploads\\secret.pdf";

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
        Exception mockException = new RuntimeException(RAW_SECRET);
        Path mockPath = Paths.get("./dummy.txt");
        com.spe.smartdocjp.service.parser.DocumentParser mockParser = org.mockito.Mockito.mock(com.spe.smartdocjp.service.parser.DocumentParser.class);
        SummaryResult fallbackResponse = aiAnalysisService.recoverAnalyzeDocument(
                mockException, mockParser, mockPath, "dummy.txt");

        assertFalse(fallbackResponse.successful());
        assertEquals(AiAnalysisService.SUMMARY_UNAVAILABLE_MESSAGE, fallbackResponse.content());
        assertFalse(fallbackResponse.content().contains("AIza-SENTINEL"));
        assertFalse(fallbackResponse.content().contains("jdbc:mysql"));
        assertFalse(fallbackResponse.content().contains("C:\\private"));
    }

    @Test
    void summaryResultFactoriesEnforceSafeFailureContentAndTypedStatus() {
        SummaryResult nullContent = SummaryResult.success(null);
        SummaryResult blankContent = SummaryResult.success("   ");
        SummaryResult unsupportedFormat = SummaryResult.unsupportedFormat();
        SummaryResult fallbackTextAsSuccess =
                SummaryResult.success(AiAnalysisService.SUMMARY_UNAVAILABLE_MESSAGE);

        assertFalse(nullContent.successful());
        assertFalse(blankContent.successful());
        assertEquals(AiAnalysisService.SUMMARY_UNAVAILABLE_MESSAGE, nullContent.content());
        assertEquals(AiAnalysisService.SUMMARY_UNAVAILABLE_MESSAGE, blankContent.content());
        assertFalse(unsupportedFormat.successful());
        assertEquals("不支持的文档格式。", unsupportedFormat.content());
        assertTrue(fallbackTextAsSuccess.successful());
    }


}
