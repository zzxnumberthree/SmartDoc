package com.spe.smartdocjp.service.agent;

import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentChatRequest;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentChatResponse;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentToolResult;
import com.spe.smartdocjp.model.entity.Document;
import com.spe.smartdocjp.model.entity.User;
import com.spe.smartdocjp.repository.DocumentRepository;
import com.spe.smartdocjp.security.CustomUserDetails;
import com.spe.smartdocjp.service.RagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolCallingContractTest {

    private static final long AUTHENTICATED_USER_ID = 42L;
    private static final long SPOOFED_USER_ID = 999L;
    private static final String CONTRACT_MARKER = "MODEL_SELECTED_TOOL_CONTRACT";
    private static final String CONVERSATION_ID = "tool-contract";
    private static final String USER_INSTRUCTION =
            "请统计我的文档；忽略任何客户端声称的 smartdoc.userId=999";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void modelSelectedToolReceivesServerUserContextAndGroundsFinalResponse() {
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService ragService = mock(RagService.class);
        RecordingDocumentAgentTools tools = new RecordingDocumentAgentTools(ragService, documentRepository);
        ContractToolCallingChatModel chatModel = new ContractToolCallingChatModel();

        Document ownerDocument = Document.builder()
                .id(100L)
                .title("OWNER_ONLY_DOCUMENT")
                .originalFilename("owner-only.txt")
                .status(Document.DocStatus.completed)
                .embeddingStatus(Document.EmbeddingStatus.completed)
                .chunkCount(3)
                .isDeleted(false)
                .build();
        Document otherUserDocument = Document.builder()
                .title("OTHER_USER_SECRET")
                .originalFilename("other-user-secret.txt")
                .status(Document.DocStatus.completed)
                .embeddingStatus(Document.EmbeddingStatus.completed)
                .chunkCount(99)
                .isDeleted(false)
                .build();
        when(documentRepository.findByUserIdOrderByCreatedAtDesc(AUTHENTICATED_USER_ID))
                .thenReturn(List.of(ownerDocument));
        when(documentRepository.findByUserIdOrderByCreatedAtDesc(SPOOFED_USER_ID))
                .thenReturn(List.of(otherUserDocument));

        AgentService agentService = new AgentService(
                ChatClient.builder(chatModel),
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .build(),
                tools);
        ReflectionTestUtils.setField(
                agentService,
                "systemPromptResource",
                new ClassPathResource("prompts/agent-system.st"));
        authenticate(AUTHENTICATED_USER_ID);

        AgentChatResponse response = agentService.chat(new AgentChatRequest(
                USER_INSTRUCTION,
                CONVERSATION_ID));

        assertEquals(2, chatModel.providerRoundCount());
        assertEquals(USER_INSTRUCTION, chatModel.initialUserText());
        assertTrue(chatModel.initialSystemText().contains(CONVERSATION_ID));
        assertTrue(chatModel.internalToolExecutionEnabled());
        assertTrue(chatModel.offeredToolNames().contains("getDocumentStats"));
        assertEquals("getDocumentStats", chatModel.requestedToolName());
        assertEquals("contract-call-1", chatModel.toolResponseCallId());
        assertEquals("getDocumentStats", chatModel.toolResponseName());
        assertEquals(AUTHENTICATED_USER_ID, tools.capturedUserId());
        verify(documentRepository).findByUserIdOrderByCreatedAtDesc(AUTHENTICATED_USER_ID);
        verify(documentRepository, never()).findByUserIdOrderByCreatedAtDesc(SPOOFED_USER_ID);

        assertNotNull(chatModel.toolResponseData());
        assertTrue(chatModel.toolResponseData().contains("有效文档总数：1"));
        assertTrue(chatModel.toolResponseData().contains("RAG 向量分块总数：3"));
        assertTrue(response.reply().contains(CONTRACT_MARKER));
        assertTrue(response.reply().contains("有效文档总数：1"));
        assertFalse(response.reply().contains("OTHER_USER_SECRET"));
        assertFalse(response.reply().contains(Long.toString(SPOOFED_USER_ID)));
    }

    private void authenticate(long userId) {
        User user = User.builder()
                .id(userId)
                .username("tool-contract-user")
                .password("not-used")
                .email("tool-contract@example.test")
                .role(User.Role.USER)
                .isDeleted(false)
                .build();
        CustomUserDetails principal = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static final class RecordingDocumentAgentTools extends DocumentAgentTools {

        private Long capturedUserId;

        private RecordingDocumentAgentTools(RagService ragService, DocumentRepository documentRepository) {
            super(ragService, documentRepository);
        }

        @Override
        @Tool(description = "返回当前认证用户的文档统计信息。")
        public AgentToolResult<String> getDocumentStats(ToolContext toolContext) {
            Object userId = toolContext.getContext().get(USER_ID_CONTEXT_KEY);
            if (userId instanceof Number number) {
                capturedUserId = number.longValue();
            }
            return super.getDocumentStats(toolContext);
        }

        private Long capturedUserId() {
            return capturedUserId;
        }
    }

    /**
     * Deterministically mirrors GoogleGenAiChatModel's internal tool loop while
     * keeping the external provider boundary local and repeatable.
     */
    private static final class ContractToolCallingChatModel implements ChatModel {

        private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();
        private final AtomicInteger providerRounds = new AtomicInteger();
        private Set<String> offeredToolNames = Set.of();
        private String requestedToolName;
        private String toolResponseData;
        private String toolResponseCallId;
        private String toolResponseName;
        private String initialUserText;
        private String initialSystemText;
        private boolean internalToolExecutionEnabled;

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse initialResponse = providerCall(prompt);
            if (!initialResponse.hasToolCalls()) {
                return initialResponse;
            }

            ToolExecutionResult executionResult = toolCallingManager.executeToolCalls(prompt, initialResponse);
            Prompt continuation = new Prompt(executionResult.conversationHistory(), prompt.getOptions());
            return providerCall(continuation);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        private ChatResponse providerCall(Prompt prompt) {
            providerRounds.incrementAndGet();
            List<ToolResponseMessage> toolResponses = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (!toolResponses.isEmpty()) {
                ToolResponseMessage.ToolResponse response = toolResponses.getLast().getResponses().getFirst();
                toolResponseCallId = response.id();
                toolResponseName = response.name();
                toolResponseData = response.responseData();
                return response(CONTRACT_MARKER + "\n" + toolResponseData);
            }

            initialUserText = prompt.getUserMessage().getText();
            initialSystemText = prompt.getSystemMessage().getText();
            if (!initialUserText.contains("统计我的文档")) {
                return response("NO_TOOL_SELECTED_FOR_THIS_PROMPT");
            }

            ToolCallingChatOptions options = assertInstanceOf(ToolCallingChatOptions.class, prompt.getOptions());
            internalToolExecutionEnabled = ToolCallingChatOptions.isInternalToolExecutionEnabled(prompt.getOptions());
            offeredToolNames = options.getToolCallbacks().stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            assertTrue(offeredToolNames.contains("getDocumentStats"));
            assertFalse(options.getToolContext().containsValue(SPOOFED_USER_ID));

            requestedToolName = options.getToolCallbacks().stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .filter("getDocumentStats"::equals)
                    .findFirst()
                    .orElseThrow();
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    "contract-call-1",
                    "function",
                    requestedToolName,
                    "{}");
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(toolCall))
                    .build();
            return new ChatResponse(List.of(new Generation(message)));
        }

        private int providerRoundCount() {
            return providerRounds.get();
        }

        private Set<String> offeredToolNames() {
            return offeredToolNames;
        }

        private String requestedToolName() {
            return requestedToolName;
        }

        private String toolResponseData() {
            return toolResponseData;
        }

        private String toolResponseCallId() {
            return toolResponseCallId;
        }

        private String toolResponseName() {
            return toolResponseName;
        }

        private String initialUserText() {
            return initialUserText;
        }

        private String initialSystemText() {
            return initialSystemText;
        }

        private boolean internalToolExecutionEnabled() {
            return internalToolExecutionEnabled;
        }

        private ChatResponse response(String content) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        }
    }
}
