package com.spe.smartdocjp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.smartdocjp.model.DTO.SearchDTOs.*;
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
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final AiAnalysisService aiAnalysisService;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String STORE_FILE_PATH = "./uploads/vector_store.json";

    /**
     * Embeds and stores the given document in both the vector store and MySQL chunks table.
     * @param doc The Document entity.
     * @param filePath The local disk path to the file.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = Exception.class)
    public void embedAndStoreDocument(Document doc, Path filePath) {
        log.info("Starting embedding pipeline for document ID: {}, path: {}", doc.getId(), filePath);
        try {
            doc.setEmbeddingStatus(Document.EmbeddingStatus.processing);
            documentRepository.save(doc);

            List<org.springframework.ai.document.Document> rawDocs;
            String filename = filePath.getFileName().toString().toLowerCase();
            if (filename.endsWith(".pdf")) {
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(new FileSystemResource(filePath));
                rawDocs = pdfReader.get();
            } else {
                TextReader textReader = new TextReader(new FileSystemResource(filePath));
                rawDocs = textReader.get();
            }

            if (rawDocs == null || rawDocs.isEmpty()) {
                log.warn("No text extracted from document ID: {}", doc.getId());
                doc.setChunkCount(0);
                doc.setEmbeddingStatus(Document.EmbeddingStatus.completed);
                documentRepository.save(doc);
                return;
            }

            TokenTextSplitter splitter = new TokenTextSplitter();
            List<org.springframework.ai.document.Document> splitChunks = splitter.apply(rawDocs);

            List<org.springframework.ai.document.Document> chunksToEmbed = new ArrayList<>();
            List<DocumentChunk> entityChunks = new ArrayList<>();

            for (int i = 0; i < splitChunks.size(); i++) {
                org.springframework.ai.document.Document chunk = splitChunks.get(i);
                Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
                metadata.put("documentId", doc.getId());
                metadata.put("documentTitle", doc.getTitle());
                metadata.put("chunkIndex", i);

                org.springframework.ai.document.Document enrichedChunk = new org.springframework.ai.document.Document(
                        chunk.getId(),
                        chunk.getText(),
                        metadata
                );
                chunksToEmbed.add(enrichedChunk);

                DocumentChunk entityChunk = DocumentChunk.builder()
                        .document(doc)
                        .chunkIndex(i)
                        .vectorId(enrichedChunk.getId())
                        .content(enrichedChunk.getText())
                        .metadata(objectMapper.writeValueAsString(metadata))
                        .isDeleted(false)
                        .build();
                entityChunks.add(entityChunk);
            }

            // Save chunks to vector store
            vectorStore.add(chunksToEmbed);

            // Persist SimpleVectorStore to disk if applicable
            if (vectorStore instanceof SimpleVectorStore simpleStore) {
                try {
                    File storeFile = new File(STORE_FILE_PATH);
                    simpleStore.save(storeFile);
                    log.info("Persisted vector store to: {}", storeFile.getAbsolutePath());
                } catch (Exception e) {
                    log.error("Failed to save SimpleVectorStore to disk", e);
                }
            }

            // Save chunks to MySQL table
            documentChunkRepository.saveAll(entityChunks);

            // Update main document status
            doc.setChunkCount(chunksToEmbed.size());
            doc.setEmbeddingStatus(Document.EmbeddingStatus.completed);
            documentRepository.save(doc);

            log.info("Successfully completed embedding pipeline for document ID: {}, chunks created: {}", doc.getId(), chunksToEmbed.size());

        } catch (Exception e) {
            log.error("Failed to embed and store document ID: " + doc.getId(), e);
            doc.setEmbeddingStatus(Document.EmbeddingStatus.failed);
            documentRepository.save(doc);
            // 遵照 GEMINI.md 规则：AI 服务调用异常优雅降级，不抛出异常中断主流程或导致事务回滚 (UnexpectedRollbackException)
        }
    }

    /**
     * Performs semantic similarity search against the vector store.
     * @param query The user's query string.
     * @param topK Maximum number of results.
     * @param similarityThreshold Minimum similarity threshold.
     * @return List of SearchResultResponse.
     */
    public List<SearchResultResponse> search(String query, int topK, double similarityThreshold) {
        log.info("Executing vector search for query: '{}', topK: {}, threshold: {}", query, topK, similarityThreshold);

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
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

    /**
     * Answers a user question based on semantic search over stored documents.
     * @param question The user's question.
     * @param topK Number of chunks to retrieve for context.
     * @return AskResponse containing the AI answer and cited sources.
     */
    public AskResponse ask(String question, int topK) {
        log.info("Executing RAG ask for question: '{}', topK: {}", question, topK);

        List<SearchResultResponse> sources = search(question, topK, 0.0);

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

        ChatClient chatClient = chatClientBuilder.build();
        String answer = chatClient.prompt(finalPrompt).call().content();

        return new AskResponse(answer, sources);
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
                        File storeFile = new File(STORE_FILE_PATH);
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
