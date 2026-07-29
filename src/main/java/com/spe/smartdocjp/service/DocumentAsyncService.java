package com.spe.smartdocjp.service;

import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import com.spe.smartdocjp.service.parser.DocumentParser;

/**
 * Service handling background asynchronous processing of AI analysis and RAG embedding.
 * Separated from DocumentService to ensure Spring AOP @Async proxying works correctly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentAsyncService {

    private final DocumentRepository documentRepository;
    private final AiAnalysisService aiAnalysisService;
    private final RagService ragService;
    private final List<DocumentParser> parsers;

    /**
     * Executes AI summary generation and RAG embedding in a background thread.
     * Updates Document status lifecycle: processing -> completed / failed.
     * @param documentId The ID of the document to process.
     * @param targetLocation The local disk path where the file is stored.
     */
    @Async("documentTaskExecutor")
    public void processAiAndRagAsync(Long documentId, Path targetLocation) {
        log.info("[Async Start] Starting async AI analysis and RAG pipeline for document ID: {}", documentId);
        Document doc = null;
        for (int i = 0; i < 5; i++) {
            doc = documentRepository.findById(documentId).orElse(null);
            if (doc != null) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}
        }
        if (doc == null) {
            log.warn("Document not found for async processing after retries, ID: {}", documentId);
            return;
        }

        try {
            // Update status to processing
            doc.setStatus(Document.DocStatus.processing);
            doc.setEmbeddingStatus(Document.EmbeddingStatus.processing);
            documentRepository.save(doc);

            // Execute AI summary with retry
            String originalFilename = doc.getOriginalFilename() == null ? "" : doc.getOriginalFilename();
            String extension = "";
            if (originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }

            String summary = "Unsupported format: " + originalFilename;
            DocumentParser matchedParser = null;
            for (DocumentParser parser : parsers) {
                if (parser.supports(extension)) {
                    matchedParser = parser;
                    break;
                }
            }

            if (matchedParser != null) {
                summary = aiAnalysisService.analyzeDocumentWithRetry(matchedParser, targetLocation, originalFilename);
            } else {
                log.warn("No suitable DocumentParser found for file: {}", originalFilename);
            }

            doc.setSummary(summary);
            if (summary != null && (summary.startsWith("AI 服务暂时不可用") || summary.startsWith("Unsupported format"))) {
                doc.setStatus(Document.DocStatus.failed);
            } else {
                doc.setStatus(Document.DocStatus.completed);
            }
            documentRepository.save(doc);
            log.info("[Async AI Done] AI summary generated for document ID: {}, status: {}", documentId, doc.getStatus());

            // Execute RAG chunking and vector embedding
            ragService.embedAndStoreDocument(doc, targetLocation);
            log.info("[Async Complete] Full async pipeline finished for document ID: {}", documentId);

        } catch (Exception e) {
            log.error("[Async Error] Async processing failed for document ID: {}: {}", documentId, e.getMessage(), e);
            if (doc != null) {
                doc.setStatus(Document.DocStatus.failed);
                doc.setEmbeddingStatus(Document.EmbeddingStatus.failed);
                doc.setSummary("AI / RAG 处理失败: " + e.getMessage());
                documentRepository.save(doc);
            }
        }
    }
}
