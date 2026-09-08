package com.spe.smartdocjp.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Stable provider-boundary substitutes. Application services and persistence remain real.
 */
@TestConfiguration(proxyBeanMethods = false)
public class DeterministicAiTestConfiguration {

    public static final String FIXTURE_MARKER = "INTERVIEW_EVIDENCE_MARKER";
    public static final String SUMMARY = "Deterministic PDF summary: " + FIXTURE_MARKER;
    public static final String GROUNDED_ANSWER =
            "Grounded answer from interview-evidence.pdf [来源文档: interview-evidence.pdf, Chunk #0] "
                    + FIXTURE_MARKER;

    @Bean
    DeterministicAiProbe deterministicAiProbe() {
        return new DeterministicAiProbe();
    }

    @Bean
    @Primary
    ChatModel deterministicChatModel(DeterministicAiProbe probe) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                boolean hasPdfMedia = prompt.getUserMessages().stream()
                        .anyMatch(message -> !message.getMedia().isEmpty());

                if (hasPdfMedia) {
                    probe.onPdfSummaryCall();
                    return response(SUMMARY);
                }

                if (prompt.getContents().contains("参考文档片段")) {
                    return response(GROUNDED_ANSWER);
                }

                return response("Deterministic chat response: " + FIXTURE_MARKER);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(
                        response("STREAM_ONE "),
                        response("STREAM_TWO "),
                        response("STREAM_COMPLETE")
                );
            }
        };
    }

    @Bean
    @Primary
    ChatClient.Builder deterministicChatClientBuilder(ChatModel deterministicChatModel) {
        return ChatClient.builder(deterministicChatModel);
    }

    @Bean
    @Primary
    EmbeddingModel deterministicEmbeddingModel() {
        return new AbstractEmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> embeddings = new ArrayList<>();
                for (int i = 0; i < request.getInstructions().size(); i++) {
                    embeddings.add(new Embedding(vector(), i));
                }
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(Document document) {
                return vector();
            }

            @Override
            public int dimensions() {
                return vector().length;
            }

            private float[] vector() {
                return new float[]{1.0f, 1.0f, 1.0f, 1.0f};
            }
        };
    }

    @Bean
    @Primary
    ResettableVectorStore deterministicVectorStore(EmbeddingModel deterministicEmbeddingModel) {
        return new ResettableVectorStore(deterministicEmbeddingModel);
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
