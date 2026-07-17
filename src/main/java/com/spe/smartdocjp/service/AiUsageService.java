package com.spe.smartdocjp.service;

import com.spe.smartdocjp.model.DTO.AiUsageSummaryDTO;
import com.spe.smartdocjp.model.entity.AiUsageRecord;
import com.spe.smartdocjp.repository.AiUsageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for tracking AI API token usage, estimating dollar cost, and enforcing daily budget limits.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiUsageService {

    private final AiUsageRepository aiUsageRepository;
    private final MeterRegistry meterRegistry;

    @Value("${smartdoc.ai.daily-token-limit:500000}")
    private long dailyTokenLimit;

    // Pricing rates per 1 token for Google Gemini 2.5-Flash / 1.5-Flash
    private static final BigDecimal PROMPT_RATE_PER_TOKEN = new BigDecimal("0.000000075");
    private static final BigDecimal COMPLETION_RATE_PER_TOKEN = new BigDecimal("0.000000300");

    /**
     * Checks if today's accumulated token usage exceeds the configured daily budget.
     * Throws an exception if the budget is exceeded to prevent further AI billing.
     */
    public void checkDailyBudgetOrThrow() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Long todayTokens = aiUsageRepository.sumTokensAfter(todayStart);
        if (todayTokens != null && todayTokens >= dailyTokenLimit) {
            log.warn("Daily AI token budget exceeded! Today tokens: {}, Limit: {}", todayTokens, dailyTokenLimit);
            throw new IllegalArgumentException("今日 AI 消耗 Token 已达到系统预算上限 (" + dailyTokenLimit + " Tokens)，已触发保护防刷机制。请明日再试或联系系统管理员提升额度。");
        }
    }

    /**
     * Asynchronously records an AI invocation record into MySQL and registers Micrometer metrics.
     */
    @Async("documentTaskExecutor")
    @Transactional
    public void recordUsageAsync(String model, int promptTokens, int completionTokens, String operationType, Long documentId, Long userId) {
        try {
            int totalTokens = promptTokens + completionTokens;
            BigDecimal promptCost = BigDecimal.valueOf(promptTokens).multiply(PROMPT_RATE_PER_TOKEN);
            BigDecimal completionCost = BigDecimal.valueOf(completionTokens).multiply(COMPLETION_RATE_PER_TOKEN);
            BigDecimal totalCost = promptCost.add(completionCost).setScale(6, RoundingMode.HALF_UP);

            AiUsageRecord record = AiUsageRecord.builder()
                    .model(model != null ? model : "gemini-2.5-flash")
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .costEstimate(totalCost)
                    .operationType(operationType != null ? operationType : "UNKNOWN")
                    .documentId(documentId)
                    .userId(userId != null ? userId : 1L)
                    .isDeleted(false)
                    .build();

            aiUsageRepository.save(record);
            log.debug("Recorded AI usage: model={}, operation={}, totalTokens={}, cost=${}", model, operationType, totalTokens, totalCost);

            // Update Micrometer metrics
            Counter.builder("ai.tokens.total")
                    .tag("operation", operationType != null ? operationType : "UNKNOWN")
                    .tag("type", "prompt")
                    .register(meterRegistry)
                    .increment(promptTokens);

            Counter.builder("ai.tokens.total")
                    .tag("operation", operationType != null ? operationType : "UNKNOWN")
                    .tag("type", "completion")
                    .register(meterRegistry)
                    .increment(completionTokens);

        } catch (Exception e) {
            log.error("Failed to asynchronously record AI usage for operation '{}': {}", operationType, e.getMessage(), e);
        }
    }

    /**
     * Retrieves a summary of AI usage statistics including total calls, tokens, cost estimate, and operation breakdown.
     */
    @Transactional(readOnly = true)
    public AiUsageSummaryDTO getUsageSummary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        long totalCalls = aiUsageRepository.countByIsDeletedFalse();
        long todayCalls = aiUsageRepository.countByCreatedAtAfterAndIsDeletedFalse(todayStart);

        Long totalTokens = aiUsageRepository.sumTotalTokens();
        Long todayTokens = aiUsageRepository.sumTokensAfter(todayStart);

        BigDecimal totalCost = aiUsageRepository.sumTotalCost();
        BigDecimal todayCost = aiUsageRepository.sumCostAfter(todayStart);

        // Group total calls by operation type
        List<AiUsageRecord> allRecords = aiUsageRepository.findAll();
        Map<String, Long> callsByOperation = allRecords.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getIsDeleted()))
                .collect(Collectors.groupingBy(
                        r -> r.getOperationType() != null ? r.getOperationType() : "UNKNOWN",
                        Collectors.counting()
                ));

        return AiUsageSummaryDTO.builder()
                .totalCalls(totalCalls)
                .todayCalls(todayCalls)
                .totalTokens(totalTokens != null ? totalTokens : 0L)
                .todayTokens(todayTokens != null ? todayTokens : 0L)
                .totalCostUsd(totalCost != null ? totalCost.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                .todayCostUsd(todayCost != null ? todayCost.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                .dailyTokenLimit(dailyTokenLimit)
                .callsByOperation(callsByOperation)
                .build();
    }
}
