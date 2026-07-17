package com.spe.smartdocjp.model.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Data Transfer Object for summarizing AI usage statistics and cost estimations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiUsageSummaryDTO {

    private long totalCalls;
    private long todayCalls;

    private long totalTokens;
    private long todayTokens;

    private BigDecimal totalCostUsd;
    private BigDecimal todayCostUsd;

    private long dailyTokenLimit;

    /**
     * Breakdown of total API call counts grouped by operation type (SUMMARY, RAG, AGENT).
     */
    private Map<String, Long> callsByOperation;
}
