package com.spe.smartdocjp.integration;

import com.spe.smartdocjp.service.AiAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Explicitly enabled, one-request Gemini smoke test. It is excluded from normal Maven test naming.
 */
@SpringBootTest
@ActiveProfiles("live-gemini-test")
@EnabledIfSystemProperty(named = "live.gemini", matches = "true")
@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
class GeminiLiveSmokeIT {

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Test
    void performsOneSmallGeminiRequest() {
        String response = aiAnalysisService.testConnect();
        assertFalse(response == null || response.isBlank(),
                "Gemini returned an empty response; verify API key, quota, model, and network settings");
    }
}
