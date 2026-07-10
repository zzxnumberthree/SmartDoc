package com.spe.smartdocjp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configuration class enabling asynchronous processing and method-level retry support.
 * Configures the thread pool task executor used for background document analysis and RAG vector embedding.
 */
@Configuration
@EnableAsync
@EnableRetry
@Slf4j
public class AsyncConfig {

    /**
     * Custom thread pool task executor for asynchronous AI document processing.
     * Core pool size: 4, Max pool size: 8, Queue capacity: 100.
     * @return Configured Executor bean named 'documentTaskExecutor'.
     */
    @Bean("documentTaskExecutor")
    public Executor documentTaskExecutor() {
        log.info("Initializing custom async ThreadPoolTaskExecutor 'documentTaskExecutor' (Core: 4, Max: 8, Queue: 100)");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("DocAsync-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
