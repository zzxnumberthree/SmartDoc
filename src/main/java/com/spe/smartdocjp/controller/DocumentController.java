package com.spe.smartdocjp.controller;


import com.spe.smartdocjp.model.DTO.DocumentDTO;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.service.AiAnalysisService;
import com.spe.smartdocjp.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final AiAnalysisService aiAnalysisService;

    @PostMapping("/upload")
    public ResponseEntity<Document> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") Long userId) {
        try {
            Document doc = documentService.uploadDocument(file, userId);
            return ResponseEntity.ok(doc); // 返回200
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build(); // 返回500，表示服务器在处理请求时发生了意外错误
        }
    }


    @GetMapping("/ai/test")
    public String test() {
        System.out.println("this is test()");
        return aiAnalysisService.testConnect();
    }

    @GetMapping("/deleteByDocumentId")
    public void deleteByDocumentId(@RequestParam("documentId") Long id) {
        documentService.deleteDocument(id);
    }

    @GetMapping("/findUploadedDocumentsByUserId")
    public List<DocumentDTO> findUploadedDocumentsByUserId(@Valid @RequestParam("userId") Long id) {
        return documentService.findUploadedDocumentsByUserId(id);
    }

    @GetMapping("/findAllDeletedDocuments")
    public List<DocumentDTO> findAllDeletedDocuments() {
        return documentService.findAllDeletedDocuments();
    }

    @GetMapping("/getAllDocumentForApi")
    public List<DocumentDTO> getAllDocumentForApi() {
        return documentService.getAllDocumentForApi();
    }



}
