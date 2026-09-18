package com.spe.smartdocjp.service;

import com.spe.smartdocjp.model.DTO.DocumentStatusDTO;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.model.entity.User;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.repository.UserRepository;
import com.spe.smartdocjp.security.CustomUserDetails;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 Unit tests for the DocumentService class.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiAnalysisService aiAnalysisService;

    @Mock
    private RagService ragService;

    @Mock
    private DocumentAsyncService documentAsyncService;

    @InjectMocks
    private DocumentService documentService;

    @Test
    @DisplayName("测试文件上传成功与异步任务触发场景")
    void testUploadFile_Success() throws IOException {
        // 准备数据 (Arrange)
        MockMultipartFile file = new MockMultipartFile(
                "file", "testUploadFile_Success()test.txt", "text/plain", "This is testUploadFile_Success()".getBytes()
        );
        Long userId = 1L;
        User mockUser = new User();
        Document mockDocument = new Document();
        mockDocument.setId(100L);
        mockDocument.setStatus(Document.DocStatus.processing);
        mockUser.setId(userId);
        mockUser.setUsername("upload-owner");
        mockUser.setPassword("unused");
        mockUser.setEmail("upload-owner@example.test");
        mockUser.setRole(User.Role.USER);
        CustomUserDetails principal = new CustomUserDetails(mockUser);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        // 定义 Mock 行为 (Stubbing)
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(documentRepository.save(any())).thenReturn(mockDocument);

        try {
            Document result = documentService.uploadDocument(file);

            // 验证 documentRepository.save() 和异步服务调用了 1次
            verify(documentRepository, times(1)).save(any());
            verify(documentAsyncService, times(1)).processAiAndRagAsync(any(), any());
            assertEquals(Document.DocStatus.processing, result.getStatus());
        } finally {
            SecurityContextHolder.clearContext();
        }

        System.out.println("Test passed: Upload verified triggering async processing without blocking.");
    }

    @Test
    @DisplayName("测试获取文档状态方法")
    void testGetDocumentStatus() {
        User owner = new User();
        owner.setId(1L);
        owner.setUsername("owner");
        owner.setPassword("unused");
        owner.setEmail("owner@example.test");
        owner.setRole(User.Role.USER);
        CustomUserDetails principal = new CustomUserDetails(owner);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        Document mockDoc = new Document();
        mockDoc.setId(1L);
        mockDoc.setStatus(Document.DocStatus.completed);
        when(documentRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(mockDoc));

        try {
            DocumentStatusDTO status = documentService.getDocumentStatus(1L);
            assertNotNull(status);
            assertEquals("completed", status.status());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("测试上传空文件应抛出异常")
    void testUploadFile_EmptyFile_ShouldThrowException() {
        // Arrange: 创建一个空内容的模拟文件
        MockMultipartFile emptyFile = new MockMultipartFile("file", "", "text/plain", new byte[0]);

        Assertions.assertThrows(RuntimeException.class, () -> {
            documentService.uploadDocument(emptyFile);
        });

        // 确保失败时，不污染系统状态
        verify(documentRepository, times(0)).save(any());
    }
}
