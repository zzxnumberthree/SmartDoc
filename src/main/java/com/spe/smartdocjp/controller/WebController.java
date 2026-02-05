package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 Web controller for serving HTML views.
 <p>
 Handles requests for the main page and form-based file uploads.
 */
@Controller // 注意：这里是 @Controller，不是 @RestController
@RequiredArgsConstructor
public class WebController {

    private final DocumentService documentService;

    /**
     Displays the main page with a list of all documents.
     @param model The Spring MVC model.
     @return The view name "index".
     */
    @GetMapping("/")
    public String index(Model model) {
        // 首页：展示列表
        model.addAttribute("documents", documentService.getAllDocumentsForView());
        return "index"; // 对应 index.html
    }

    /**
     Handles file uploads from a web form and redirects to the main page.
     @param file The uploaded file.
     @param userId The ID of the uploading user.
     @return A redirect to the home page ("/").
     */
    @PostMapping("/upload-view")
    public String upload(@RequestParam("file") MultipartFile file, @RequestParam("userId") Long userId) {
        // 上传动作：处理后重定向回首页
        try {
            documentService.uploadDocument(file, userId);
        } catch (IOException e) {
            // 简单处理：实际项目中可以添加错误信息到 RedirectAttributes
            e.printStackTrace();
        }
        return "redirect:/"; // 重定向回首页，刷新列表
    }
}