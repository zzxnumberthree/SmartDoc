package com.spe.smartdocjp.controller;


import com.spe.smartdocjp.model.DTO.DocumentDTO;
import com.spe.smartdocjp.model.DTO.DocumentStatusDTO;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.service.AiAnalysisService;
import com.spe.smartdocjp.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 REST controller for handling document-related operations.
 <p>
 Provides endpoints for uploading, deleting, and retrieving documents,
 as well as testing the AI analysis service.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final AiAnalysisService aiAnalysisService;

    /**
     Uploads a document file for a specific user.
     @param file The uploaded file.
     @param userId The ID of the uploading user.
     @return The saved document entity.
     */
    @PostMapping("/upload")
    public ResponseEntity<Document> uploadFile(
            @RequestParam("file") MultipartFile file) {
        try {
            Long userId = com.spe.smartdocjp.security.SecurityUtils.getCurrentUserId();
            Document doc = documentService.uploadDocument(file, userId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(doc); // 返回 202 Accepted 表示已接受异步任务
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Retrieves the async processing status of a document by path variable ID.
     * @param id Document ID.
     * @return DocumentStatusDTO wrapped in ResponseEntity.
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<DocumentStatusDTO> getStatusById(@PathVariable("id") Long id) {
        DocumentStatusDTO statusDTO = documentService.getDocumentStatus(id);
        if (statusDTO == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(statusDTO);
    }

    /**
     * Retrieves the async processing status of a document by query parameter ID.
     * @param documentId Document ID.
     * @return DocumentStatusDTO wrapped in ResponseEntity.
     */
    @GetMapping("/status")
    public ResponseEntity<DocumentStatusDTO> getStatusByQueryParam(@RequestParam("documentId") Long documentId) {
        DocumentStatusDTO statusDTO = documentService.getDocumentStatus(documentId);
        if (statusDTO == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(statusDTO);
    }

    @GetMapping("/ai/test")
    public String test() {
        System.out.println("this is test()");
        return aiAnalysisService.testConnect();
    }

    /**
     Deletes a document by its ID.
     @param id The ID of the document to delete.
     */
    @GetMapping("/deleteByDocumentId")
    public void deleteByDocumentId(@RequestParam("documentId") Long id) {
        documentService.deleteDocument(id);
    }

    /**
     Retrieves all documents uploaded by a specific user.
     @param id The user ID.
     @return A list of document DTOs.
     */
    @GetMapping("/findUploadedDocumentsByUserId")
    public List<DocumentDTO> findUploadedDocumentsByUserId() {
        Long id = com.spe.smartdocjp.security.SecurityUtils.getCurrentUserId();
        return documentService.findUploadedDocumentsByUserId(id);
    }

    /**
     Retrieves all documents marked as deleted.
     @return A list of deleted document DTOs.
     */
    @GetMapping("/findAllDeletedDocuments")
    public List<DocumentDTO> findAllDeletedDocuments() {
        return documentService.findAllDeletedDocuments();
    }

    /**
     Retrieves all documents for API consumption.
     @return A list of all document DTOs.
     */
    @GetMapping("/getAllDocumentForApi")
    public List<DocumentDTO> getAllDocumentForApi() {
        return documentService.getAllDocumentForApi();
    }


}
