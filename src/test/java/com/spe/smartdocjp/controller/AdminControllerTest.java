package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.model.DTO.AiUsageSummaryDTO;
import com.spe.smartdocjp.service.AiUsageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.context.annotation.Import;
import com.spe.smartdocjp.security.SecurityConfig;
import com.spe.smartdocjp.security.JwtAuthenticationFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiUsageService aiUsageService;

    @Test
    @DisplayName("测试 GET /api/admin/ai-usage/summary 成功返回统计 JSON")
    @WithMockUser(roles = "ADMIN")
    void getSummary_ReturnsSummaryJson() throws Exception {
        AiUsageSummaryDTO dto = AiUsageSummaryDTO.builder()
                .totalCalls(15L)
                .todayCalls(3L)
                .totalTokens(8500L)
                .todayTokens(2100L)
                .totalCostUsd(new BigDecimal("0.0125"))
                .todayCostUsd(new BigDecimal("0.0031"))
                .dailyTokenLimit(500000L)
                .callsByOperation(Collections.singletonMap("SUMMARY", 15L))
                .build();

        when(aiUsageService.getUsageSummary()).thenReturn(dto);

        mockMvc.perform(get("/api/admin/ai-usage/summary")
                        .accept(MediaType.APPLICATION_JSON))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").value(15))
                .andExpect(jsonPath("$.todayCalls").value(3))
                .andExpect(jsonPath("$.totalTokens").value(8500))
                .andExpect(jsonPath("$.todayTokens").value(2100))
                .andExpect(jsonPath("$.dailyTokenLimit").value(500000))
                .andExpect(jsonPath("$.callsByOperation.SUMMARY").value(15));
    }
}
