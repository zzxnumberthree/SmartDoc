package com.spe.smartdocjp.service;

import com.spe.smartdocjp.model.DTO.DocumentStatusDTO;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.model.entity.User;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.repository.UserRepository;
import com.spe.smartdocjp.security.CustomUserDetails;
import com.spe.smartdocjp.service.parser.DocumentParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Mock
    private DocumentParser documentParser;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(
                documentRepository,
                userRepository,
                aiAnalysisService,
                ragService,
                documentAsyncService,
                List.of(documentParser)
        );
    }

    @Test
    @DisplayName("测试文件上传成功与异步任务触发场景")
    void testUploadFile_Success() throws IOException {
        when(documentParser.supports(".txt")).thenReturn(true);

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
    @DisplayName("测试大写扩展名规范化为小写并保留合法上传行为")
    void testUploadFile_Success_UppercaseExtension() throws IOException {
        when(documentParser.supports(".pdf")).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.PDF", "application/pdf", "dummy pdf content".getBytes()
        );
        Long userId = 1L;
        User mockUser = new User();
        mockUser.setId(userId);
        mockUser.setUsername("upload-owner");
        mockUser.setPassword("unused");
        mockUser.setEmail("upload-owner@example.test");
        mockUser.setRole(User.Role.USER);
        CustomUserDetails principal = new CustomUserDetails(mockUser);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        try {
            Document result = documentService.uploadDocument(file);
            assertNotNull(result);
            assertEquals("report.PDF", result.getOriginalFilename());
            assertTrue(result.getStoragePath().endsWith(".pdf"));
            verify(documentRepository, times(1)).save(any());
            verify(documentAsyncService, times(1)).processAiAndRagAsync(any(), any());
        } finally {
            SecurityContextHolder.clearContext();
        }
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

    @Test
    @DisplayName("测试非法文件名在任何前置工作前被拒绝且不产生磁盘文件")
    void testUploadFile_InvalidFilenames_RejectedEarlyWithoutSideEffects(@TempDir Path tempDir) {
        ReflectionTestUtils.setField(documentService, "uploadDirectory", tempDir.resolve("target-upload").toString());

        String[] invalidFilenames = new String[] {
                null,
                "",
                "   ",
                "folder/test.txt",
                "folder\\test.txt",
                "bad\0name.txt",
                ".txt",
                "  .txt",
                "filename_without_extension",
                "test.",
                "test.exe",
                "test.sh"
        };

        for (String invalidFilename : invalidFilenames) {
            MockMultipartFile file = new MockMultipartFile(
                    "file", invalidFilename, "application/octet-stream", "dummy content".getBytes()
            );

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
                documentService.uploadDocument(file);
            });

            assertEquals("不支持的文件名或文件格式 (Invalid or unsupported file name)", exception.getMessage());
        }

        // 验证未进行用户查找、数据库写入、异步任务调度
        verifyNoInteractions(userRepository);
        verifyNoInteractions(documentRepository);
        verifyNoInteractions(documentAsyncService);

        // 验证未创建上传目录或写入磁盘文件
        Path configuredPath = tempDir.resolve("target-upload");
        assertFalse(Files.exists(configuredPath));
    }
}
