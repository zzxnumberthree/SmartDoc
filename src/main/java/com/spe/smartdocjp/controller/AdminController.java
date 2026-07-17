package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.model.DTO.AiUsageSummaryDTO;
import com.spe.smartdocjp.service.AiUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller providing administrative REST endpoints for AI usage statistics and cost monitoring.
 */
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
    @GetMapping("/summary")
    public ResponseEntity<AiUsageSummaryDTO> getSummary() {
        log.info("REST request to get AI usage summary stats.");
        return ResponseEntity.ok(aiUsageService.getUsageSummary());
    }
}
