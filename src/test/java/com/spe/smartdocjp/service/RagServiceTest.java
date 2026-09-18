package com.spe.smartdocjp.service;

import com.spe.smartdocjp.exception.RagEmbeddingException;
import com.spe.smartdocjp.model.DTO.SearchDTOs.*;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.model.entity.DocumentChunk;
import com.spe.smartdocjp.model.entity.User;
import com.spe.smartdocjp.repository.DocumentChunkRepository;
import com.spe.smartdocjp.repository.DocumentRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private AiAnalysisService aiAnalysisService;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @InjectMocks
    private RagService ragService;

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("测试语义检索返回正确的 SearchResultResponse 列表")
    void testSearch_Success() {
        // Arrange
        org.springframework.ai.document.Document mockDoc = new org.springframework.ai.document.Document(
                "chunk-1",
                "这是分块测试文本内容",
                Map.of("documentId", 100L, "documentTitle", "测试文档.pdf", "chunkIndex", 2)
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(mockDoc));

        // Act
        List<SearchResultResponse> results = ragService.search("测试查询", 5, 0.5, 42L);

        // Assert
        Assertions.assertNotNull(results);
        Assertions.assertEquals(1, results.size());
        SearchResultResponse res = results.get(0);
        Assertions.assertEquals(100L, res.documentId());
        Assertions.assertEquals("测试文档.pdf", res.documentTitle());
        Assertions.assertEquals(2, res.chunkIndex());
        Assertions.assertEquals("这是分块测试文本内容", res.content());
        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(1)).similaritySearch(requestCaptor.capture());
        Assertions.assertTrue(requestCaptor.getValue().hasFilterExpression());
        Assertions.assertTrue(requestCaptor.getValue().getFilterExpression().toString().contains("userId"));
        Assertions.assertTrue(requestCaptor.getValue().getFilterExpression().toString().contains("42"));
    }

    @Test
    @DisplayName("数据库分块写入失败时补偿删除已添加的向量")
    void databaseChunkFailureCompensatesVectorWrite() throws Exception {
        Path textFile = Files.writeString(tempDir.resolve("late-db-failure.txt"), "compensation fixture text");
        Document document = document(7L, "late-db-failure.txt");
        when(documentChunkRepository.saveAllAndFlush(anyList()))
                .thenThrow(new IllegalStateException("deterministic database failure"));

        RagEmbeddingException failure = assertThrows(
                RagEmbeddingException.class,
                () -> ragService.embedAndStoreDocument(document, textFile)
       );

        assertEquals(7L, failure.getDocumentId());
        assertInstanceOf(IllegalStateException.class, failure.getCause());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.document.Document>> addedChunks = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deletedVectorIds = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(addedChunks.capture());
        verify(vectorStore).delete(deletedVectorIds.capture());
        Assertions.assertTrue(addedChunks.getValue().stream()
                .allMatch(chunk -> Long.valueOf(42L).equals(chunk.getMetadata().get("userId"))));
        assertEquals(
                addedChunks.getValue().stream().map(org.springframework.ai.document.Document::getId).toList(),
                deletedVectorIds.getValue()
        );
    }

    @Test
    @DisplayName("文件型向量库持久化失败时不得报告嵌入成功")
    void vectorStoreFileFailureIsPropagatedAndCompensated() throws Exception {
        Path textFile = Files.writeString(tempDir.resolve("file-store-failure.txt"), "file persistence fixture");
        Document document = document(8L, "file-store-failure.txt");
        SimpleVectorStore fileBackedStore = mock(SimpleVectorStore.class);
        RagService fileBackedRagService = new RagService(
                fileBackedStore,
                documentRepository,
                documentChunkRepository,
                aiAnalysisService,
                chatClientBuilder
        );
        ReflectionTestUtils.setField(
                fileBackedRagService,
                "storeFilePath",
                tempDir.resolve("vector-store.json").toString()
        );
        doThrow(new IllegalStateException("deterministic file persistence failure"))
                .when(fileBackedStore).save(any(File.class));

        RagEmbeddingException failure = assertThrows(
                RagEmbeddingException.class,
                () -> fileBackedRagService.embedAndStoreDocument(document, textFile)
        );

        assertEquals(8L, failure.getDocumentId());
        verify(fileBackedStore).add(anyList());
        verify(fileBackedStore).delete(anyList());
        verify(fileBackedStore, times(2)).save(any(File.class));
        verify(documentChunkRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    @DisplayName("重新索引成功时替换旧分块和向量，且新向量包含所有者 userId")
    void existingChunksAndVectorsAreReplacedOnSuccessfulReindex() throws Exception {
        Path textFile = Files.writeString(tempDir.resolve("reindex.txt"), "new generation chunk content");
        Document document = document(9L, "reindex.txt");

        DocumentChunk oldChunk1 = chunk(document, 0, "old-vec-1", "old content 1");
        DocumentChunk oldChunk2 = chunk(document, 1, "old-vec-2", "old content 2");
        List<DocumentChunk> oldChunks = List.of(oldChunk1, oldChunk2);
        List<String> oldVectorIds = List.of("old-vec-1", "old-vec-2");

        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(9L)).thenReturn(oldChunks);

        ragService.embedAndStoreDocument(document, textFile);

        assertEquals(Document.EmbeddingStatus.completed, document.getEmbeddingStatus());
        assertEquals(1, document.getChunkCount());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.document.Document>> addedChunksCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deletedVectorIdsCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentChunk>> savedChunksCaptor = ArgumentCaptor.forClass(List.class);

        InOrder inOrder = inOrder(vectorStore, documentChunkRepository, documentRepository);
        inOrder.verify(vectorStore).add(addedChunksCaptor.capture());
        inOrder.verify(documentChunkRepository).deleteAll(oldChunks);
        inOrder.verify(documentChunkRepository).flush();
        inOrder.verify(documentChunkRepository).saveAllAndFlush(savedChunksCaptor.capture());
        inOrder.verify(vectorStore).delete(deletedVectorIdsCaptor.capture());
        inOrder.verify(documentRepository).saveAndFlush(document);

        List<org.springframework.ai.document.Document> addedChunks = addedChunksCaptor.getValue();
        assertEquals(1, addedChunks.size());
        org.springframework.ai.document.Document newChunk = addedChunks.get(0);
        assertEquals(42L, newChunk.getMetadata().get("userId"));
        assertEquals(9L, newChunk.getMetadata().get("documentId"));
        assertEquals("reindex.txt", newChunk.getMetadata().get("documentTitle"));
        assertEquals(0, newChunk.getMetadata().get("chunkIndex"));
        Assertions.assertFalse(oldVectorIds.contains(newChunk.getId()));

        assertEquals(oldVectorIds, deletedVectorIdsCaptor.getValue());

        List<DocumentChunk> savedChunks = savedChunksCaptor.getValue();
        assertEquals(1, savedChunks.size());
        assertEquals(newChunk.getId(), savedChunks.get(0).getVectorId());
    }

    @Test
    @DisplayName("数据库分块写入失败时补偿新向量但保留旧向量")
    void databaseChunkFailureCompensatesNewVectorsAndLeavesOldVectorsUntouched() throws Exception {
        Path textFile = Files.writeString(tempDir.resolve("reindex-db-fail.txt"), "new content causing db failure");
        Document document = document(10L, "reindex-db-fail.txt");

        DocumentChunk oldChunk1 = chunk(document, 0, "old-vec-preserved-1", "prior content 1");
        DocumentChunk oldChunk2 = chunk(document, 1, "old-vec-preserved-2", "prior content 2");
        List<DocumentChunk> oldChunks = List.of(oldChunk1, oldChunk2);
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(10L)).thenReturn(oldChunks);
        when(documentChunkRepository.saveAllAndFlush(anyList()))
                .thenThrow(new IllegalStateException("db persistence failure"));

        RagEmbeddingException failure = assertThrows(
                RagEmbeddingException.class,
                () -> ragService.embedAndStoreDocument(document, textFile)
        );

        assertEquals(10L, failure.getDocumentId());
        assertInstanceOf(IllegalStateException.class, failure.getCause());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.document.Document>> addedCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deletedCaptor = ArgumentCaptor.forClass(List.class);

        verify(vectorStore).add(addedCaptor.capture());
        verify(vectorStore).delete(deletedCaptor.capture());

        List<String> newVectorIds = addedCaptor.getValue().stream().map(org.springframework.ai.document.Document::getId).toList();
        assertEquals(newVectorIds, deletedCaptor.getValue());
        Assertions.assertFalse(deletedCaptor.getValue().contains("old-vec-preserved-1"));
        Assertions.assertFalse(deletedCaptor.getValue().contains("old-vec-preserved-2"));
    }

    @Test
    @DisplayName("重新索引产生空分块时清除旧数据库分块与向量并记录零分块成功")
    void reindexYieldingNoChunksClearsPriorIndexAndRecordsZeroCompletedChunks() throws Exception {
        Path emptyFile = Files.writeString(tempDir.resolve("empty.txt"), "");
        Document document = document(11L, "empty.txt");

        DocumentChunk oldChunk = chunk(document, 0, "old-vec-empty-1", "prior content to be cleared");
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(11L)).thenReturn(List.of(oldChunk));

        ragService.embedAndStoreDocument(document, emptyFile);

        assertEquals(0, document.getChunkCount());
        assertEquals(Document.EmbeddingStatus.completed, document.getEmbeddingStatus());

        verify(vectorStore, never()).add(anyList());
        verify(documentChunkRepository).deleteAll(List.of(oldChunk));
        verify(documentChunkRepository).flush();
        verify(documentChunkRepository, never()).saveAllAndFlush(anyList());
        verify(vectorStore).delete(List.of("old-vec-empty-1"));
        verify(documentRepository).saveAndFlush(document);
    }

    @Test
    @DisplayName("旧向量清理失败时回滚事务并尽最大努力恢复旧向量")
    void oldVectorCleanupFailureTriggersRollbackAndRestoresPriorVectors() throws Exception {
        Path textFile = Files.writeString(tempDir.resolve("cleanup-failure.txt"), "cleanup failure test text");
        Document document = document(12L, "cleanup-failure.txt");

        DocumentChunk oldChunk1 = chunk(document, 0, "old-restore-1", "prior content 1");
        DocumentChunk oldChunk2 = chunk(document, 1, "old-restore-2", "prior content 2");
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(12L)).thenReturn(List.of(oldChunk1, oldChunk2));

        doThrow(new IllegalStateException("cleanup deletion failure"))
                .when(vectorStore).delete(List.of("old-restore-1", "old-restore-2"));

        RagEmbeddingException failure = assertThrows(
                RagEmbeddingException.class,
                () -> ragService.embedAndStoreDocument(document, textFile)
        );

        assertEquals(12L, failure.getDocumentId());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("cleanup deletion failure", failure.getCause().getMessage());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.document.Document>> restoredChunksCaptor = ArgumentCaptor.forClass(List.class);

        verify(vectorStore, times(2)).add(restoredChunksCaptor.capture());

        List<List<org.springframework.ai.document.Document>> allAdds = restoredChunksCaptor.getAllValues();
        List<org.springframework.ai.document.Document> restoredChunks = allAdds.get(1);
        assertEquals(2, restoredChunks.size());
        assertEquals("old-restore-1", restoredChunks.get(0).getId());
        assertEquals("prior content 1", restoredChunks.get(0).getText());
        assertEquals(42L, ((Number) restoredChunks.get(0).getMetadata().get("userId")).longValue());
        assertEquals("old-restore-2", restoredChunks.get(1).getId());
        assertEquals("prior content 2", restoredChunks.get(1).getText());
    }

    @Test
    @DisplayName("最终文档保存失败时触发补偿与旧向量恢复并保持事务回滚")
    void finalDocumentSaveFailureCompensatesNewVectorsAndRestoresPriorVectors() throws Exception {
        Path textFile = Files.writeString(tempDir.resolve("final-save-failure.txt"), "final save failure test text");
        Document document = document(14L, "final-save-failure.txt");

        DocumentChunk oldChunk1 = chunk(document, 0, "old-save-fail-1", "prior content 1");
        DocumentChunk oldChunk2 = chunk(document, 1, "old-save-fail-2", "prior content 2");
        List<DocumentChunk> oldChunks = List.of(oldChunk1, oldChunk2);
        List<String> oldVectorIds = List.of("old-save-fail-1", "old-save-fail-2");
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(14L)).thenReturn(oldChunks);

        IllegalStateException finalSaveFailure = new IllegalStateException("document saveAndFlush deterministic failure");
        when(documentRepository.saveAndFlush(document)).thenThrow(finalSaveFailure);

        RagEmbeddingException failure = assertThrows(
                RagEmbeddingException.class,
                () -> ragService.embedAndStoreDocument(document, textFile)
        );

        assertEquals(14L, failure.getDocumentId());
        assertSame(finalSaveFailure, failure.getCause());

        InOrder inOrder = inOrder(vectorStore, documentChunkRepository, documentRepository);
        inOrder.verify(vectorStore).add(anyList());
        inOrder.verify(documentChunkRepository).deleteAll(oldChunks);
        inOrder.verify(documentChunkRepository).flush();
        inOrder.verify(documentChunkRepository).saveAllAndFlush(anyList());
        inOrder.verify(vectorStore).delete(oldVectorIds);
        inOrder.verify(documentRepository).saveAndFlush(document);
        inOrder.verify(vectorStore).delete(anyList());
        inOrder.verify(vectorStore).add(anyList());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.document.Document>> addedCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deletedCaptor = ArgumentCaptor.forClass(List.class);

        verify(vectorStore, times(2)).add(addedCaptor.capture());
        verify(vectorStore, times(2)).delete(deletedCaptor.capture());

        List<List<org.springframework.ai.document.Document>> allAdds = addedCaptor.getAllValues();
        List<List<String>> allDeletes = deletedCaptor.getAllValues();

        List<org.springframework.ai.document.Document> newStagedDocs = allAdds.get(0);
        List<String> newStagedIds = newStagedDocs.stream().map(org.springframework.ai.document.Document::getId).toList();

        assertEquals(oldVectorIds, allDeletes.get(0));
        assertEquals(newStagedIds, allDeletes.get(1));

        List<org.springframework.ai.document.Document> restoredDocs = allAdds.get(1);
        assertEquals(2, restoredDocs.size());
        assertEquals("old-save-fail-1", restoredDocs.get(0).getId());
        assertEquals("prior content 1", restoredDocs.get(0).getText());
        assertEquals(42L, ((Number) restoredDocs.get(0).getMetadata().get("userId")).longValue());
        assertEquals("old-save-fail-2", restoredDocs.get(1).getId());
        assertEquals("prior content 2", restoredDocs.get(1).getText());
        assertEquals(42L, ((Number) restoredDocs.get(1).getMetadata().get("userId")).longValue());
    }

    @Test
    @DisplayName("文档所有者缺失时拒绝索引并抛出异常")
    void missingDocumentOwnerThrowsException() throws Exception {
        Path textFile = Files.writeString(tempDir.resolve("no-owner.txt"), "no owner text");
        Document document = new Document();
        document.setId(13L);

        RagEmbeddingException failure = assertThrows(
                RagEmbeddingException.class,
                () -> ragService.embedAndStoreDocument(document, textFile)
        );

        assertEquals(13L, failure.getDocumentId());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("Document owner is required for RAG indexing", failure.getCause().getMessage());
    }

    private DocumentChunk chunk(Document doc, int index, String vectorId, String content) throws Exception {
        Map<String, Object> meta = Map.of(
                "documentId", doc.getId(),
                "documentTitle", doc.getTitle(),
                "chunkIndex", index,
                "userId", doc.getUser().getId()
        );
        return DocumentChunk.builder()
                .document(doc)
                .chunkIndex(index)
                .vectorId(vectorId)
                .content(content)
                .metadata(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta))
                .isDeleted(false)
                .build();
    }

    private Document document(Long id, String filename) {
        Document document = new Document();
        document.setId(id);
        document.setTitle(filename);
        document.setOriginalFilename(filename);
        document.setStoragePath(filename);
        User owner = new User();
        owner.setId(42L);
        document.setUser(owner);
        return document;
    }
}
