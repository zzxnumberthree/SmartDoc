package com.spe.smartdocjp.model.DTO;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Collection of Data Transfer Objects for vector search and RAG ask operations.
 */
public class SearchDTOs {

    /**
     * Request for semantic similarity search.
     * @param query The natural language query.
     * @param topK The maximum number of relevant chunks to return (defaults to 5 if null).
     * @param similarityThreshold The minimum similarity score threshold (0.0 to 1.0).
     */
    public record SearchQueryRequest(
        @NotBlank(message = "検索クエリは必須です (Search query is required)")
        String query,

        Integer topK,

        Double similarityThreshold
    ) {
        public int getEffectiveTopK() {
            return (topK == null || topK <= 0) ? 5 : topK;
        }

        public double getEffectiveThreshold() {
            return (similarityThreshold == null || similarityThreshold < 0.0) ? 0.0 : similarityThreshold;
        }
    }

    /**
     * Response representing a single relevant document chunk.
     * @param documentId The ID of the source document.
     * @param documentTitle The title or filename of the source document.
     * @param chunkIndex The index of the chunk in the source document.
     * @param content The text snippet of the chunk.
     * @param score The similarity score from vector search.
     */
    public record SearchResultResponse(
        Long documentId,
        String documentTitle,
        Integer chunkIndex,
        String content,
        Double score
    ) {}

    /**
     * Request for intelligent question answering (Ask AI).
     * @param question The question posed by the user.
     * @param topK The maximum number of relevant chunks to retrieve as context.
     */
    public record AskRequest(
        @NotBlank(message = "質問は必須です (Question is required)")
        String question,

        Integer topK
    ) {
        public int getEffectiveTopK() {
            return (topK == null || topK <= 0) ? 5 : topK;
        }
    }

    /**
     * Response containing the AI answer along with cited sources.
     * @param answer The generated response from AI.
     * @param sources The list of document chunks cited or retrieved.
     */
    public record AskResponse(
        String answer,
        List<SearchResultResponse> sources
    ) {}
}
