package com.spe.smartdocjp.service.agent;

import com.spe.smartdocjp.model.DTO.SearchDTOs.SearchResultResponse;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.service.DocumentService;
import com.spe.smartdocjp.service.RagService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAgentToolsTest {

    @Mock
    private RagService ragService;

    @Mock
    private DocumentService documentService;

    @Mock
    private DocumentRepository documentRepository;

    @InjectMocks
    private DocumentAgentTools tools;

    @Test
    @DisplayName("测试 searchDocuments 工具方法")
    void testSearchDocuments_Success() {
        when(ragService.search(eq("Spring"), anyInt(), anyDouble()))
                .thenReturn(List.of(new SearchResultResponse(1L, "guide.pdf", 0, "Spring AI RAG text", 0.88)));

        String result = tools.searchDocuments("Spring");
        Assertions.assertTrue(result.contains("guide.pdf"));
        Assertions.assertTrue(result.contains("Spring AI RAG text"));
    }

    @Test
    @DisplayName("测试 getDocumentStats 工具方法")
    void testGetDocumentStats_Success() {
        Document d1 = new Document();
        d1.setStatus(Document.DocStatus.completed);
        d1.setChunkCount(10);

        Document d2 = new Document();
        d2.setStatus(Document.DocStatus.processing);
        d2.setChunkCount(0);

        when(documentRepository.findAll()).thenReturn(List.of(d1, d2));

        String result = tools.getDocumentStats();
        Assertions.assertTrue(result.contains("有效文档总数：2"));
        Assertions.assertTrue(result.contains("处理已完成数量：1"));
        Assertions.assertTrue(result.contains("RAG 向量分块总数：10"));
    }

    @Test
    @DisplayName("测试 compareDocuments 工具方法")
    void testCompareDocuments_Success() {
        Document d1 = new Document();
        d1.setId(101L);
        d1.setTitle("Doc 1");
        d1.setOriginalFilename("d1.txt");
        d1.setSummary("Summary 1");

        Document d2 = new Document();
        d2.setId(102L);
        d2.setTitle("Doc 2");
        d2.setOriginalFilename("d2.txt");
        d2.setSummary("Summary 2");

        when(documentRepository.findById(101L)).thenReturn(Optional.of(d1));
        when(documentRepository.findById(102L)).thenReturn(Optional.of(d2));

        String result = tools.compareDocuments(101L, 102L);
        Assertions.assertTrue(result.contains("Doc 1"));
        Assertions.assertTrue(result.contains("Summary 1"));
        Assertions.assertTrue(result.contains("Doc 2"));
        Assertions.assertTrue(result.contains("Summary 2"));
    }
}
