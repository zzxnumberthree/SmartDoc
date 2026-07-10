package com.spe.smartdocjp.model.DTO;

import com.spe.smartdocjp.model.entity.Document;

/**
 * Data Transfer Object representing the processing and embedding status of a document.
 */
public record DocumentStatusDTO(
        Long id,
        String originalFilename,
        String status,
        String embeddingStatus,
        String summary,
        Integer chunkCount
) {
    /**
     * Converts a Document entity into a DocumentStatusDTO.
     * @param doc The Document entity.
     * @return A populated DocumentStatusDTO instance.
     */
    public static DocumentStatusDTO from(Document doc) {
        if (doc == null) {
            return null;
        }
        return new DocumentStatusDTO(
                doc.getId(),
                doc.getOriginalFilename(),
                doc.getStatus() != null ? doc.getStatus().name() : "unknown",
                doc.getEmbeddingStatus() != null ? doc.getEmbeddingStatus().name() : "unknown",
                doc.getSummary(),
                doc.getChunkCount() != null ? doc.getChunkCount() : 0
        );
    }
}
