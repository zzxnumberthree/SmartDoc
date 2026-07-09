package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

/**
 Web controller for serving HTML views.
 <p>
 Handles requests for the main page and form-based file uploads.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class WebController {

    private final DocumentService documentService;

    /**
     Displays the main page with a list of all documents.
     @param model The Spring MVC model.
     @return The view name "index".
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("documents", documentService.getAllDocumentsForView());
        return "index";
    }

    /**
     Handles automatic browser favicon requests to avoid 404/500 errors.
     */
    @GetMapping("/favicon.ico")
    @ResponseBody
    public void returnNoFavicon() {
        // No-op to satisfy browser requests cleanly with 200 OK
    }

    /**
     Redirects GET requests to /upload-view back to home to prevent 405/NoResourceFound errors.
     @return A redirect to home page ("/").
     */
    @GetMapping("/upload-view")
    public String uploadGet() {
        return "redirect:/";
    }

    /**
     Handles file uploads from a web form and redirects to the main page.
     @param file The uploaded file.
     @param userId The ID of the uploading user.
     @param redirectAttributes Flash attributes for feedback messages.
     @return A redirect to the home page ("/").
     */
    @PostMapping("/upload-view")
    public String upload(@RequestParam("file") MultipartFile file, 
                         @RequestParam("userId") Long userId,
                         RedirectAttributes redirectAttributes) {
        try {
            documentService.uploadDocument(file, userId);
            redirectAttributes.addFlashAttribute("message", "文件上传并处理成功！");
        } catch (Exception e) {
            log.error("File upload failed for user {}: {}", userId, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "上传处理发生异常: " + e.getMessage());
        }
        return "redirect:/";
    }
}