package com.spe.smartdocjp.config;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleGenAiEmbeddingModel extends AbstractEmbeddingModel {

    private final Client client;

    @Value("${spring.ai.google.genai.embedding.model:text-embedding-004}")
    private String modelName;

    @Override
    public float[] embed(Document document) {
        Assert.notNull(document, "Document must not be null");
        return this.embed(document.getText());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        Assert.notNull(request, "EmbeddingRequest must not be null");
        List<String> instructions = request.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            return new EmbeddingResponse(List.of());
        }

        log.debug("Calling Google GenAI embedding model '{}' for {} instructions", modelName, instructions.size());

        EmbedContentConfig config = EmbedContentConfig.builder()
                .outputDimensionality(768)
                .build();

        List<Embedding> resultEmbeddings = new ArrayList<>();

        if (instructions.size() == 1) {
            float[] vector = embedSingleText(instructions.get(0), config);
            resultEmbeddings.add(new Embedding(vector, 0));
        } else {
            try {
                EmbedContentResponse response = client.models.embedContent(modelName, instructions, config);
                Optional<List<ContentEmbedding>> embeddingsOpt = response.embeddings();
                if (embeddingsOpt.isPresent() && embeddingsOpt.get() != null && embeddingsOpt.get().size() == instructions.size()) {
                    List<ContentEmbedding> contentEmbeddings = embeddingsOpt.get();
                    for (int i = 0; i < contentEmbeddings.size(); i++) {
                        resultEmbeddings.add(new Embedding(extractVector(contentEmbeddings.get(i)), i));
                    }
                } else {
                    log.warn("Batch embed returned {} embeddings for {} instructions, falling back to individual calls",
                            embeddingsOpt.map(List::size).orElse(0), instructions.size());
                    for (int i = 0; i < instructions.size(); i++) {
                        resultEmbeddings.add(new Embedding(embedSingleText(instructions.get(i), config), i));
                    }
                }
            } catch (Exception e) {
                log.warn("Batch embed request failed ({}), falling back to individual embedding calls", e.getMessage());
                for (int i = 0; i < instructions.size(); i++) {
                    resultEmbeddings.add(new Embedding(embedSingleText(instructions.get(i), config), i));
                }
            }
        }

        return new EmbeddingResponse(resultEmbeddings);
    }

    private float[] embedSingleText(String text, EmbedContentConfig config) {
        try {
            EmbedContentResponse response = client.models.embedContent(modelName, text, config);
            Optional<List<ContentEmbedding>> embeddingsOpt = response.embeddings();
            if (embeddingsOpt.isPresent() && embeddingsOpt.get() != null && !embeddingsOpt.get().isEmpty()) {
                return extractVector(embeddingsOpt.get().get(0));
            }
            log.warn("No embedding returned from Google GenAI for text snippet");
            return new float[0];
        } catch (Exception e) {
            log.error("Failed to generate single text embedding using model '{}': {}", modelName, e.getMessage(), e);
            throw new RuntimeException("Embedding generation failed: " + e.getMessage(), e);
        }
    }

    private float[] extractVector(ContentEmbedding contentEmbedding) {
        Optional<List<Float>> valuesOpt = contentEmbedding.values();
        if (valuesOpt.isPresent() && valuesOpt.get() != null) {
            List<Float> floatList = valuesOpt.get();
            float[] vector = new float[floatList.size()];
            for (int j = 0; j < floatList.size(); j++) {
                Float val = floatList.get(j);
                vector[j] = val != null ? val : 0.0f;
            }
            return vector;
        }
        return new float[0];
    }
}
