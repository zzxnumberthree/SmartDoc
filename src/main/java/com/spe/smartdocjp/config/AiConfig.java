package com.spe.smartdocjp.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@Slf4j
public class AiConfig {

    @Value("${ai.proxy.host:}")
    private String proxyHost;

    @Value("${ai.proxy.port:}")
    private String proxyPort;

    @Value("${ai.proxy.enabled:false}")
    private boolean proxyEnabled;

    /**
     * Configures the system proxy properties before the bean is initialized.
     */
    @PostConstruct
    public void initProxy() {
        if (proxyEnabled && !proxyHost.isEmpty() && !proxyPort.isEmpty()) {
            log.info("Configuring AI analysis proxy: {}:{}", proxyHost, proxyPort);
            System.setProperty("https.proxyHost", proxyHost);
            System.setProperty("https.proxyPort", proxyPort);
            System.setProperty("http.proxyHost", proxyHost);
            System.setProperty("http.proxyPort", proxyPort);
        } else {
            log.info("AI analysis proxy is disabled.");
        }
    }

    /**
     * Creates and configures the HTTP client for Google Generative AI.
     * @return A configured Client instance.
     */
    @Bean
    public Client googleGenAiClient() {
        String googleApiKey = System.getenv("GOOGLE_API_KEY");

        // 1. 配置 HTTP 选项，设置超时时间
        HttpOptions httpOptions = HttpOptions.builder()
                .timeout((int) Duration.ofSeconds(60).toMillis()) // 设置超时为 60 秒 (单位是毫秒)
                .build();

        // 2. 创建并返回 Client
        return Client.builder()
                .apiKey(googleApiKey)
                .httpOptions(httpOptions)
                .build();
    }

    /**
     * Configures the VectorStore bean using SimpleVectorStore.
     * @param embeddingModel The autoconfigured EmbeddingModel (Google GenAI).
     * @return A configured VectorStore instance.
     */
    @Bean
    public org.springframework.ai.vectorstore.SimpleVectorStore vectorStore(
            org.springframework.ai.embedding.EmbeddingModel embeddingModel) {
        log.info("Initializing SimpleVectorStore bean with EmbeddingModel: {}", embeddingModel.getClass().getSimpleName());
        org.springframework.ai.vectorstore.SimpleVectorStore store =
                org.springframework.ai.vectorstore.SimpleVectorStore.builder(embeddingModel).build();
        java.io.File storeFile = new java.io.File("./uploads/vector_store.json");
        if (storeFile.exists()) {
            try {
                log.info("Loading vector store from file: {}", storeFile.getAbsolutePath());
                store.load(storeFile);
            } catch (Exception e) {
                log.warn("Failed to load existing vector store from file: {}", e.getMessage());
            }
        }
        return store;
    }
}
