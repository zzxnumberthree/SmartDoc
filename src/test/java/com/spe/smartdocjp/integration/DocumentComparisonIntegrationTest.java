package com.spe.smartdocjp.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.model.entity.DocumentChunk;
import com.spe.smartdocjp.model.entity.User;
import com.spe.smartdocjp.repository.DocumentChunkRepository;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.repository.UserRepository;
import com.spe.smartdocjp.security.CustomUserDetails;
import com.spe.smartdocjp.support.DeterministicAiTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("deterministic-test")
@Import(DeterministicAiTestConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class DocumentComparisonIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;

    @Test
    void ownerGetsExactUniqueDifferencesWithPersistedChunkAndPageEvidence() throws Exception {
        Authentication ownerAuth = createAuthentication("compare-owner", User.Role.USER);
        User owner = ((CustomUserDetails) ownerAuth.getPrincipal()).getUser();
        Document documentA = saveDocument(owner, "Comparison A");
        Document documentB = saveDocument(owner, "Comparison B");
        saveChunk(documentA, 4, "Shared paragraph\nOnly in A\n  only   in a  \n\n", "{\"page_number\":2}");
        saveChunk(documentB, 8, "shared PARAGRAPH\nOnly in B", "{\"page_number\":5}");

        mockMvc.perform(post("/api/documents/compare")
                        .with(authentication(ownerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request(documentA, documentB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentA.id").value(documentA.getId()))
                .andExpect(jsonPath("$.data.documentA.title").value("Comparison A"))
                .andExpect(jsonPath("$.data.documentB.id").value(documentB.getId()))
                .andExpect(jsonPath("$.data.sharedCount").value(1))
                .andExpect(jsonPath("$.data.onlyACount").value(1))
                .andExpect(jsonPath("$.data.onlyBCount").value(1))
                .andExpect(jsonPath("$.data.onlyA[0].documentId").value(documentA.getId()))
                .andExpect(jsonPath("$.data.onlyA[0].chunkIndex").value(4))
                .andExpect(jsonPath("$.data.onlyA[0].pageNumber").value(2))
                .andExpect(jsonPath("$.data.onlyA[0].text").value("Only in A"))
                .andExpect(jsonPath("$.data.onlyB[0].documentId").value(documentB.getId()))
                .andExpect(jsonPath("$.data.onlyB[0].chunkIndex").value(8))
                .andExpect(jsonPath("$.data.onlyB[0].pageNumber").value(5))
                .andExpect(jsonPath("$.data.onlyB[0].text").value("Only in B"))
                .andExpect(jsonPath("$.data.onlyATruncated").value(false))
                .andExpect(jsonPath("$.data.onlyBTruncated").value(false));
    }

    @Test
    void adminCanCompareDifferentOwnersButForeignAndDeletedDocumentsStayNotFound() throws Exception {
        Authentication ownerAuth = createAuthentication("compare-owner-a", User.Role.USER);
        Authentication otherAuth = createAuthentication("compare-owner-b", User.Role.USER);
        Authentication adminAuth = createAuthentication("compare-admin", User.Role.ADMIN);
        Document documentA = saveDocument(((CustomUserDetails) ownerAuth.getPrincipal()).getUser(), "Admin A");
        Document documentB = saveDocument(((CustomUserDetails) otherAuth.getPrincipal()).getUser(), "Admin B");

        mockMvc.perform(post("/api/documents/compare")
                        .with(authentication(adminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request(documentA, documentB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentA.id").value(documentA.getId()))
                .andExpect(jsonPath("$.data.documentB.id").value(documentB.getId()));

        String foreignError = mockMvc.perform(post("/api/documents/compare")
                        .with(authentication(ownerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request(documentA, documentB))))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/documents/compare")
                        .with(authentication(ownerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentIdA\":" + documentA.getId() + ",\"documentIdB\":999999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("文档未找到"));
        org.junit.jupiter.api.Assertions.assertTrue(foreignError.contains("文档未找到"));

        documentRepository.deleteById(documentB.getId());
        documentRepository.flush();
        mockMvc.perform(post("/api/documents/compare")
                        .with(authentication(adminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request(documentA, documentB))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("文档未找到"));
    }

    @Test
    void rejectsSameOrInvalidDocumentIdsWithBadRequest() throws Exception {
        Authentication auth = createAuthentication("compare-invalid", User.Role.USER);
        Document document = saveDocument(((CustomUserDetails) auth.getPrincipal()).getUser(), "Invalid");

        mockMvc.perform(post("/api/documents/compare")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentIdA\":" + document.getId() + ",\"documentIdB\":" + document.getId() + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/documents/compare")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentIdA\":0,\"documentIdB\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void boundsDisplayedEvidenceAndMarksOmittedDifferences() throws Exception {
        Authentication auth = createAuthentication("compare-bounds", User.Role.USER);
        User owner = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Document documentA = saveDocument(owner, "Bounds A");
        Document documentB = saveDocument(owner, "Bounds B");
        StringBuilder content = new StringBuilder("x".repeat(620));
        for (int i = 0; i < 20; i++) content.append("\nline-").append(i);
        saveChunk(documentA, 1, content.toString(), "{}");
        saveChunk(documentB, 1, "", "{}");

        String response = mockMvc.perform(post("/api/documents/compare")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request(documentA, documentB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onlyACount").value(21))
                .andExpect(jsonPath("$.data.onlyA.length()").value(20))
                .andExpect(jsonPath("$.data.onlyATruncated").value(true))
                .andExpect(jsonPath("$.data.onlyBTruncated").value(false))
                .andReturn().getResponse().getContentAsString();
        assertEquals(500, objectMapper.readTree(response).path("data").path("onlyA").get(0).path("text").asText().length());
        assertTrue(objectMapper.readTree(response).path("data").path("onlyA").get(0).path("text").asText().endsWith("…"));
    }

    @Test
    void rejectsDocumentsOverCharacterLineAndUniqueUnitLimits() throws Exception {
        Authentication auth = createAuthentication("compare-input-bounds", User.Role.USER);
        User owner = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Document comparisonTarget = saveDocument(owner, "Input Bounds Target");

        Document overCharacters = saveDocument(owner, "Too Many Characters");
        saveChunk(overCharacters, 1, "x".repeat(2_000_001), "{}");
        expectComparisonLimit(auth, overCharacters, comparisonTarget);

        Document overLines = saveDocument(owner, "Too Many Lines");
        saveChunk(overLines, 1, "same\n".repeat(50_001), "{}");
        expectComparisonLimit(auth, overLines, comparisonTarget);

        Document overUniqueUnits = saveDocument(owner, "Too Many Unique Units");
        StringBuilder manyUnique = new StringBuilder();
        for (int i = 0; i < 20_001; i++) manyUnique.append("unit-").append(i).append('\n');
        saveChunk(overUniqueUnits, 1, manyUnique.toString(), "{}");
        expectComparisonLimit(auth, overUniqueUnits, comparisonTarget);
    }

    private void expectComparisonLimit(Authentication auth, Document a, Document b) throws Exception {
        mockMvc.perform(post("/api/documents/compare")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request(a, b))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("上限")));
    }

    private Authentication createAuthentication(String prefix, User.Role role) {
        String suffix = UUID.randomUUID().toString();
        User user = userRepository.saveAndFlush(User.builder()
                .username(prefix + "-" + suffix)
                .password("not-used")
                .email(prefix + "-" + suffix + "@example.test")
                .role(role)
                .isDeleted(false)
                .build());
        CustomUserDetails principal = new CustomUserDetails(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private Document saveDocument(User owner, String title) {
        return documentRepository.saveAndFlush(Document.builder()
                .title(title)
                .originalFilename(title + ".txt")
                .storagePath("comparison-tests/" + UUID.randomUUID() + ".txt")
                .summary("test")
                .user(owner)
                .status(Document.DocStatus.completed)
                .embeddingStatus(Document.EmbeddingStatus.completed)
                .chunkCount(0)
                .isDeleted(false)
                .build());
    }

    private void saveChunk(Document document, int index, String content, String metadata) {
        chunkRepository.saveAndFlush(DocumentChunk.builder()
                .document(document)
                .chunkIndex(index)
                .content(content)
                .metadata(metadata)
                .isDeleted(false)
                .build());
    }

    private DocumentComparisonRequest request(Document a, Document b) {
        return new DocumentComparisonRequest(a.getId(), b.getId());
    }

    private record DocumentComparisonRequest(Long documentIdA, Long documentIdB) {}
}
