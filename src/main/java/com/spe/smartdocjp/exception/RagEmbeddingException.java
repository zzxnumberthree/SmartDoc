package com.spe.smartdocjp.exception;

/**
 * Signals that a document could not complete its RAG indexing transaction.
 * The asynchronous coordinator is responsible for persisting the final
 * document and embedding failure states after this transaction exits.
 */
public final class RagEmbeddingException extends RuntimeException {

    private final Long documentId;

    public RagEmbeddingException(Long documentId, Throwable cause) {
        super("RAG indexing failed for document " + documentId, cause);
        this.documentId = documentId;
    }

    public Long getDocumentId() {
        return documentId;
    }
}
