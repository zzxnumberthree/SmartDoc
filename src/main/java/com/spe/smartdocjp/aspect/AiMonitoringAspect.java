package com.spe.smartdocjp.aspect;

import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentChatRequest;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentChatResponse;
import com.spe.smartdocjp.service.AiUsageService;
import com.spe.smartdocjp.model.DTO.SearchDTOs.AskResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * AOP aspect for monitoring AI invocations across services.
 * Enforces daily token budgets, measures duration, records metrics, and asynchronously logs usage.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AiMonitoringAspect {

    private final AiUsageService aiUsageService;
    private final MeterRegistry meterRegistry;

    @Pointcut("execution(* com.spe.smartdocjp.service.AiAnalysisService.analyzeDocumentWithRetry(..))")
    public void summaryOperation() {}

    @Pointcut("execution(* com.spe.smartdocjp.service.RagService.ask(..))")
    public void ragOperation() {}

    @Pointcut("execution(* com.spe.smartdocjp.service.agent.AgentService.chat(..)) || execution(* com.spe.smartdocjp.service.agent.AgentService.chatStream(..))")
    public void agentOperation() {}

    /**
     * Enforces daily budget limit before executing any AI operation.
     */
    @Before("summaryOperation() || ragOperation() || agentOperation()")
    public void enforceDailyBudget() {
        aiUsageService.checkDailyBudgetOrThrow();
    }

    @Around("summaryOperation()")
    public Object monitorSummary(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorSyncOperation(joinPoint, "SUMMARY", 1500);
    }

    @Around("ragOperation()")
    public Object monitorRag(ProceedingJoinPoint joinPoint) throws Throwable {
        int promptTokens = 500;
        Object[] args = joinPoint.getArgs();
        if (args != null && args.length > 0 && args[0] instanceof String question) {
            int topK = args.length > 1 && args[1] instanceof Integer k ? k : 5;
            promptTokens = estimateTokens(question) + (topK * 600);
        }
        return monitorSyncOperation(joinPoint, "RAG", promptTokens);
    }

    @Around("execution(* com.spe.smartdocjp.service.agent.AgentService.chat(..))")
    public Object monitorAgentChat(ProceedingJoinPoint joinPoint) throws Throwable {
        int promptTokens = 250;
        Object[] args = joinPoint.getArgs();
        if (args != null && args.length > 0 && args[0] instanceof AgentChatRequest req) {
            promptTokens = estimateTokens(req.message()) + 200;
        }
        return monitorSyncOperation(joinPoint, "AGENT", promptTokens);
    }

    @Around("execution(* com.spe.smartdocjp.service.agent.AgentService.chatStream(..))")
    public Object monitorAgentChatStream(ProceedingJoinPoint joinPoint) throws Throwable {
        int promptTokens = 250;
        Object[] args = joinPoint.getArgs();
        if (args != null && args.length > 0 && args[0] instanceof AgentChatRequest req) {
            promptTokens = estimateTokens(req.message()) + 200;
        }
        final int finalPromptTokens = promptTokens;

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Object result = joinPoint.proceed();
            if (result instanceof Flux<?> flux) {
                AtomicInteger outputChars = new AtomicInteger(0);
                return flux
                        .doOnNext(item -> {
                            if (item instanceof String chunk) {
                                outputChars.addAndGet(chunk.length());
                            }
                        })
                        .doOnComplete(() -> {
                            sample.stop(meterRegistry.timer("ai.calls.duration", "operation", "AGENT"));
                            recordMetrics("AGENT", true, null);
                            int completionTokens = estimateTokens(outputChars.get());
                            aiUsageService.recordUsageAsync("gemini-2.5-flash", finalPromptTokens, completionTokens, "AGENT", null, 1L);
                        })
                        .doOnError(ex -> {
                            sample.stop(meterRegistry.timer("ai.calls.duration", "operation", "AGENT"));
                            recordMetrics("AGENT", false, ex.getClass().getSimpleName());
                        });
            }
            return result;
        } catch (Throwable ex) {
            sample.stop(meterRegistry.timer("ai.calls.duration", "operation", "AGENT"));
            recordMetrics("AGENT", false, ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private Object monitorSyncOperation(ProceedingJoinPoint joinPoint, String operationType, int promptTokens) throws Throwable {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Object result = joinPoint.proceed();
            sample.stop(meterRegistry.timer("ai.calls.duration", "operation", operationType));
            recordMetrics(operationType, true, null);

            int completionTokens = 200;
            if (result instanceof String text) {
                completionTokens = estimateTokens(text);
            } else if (result instanceof AskResponse askResp) {
                completionTokens = estimateTokens(askResp.answer());
            } else if (result instanceof AgentChatResponse agentResp) {
                completionTokens = estimateTokens(agentResp.reply());
            }
            aiUsageService.recordUsageAsync("gemini-2.5-flash", promptTokens, completionTokens, operationType, null, 1L);

            return result;
        } catch (Throwable ex) {
            sample.stop(meterRegistry.timer("ai.calls.duration", "operation", operationType));
            recordMetrics(operationType, false, ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private void recordMetrics(String operationType, boolean success, String errorType) {
        Counter.builder("ai.calls.total")
                .tag("operation", operationType)
                .tag("status", success ? "success" : "error")
                .register(meterRegistry)
                .increment();

        if (!success) {
            Counter.builder("ai.calls.errors")
                    .tag("operation", operationType)
                    .tag("error", errorType != null ? errorType : "UnknownError")
                    .register(meterRegistry)
                    .increment();
        }
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.max(1, Math.round(text.length() * 0.8));
    }
    private int estimateTokens(int charCount) {
        if (charCount <= 0) {
            return 0;
        }
        return (int) Math.max(1, Math.round(charCount * 0.8));
    }
}
