package com.spe.smartdocjp.service.parser;

import com.spe.smartdocjp.service.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TxtDocumentParser implements DocumentParser {

    private final AiAnalysisService aiAnalysisService;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".txt", ".md", ".java");

    @Override
    public boolean supports(String extension) {
        return SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public String parseAndAnalyze(Path filePath) throws Exception {
        String content = Files.readString(filePath);
        return aiAnalysisService.generateSummaryFromText(content);
    }
}
