package com.spe.smartdocjp.service;

import com.spe.smartdocjp.exception.RagEmbeddingException;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import com.spe.smartdocjp.service.AiAnalysisService.SummaryResult;
import com.spe.smartdocjp.service.parser.DocumentParser;

/**
 * Service handling background asynchronous processing of AI analysis and RAG embedding.
 * Separated from DocumentService to ensure Spring AOP @Async proxying works correctly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentAsyncService {

    static final String PROCESSING_FAILED_MESSAGE = "AI / RAG 处理失败，请稍后重试。";

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
        // afterCommit 已保证事务提交后才触发此方法，因此直接查询即可，无需轮询重试
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            log.warn("Document not found for async processing, ID: {}", documentId);
            return;
        }

        boolean summaryGenerated = false;
        boolean ragStarted = false;
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

            SummaryResult summaryResult = SummaryResult.unsupportedFormat();
            DocumentParser matchedParser = null;
            for (DocumentParser parser : parsers) {
                if (parser.supports(extension)) {
                    matchedParser = parser;
                    break;
                }
            }

            if (matchedParser != null) {
                summaryResult = aiAnalysisService.analyzeDocumentWithRetry(
                        matchedParser, targetLocation, originalFilename);
            } else {
                log.warn("No suitable DocumentParser found for file: {}", originalFilename);
            }

            doc.setSummary(summaryResult.content());
            boolean summaryFailed = !summaryResult.successful();
            summaryGenerated = !summaryFailed;
            if (summaryFailed) {
                doc.setStatus(Document.DocStatus.failed);
            } else {
                // Overall completion requires both summary generation and RAG indexing.
                doc.setStatus(Document.DocStatus.processing);
            }
            documentRepository.save(doc);
            if (summaryGenerated) {
                log.info("[Async AI Done] AI summary generated for document ID: {}, status: {}",
                        documentId, doc.getStatus());
            } else {
                log.warn("[Async AI Failed] Summary generation failed for document ID: {}, status: {}",
                        documentId, doc.getStatus());
            }

            // Execute RAG chunking and vector embedding
            ragStarted = true;
            ragService.embedAndStoreDocument(doc, targetLocation);

            if (summaryFailed) {
                log.warn("[Async Partial Failure] RAG indexing completed but summary generation failed for document ID: {}",
                        documentId);
            } else {
                doc.setStatus(Document.DocStatus.completed);
                documentRepository.save(doc);
                log.info("[Async Complete] Full async pipeline finished for document ID: {}", documentId);
            }

        } catch (RagEmbeddingException e) {
            persistIndexFailure(doc, documentId, e);
        } catch (Exception e) {
            if (ragStarted) {
                persistIndexFailure(doc, documentId, e);
                return;
            }
            log.error("[Async Error] Async processing failed for document ID: {}", documentId, e);
            if (doc != null) {
                doc.setStatus(Document.DocStatus.failed);
                doc.setEmbeddingStatus(Document.EmbeddingStatus.failed);
                if (!summaryGenerated) {
                    doc.setSummary(PROCESSING_FAILED_MESSAGE);
                }
                documentRepository.save(doc);
            }
        }
    }

    private void persistIndexFailure(Document doc, Long documentId, Exception failure) {
        log.error("[Async RAG Error] Summary was retained but RAG indexing failed for document ID: {}",
                documentId, failure);
        doc.setStatus(Document.DocStatus.failed);
        doc.setEmbeddingStatus(Document.EmbeddingStatus.failed);
        documentRepository.save(doc);
    }
}
