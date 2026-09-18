package com.spe.smartdocjp.aspect;

import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentChatRequest;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentChatResponse;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentStreamEvent;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentStreamEventType;
import com.spe.smartdocjp.service.AiUsageService;
import com.spe.smartdocjp.service.AiAnalysisService.SummaryResult;
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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AOP aspect for monitoring AI invocations across services.
 * Enforces daily token budgets, measures duration, records metrics, and asynchronously logs usage.
 */
@Aspect
@Component
// Spring Retry uses LOWEST_PRECEDENCE - 1; monitor the final logical outcome, not each attempt.
@Order(Ordered.LOWEST_PRECEDENCE - 2)
@RequiredArgsConstructor
@Slf4j
public class AiMonitoringAspect {

    private static final String SUMMARY_RESULT_FAILURE = "SUMMARY_RESULT_FAILURE";

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
                AtomicReference<AgentStreamEvent> terminalEvent = new AtomicReference<>();
                AtomicBoolean outcomeRecorded = new AtomicBoolean(false);
                return flux
                        .doOnNext(item -> {
                            if (item instanceof AgentStreamEvent event) {
                                if (event.type() == AgentStreamEventType.TOKEN && event.delta() != null) {
                                    outputChars.addAndGet(event.delta().length());
                                } else if (event.type() == AgentStreamEventType.COMPLETE
                                        || event.type() == AgentStreamEventType.ERROR) {
                                    terminalEvent.compareAndSet(null, event);
                                }
                            }
                        })
                        .doOnComplete(() -> {
                            AgentStreamEvent terminal = terminalEvent.get();
                            boolean success = terminal != null && terminal.type() == AgentStreamEventType.COMPLETE;
                            String errorType = terminal != null && terminal.errorCode() != null
                                    ? terminal.errorCode().name()
                                    : "MISSING_TERMINAL_EVENT";
                            recordStreamOutcome(
                                    sample,
                                    outcomeRecorded,
                                    success,
                                    errorType,
                                    finalPromptTokens,
                                    outputChars.get());
                        })
                        .doOnError(ex -> recordStreamOutcome(
                                sample,
                                outcomeRecorded,
                                false,
                                ex.getClass().getSimpleName(),
                                finalPromptTokens,
                                outputChars.get()))
                        .doOnCancel(() -> recordStreamOutcome(
                                sample,
                                outcomeRecorded,
                                false,
                                "CLIENT_CANCELLED",
                                finalPromptTokens,
                                outputChars.get()));
            }
            return result;
        } catch (Throwable ex) {
            sample.stop(meterRegistry.timer("ai.calls.duration", "operation", "AGENT"));
            recordMetrics("AGENT", false, ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private void recordStreamOutcome(
            Timer.Sample sample,
            AtomicBoolean outcomeRecorded,
            boolean success,
            String errorType,
            int promptTokens,
            int outputChars) {
        if (!outcomeRecorded.compareAndSet(false, true)) {
            return;
        }
        sample.stop(meterRegistry.timer("ai.calls.duration", "operation", "AGENT"));
        recordMetrics("AGENT", success, success ? null : errorType);
        if (success) {
            aiUsageService.recordUsageAsync(
                    "gemini-2.5-flash",
                    promptTokens,
                    estimateTokens(outputChars),
                    "AGENT",
                    null,
                    1L);
        }
    }

    private Object monitorSyncOperation(ProceedingJoinPoint joinPoint, String operationType, int promptTokens) throws Throwable {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Object result = joinPoint.proceed();
            sample.stop(meterRegistry.timer("ai.calls.duration", "operation", operationType));

            if (result instanceof SummaryResult summaryResult && !summaryResult.successful()) {
                recordMetrics(operationType, false, SUMMARY_RESULT_FAILURE);
                return result;
            }

            recordMetrics(operationType, true, null);

            int completionTokens = 200;
            if (result instanceof String text) {
                completionTokens = estimateTokens(text);
            } else if (result instanceof SummaryResult summaryResult) {
                completionTokens = estimateTokens(summaryResult.content());
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
