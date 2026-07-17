package com.spe.smartdocjp.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * Entity representing an AI API call usage record.
 * Stores model name, token counts, cost estimate, operation type, and related identifiers.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
@Entity
@Table(name = "ai_usage_record")
public class AiUsageRecord extends BaseEntity {

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    @Builder.Default
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    @Builder.Default
    private Integer completionTokens = 0;

    @Column(name = "total_tokens", nullable = false)
    @Builder.Default
    private Integer totalTokens = 0;

    @Column(name = "cost_estimate", precision = 12, scale = 6, nullable = false)
    @Builder.Default
    private BigDecimal costEstimate = BigDecimal.ZERO;

    /**
     * Operation type (e.g., SUMMARY, RAG, AGENT).
     */
    @Column(name = "operation_type", nullable = false, length = 32)
    private String operationType;

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "user_id", nullable = false)
    @Builder.Default
    private Long userId = 1L;
}
