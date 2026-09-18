package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.security.SecurityUtils;
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

import java.util.List;

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
        Long userId = SecurityUtils.getCurrentUserId();
        model.addAttribute("documents",
                userId == null ? List.of() : documentService.getAllDocumentsForView());
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
     Redirects authenticated GET requests to /upload-view back to home.
     @return A redirect to home page ("/").
     */
    @GetMapping("/upload-view")
    public String uploadGet() {
        return "redirect:/";
    }

    /**
     Handles file uploads from a web form and redirects to the main page.
     @param file The uploaded file.
     @param redirectAttributes Flash attributes for feedback messages.
     @return A redirect to the home page ("/").
     */
    @PostMapping("/upload-view")
    public String upload(@RequestParam("file") MultipartFile file,
                         RedirectAttributes redirectAttributes) {
        SecurityUtils.requireCurrentUserId();
        try {
            documentService.uploadDocument(file);
            redirectAttributes.addFlashAttribute("message", "文件上传并处理成功！");
        } catch (Exception e) {
            log.error("File upload failed", e);
            redirectAttributes.addFlashAttribute("error", "上传处理发生异常，请稍后重试。");
        }
        return "redirect:/";
    }
}
