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

    /**
     * Configures the in-memory chat memory bean for multi-turn conversations.
     * @return A configured ChatMemory instance.
     */
    @Bean
    public org.springframework.ai.chat.memory.ChatMemory chatMemory() {
        log.info("Initializing ChatMemory bean for Agent multi-turn dialogues.");
        return new org.springframework.ai.chat.memory.ChatMemory() {
            private final java.util.Map<String, java.util.List<org.springframework.ai.chat.messages.Message>> store =
                    new java.util.concurrent.ConcurrentHashMap<>();

            @Override
            public void add(String conversationId, java.util.List<org.springframework.ai.chat.messages.Message> messages) {
                store.computeIfAbsent(conversationId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).addAll(messages);
                java.util.List<org.springframework.ai.chat.messages.Message> list = store.get(conversationId);
                if (list != null && list.size() > 50) {
                    java.util.List<org.springframework.ai.chat.messages.Message> trimmed = new java.util.concurrent.CopyOnWriteArrayList<>(
                            list.subList(list.size() - 50, list.size())
                    );
                    store.put(conversationId, trimmed);
                }
            }

            @Override
            public java.util.List<org.springframework.ai.chat.messages.Message> get(String conversationId) {
                java.util.List<org.springframework.ai.chat.messages.Message> list = store.get(conversationId);
                return list != null ? new java.util.ArrayList<>(list) : java.util.List.of();
            }

            @Override
            public void clear(String conversationId) {
                store.remove(conversationId);
            }
        };
    }
}
