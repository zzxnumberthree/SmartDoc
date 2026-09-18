package com.spe.smartdocjp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.smartdocjp.model.DTO.SearchDTOs.*;
import com.spe.smartdocjp.exception.RagEmbeddingException;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.model.entity.DocumentChunk;
import com.spe.smartdocjp.repository.DocumentChunkRepository;
import com.spe.smartdocjp.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    static final String USER_ID_METADATA = "userId";

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final AiAnalysisService aiAnalysisService;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${smartdoc.vector-store.file:./uploads/vector_store.json}")
    private String storeFilePath;

    /**
     * Embeds and stores the given document in both the vector store and MySQL chunks table.
     * @param doc The Document entity.
     * @param filePath The local disk path to the file.
     */
    @Transactional
    public void embedAndStoreDocument(Document doc, Path filePath) {
        log.info("Starting embedding pipeline for document ID: {}, path: {}", doc.getId(), filePath);
        List<String> newVectorIds = new ArrayList<>();
        List<DocumentChunk> priorChunks = List.of();
        List<String> priorVectorIds = List.of();
        List<org.springframework.ai.document.Document> chunksToEmbed = new ArrayList<>();
        List<DocumentChunk> entityChunks = new ArrayList<>();

        try {
            if (doc.getUser() == null || doc.getUser().getId() == null) {
                throw new IllegalStateException("Document owner is required for RAG indexing");
            }

            doc.setEmbeddingStatus(Document.EmbeddingStatus.processing);
            documentRepository.save(doc);

            // 1. Load active chunks and record their non-empty vector IDs
            priorChunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getId());
            if (priorChunks == null) {
                priorChunks = List.of();
            }
            priorVectorIds = priorChunks.stream()
                    .map(DocumentChunk::getVectorId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();

            List<org.springframework.ai.document.Document> rawDocs;
            String filename = filePath.getFileName().toString().toLowerCase();
            if (filename.endsWith(".pdf")) {
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(new FileSystemResource(filePath));
                rawDocs = pdfReader.get();
            } else {
                TextReader textReader = new TextReader(new FileSystemResource(filePath));
                rawDocs = textReader.get();
            }

            boolean hasExtractedText = rawDocs != null && rawDocs.stream()
                    .anyMatch(d -> d.getText() != null && !d.getText().isBlank());

            if (hasExtractedText) {
                TokenTextSplitter splitter = new TokenTextSplitter();
                List<org.springframework.ai.document.Document> splitChunks = splitter.apply(rawDocs);

                if (splitChunks != null) {
                    Set<String> existingVectorIdSet = new HashSet<>(priorVectorIds);

                    for (int i = 0; i < splitChunks.size(); i++) {
                        org.springframework.ai.document.Document chunk = splitChunks.get(i);
                        if (chunk.getText() == null || chunk.getText().isBlank()) {
                            continue;
                        }

                        Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
                        metadata.put("documentId", doc.getId());
                        metadata.put("documentTitle", doc.getTitle());
                        metadata.put("chunkIndex", chunksToEmbed.size());
                        metadata.put(USER_ID_METADATA, doc.getUser().getId());

                        // 2. Build vector IDs that cannot collide with prior generation
                        String newVectorId = UUID.randomUUID().toString();
                        while (existingVectorIdSet.contains(newVectorId)) {
                            newVectorId = UUID.randomUUID().toString();
                        }
                        existingVectorIdSet.add(newVectorId);

                        // 3. Every new chunk contains documentId, documentTitle, chunkIndex, userId
                        org.springframework.ai.document.Document enrichedChunk = new org.springframework.ai.document.Document(
                                newVectorId,
                                chunk.getText(),
                                metadata
                        );
                        chunksToEmbed.add(enrichedChunk);

                        DocumentChunk entityChunk = DocumentChunk.builder()
                                .document(doc)
                                .chunkIndex(chunksToEmbed.size() - 1)
                                .vectorId(enrichedChunk.getId())
                                .content(enrichedChunk.getText())
                                .metadata(objectMapper.writeValueAsString(metadata))
                                .isDeleted(false)
                                .build();
                        entityChunks.add(entityChunk);
                    }
                }
            } else {
                log.warn("No text extracted from document ID: {}", doc.getId());
            }

            newVectorIds = chunksToEmbed.stream()
                    .map(org.springframework.ai.document.Document::getId)
                    .toList();

            // 4. Add and persist new vectors before deleting old vectors
            if (!chunksToEmbed.isEmpty()) {
                vectorStore.add(chunksToEmbed);
                persistVectorStoreIfFileBacked();
            }

            // 5. Replace database rows: remove old active rows, flush, save new rows
            if (!priorChunks.isEmpty()) {
                documentChunkRepository.deleteAll(priorChunks);
                documentChunkRepository.flush();
            }
            if (!entityChunks.isEmpty()) {
                documentChunkRepository.saveAllAndFlush(entityChunks);
            }

        } catch (Exception e) {
            log.error("Failed to stage RAG index replacement for document ID: " + doc.getId(), e);
            compensateVectorWrite(newVectorIds, doc.getId());
            throw (e instanceof RagEmbeddingException re ? re : new RagEmbeddingException(doc.getId(), e));
        }

        // 6. Delete and persist old vector generation only after new vectors and new database rows succeed
        // 7. Update chunkCount and embeddingStatus only according to final replacement outcome
        // 8. Re-indexing yielding no chunks clears prior index and sets chunkCount=0, completed
        try {
            if (!priorVectorIds.isEmpty()) {
                vectorStore.delete(priorVectorIds);
                persistVectorStoreIfFileBacked();
                log.info("Deleted {} old vectors from vector store for document ID: {}",
                        priorVectorIds.size(), doc.getId());
            }

            doc.setChunkCount(chunksToEmbed.size());
            doc.setEmbeddingStatus(Document.EmbeddingStatus.completed);
            documentRepository.saveAndFlush(doc);

            log.info("Successfully completed embedding pipeline for document ID: {}, chunks created: {}",
                    doc.getId(), chunksToEmbed.size());
        } catch (Exception postStagingError) {
            log.error("Failed during old-vector cleanup or document finalization for document ID: {}",
                    doc.getId(), postStagingError);
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (Exception ignored) {}
            try {
                compensateVectorWrite(newVectorIds, doc.getId());
            } catch (Exception compensationError) {
                log.error("Failed during vector write compensation for document ID: {}", doc.getId(), compensationError);
            }
            try {
                restoreOldVectors(priorChunks, doc.getId());
            } catch (Exception restoreError) {
                log.error("Failed during old vector restoration for document ID: {}", doc.getId(), restoreError);
            }
            throw new RagEmbeddingException(doc.getId(), postStagingError);
        }
    }

    private void persistVectorStoreIfFileBacked() {
        if (vectorStore instanceof SimpleVectorStore simpleStore) {
            File storeFile = new File(storeFilePath);
            simpleStore.save(storeFile);
            log.info("Persisted vector store to: {}", storeFile.getAbsolutePath());
        }
    }

    private void compensateVectorWrite(List<String> vectorIds, Long documentId) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return;
        }

        try {
            vectorStore.delete(vectorIds);
            persistVectorStoreIfFileBacked();
            log.warn("Compensated {} vector entries after failed indexing for document ID: {}",
                    vectorIds.size(), documentId);
        } catch (Exception compensationError) {
            log.error("Failed to compensate vector entries for document ID: {}", documentId, compensationError);
        }
    }

    private void restoreOldVectors(List<DocumentChunk> priorChunks, Long documentId) {
        if (priorChunks == null || priorChunks.isEmpty()) {
            return;
        }

        try {
            List<org.springframework.ai.document.Document> docsToRestore = new ArrayList<>();
            for (DocumentChunk chunk : priorChunks) {
                if (chunk.getVectorId() == null || chunk.getVectorId().isBlank()) {
                    continue;
                }
                Map<String, Object> metadata = new HashMap<>();
                if (chunk.getMetadata() != null && !chunk.getMetadata().isBlank()) {
                    try {
                        metadata = objectMapper.readValue(chunk.getMetadata(), new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        log.warn("Failed to deserialize metadata for chunk vector ID: {}", chunk.getVectorId(), e);
                    }
                }
                docsToRestore.add(new org.springframework.ai.document.Document(
                        chunk.getVectorId(),
                        chunk.getContent() != null ? chunk.getContent() : "",
                        metadata
                ));
            }
            if (!docsToRestore.isEmpty()) {
                vectorStore.add(docsToRestore);
                persistVectorStoreIfFileBacked();
                log.info("Restored {} old vector entries for document ID: {}", docsToRestore.size(), documentId);
            }
        } catch (Exception restoreError) {
            log.error("Failed to restore old vector entries for document ID: {}", documentId, restoreError);
        }
    }

    /**
     * Performs semantic similarity search against the vector store.
     * @param query The user's query string.
     * @param topK Maximum number of results.
     * @param similarityThreshold Minimum similarity threshold.
     * @param userId Authenticated owner whose chunks may be searched.
     * @return List of SearchResultResponse.
     */
    public List<SearchResultResponse> search(String query, int topK, double similarityThreshold, Long userId) {
        requireUserId(userId);
        log.info("Executing vector search for user ID: {}, topK: {}, threshold: {}", userId, topK, similarityThreshold);

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(USER_ID_METADATA + " == " + userId)
                .build();

        List<org.springframework.ai.document.Document> results = vectorStore.similaritySearch(request);

        return results.stream().map(doc -> {
            Map<String, Object> meta = doc.getMetadata();
            Long docId = null;
            if (meta.get("documentId") != null) {
                try {
                    docId = Long.valueOf(meta.get("documentId").toString());
                } catch (NumberFormatException ignored) {}
            }
            String title = meta.get("documentTitle") != null ? meta.get("documentTitle").toString() : "Unknown";
            Integer chunkIndex = null;
            if (meta.get("chunkIndex") != null) {
                try {
                    chunkIndex = Integer.valueOf(meta.get("chunkIndex").toString());
                } catch (NumberFormatException ignored) {}
            }
            Double score = doc.getScore();

            return new SearchResultResponse(docId, title, chunkIndex, doc.getText(), score);
        }).toList();
    }

    private volatile ChatClient chatClient;

    private ChatClient getOrCreateChatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    ChatClient.Builder builderToUse = null;
                    try {
                        builderToUse = chatClientBuilder.clone();
                    } catch (Exception ignored) {}
                    if (builderToUse == null) {
                        builderToUse = chatClientBuilder;
                    }
                    chatClient = builderToUse.build();
                }
            }
        }
        return chatClient;
    }

    /**
     * Answers a user question based on semantic search over stored documents.
     * @param question The user's question.
     * @param topK Number of chunks to retrieve for context.
     * @param userId Authenticated owner whose chunks may be used as context.
     * @return AskResponse containing the AI answer and cited sources.
     */
    public AskResponse ask(String question, int topK, Long userId) {
        requireUserId(userId);
        log.info("Executing RAG ask for user ID: {}, topK: {}", userId, topK);

        List<SearchResultResponse> sources = search(question, topK, 0.0, userId);

        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            SearchResultResponse src = sources.get(i);
            contextBuilder.append(String.format("[%d] [来源文档: %s, Chunk #%s]\n%s\n\n",
                    i + 1,
                    src.documentTitle() != null ? src.documentTitle() : "未知文档",
                    src.chunkIndex() != null ? src.chunkIndex() : "?",
                    src.content()));
        }

        String context = contextBuilder.toString().trim();
        if (context.isEmpty()) {
            context = "参考文档中暂无相关的文档片段。";
        }

        String promptTemplate = """
                你是一位精通日语的智能文档助手。请根据以下提供的【参考文档片段】来回答用户提出的【问题】。
                
                回答规则：
                1. 必须完全使用日语（日本語）回答。
                2. 回答内容必须基于给定的参考文档片段，如果不确定或文档片段中没有提及相关内容，请坦率地说明无法从参考文档中找到答案，不要凭空编造（幻觉）。
                3. 在回答中引用相关观点或内容时，请明确标注来源，格式例如：[来源文档: xxx.pdf, Chunk #2]。
                
                【参考文档片段】:
                %s
                
                【问题】:
                %s
                """;

        String finalPrompt = String.format(promptTemplate, context, question);

        ChatClient client = getOrCreateChatClient();
        String answer = client.prompt(finalPrompt).call().content();

        return new AskResponse(answer, sources);
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Authenticated user ID is required for RAG access");
        }
    }

    /**
     * Deletes document chunks from MySQL and corresponding vectors from VectorStore when a document is deleted.
     * @param documentId The ID of the deleted document.
     */
    @Transactional
    public void deleteDocumentChunksAndVectors(Long documentId) {
        log.info("Cleaning up RAG chunks and vectors for deleted document ID: {}", documentId);
        try {
            List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
            List<String> vectorIds = chunks.stream()
                    .map(DocumentChunk::getVectorId)
                    .filter(id -> id != null && !id.isEmpty())
                    .toList();

            if (!vectorIds.isEmpty()) {
                vectorStore.delete(vectorIds);
                log.info("Deleted {} vectors from VectorStore for document ID: {}", vectorIds.size(), documentId);

                if (vectorStore instanceof SimpleVectorStore simpleStore) {
                    try {
                        File storeFile = new File(storeFilePath);
                        simpleStore.save(storeFile);
                    } catch (Exception e) {
                        log.warn("Failed to update vector store file after deletion", e);
                    }
                }
            }

            documentChunkRepository.deleteByDocumentId(documentId);
            log.info("Deleted chunks from MySQL for document ID: {}", documentId);
        } catch (Exception e) {
            log.error("Error cleaning up RAG data for document ID: " + documentId, e);
            throw new RuntimeException("Failed to clean up RAG data for document: " + documentId, e);
        }
    }
}
