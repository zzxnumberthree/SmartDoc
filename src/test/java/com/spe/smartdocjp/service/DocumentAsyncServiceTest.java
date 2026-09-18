package com.spe.smartdocjp.service;

import com.spe.smartdocjp.exception.RagEmbeddingException;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.service.AiAnalysisService.SummaryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.List;
import com.spe.smartdocjp.service.parser.DocumentParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DocumentAsyncService lifecycle transitions.
 */
@ExtendWith(MockitoExtension.class)
class DocumentAsyncServiceTest {

    private static final String RAW_SECRET =
            "AIza-SENTINEL jdbc:mysql://db:3306/smartdoc C:\\private\\uploads\\secret.pdf";

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private AiAnalysisService aiAnalysisService;

    @Mock
    private RagService ragService;

    @Mock
    private DocumentParser documentParser;

    @InjectMocks
    private DocumentAsyncService documentAsyncService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        documentAsyncService = new DocumentAsyncService(documentRepository, aiAnalysisService, ragService, List.of(documentParser));
    }

    @Test
    @DisplayName("测试异步管道执行成功后的文档状态变更与服务调用")
    void testProcessAiAndRagAsync_Success() throws Exception {
        Long docId = 1L;
        Path mockPath = Paths.get("./dummy.txt");
        Document mockDoc = new Document();
        mockDoc.setId(docId);
        mockDoc.setOriginalFilename("test.txt");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(mockDoc));
        when(documentParser.supports(anyString())).thenReturn(true);
        when(aiAnalysisService.analyzeDocumentWithRetry(eq(documentParser), any(), anyString()))
                .thenReturn(SummaryResult.success(AiAnalysisService.SUMMARY_UNAVAILABLE_MESSAGE));
        doAnswer(invocation -> {
            mockDoc.setEmbeddingStatus(Document.EmbeddingStatus.completed);
            return null;
        }).when(ragService).embedAndStoreDocument(mockDoc, mockPath);

        documentAsyncService.processAiAndRagAsync(docId, mockPath);

        verify(documentRepository, atLeast(2)).save(mockDoc);
        verify(aiAnalysisService, times(1)).analyzeDocumentWithRetry(documentParser, mockPath, "test.txt");
        verify(ragService, times(1)).embedAndStoreDocument(mockDoc, mockPath);
        assertEquals(Document.DocStatus.completed, mockDoc.getStatus());
        assertEquals(Document.EmbeddingStatus.completed, mockDoc.getEmbeddingStatus());
        assertEquals(AiAnalysisService.SUMMARY_UNAVAILABLE_MESSAGE, mockDoc.getSummary(),
                "A successful model result must not be reclassified by matching fallback text");
    }

    @Test
    @DisplayName("RAG 索引失败时保留成功摘要并标记整体失败")
    void embeddingFailurePreservesSummaryAndFailsOverallLifecycle() throws Exception {
        Long docId = 3L;
        Path mockPath = Paths.get("./dummy.txt");
        Document mockDoc = new Document();
        mockDoc.setId(docId);
        mockDoc.setOriginalFilename("test.txt");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(mockDoc));
        when(documentParser.supports(anyString())).thenReturn(true);
        when(aiAnalysisService.analyzeDocumentWithRetry(eq(documentParser), any(), anyString()))
                .thenReturn(SummaryResult.success("Successful Summary"));
        doThrow(new RagEmbeddingException(docId, new IllegalStateException("provider details")))
                .when(ragService).embedAndStoreDocument(mockDoc, mockPath);

        documentAsyncService.processAiAndRagAsync(docId, mockPath);

        assertEquals(Document.DocStatus.failed, mockDoc.getStatus());
        assertEquals(Document.EmbeddingStatus.failed, mockDoc.getEmbeddingStatus());
        assertEquals("Successful Summary", mockDoc.getSummary());
        verify(documentRepository, atLeast(3)).save(mockDoc);
    }

    @Test
    @DisplayName("摘要返回受控失败时仍完成 RAG，并保持整体失败状态")
    void typedSummaryFailureStillIndexesDocumentWithoutExposingProviderDetails() throws Exception {
        Long docId = 4L;
        Path mockPath = Paths.get("./dummy.txt");
        Document mockDoc = new Document();
        mockDoc.setId(docId);
        mockDoc.setOriginalFilename("test.txt");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(mockDoc));
        when(documentParser.supports(anyString())).thenReturn(true);
        when(aiAnalysisService.analyzeDocumentWithRetry(eq(documentParser), any(), anyString()))
                .thenReturn(SummaryResult.unavailable());
        doAnswer(invocation -> {
            mockDoc.setEmbeddingStatus(Document.EmbeddingStatus.completed);
            return null;
        }).when(ragService).embedAndStoreDocument(mockDoc, mockPath);

        documentAsyncService.processAiAndRagAsync(docId, mockPath);

        assertEquals(Document.DocStatus.failed, mockDoc.getStatus());
        assertEquals(Document.EmbeddingStatus.completed, mockDoc.getEmbeddingStatus());
        assertEquals(AiAnalysisService.SUMMARY_UNAVAILABLE_MESSAGE, mockDoc.getSummary());
        verify(ragService).embedAndStoreDocument(mockDoc, mockPath);
        verify(documentRepository, atLeast(2)).save(mockDoc);
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
        when(documentParser.supports(anyString())).thenReturn(true);
        when(aiAnalysisService.analyzeDocumentWithRetry(eq(documentParser), any(), anyString()))
                .thenThrow(new RuntimeException(RAW_SECRET));

        documentAsyncService.processAiAndRagAsync(docId, mockPath);

        verify(documentRepository, atLeast(2)).save(mockDoc);
        verify(ragService, never()).embedAndStoreDocument(any(), any());
        assertEquals(Document.DocStatus.failed, mockDoc.getStatus());
        assertEquals(Document.EmbeddingStatus.failed, mockDoc.getEmbeddingStatus());
        assertEquals(DocumentAsyncService.PROCESSING_FAILED_MESSAGE, mockDoc.getSummary());
        org.junit.jupiter.api.Assertions.assertFalse(mockDoc.getSummary().contains("AIza-SENTINEL"));
        org.junit.jupiter.api.Assertions.assertFalse(mockDoc.getSummary().contains("jdbc:mysql"));
        org.junit.jupiter.api.Assertions.assertFalse(mockDoc.getSummary().contains("C:\\private"));
    }
}
