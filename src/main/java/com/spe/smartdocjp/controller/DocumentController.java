package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.common.ApiResponse;
import com.spe.smartdocjp.model.DTO.DocumentDTO;
import com.spe.smartdocjp.model.DTO.DocumentStatusDTO;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.service.AiAnalysisService;
import com.spe.smartdocjp.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * REST controller for handling document-related operations.
 * <p>
 * Provides endpoints for uploading, deleting, and retrieving documents,
 * as well as testing the AI analysis service.
 */
@Tag(name = "文档管理", description = "文档上传、查询、删除与状态获取")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final AiAnalysisService aiAnalysisService;

    @Operation(summary = "上传文档", description = "上传文档进行AI分析并进行向量化处理")
    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<Document>> uploadFile(
            @RequestParam("file") MultipartFile file) {
        try {
            Long userId = com.spe.smartdocjp.security.SecurityUtils.getCurrentUserId();
            Document doc = documentService.uploadDocument(file, userId);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(doc, "文件已接收，正在异步处理"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("文件上传失败: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @Operation(summary = "获取文档状态 (Path Variable)", description = "根据文档 ID 获取当前异步处理状态")
    @GetMapping("/{id}/status")
    public ResponseEntity<ApiResponse<DocumentStatusDTO>> getStatusById(@PathVariable("id") Long id) {
        DocumentStatusDTO statusDTO = documentService.getDocumentStatus(id);
        if (statusDTO == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("文档未找到"));
        }
        return ResponseEntity.ok(ApiResponse.success(statusDTO));
    }

    @Operation(summary = "获取文档状态 (Query Param)", description = "根据文档 ID 获取当前异步处理状态")
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<DocumentStatusDTO>> getStatusByQueryParam(@RequestParam("documentId") Long documentId) {
        DocumentStatusDTO statusDTO = documentService.getDocumentStatus(documentId);
        if (statusDTO == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("文档未找到"));
        }
        return ResponseEntity.ok(ApiResponse.success(statusDTO));
    }

    @Operation(summary = "测试 AI 连接", description = "测试 Gemini AI 服务是否连通")
    @GetMapping("/ai/test")
    public ResponseEntity<ApiResponse<String>> test() {
        return ResponseEntity.ok(ApiResponse.success(aiAnalysisService.testConnect()));
    }

    @Operation(summary = "删除文档", description = "根据 ID 删除文档（仅限所有者或管理员）")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(@PathVariable("id") Long id) {
        documentService.deleteDocument(id);
        return ResponseEntity.ok(ApiResponse.success(null, "删除成功"));
    }

    @Operation(summary = "获取我上传的文档", description = "获取当前登录用户上传的所有文档")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<DocumentDTO>>> findUploadedDocumentsByUserId() {
        Long id = com.spe.smartdocjp.security.SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(documentService.findUploadedDocumentsByUserId(id)));
    }

    @Operation(summary = "获取回收站文档", description = "获取所有被标记为删除的文档")
    @GetMapping("/deleted")
    public ResponseEntity<ApiResponse<List<DocumentDTO>>> findAllDeletedDocuments() {
        return ResponseEntity.ok(ApiResponse.success(documentService.findAllDeletedDocuments()));
    }

    @Operation(summary = "获取所有文档 (分页)", description = "分页获取系统中的所有文档")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<DocumentDTO>>> getAllDocuments(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(documentService.getAllDocuments(pageable)));
    }
}
