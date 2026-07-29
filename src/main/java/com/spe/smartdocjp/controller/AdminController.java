package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.common.ApiResponse;
import com.spe.smartdocjp.model.DTO.AiUsageSummaryDTO;
import com.spe.smartdocjp.service.AiUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller providing administrative REST endpoints for AI usage statistics and cost monitoring.
 */
@Tag(name = "系统管理", description = "仅限管理员角色访问的后台监控接口")
@RestController
@RequestMapping("/api/admin/ai-usage")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final AiUsageService aiUsageService;

    /**
     * Retrieves overall and daily AI API token usage, cost estimations, and operation breakdown.
     * @return ResponseEntity containing AiUsageSummaryDTO.
     */
    @Operation(summary = "获取 AI 使用统计", description = "获取全局及每日的 AI Token 消耗、预估成本等统计数据")
    @GetMapping("/summary")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AiUsageSummaryDTO>> getSummary() {
        log.info("REST request to get AI usage summary stats.");
        return ResponseEntity.ok(ApiResponse.success(aiUsageService.getUsageSummary()));
    }
}
