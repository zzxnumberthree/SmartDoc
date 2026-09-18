package com.spe.smartdocjp.integration;

import com.spe.smartdocjp.service.AiAnalysisService;
import com.spe.smartdocjp.service.AiAnalysisService.SummaryResult;
import com.spe.smartdocjp.service.AiUsageService;
import com.spe.smartdocjp.service.parser.DocumentParser;
import com.spe.smartdocjp.support.DeterministicAiTestConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("deterministic-test")
@Import(DeterministicAiTestConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class SpringRetryMonitoringIntegrationTest {

    private static final String SAFE_FALLBACK = "AI 服务暂时不可用，请稍后重试。";

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private AiUsageService aiUsageService;

    @Test
    void exhaustedRetriesProduceOneLogicalFailureMetricWithoutSuccessfulUsage() throws Exception {
        assertTrue(AopUtils.isAopProxy(aiAnalysisService));

        DocumentParser parser = mock(DocumentParser.class);
        when(parser.parseAndAnalyze(any(Path.class)))
                .thenThrow(new IllegalStateException("provider failure sentinel"));

        double errorTotalBefore = counterValue(
                "ai.calls.total", "operation", "SUMMARY", "status", "error");
        double successTotalBefore = counterValue(
                "ai.calls.total", "operation", "SUMMARY", "status", "success");
        double typedFailureBefore = counterValue(
                "ai.calls.errors", "operation", "SUMMARY", "error", "SUMMARY_RESULT_FAILURE");
        double attemptFailureBefore = counterValue(
                "ai.calls.errors", "operation", "SUMMARY", "error", "IllegalStateException");
        long durationBefore = timerCount("ai.calls.duration", "operation", "SUMMARY");

        SummaryResult result = aiAnalysisService.analyzeDocumentWithRetry(
                parser, Path.of("retry-metrics.pdf"), "retry-metrics.pdf");

        assertFalse(result.successful());
        assertEquals(SAFE_FALLBACK, result.content());
        verify(parser, times(2)).parseAndAnalyze(any(Path.class));
        assertEquals(errorTotalBefore + 1.0, counterValue(
                "ai.calls.total", "operation", "SUMMARY", "status", "error"));
        assertEquals(successTotalBefore, counterValue(
                "ai.calls.total", "operation", "SUMMARY", "status", "success"));
        assertEquals(typedFailureBefore + 1.0, counterValue(
                "ai.calls.errors", "operation", "SUMMARY", "error", "SUMMARY_RESULT_FAILURE"));
        assertEquals(attemptFailureBefore, counterValue(
                "ai.calls.errors", "operation", "SUMMARY", "error", "IllegalStateException"));
        assertEquals(durationBefore + 1L, timerCount(
                "ai.calls.duration", "operation", "SUMMARY"));
        verify(aiUsageService, times(1)).checkDailyBudgetOrThrow();
        verify(aiUsageService, never()).recordUsageAsync(
                anyString(), anyInt(), anyInt(), anyString(), any(), anyLong());
    }

    private double counterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long timerCount(String name, String... tags) {
        Timer timer = meterRegistry.find(name).tags(tags).timer();
        return timer == null ? 0L : timer.count();
    }
}
