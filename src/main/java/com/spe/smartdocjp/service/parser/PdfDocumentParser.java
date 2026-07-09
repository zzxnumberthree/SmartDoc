package com.spe.smartdocjp.service.parser;

import com.spe.smartdocjp.service.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class PdfDocumentParser implements DocumentParser {

    private final AiAnalysisService aiAnalysisService;

    @Override
    public boolean supports(String extension) {
        return ".pdf".equalsIgnoreCase(extension);
    }

    @Override
    public String parseAndAnalyze(Path filePath) throws Exception {
        return aiAnalysisService.generateSummaryFromPdf(new FileSystemResource(filePath));
    }
}
