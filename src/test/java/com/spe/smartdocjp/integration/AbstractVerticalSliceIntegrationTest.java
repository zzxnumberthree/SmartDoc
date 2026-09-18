package com.spe.smartdocjp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentToolResult;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.model.entity.User;
import com.spe.smartdocjp.repository.DocumentChunkRepository;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.repository.UserRepository;
import com.spe.smartdocjp.security.CustomUserDetails;
import com.spe.smartdocjp.service.agent.DocumentAgentTools;
import com.spe.smartdocjp.support.DeterministicAiProbe;
import com.spe.smartdocjp.support.DeterministicAiTestConfiguration;
import com.spe.smartdocjp.support.ResettableVectorStore;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reusable application-path scenario. Database-specific subclasses supply H2 or MySQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("deterministic-test")
@Execution(ExecutionMode.SAME_THREAD)
abstract class AbstractVerticalSliceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private DeterministicAiProbe aiProbe;

    @Autowired
    private ResettableVectorStore vectorStore;

    @Autowired
    private DocumentAgentTools documentAgentTools;

    @BeforeEach
    void resetProbe() {
        aiProbe.reset();
        vectorStore.reset();
    }

    @Test
    void pdfUploadRunsThroughAsyncSummaryRagQaAndSse() throws Exception {
        Authentication user = createAuthentication("vertical-success");
        aiProbe.blockPdfSummary();

        CompletableFuture<MvcResult> uploadFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return uploadPdf("interview-evidence.pdf", user);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });

        assertTrue(aiProbe.awaitSummaryStarted(Duration.ofSeconds(5)),
                "The background summary should reach the deterministic provider");

        MvcResult uploadResult;
        try {
            uploadResult = uploadFuture.get(2, TimeUnit.SECONDS);
        } finally {
            aiProbe.releasePdfSummary();
        }

        JsonNode uploadJson = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        long documentId = uploadJson.path("data").path("id").asLong();
        assertTrue(documentId > 0);
        assertEquals("processing", uploadJson.path("data").path("status").asText());
        assertEquals("processing", uploadJson.path("data").path("embeddingStatus").asText());
        assertNotNull(aiProbe.summaryThreadName());
        assertTrue(aiProbe.summaryThreadName().startsWith("DocAsync-"),
                "Summary must run on the configured Spring @Async executor");

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Document document = documentRepository.findById(documentId).orElseThrow();
            assertEquals(Document.DocStatus.completed, document.getStatus());
            assertEquals(Document.EmbeddingStatus.completed, document.getEmbeddingStatus());
            assertEquals(DeterministicAiTestConfiguration.SUMMARY, document.getSummary());
            assertTrue(document.getChunkCount() > 0);
            assertFalse(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId).isEmpty());
            assertTrue(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId).stream()
                    .anyMatch(chunk -> chunk.getContent().contains(DeterministicAiTestConfiguration.FIXTURE_MARKER)));
        });

        mockMvc.perform(post("/api/search/query")
                        .with(authentication(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"INTERVIEW_EVIDENCE_MARKER","topK":5,"similarityThreshold":0.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].documentId").value(documentId))
                .andExpect(jsonPath("$.data[0].documentTitle").value("interview-evidence.pdf"))
                .andExpect(jsonPath("$.data[0].content").value(
                        org.hamcrest.Matchers.containsString(DeterministicAiTestConfiguration.FIXTURE_MARKER)));

        mockMvc.perform(post("/api/search/ask")
                        .with(authentication(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"What evidence is in the fixture?","topK":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(
                        org.hamcrest.Matchers.containsString("Grounded answer")))
                .andExpect(jsonPath("$.data.answer").value(
                        org.hamcrest.Matchers.containsString("interview-evidence.pdf")))
                .andExpect(jsonPath("$.data.sources[0].documentId").value(documentId));

        MvcResult streamStarted = mockMvc.perform(get("/api/agent/chat/stream")
                        .with(authentication(user))
                        .param("message", "Stream a deterministic answer")
                        .param("conversationId", "vertical-slice")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        streamStarted.getAsyncResult(5_000);
        String streamBody = mockMvc.perform(asyncDispatch(streamStarted))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(streamBody.contains("STREAM_ONE"));
        assertTrue(streamBody.contains("STREAM_TWO"));
        assertTrue(streamBody.contains("STREAM_COMPLETE"));
        assertTrue(streamBody.contains("event:token"));
        assertTrue(streamBody.contains("event:complete"));
        assertFalse(streamBody.contains("event:error"));
        assertTrue(countOccurrences(streamBody, "data:") >= 4,
                "Successful SSE should contain token frames and one completion frame");
        assertTrue(streamBody.indexOf("STREAM_ONE") < streamBody.indexOf("STREAM_COMPLETE"));
    }

    @Test
    void exhaustedSummaryRetriesProduceControlledFailedState() throws Exception {
        Authentication user = createAuthentication("vertical-failure");
        aiProbe.failPdfSummary();

        MvcResult uploadResult = uploadPdf("provider-failure.pdf", user);
        JsonNode uploadJson = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        long documentId = uploadJson.path("data").path("id").asLong();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Document document = documentRepository.findById(documentId).orElseThrow();
            assertEquals(Document.DocStatus.failed, document.getStatus(),
                    "Provider failure must not become a false COMPLETED summary state");
            assertEquals("AI 服务暂时不可用，请稍后重试。", document.getSummary());
            assertFalse(document.getSummary().contains(DeterministicAiProbe.PROVIDER_FAILURE_SENTINEL));
            assertEquals(Document.EmbeddingStatus.completed, document.getEmbeddingStatus(),
                    "PDF ingestion remains deterministic even when summary generation fails");
            assertTrue(document.getChunkCount() > 0);
            assertTrue(aiProbe.pdfSummaryCalls() >= 2,
                    "The call must pass through the Spring Retry proxy before recovery");
        });

        String statusBody = mockMvc.perform(get("/api/documents/{id}/status", documentId)
                        .with(authentication(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.embeddingStatus").value("completed"))
                .andExpect(jsonPath("$.data.summary").value("AI 服务暂时不可用，请稍后重试。"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(statusBody.contains("AIza-SENTINEL"));
        assertFalse(statusBody.contains("jdbc:mysql"));
        assertFalse(statusBody.contains("C:\\private"));
    }

    @Test
    void embeddingFailureCannotReportFullPipelineSuccess() throws Exception {
        Authentication user = createAuthentication("vertical-embedding-failure");
        vectorStore.failAdds();

        MvcResult uploadResult = uploadPdf("embedding-failure.pdf", user);
        JsonNode uploadJson = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        long documentId = uploadJson.path("data").path("id").asLong();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Document document = documentRepository.findById(documentId).orElseThrow();
            assertEquals(Document.DocStatus.failed, document.getStatus(),
                    "Indexing failure must fail the overall document lifecycle");
            assertEquals(Document.EmbeddingStatus.failed, document.getEmbeddingStatus());
            assertEquals(DeterministicAiTestConfiguration.SUMMARY, document.getSummary(),
                    "A successful summary must not be replaced with provider error details");
            assertEquals(0, document.getChunkCount());
            assertTrue(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId).isEmpty(),
                    "A vector-store failure before chunk persistence must not leave database chunks");
        });
    }

    @Test
    void authenticatedUserCannotRetrieveAnotherUsersRagOrToolData() throws Exception {
        Authentication authenticationA = createAuthentication("tenant-a");
        Authentication authenticationB = createAuthentication("tenant-b");
        User ownerA = ((CustomUserDetails) authenticationA.getPrincipal()).getUser();
        User ownerB = ((CustomUserDetails) authenticationB.getPrincipal()).getUser();

        Document documentA = saveCompletedDocument(ownerA, "USER_A_DOC", "USER_A_SECRET");
        Document documentB = saveCompletedDocument(ownerB, "USER_B_DOC", "USER_B_SECRET");

        vectorStore.add(List.of(
                vectorChunk("tenant-b-chunk", documentB, "USER_B_SECRET"),
                vectorChunk("tenant-a-chunk", documentA, "USER_A_SECRET")
        ));

        String searchBody = mockMvc.perform(post("/api/search/query")
                        .with(authentication(authenticationA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"secret","topK":1,"similarityThreshold":0.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].documentId").value(documentA.getId()))
                .andExpect(jsonPath("$.data[0].documentTitle").value("USER_A_DOC"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(searchBody.contains("USER_A_SECRET"));
        assertFalse(searchBody.contains("USER_B_SECRET"));
        assertFalse(searchBody.contains("USER_B_DOC"));

        String askBody = mockMvc.perform(post("/api/search/ask")
                        .with(authentication(authenticationA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"What is the secret?","topK":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources.length()").value(1))
                .andExpect(jsonPath("$.data.sources[0].documentId").value(documentA.getId()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(askBody.contains("USER_B_SECRET"));
        assertFalse(askBody.contains("USER_B_DOC"));

        ToolContext userAToolContext = new ToolContext(Map.of(
                DocumentAgentTools.USER_ID_CONTEXT_KEY,
                ownerA.getId()
        ));

        AgentToolResult<String> toolSearch = documentAgentTools.searchDocuments("secret", userAToolContext);
        assertTrue(toolSearch.data().contains("USER_A_SECRET"));
        assertFalse(toolSearch.data().contains("USER_B_SECRET"));

        AgentToolResult<String> foreignDocument = documentAgentTools.getDocumentById(documentB.getId(), userAToolContext);
        assertFalse(foreignDocument.success());
        assertFalse(foreignDocument.toString().contains("USER_B_DOC"));
        assertFalse(foreignDocument.toString().contains("USER_B_SECRET"));

        AgentToolResult<String> recentDocuments = documentAgentTools.listRecentDocuments(10, userAToolContext);
        assertTrue(recentDocuments.data().contains("USER_A_DOC"));
        assertFalse(recentDocuments.data().contains("USER_B_DOC"));

        AgentToolResult<String> stats = documentAgentTools.getDocumentStats(userAToolContext);
        assertTrue(stats.data().contains("有效文档总数：1"));

        AgentToolResult<String> comparison = documentAgentTools.compareDocuments(
                documentA.getId(), documentB.getId(), userAToolContext);
        assertFalse(comparison.success());
        assertFalse(comparison.toString().contains("USER_B_DOC"));
        assertFalse(comparison.toString().contains("USER_B_SECRET"));
    }

    private Document saveCompletedDocument(User owner, String title, String summary) {
        return documentRepository.saveAndFlush(Document.builder()
                .title(title)
                .originalFilename(title + ".txt")
                .storagePath("test-only/" + title + ".txt")
                .summary(summary)
                .user(owner)
                .status(Document.DocStatus.completed)
                .embeddingStatus(Document.EmbeddingStatus.completed)
                .chunkCount(1)
                .isDeleted(false)
                .build());
    }

    private org.springframework.ai.document.Document vectorChunk(
            String vectorId, Document source, String content) {
        return new org.springframework.ai.document.Document(
                vectorId,
                content,
                Map.of(
                        "documentId", source.getId(),
                        "documentTitle", source.getTitle(),
                        "chunkIndex", 0,
                        "userId", source.getUser().getId()
                )
        );
    }

    private MvcResult uploadPdf(String filename, Authentication user) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                MediaType.APPLICATION_PDF_VALUE,
                createPdfFixture()
        );

        return mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .with(authentication(user)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
    }

    private Authentication createAuthentication(String prefix) {
        String suffix = UUID.randomUUID().toString();
        User user = User.builder()
                .username(prefix + "-" + suffix)
                .password("not-used-by-request-authentication")
                .email(prefix + "-" + suffix + "@example.test")
                .role(User.Role.USER)
                .isDeleted(false)
                .build();
        user = userRepository.saveAndFlush(user);

        CustomUserDetails principal = new CustomUserDetails(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private byte[] createPdfFixture() throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("SmartDoc deterministic interview fixture "
                        + DeterministicAiTestConfiguration.FIXTURE_MARKER);
                content.endText();
            }
            pdf.save(output);
            return output.toByteArray();
        }
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
