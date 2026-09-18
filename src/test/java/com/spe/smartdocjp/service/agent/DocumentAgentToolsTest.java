package com.spe.smartdocjp.service.agent;

import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentToolResult;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentToolResultCode;
import com.spe.smartdocjp.model.DTO.SearchDTOs.SearchResultResponse;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.service.RagService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAgentToolsTest {

    private static final Long USER_ID = 42L;
    private static final ToolContext USER_CONTEXT = new ToolContext(
            Map.of(DocumentAgentTools.USER_ID_CONTEXT_KEY, USER_ID));

    @Mock
    private RagService ragService;

    @Mock
    private DocumentRepository documentRepository;

    @InjectMocks
    private DocumentAgentTools tools;

    @Test
    @DisplayName("测试 searchDocuments 工具方法")
    void testSearchDocuments_Success() {
        when(ragService.search(eq("Spring"), anyInt(), anyDouble(), eq(USER_ID)))
                .thenReturn(List.of(new SearchResultResponse(1L, "guide.pdf", 0, "Spring AI RAG text", 0.88)));

        AgentToolResult<String> result = tools.searchDocuments("Spring", USER_CONTEXT);
        Assertions.assertTrue(result.success());
        Assertions.assertTrue(result.data().contains("guide.pdf"));
        Assertions.assertTrue(result.data().contains("Spring AI RAG text"));
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

        when(documentRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(d1, d2));

        AgentToolResult<String> result = tools.getDocumentStats(USER_CONTEXT);
        Assertions.assertTrue(result.success());
        Assertions.assertTrue(result.data().contains("有效文档总数：2"));
        Assertions.assertTrue(result.data().contains("处理已完成数量：1"));
        Assertions.assertTrue(result.data().contains("RAG 向量分块总数：10"));
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

        when(documentRepository.findByIdAndUserId(101L, USER_ID)).thenReturn(Optional.of(d1));
        when(documentRepository.findByIdAndUserId(102L, USER_ID)).thenReturn(Optional.of(d2));

        AgentToolResult<String> result = tools.compareDocuments(101L, 102L, USER_CONTEXT);
        Assertions.assertTrue(result.success());
        Assertions.assertTrue(result.data().contains("Doc 1"));
        Assertions.assertTrue(result.data().contains("Summary 1"));
        Assertions.assertTrue(result.data().contains("Doc 2"));
        Assertions.assertTrue(result.data().contains("Summary 2"));
    }

    @Test
    @DisplayName("工具按服务端用户上下文查询文档，外部文档与不存在文档表现一致")
    void getDocumentByIdUsesAuthenticatedOwnerScope() {
        when(documentRepository.findByIdAndUserId(999L, USER_ID)).thenReturn(Optional.empty());

        AgentToolResult<String> result = tools.getDocumentById(999L, USER_CONTEXT);

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(AgentToolResultCode.NOT_FOUND, result.code());
        Assertions.assertTrue(result.message().contains("未找到"));
        Assertions.assertFalse(result.toString().contains("FOREIGN_SECRET"));
        verify(documentRepository).findByIdAndUserId(999L, USER_ID);
    }

    @Test
    @DisplayName("缺少服务端 ToolContext 时工具必须失败关闭")
    void missingToolContextFailsClosed() {
        AgentToolResult<String> result = tools.searchDocuments("secret", new ToolContext(Map.of()));

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(AgentToolResultCode.AUTH_CONTEXT_MISSING, result.code());
        Assertions.assertNull(result.errorId());
        Assertions.assertFalse(result.retryable());
    }

    @Test
    @DisplayName("工具依赖异常必须返回结构化脱敏结果")
    void dependencyFailureIsStructuredAndRedacted() {
        String secret = "jdbc:mysql://db/private?password=SECRET GOOGLE_API_KEY=raw-key C:\\Users\\private";
        when(ragService.search(eq("secret"), anyInt(), anyDouble(), eq(USER_ID)))
                .thenThrow(new RuntimeException(secret));

        AgentToolResult<String> result = tools.searchDocuments("secret", USER_CONTEXT);

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(AgentToolResultCode.TOOL_EXECUTION_FAILED, result.code());
        Assertions.assertEquals("工具暂时不可用，请稍后重试。", result.message());
        Assertions.assertNotNull(result.errorId());
        Assertions.assertTrue(result.retryable());
        Assertions.assertFalse(result.toString().contains("SECRET"));
        Assertions.assertFalse(result.toString().contains("GOOGLE_API_KEY"));
        Assertions.assertFalse(result.toString().contains("jdbc:mysql"));
    }
}
