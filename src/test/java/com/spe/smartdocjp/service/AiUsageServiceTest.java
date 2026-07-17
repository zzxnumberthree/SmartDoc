package com.spe.smartdocjp.service;

import com.spe.smartdocjp.model.DTO.AiUsageSummaryDTO;
import com.spe.smartdocjp.model.entity.AiUsageRecord;
import com.spe.smartdocjp.repository.AiUsageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiUsageServiceTest {

    @Mock
    private AiUsageRepository aiUsageRepository;

    private MeterRegistry meterRegistry;
    private AiUsageService aiUsageService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        aiUsageService = new AiUsageService(aiUsageRepository, meterRegistry);
        ReflectionTestUtils.setField(aiUsageService, "dailyTokenLimit", 500000L);
    }

    @Test
    @DisplayName("测试当今日消耗未超限时 checkDailyBudgetOrThrow 正常执行")
    void checkDailyBudgetOrThrow_WhenUnderLimit_DoesNotThrow() {
        when(aiUsageRepository.sumTokensAfter(any(LocalDateTime.class))).thenReturn(100000L);
        assertDoesNotThrow(() -> aiUsageService.checkDailyBudgetOrThrow());
    }

    @Test
    @DisplayName("测试当今日消耗超限时 checkDailyBudgetOrThrow 抛出安全拦截异常")
    void checkDailyBudgetOrThrow_WhenExceedsLimit_ThrowsException() {
        when(aiUsageRepository.sumTokensAfter(any(LocalDateTime.class))).thenReturn(500000L);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> aiUsageService.checkDailyBudgetOrThrow());
        assertTrue(exception.getMessage().contains("系统预算上限"));
    }

    @Test
    @DisplayName("测试异步记账 recordUsageAsync 正确计算费用与累计 Micrometer 指标")
    void recordUsageAsync_SavesRecordAndIncrementsMetrics() {
        aiUsageService.recordUsageAsync("gemini-2.5-flash", 1000, 500, "SUMMARY", 1L, 1L);

        ArgumentCaptor<AiUsageRecord> captor = ArgumentCaptor.forClass(AiUsageRecord.class);
        verify(aiUsageRepository).save(captor.capture());

        AiUsageRecord saved = captor.getValue();
        assertEquals("gemini-2.5-flash", saved.getModel());
        assertEquals(1000, saved.getPromptTokens());
        assertEquals(500, saved.getCompletionTokens());
        assertEquals(1500, saved.getTotalTokens());
        assertEquals("SUMMARY", saved.getOperationType());

        // Check Micrometer counter
        Counter promptCounter = meterRegistry.find("ai.tokens.total").tag("type", "prompt").counter();
        assertNotNull(promptCounter);
        assertEquals(1000, promptCounter.count());
    }

    @Test
    @DisplayName("测试统计报表 getUsageSummary 正确汇总各项数据")
    void getUsageSummary_ReturnsCorrectDTO() {
        when(aiUsageRepository.countByIsDeletedFalse()).thenReturn(20L);
        when(aiUsageRepository.countByCreatedAtAfterAndIsDeletedFalse(any(LocalDateTime.class))).thenReturn(5L);
        when(aiUsageRepository.sumTotalTokens()).thenReturn(10000L);
        when(aiUsageRepository.sumTokensAfter(any(LocalDateTime.class))).thenReturn(3000L);
        when(aiUsageRepository.sumTotalCost()).thenReturn(new BigDecimal("0.0150"));
        when(aiUsageRepository.sumCostAfter(any(LocalDateTime.class))).thenReturn(new BigDecimal("0.0045"));
        when(aiUsageRepository.findAll()).thenReturn(Collections.emptyList());

        AiUsageSummaryDTO summary = aiUsageService.getUsageSummary();

        assertEquals(20L, summary.getTotalCalls());
        assertEquals(5L, summary.getTodayCalls());
        assertEquals(10000L, summary.getTotalTokens());
        assertEquals(3000L, summary.getTodayTokens());
        assertEquals(500000L, summary.getDailyTokenLimit());
    }
}
