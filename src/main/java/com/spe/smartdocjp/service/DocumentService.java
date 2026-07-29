package com.spe.smartdocjp.service;

import com.spe.smartdocjp.model.DTO.DocumentDTO;
import com.spe.smartdocjp.model.DTO.DocumentStatusDTO;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.model.entity.User;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final AiAnalysisService aiAnalysisService;
    private final RagService ragService;
    private final DocumentAsyncService documentAsyncService;
    private final Path fileStorageLocation = Paths
            .get("./uploads").toAbsolutePath().normalize();
    // ./uploads 表示放在项目根目录下叫 uploads
    // 获取文件存放的根目录，转成绝对路径，清除多余..防止路径注入，适配不同平台

    /**
     Uploads a file, stores it on disk, analyzes it with AI, and saves the record.
     <p>
     The transaction ensures rollback on failure. If the database save fails,
     the newly created file on disk is deleted.
     @param file The uploaded file (must not be null).
     @param userId The ID of the uploading user.
     @return The saved document entity.
     @throws IOException If an I/O error occurs during file handling.
     @throws RuntimeException If the user is not found or file writing fails.
     */
    @Transactional // 报错后能回滚
    public Document uploadDocument(MultipartFile file, Long userId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("上传的文件不能为空 (File is empty)");
        }
        // 先确保 存储目录存在
        Files.createDirectories(fileStorageLocation);

        // 获取当前认证用户
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("当前认证用户不存在 (User not found)"));

        // 存储前重新给文件命名 使用UUID
        String originalFilename = file.getOriginalFilename();
        String extension = ""; // extension 表示后缀名

        if (originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        // 以UUID码为文件名储存在磁盘
        //  String sortedFilename = UUID.randomUUID().toString() + extension;
        String sortedFilename = UUID.randomUUID() + extension;

        // 文件存储到磁盘的路径
        Path targetLocation = this.fileStorageLocation.resolve(sortedFilename); // resolve 自动处理不同系统的斜杠

        String summary = "正在进行 AI 摘要分析与 RAG 向量化处理...";

        try { // 将文件存储到磁盘，REPLACE_EXISTING 文件名冲突则覆盖
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("文件写入失败", e);
        }

        Document doc = Document.builder()
                .title(originalFilename)
                .originalFilename(originalFilename)
                .storagePath(sortedFilename)
                .user(user)
                .summary(summary)
                .status(Document.DocStatus.processing)
                .embeddingStatus(Document.EmbeddingStatus.processing)
                .isDeleted(false)
                .build();

        try {
            // 将初始文件信息存储到数据库
            documentRepository.save(doc);
        } catch (Exception e) {
            // 数据库保存失败时，删除磁盘垃圾文件并抛出回滚
            Files.deleteIfExists(targetLocation);
            log.error("检测到数据库存储失败，已清理磁盘文件: {}", targetLocation, e);
            throw e;
        }

        // 异步触发 AI 摘要与 RAG 嵌入分块管线：确保在数据库事务提交后才启动异步线程，避免异步线程在事务未提交前查询数据库找不到记录
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            documentAsyncService.processAiAndRagAsync(doc.getId(), targetLocation);
                        }
                    }
            );
        } else {
            documentAsyncService.processAiAndRagAsync(doc.getId(), targetLocation);
        }

        return doc;
    }

    /**
     * Retrieves the current processing status of a document.
     * @param documentId The ID of the document.
     * @return DTO containing status information, or null if not found.
     */
    public DocumentStatusDTO getDocumentStatus(Long documentId) {
        return documentRepository.findById(documentId)
                .map(DocumentStatusDTO::from)
                .orElse(null);
    }

    /**
     Returns all documents sorted by creation date (newest first).
     @return A sorted list of all documents.
     */
    public List<Document> getAllDocumentsForView() {
        return documentRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /**
     Returns all documents as Data Transfer Objects (DTOs) for API responses.
     @return A list of document DTOs.
     */
    public List<DocumentDTO> getAllDocumentForApi() {
        return documentRepository.findAll()
                .stream()
                .map(DocumentDTO::from)
                .toList();
    }

    /**
     * Returns a paginated list of documents as DTOs.
     * @param pageable Pagination information
     * @return A page of document DTOs
     */
    public Page<DocumentDTO> getAllDocuments(Pageable pageable) {
        return documentRepository.findAll(pageable)
                .map(DocumentDTO::from);
    }

    public void deleteDocument(Long id) {
        Document doc = documentRepository.findById(id).orElseThrow(() -> new RuntimeException("Document not found"));
        Long currentUserId = com.spe.smartdocjp.security.SecurityUtils.getCurrentUserId();
        String role = com.spe.smartdocjp.security.SecurityUtils.getCurrentUsername(); // Actually we need Role from authorities, but let's check userId for now.
        // Or simply:
        if (!doc.getUser().getId().equals(currentUserId) &&
            !org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new org.springframework.security.access.AccessDeniedException("您只能删除自己的文档");
        }
        
        // 调用这行代码时，Hibernate 会自动把它转换成 UPDATE 语句
        documentRepository.deleteById(id);
        // 同步级联清理 RAG 分块与向量库中的数据
        try {
            ragService.deleteDocumentChunksAndVectors(id);
        } catch (Exception e) {
            System.out.println("清理 RAG 向量和分块数据异常: " + e.getMessage());
        }
    }

    /**
     Finds all documents uploaded by a specific user.
     @param id The unique ID of the user.
     @return A list of DTOs for the user's uploaded documents.
     */
    public List<DocumentDTO> findUploadedDocumentsByUserId(Long id) {
        return documentRepository.findUploadedDocumentsByUserId(id)
                .stream()
                .map(DocumentDTO::from)
                .toList();
    }

    /**
     Retrieves all documents marked as deleted in the system.
     @return A list of DTOs for deleted documents.
     */
    public List<DocumentDTO> findAllDeletedDocuments() {
        return documentRepository.findAllDeletedDocuments()
                .stream()
                .map(DocumentDTO::from)
                .toList();
    }


}
