package com.spe.smartdocjp.aspect;

import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentChatRequest;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentStreamErrorCode;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentStreamEvent;
import com.spe.smartdocjp.service.AiUsageService;
import com.spe.smartdocjp.service.AiAnalysisService.SummaryResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMonitoringAspectTest {

    @Test
    void failedSummaryResultIsRecordedAsErrorWithoutUsage() throws Throwable {
        AiUsageService usageService = mock(AiUsageService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMonitoringAspect aspect = new AiMonitoringAspect(usageService, registry);
        SummaryResult result = SummaryResult.unavailable();
        ProceedingJoinPoint joinPoint = joinPointReturning(result);

        Object monitored = aspect.monitorSummary(joinPoint);

        assertSame(result, monitored);
        assertEquals(0.0, registry.counter(
                "ai.calls.total", "operation", "SUMMARY", "status", "success").count());
        assertEquals(1.0, registry.counter(
                "ai.calls.total", "operation", "SUMMARY", "status", "error").count());
        assertEquals(1.0, registry.counter(
                "ai.calls.errors", "operation", "SUMMARY", "error", "SUMMARY_RESULT_FAILURE").count());
        assertEquals(1L, registry.timer(
                "ai.calls.duration", "operation", "SUMMARY").count());
        verify(usageService, never()).recordUsageAsync(
                any(), anyInt(), anyInt(), any(), any(), anyLong());
    }

    @Test
    void successfulSummaryResultIsRecordedAsSuccessAndUsage() throws Throwable {
        AiUsageService usageService = mock(AiUsageService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMonitoringAspect aspect = new AiMonitoringAspect(usageService, registry);
        SummaryResult result = SummaryResult.success("12345");
        ProceedingJoinPoint joinPoint = joinPointReturning(result);

        Object monitored = aspect.monitorSummary(joinPoint);

        assertSame(result, monitored);
        assertEquals(1.0, registry.counter(
                "ai.calls.total", "operation", "SUMMARY", "status", "success").count());
        assertEquals(0.0, registry.counter(
                "ai.calls.total", "operation", "SUMMARY", "status", "error").count());
        verify(usageService).recordUsageAsync(
                eq("gemini-2.5-flash"), eq(1500), eq(4), eq("SUMMARY"), eq(null), eq(1L));
    }

    @Test
    void terminalErrorEventIsRecordedAsFailureWithoutSuccessfulUsage() throws Throwable {
        AiUsageService usageService = mock(AiUsageService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMonitoringAspect aspect = new AiMonitoringAspect(usageService, registry);
        ProceedingJoinPoint joinPoint = joinPointReturning(Flux.just(AgentStreamEvent.error(
                "monitor-error",
                AgentStreamErrorCode.MODEL_TIMEOUT,
                "safe timeout",
                true,
                "error-id")));

        @SuppressWarnings("unchecked")
        Flux<AgentStreamEvent> monitored = (Flux<AgentStreamEvent>) aspect.monitorAgentChatStream(joinPoint);
        monitored.collectList().block();

        assertEquals(1.0, registry.counter(
                "ai.calls.total", "operation", "AGENT", "status", "error").count());
        assertEquals(1.0, registry.counter(
                "ai.calls.errors", "operation", "AGENT", "error", "MODEL_TIMEOUT").count());
        verify(usageService, never()).recordUsageAsync(
                any(), anyInt(), anyInt(), any(), any(), anyLong());
    }

    @Test
    void completionEventIsRecordedAsSuccessAndCountsOnlyTokenDeltas() throws Throwable {
        AiUsageService usageService = mock(AiUsageService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMonitoringAspect aspect = new AiMonitoringAspect(usageService, registry);
        ProceedingJoinPoint joinPoint = joinPointReturning(Flux.just(
                AgentStreamEvent.token("monitor-success", "12345"),
                AgentStreamEvent.complete("monitor-success")));

        @SuppressWarnings("unchecked")
        Flux<AgentStreamEvent> monitored = (Flux<AgentStreamEvent>) aspect.monitorAgentChatStream(joinPoint);
        monitored.collectList().block();

        assertEquals(1.0, registry.counter(
                "ai.calls.total", "operation", "AGENT", "status", "success").count());
        verify(usageService).recordUsageAsync(
                eq("gemini-2.5-flash"), eq(204), eq(4), eq("AGENT"), eq(null), eq(1L));
    }

    private ProceedingJoinPoint joinPointReturning(Flux<AgentStreamEvent> stream) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{
                new AgentChatRequest("12345", "monitor-contract")
        });
        when(joinPoint.proceed()).thenReturn(stream);
        return joinPoint;
    }

    private ProceedingJoinPoint joinPointReturning(SummaryResult result) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(result);
        return joinPoint;
    }
}
