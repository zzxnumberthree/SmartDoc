package com.spe.smartdocjp.service;

import com.spe.smartdocjp.model.DTO.SearchDTOs.*;
import com.spe.smartdocjp.repository.DocumentChunkRepository;
import com.spe.smartdocjp.repository.DocumentRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

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
        List<SearchResultResponse> results = ragService.search("测试查询", 5, 0.5);

        // Assert
        Assertions.assertNotNull(results);
        Assertions.assertEquals(1, results.size());
        SearchResultResponse res = results.get(0);
        Assertions.assertEquals(100L, res.documentId());
        Assertions.assertEquals("测试文档.pdf", res.documentTitle());
        Assertions.assertEquals(2, res.chunkIndex());
        Assertions.assertEquals("这是分块测试文本内容", res.content());
        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
    }
}
