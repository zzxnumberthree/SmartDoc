package com.spe.smartdocjp.service.parser;

import java.nio.file.Path;

/**
 * Strategy interface for parsing different document formats and generating summaries.
 */
public interface DocumentParser {
    /**
     * Checks if this parser supports the given file extension.
     * @param extension The file extension (e.g., ".pdf", ".txt").
     * @return true if supported, false otherwise.
     */
    boolean supports(String extension);

    /**
     * Parses the file at the given path and returns an AI-generated summary.
     * @param filePath The path to the stored file.
     * @return The summary string.
     * @throws Exception If parsing or AI analysis fails.
     */
    String parseAndAnalyze(Path filePath) throws Exception;
}
