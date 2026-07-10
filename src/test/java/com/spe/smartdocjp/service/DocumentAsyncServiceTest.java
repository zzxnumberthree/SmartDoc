package com.spe.smartdocjp.service;

import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.repository.DocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DocumentAsyncService lifecycle transitions.
 */
@ExtendWith(MockitoExtension.class)
class DocumentAsyncServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private AiAnalysisService aiAnalysisService;

    @Mock
    private RagService ragService;

    @InjectMocks
    private DocumentAsyncService documentAsyncService;

    @Test
    @DisplayName("测试异步管道执行成功后的文档状态变更与服务调用")
    void testProcessAiAndRagAsync_Success() throws Exception {
        Long docId = 1L;
        Path mockPath = Paths.get("./dummy.txt");
        Document mockDoc = new Document();
        mockDoc.setId(docId);
        mockDoc.setOriginalFilename("test.txt");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(mockDoc));
        when(aiAnalysisService.analyzeDocumentWithRetry(any(), anyString())).thenReturn("Mock Summary");

        documentAsyncService.processAiAndRagAsync(docId, mockPath);

        verify(documentRepository, atLeast(2)).save(mockDoc);
        verify(aiAnalysisService, times(1)).analyzeDocumentWithRetry(mockPath, "test.txt");
        verify(ragService, times(1)).embedAndStoreDocument(mockDoc, mockPath);
        assertEquals(Document.DocStatus.completed, mockDoc.getStatus());
        assertEquals("Mock Summary", mockDoc.getSummary());
    }

    @Test
    @DisplayName("测试异步管道异常或重试耗尽后的错误状态捕获")
    void testProcessAiAndRagAsync_Failure() throws Exception {
        Long docId = 2L;
        Path mockPath = Paths.get("./dummy.txt");
        Document mockDoc = new Document();
        mockDoc.setId(docId);
        mockDoc.setOriginalFilename("test.txt");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(mockDoc));
        when(aiAnalysisService.analyzeDocumentWithRetry(any(), anyString()))
                .thenThrow(new RuntimeException("Total Failure"));

        documentAsyncService.processAiAndRagAsync(docId, mockPath);

        verify(documentRepository, atLeast(2)).save(mockDoc);
        verify(ragService, never()).embedAndStoreDocument(any(), any());
        assertEquals(Document.DocStatus.failed, mockDoc.getStatus());
        assertEquals(Document.EmbeddingStatus.failed, mockDoc.getEmbeddingStatus());
        assertTrue(mockDoc.getSummary().contains("Total Failure"));
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
