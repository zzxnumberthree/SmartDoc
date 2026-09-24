package com.spe.smartdocjp.service;

import com.spe.smartdocjp.exception.DocumentNotFoundException;
import com.spe.smartdocjp.exception.DocumentSourceUnavailableException;
import com.spe.smartdocjp.model.DTO.DocumentDTO;
import com.spe.smartdocjp.model.DTO.DocumentStatusDTO;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.model.entity.User;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.repository.UserRepository;
import com.spe.smartdocjp.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.spe.smartdocjp.model.DTO.UpdateDocRequest;
import com.spe.smartdocjp.service.parser.DocumentParser;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final AiAnalysisService aiAnalysisService;
    private final RagService ragService;
    private final DocumentAsyncService documentAsyncService;
    private final List<DocumentParser> parsers;
    @Value("${smartdoc.upload-dir:./uploads}")
    private String uploadDirectory;
    // ./uploads 表示放在项目根目录下叫 uploads
    // 获取文件存放的根目录，转成绝对路径，清除多余..防止路径注入，适配不同平台

    private static final String INVALID_FILENAME_MESSAGE = "不支持的文件名或文件格式 (Invalid or unsupported file name)";
    private static final String RESTORING_SUMMARY = "正在重新进行 AI 摘要与 RAG 向量化处理...";

    /**
     Uploads a file, stores it on disk, analyzes it with AI, and saves the record.
     <p>
     The transaction ensures rollback on failure. If the database save fails,
     the newly created file on disk is deleted.
     @param file The uploaded file (must not be null).
     @return The saved document entity.
     @throws IOException If an I/O error occurs during file handling.
     @throws RuntimeException If the user is not found or file writing fails.
     */
    @Transactional // 报错后能回滚
    public Document uploadDocument(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件不能为空 (File is empty)");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = validateAndExtractExtension(originalFilename);

        Long userId = SecurityUtils.requireCurrentUserId();
        // 先确保 存储目录存在
        Path fileStorageLocation = getFileStorageLocation();
        Files.createDirectories(fileStorageLocation);

        // 获取当前认证用户
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("当前认证用户不存在 (User not found)"));

        // 存储前重新给文件命名 使用UUID 与 验证后的全小写后缀名
        String sortedFilename = UUID.randomUUID() + extension;

        // 文件存储到磁盘的路径
        Path targetLocation = fileStorageLocation.resolve(sortedFilename); // resolve 自动处理不同系统的斜杠

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
        return DocumentStatusDTO.from(requireAccessibleDocument(documentId));
    }

    /**
     Returns all documents sorted by creation date (newest first).
     @return A sorted list of all documents.
     */
    public List<Document> getAllDocumentsForView() {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (SecurityUtils.isCurrentUserAdmin()) {
            return documentRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        return documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     Returns all documents as Data Transfer Objects (DTOs) for API responses.
     @return A list of document DTOs.
     */
    public List<DocumentDTO> getAllDocumentForApi() {
        return getAllDocumentsForView()
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
        Long userId = SecurityUtils.requireCurrentUserId();
        Page<Document> documents = SecurityUtils.isCurrentUserAdmin()
                ? documentRepository.findAll(pageable)
                : documentRepository.findByUserId(userId, pageable);
        return documents
                .map(DocumentDTO::from);
    }

    public DocumentDTO getDocumentById(Long id) {
        return DocumentDTO.from(requireAccessibleDocument(id));
    }

    @Transactional
    public DocumentDTO updateDocumentMetadata(Long id, UpdateDocRequest request) {
        Document doc = requireAccessibleDocument(id);

        if (request.getTitle() != null && !request.getTitle().trim().isEmpty()) {
            doc.setTitle(request.getTitle().trim());
        }

        documentRepository.save(doc);
        return DocumentDTO.from(doc);
    }

    @Transactional
    public void reAnalyzeDocument(Long id) {
        Document doc = requireAccessibleDocument(id);

        Path targetLocation = getFileStorageLocation().resolve(doc.getStoragePath());

        doc.setStatus(Document.DocStatus.processing);
        doc.setEmbeddingStatus(Document.EmbeddingStatus.processing);
        doc.setSummary("重新触发 AI 摘要与 RAG 向量化处理...");
        documentRepository.save(doc);

        // 异步触发
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
    }

    public void deleteDocument(Long id) {
        requireAccessibleDocument(id);
        
        // 调用这行代码时，Hibernate 会自动把它转换成 UPDATE 语句
        documentRepository.deleteById(id);
        // 同步级联清理 RAG 分块与向量库中的数据
        try {
            ragService.deleteDocumentChunksAndVectors(id);
        } catch (Exception e) {
            log.warn("清理 RAG 向量和分块数据异常 (documentId={}): {}", id, e.getMessage(), e);
        }
    }

    /** Restores a recycle-bin entry and rebuilds its summary and RAG index after commit. */
    @Transactional
    public void restoreDocument(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        boolean admin = SecurityUtils.isCurrentUserAdmin();
        Document deleted = (admin
                ? documentRepository.findDeletedById(id)
                : documentRepository.findDeletedByIdAndUserId(id, userId))
                .orElseThrow(DocumentNotFoundException::new);

        Path source = requireRestoreSource(deleted.getStoragePath());
        int updated = admin
                ? documentRepository.restoreDeletedById(id, RESTORING_SUMMARY)
                : documentRepository.restoreDeletedByIdAndUserId(id, userId, RESTORING_SUMMARY);
        if (updated != 1) {
            throw new DocumentNotFoundException();
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                documentAsyncService.processAiAndRagAsync(id, source);
            }
        });
    }

    private Path requireRestoreSource(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new DocumentSourceUnavailableException();
        }
        try {
            Path root = getFileStorageLocation();
            Path source = root.resolve(storedPath).normalize();
            if (!source.startsWith(root) || !Files.isRegularFile(source)) {
                throw new DocumentSourceUnavailableException();
            }
            Path realSource = source.toRealPath();
            if (!realSource.startsWith(root.toRealPath())) {
                throw new DocumentSourceUnavailableException();
            }
            return realSource;
        } catch (IOException | InvalidPathException | SecurityException e) {
            throw new DocumentSourceUnavailableException();
        }
    }

    /**
     Finds all documents uploaded by the current user.
     @return A list of DTOs for the current user's uploaded documents.
     */
    public List<DocumentDTO> findUploadedDocumentsForCurrentUser() {
        Long userId = SecurityUtils.requireCurrentUserId();
        return documentRepository.findUploadedDocumentsByUserId(userId)
                .stream()
                .map(DocumentDTO::from)
                .toList();
    }

    /**
     Retrieves all documents marked as deleted in the system.
     @return A list of DTOs for deleted documents.
     */
    public List<DocumentDTO> findAllDeletedDocuments() {
        Long userId = SecurityUtils.requireCurrentUserId();
        List<Document> documents = SecurityUtils.isCurrentUserAdmin()
                ? documentRepository.findAllDeletedDocuments()
                : documentRepository.findDeletedDocumentsByUserId(userId);
        return documents
                .stream()
                .map(DocumentDTO::from)
                .toList();
    }

    private Document requireAccessibleDocument(Long documentId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        return (SecurityUtils.isCurrentUserAdmin()
                ? documentRepository.findById(documentId)
                : documentRepository.findByIdAndUserId(documentId, userId))
                .orElseThrow(DocumentNotFoundException::new);
    }

    private Path getFileStorageLocation() {
        String configuredDirectory = uploadDirectory == null || uploadDirectory.isBlank()
                ? "./uploads"
                : uploadDirectory;
        return Paths.get(configuredDirectory).toAbsolutePath().normalize();
    }

    private String validateAndExtractExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException(INVALID_FILENAME_MESSAGE);
        }
        if (originalFilename.indexOf('/') >= 0 || originalFilename.indexOf('\\') >= 0 || originalFilename.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(INVALID_FILENAME_MESSAGE);
        }
        int lastDotIndex = originalFilename.lastIndexOf('.');
        if (lastDotIndex <= 0 || originalFilename.substring(0, lastDotIndex).isBlank()
                || lastDotIndex == originalFilename.length() - 1) {
            throw new IllegalArgumentException(INVALID_FILENAME_MESSAGE);
        }
        String extension = originalFilename.substring(lastDotIndex).toLowerCase(Locale.ROOT);
        boolean supported = false;
        if (parsers != null) {
            for (DocumentParser parser : parsers) {
                if (parser.supports(extension)) {
                    supported = true;
                    break;
                }
            }
        }
        if (!supported) {
            throw new IllegalArgumentException(INVALID_FILENAME_MESSAGE);
        }
        return extension;
    }

}
