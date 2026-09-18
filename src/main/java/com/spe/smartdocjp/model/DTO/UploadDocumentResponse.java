package com.spe.smartdocjp.model.DTO;

import com.spe.smartdocjp.model.entity.Document;

/**
 * Safe acknowledgement returned after a document upload is accepted.
 * Persistence entities and their associated user credentials are intentionally
 * excluded from the HTTP response.
 */
public record UploadDocumentResponse(
        Long id,
        String fileName,
        String status,
        String embeddingStatus
) {
    public static UploadDocumentResponse from(Document document) {
        return new UploadDocumentResponse(
                document.getId(),
                document.getOriginalFilename(),
                document.getStatus().name(),
                document.getEmbeddingStatus().name());
    }
}
