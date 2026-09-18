package com.spe.smartdocjp.exception;

/**
 * Signals that a document is not visible in the current authenticated scope.
 * Foreign and unknown identifiers intentionally share this exception so callers
 * cannot enumerate another user's documents.
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException() {
        super("文档未找到");
    }
}
