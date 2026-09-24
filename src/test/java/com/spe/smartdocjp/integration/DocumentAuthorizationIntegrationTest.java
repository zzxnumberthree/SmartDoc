package com.spe.smartdocjp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.model.entity.User;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.repository.UserRepository;
import com.spe.smartdocjp.security.CustomUserDetails;
import com.spe.smartdocjp.service.DocumentAsyncService;
import com.spe.smartdocjp.support.DeterministicAiTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("deterministic-test")
@Import(DeterministicAiTestConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class DocumentAuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private DocumentAsyncService documentAsyncService;

    @BeforeEach
    void resetAsyncService() {
        reset(documentAsyncService);
    }

    @Test
    void foreignAndUnknownSingleDocumentOperationsAreIndistinguishable() throws Exception {
        Authentication owner = createAuthentication("doc-owner", User.Role.USER);
        Authentication otherUser = createAuthentication("doc-other", User.Role.USER);
        Authentication admin = createAuthentication("doc-admin", User.Role.ADMIN);
        User ownerEntity = ((CustomUserDetails) owner.getPrincipal()).getUser();
        Document document = saveDocument(ownerEntity, "OWNER_ACTIVE", "OWNER_SUMMARY");
        long unknownId = document.getId() + 1_000_000;

        mockMvc.perform(get("/api/documents/{id}/status", document.getId())
                        .with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("OWNER_SUMMARY"));

        mockMvc.perform(get("/api/documents/{id}/status", document.getId())
                        .with(authentication(otherUser)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("文档未找到"));
        mockMvc.perform(get("/api/documents/status")
                        .param("documentId", document.getId().toString())
                        .with(authentication(otherUser)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents/{id}", document.getId())
                        .with(authentication(otherUser)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents/{id}", unknownId)
                        .with(authentication(otherUser)))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/documents/{id}", document.getId())
                        .with(authentication(otherUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"HACKED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/documents/{id}/re-analyze", document.getId())
                        .with(authentication(otherUser)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/documents/{id}", document.getId())
                        .with(authentication(otherUser)))
                .andExpect(status().isNotFound());

        Document unchanged = documentRepository.findById(document.getId()).orElseThrow();
        assertEquals("OWNER_ACTIVE", unchanged.getTitle());
        assertEquals("OWNER_SUMMARY", unchanged.getSummary());
        verify(documentAsyncService, never()).processAiAndRagAsync(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());

        mockMvc.perform(get("/api/documents/{id}", document.getId())
                        .with(authentication(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(document.getId()));
    }

    @Test
    void documentListsAndHomePageAreScopedToTheAuthenticatedUser() throws Exception {
        Authentication userA = createAuthentication("list-a", User.Role.USER);
        Authentication userB = createAuthentication("list-b", User.Role.USER);
        Authentication admin = createAuthentication("list-admin", User.Role.ADMIN);
        Document documentA = saveDocument(
                ((CustomUserDetails) userA.getPrincipal()).getUser(), "LIST_A", "LIST_A_SUMMARY");
        Document documentB = saveDocument(
                ((CustomUserDetails) userB.getPrincipal()).getUser(), "LIST_B", "LIST_B_SUMMARY");

        String userAList = mockMvc.perform(get("/api/documents").with(authentication(userA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(userAList.contains(documentA.getOriginalFilename()));
        assertFalse(userAList.contains(documentB.getOriginalFilename()));

        String userAMe = mockMvc.perform(get("/api/documents/me").with(authentication(userA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(userAMe.contains(documentA.getOriginalFilename()));
        assertFalse(userAMe.contains(documentB.getOriginalFilename()));

        String adminList = mockMvc.perform(get("/api/documents").with(authentication(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(adminList.contains(documentA.getOriginalFilename()));
        assertTrue(adminList.contains(documentB.getOriginalFilename()));

        String anonymousHome = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(anonymousHome.contains("LIST_A"));
        assertFalse(anonymousHome.contains("LIST_B"));

        String userAHome = mockMvc.perform(get("/").with(authentication(userA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(userAHome.contains("LIST_A"));
        assertFalse(userAHome.contains("LIST_B"));

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedSearchEndpointsFailClosedForUnsupportedAuthenticatedPrincipal() throws Exception {
        Authentication unsupportedPrincipal = new UsernamePasswordAuthenticationToken(
                "external-principal",
                "unused",
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(post("/api/search/query")
                        .with(authentication(unsupportedPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"secret\",\"topK\":3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("无权访问该资源"));
        mockMvc.perform(post("/api/search/ask")
                        .with(authentication(unsupportedPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"secret\",\"topK\":3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("无权访问该资源"));
    }

    @Test
    void recycleBinIsOwnerScopedAndAdminCanSeeAllDeletedDocuments() throws Exception {
        Authentication userA = createAuthentication("deleted-a", User.Role.USER);
        Authentication userB = createAuthentication("deleted-b", User.Role.USER);
        Authentication admin = createAuthentication("deleted-admin", User.Role.ADMIN);
        Document documentA = saveDocument(
                ((CustomUserDetails) userA.getPrincipal()).getUser(), "DELETED_A", "A");
        Document documentB = saveDocument(
                ((CustomUserDetails) userB.getPrincipal()).getUser(), "DELETED_B", "B");
        jdbcTemplate.update("UPDATE documents SET is_deleted = true WHERE id IN (?, ?)",
                documentA.getId(), documentB.getId());

        String userADeleted = mockMvc.perform(get("/api/documents/deleted").with(authentication(userA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(userADeleted.contains(documentA.getOriginalFilename()));
        assertFalse(userADeleted.contains(documentB.getOriginalFilename()));

        String userBDeleted = mockMvc.perform(get("/api/documents/deleted").with(authentication(userB)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(userBDeleted.contains(documentA.getOriginalFilename()));
        assertTrue(userBDeleted.contains(documentB.getOriginalFilename()));

        String adminDeleted = mockMvc.perform(get("/api/documents/deleted").with(authentication(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(adminDeleted.contains(documentA.getOriginalFilename()));
        assertTrue(adminDeleted.contains(documentB.getOriginalFilename()));
    }

    @Test
    void ownerCanRestoreDeletedDocumentAndScheduleReanalysisOnce() throws Exception {
        Authentication owner = createAuthentication("restore-owner", User.Role.USER);
        Document document = saveDocument(
                ((CustomUserDetails) owner.getPrincipal()).getUser(), "RESTORE_OWNER", "old summary");
        Path source = createStoredSource(document);
        try {
            mockMvc.perform(post("/api/documents/{id}/restore", document.getId())
                            .with(authentication(owner)))
                    .andExpect(status().isNotFound());
            jdbcTemplate.update("UPDATE documents SET is_deleted = true WHERE id = ?", document.getId());

            mockMvc.perform(post("/api/documents/{id}/restore", document.getId())
                            .with(authentication(owner)))
                    .andExpect(status().isAccepted());

            assertFalse(jdbcTemplate.queryForObject(
                    "SELECT is_deleted FROM documents WHERE id = ?", Boolean.class, document.getId()));
            assertEquals("processing", jdbcTemplate.queryForObject(
                    "SELECT status FROM documents WHERE id = ?", String.class, document.getId()));
            assertEquals("processing", jdbcTemplate.queryForObject(
                    "SELECT embedding_status FROM documents WHERE id = ?", String.class, document.getId()));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT chunk_count FROM documents WHERE id = ?", Integer.class, document.getId()));
            assertTrue(mockMvc.perform(get("/api/documents/{id}", document.getId())
                            .with(authentication(owner)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString().contains(document.getOriginalFilename()));
            verify(documentAsyncService, times(1)).processAiAndRagAsync(document.getId(), source.toRealPath());

            mockMvc.perform(post("/api/documents/{id}/restore", document.getId())
                            .with(authentication(owner)))
                    .andExpect(status().isNotFound());
            verify(documentAsyncService, times(1)).processAiAndRagAsync(document.getId(), source.toRealPath());
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void onlyAdminCanRestoreAnotherUsersDeletedDocument() throws Exception {
        Authentication owner = createAuthentication("restore-admin-owner", User.Role.USER);
        Authentication foreignUser = createAuthentication("restore-foreign", User.Role.USER);
        Authentication admin = createAuthentication("restore-admin", User.Role.ADMIN);
        Document document = saveDocument(
                ((CustomUserDetails) owner.getPrincipal()).getUser(), "RESTORE_ADMIN", "old summary");
        Path source = createStoredSource(document);
        try {
            jdbcTemplate.update("UPDATE documents SET is_deleted = true WHERE id = ?", document.getId());
            long unknownId = document.getId() + 1_000_000;

            mockMvc.perform(post("/api/documents/{id}/restore", document.getId())
                            .with(authentication(foreignUser)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("文档未找到"));
            mockMvc.perform(post("/api/documents/{id}/restore", unknownId)
                            .with(authentication(admin)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("文档未找到"));
            verify(documentAsyncService, never()).processAiAndRagAsync(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());

            mockMvc.perform(post("/api/documents/{id}/restore", document.getId())
                            .with(authentication(admin)))
                    .andExpect(status().isAccepted());
            verify(documentAsyncService, times(1)).processAiAndRagAsync(document.getId(), source.toRealPath());
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void missingOrEscapingSourceCannotBeRestored() throws Exception {
        Authentication owner = createAuthentication("restore-invalid", User.Role.USER);
        User user = ((CustomUserDetails) owner.getPrincipal()).getUser();
        Document missing = saveDocument(user, "RESTORE_MISSING_" + UUID.randomUUID(), "old summary");
        jdbcTemplate.update("UPDATE documents SET is_deleted = true WHERE id = ?", missing.getId());

        mockMvc.perform(post("/api/documents/{id}/restore", missing.getId())
                        .with(authentication(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("文档原文件不可用，无法恢复，请重新上传。"));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM documents WHERE id = ?", Boolean.class, missing.getId()));

        Document escaping = saveDocument(user, "RESTORE_ESCAPE", "old summary");
        escaping.setStoragePath("../restore-outside.txt");
        documentRepository.saveAndFlush(escaping);
        jdbcTemplate.update("UPDATE documents SET is_deleted = true WHERE id = ?", escaping.getId());
        Path outside = Path.of("target/deterministic-test/restore-outside.txt").toAbsolutePath();
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "outside");
        try {
            mockMvc.perform(post("/api/documents/{id}/restore", escaping.getId())
                            .with(authentication(owner)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("文档原文件不可用，无法恢复，请重新上传。"));
            assertTrue(jdbcTemplate.queryForObject(
                    "SELECT is_deleted FROM documents WHERE id = ?", Boolean.class, escaping.getId()));
            verify(documentAsyncService, never()).processAiAndRagAsync(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void ownersAndAdminsCanMutateDocumentsWhileEveryUnknownOperationReturns404() throws Exception {
        Authentication owner = createAuthentication("mutation-owner", User.Role.USER);
        Authentication admin = createAuthentication("mutation-admin", User.Role.ADMIN);
        User ownerEntity = ((CustomUserDetails) owner.getPrincipal()).getUser();

        Document ownerUpdate = saveDocument(ownerEntity, "OWNER_UPDATE", "owner-update");
        Document ownerReanalysis = saveDocument(ownerEntity, "OWNER_REANALYZE", "owner-reanalyze");
        Document ownerDelete = saveDocument(ownerEntity, "OWNER_DELETE", "owner-delete");
        Document adminUpdate = saveDocument(ownerEntity, "ADMIN_UPDATE", "admin-update");
        Document adminReanalysis = saveDocument(ownerEntity, "ADMIN_REANALYZE", "admin-reanalyze");
        Document adminDelete = saveDocument(ownerEntity, "ADMIN_DELETE", "admin-delete");

        mockMvc.perform(patch("/api/documents/{id}", ownerUpdate.getId())
                        .with(authentication(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"OWNER_UPDATED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/documents/{id}/re-analyze", ownerReanalysis.getId())
                        .with(authentication(owner)))
                .andExpect(status().isAccepted());
        mockMvc.perform(delete("/api/documents/{id}", ownerDelete.getId())
                        .with(authentication(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/documents/{id}", adminUpdate.getId())
                        .with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ADMIN_UPDATED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/documents/{id}/re-analyze", adminReanalysis.getId())
                        .with(authentication(admin)))
                .andExpect(status().isAccepted());
        mockMvc.perform(delete("/api/documents/{id}", adminDelete.getId())
                        .with(authentication(admin)))
                .andExpect(status().isOk());

        assertEquals("OWNER_UPDATED", documentRepository.findById(ownerUpdate.getId()).orElseThrow().getTitle());
        assertEquals("ADMIN_UPDATED", documentRepository.findById(adminUpdate.getId()).orElseThrow().getTitle());
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM documents WHERE id = ?", Boolean.class, ownerDelete.getId()));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM documents WHERE id = ?", Boolean.class, adminDelete.getId()));
        verify(documentAsyncService, times(2)).processAiAndRagAsync(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());

        long unknownId = adminDelete.getId() + 1_000_000;
        mockMvc.perform(get("/api/documents/{id}/status", unknownId).with(authentication(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents/status")
                        .param("documentId", Long.toString(unknownId))
                        .with(authentication(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents/{id}", unknownId).with(authentication(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/documents/{id}", unknownId)
                        .with(authentication(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"UNKNOWN\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/documents/{id}/re-analyze", unknownId).with(authentication(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/documents/{id}", unknownId).with(authentication(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadEndpointsIgnoreSpoofedUserIdAndTrustOnlyAuthentication() throws Exception {
        Authentication userA = createAuthentication("upload-a", User.Role.USER);
        Authentication userB = createAuthentication("upload-b", User.Role.USER);
        User ownerA = ((CustomUserDetails) userA.getPrincipal()).getUser();
        User ownerB = ((CustomUserDetails) userB.getPrincipal()).getUser();
        long beforeA = documentRepository.findByUserId(ownerA.getId()).size();
        long beforeB = documentRepository.findByUserId(ownerB.getId()).size();

        MockMultipartFile apiFile = new MockMultipartFile(
                "file", "AUTH_API_UPLOAD.txt", MediaType.TEXT_PLAIN_VALUE, "api".getBytes());
        String apiResponse = mockMvc.perform(multipart("/api/documents/upload")
                        .file(apiFile)
                        .param("userId", ownerB.getId().toString())
                        .with(authentication(userA)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.user").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.storagePath").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        JsonNode apiJson = objectMapper.readTree(apiResponse);
        assertTrue(apiJson.path("data").path("user").isMissingNode());
        assertTrue(apiJson.path("data").path("password").isMissingNode());
        assertTrue(apiJson.path("data").path("storagePath").isMissingNode());
        Document apiDocument = documentRepository.findById(apiJson.path("data").path("id").asLong()).orElseThrow();
        assertEquals(ownerA.getId(), apiDocument.getUser().getId());

        MockMultipartFile webFile = new MockMultipartFile(
                "file", "AUTH_WEB_UPLOAD.txt", MediaType.TEXT_PLAIN_VALUE, "web".getBytes());
        mockMvc.perform(multipart("/upload-view")
                        .file(webFile)
                        .param("userId", ownerB.getId().toString())
                        .with(authentication(userA)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertEquals(beforeA + 2, documentRepository.findByUserId(ownerA.getId()).size());
        assertEquals(beforeB, documentRepository.findByUserId(ownerB.getId()).size());

        long totalBeforeAnonymousRequests = documentRepository.count();
        mockMvc.perform(multipart("/api/documents/upload").file(apiFile))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/upload-view").file(webFile))
                .andExpect(status().isForbidden());
        assertEquals(totalBeforeAnonymousRequests, documentRepository.count());
    }

    private Authentication createAuthentication(String prefix, User.Role role) {
        String suffix = UUID.randomUUID().toString();
        User user = User.builder()
                .username(prefix + "-" + suffix)
                .password("not-used")
                .email(prefix + "-" + suffix + "@example.test")
                .role(role)
                .isDeleted(false)
                .build();
        user = userRepository.saveAndFlush(user);
        CustomUserDetails principal = new CustomUserDetails(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private Document saveDocument(User owner, String marker, String summary) {
        return documentRepository.saveAndFlush(Document.builder()
                .title(marker)
                .originalFilename(marker + ".txt")
                .storagePath("test-only/" + marker + ".txt")
                .summary(summary)
                .user(owner)
                .status(Document.DocStatus.completed)
                .embeddingStatus(Document.EmbeddingStatus.completed)
                .chunkCount(0)
                .isDeleted(false)
                .build());
    }

    private Path createStoredSource(Document document) throws Exception {
        Path source = Path.of("target/deterministic-test/uploads")
                .resolve(document.getStoragePath()).toAbsolutePath();
        Files.createDirectories(source.getParent());
        Files.writeString(source, "restore fixture");
        return source;
    }
}
