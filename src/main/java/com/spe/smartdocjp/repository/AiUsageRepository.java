package com.spe.smartdocjp.repository;

import com.spe.smartdocjp.model.entity.AiUsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AiUsageRepository extends JpaRepository<AiUsageRecord, Long> {

    List<AiUsageRecord> findByCreatedAtAfterAndIsDeletedFalse(LocalDateTime time);

    @Query("SELECT COALESCE(SUM(r.totalTokens), 0) FROM AiUsageRecord r WHERE r.isDeleted = false")
    Long sumTotalTokens();

    @Query("SELECT COALESCE(SUM(r.totalTokens), 0) FROM AiUsageRecord r WHERE r.createdAt >= :time AND r.isDeleted = false")
    Long sumTokensAfter(@Param("time") LocalDateTime time);

    @Query("SELECT COALESCE(SUM(r.costEstimate), 0) FROM AiUsageRecord r WHERE r.isDeleted = false")
    BigDecimal sumTotalCost();

    @Query("SELECT COALESCE(SUM(r.costEstimate), 0) FROM AiUsageRecord r WHERE r.createdAt >= :time AND r.isDeleted = false")
    BigDecimal sumCostAfter(@Param("time") LocalDateTime time);

    long countByIsDeletedFalse();

    long countByCreatedAtAfterAndIsDeletedFalse(LocalDateTime time);
}
