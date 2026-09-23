package com.spe.smartdocjp.service.agent;

import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentChatRequest;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentStreamErrorCode;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentStreamEvent;
import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentStreamEventType;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentStreamContractTest {

    private static final String CONVERSATION_ID = "stream-contract";
    private static final String RAW_SECRET =
            "jdbc:mysql://private-db password=SECRET GOOGLE_API_KEY=raw-key C:\\Users\\private";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successfulStreamEndsWithExactlyOneCompletionEvent() {
        StubStreamingChatModel model = new StubStreamingChatModel(() -> Flux.just(
                response("TOKEN_ONE "),
                response("TOKEN_TWO")));
        AgentService service = service(model);
        authenticate(42L);

        List<AgentStreamEvent> events = service.chatStream(
                        new AgentChatRequest("安全的流式请求", CONVERSATION_ID))
                .collectList()
                .block();

        assertEquals(3, events.size());
        assertEquals(List.of(
                        AgentStreamEventType.TOKEN,
                        AgentStreamEventType.TOKEN,
                        AgentStreamEventType.COMPLETE),
                events.stream().map(AgentStreamEvent::type).toList());
        assertEquals("TOKEN_ONE ", events.get(0).delta());
        assertEquals("TOKEN_TWO", events.get(1).delta());
        assertNull(events.get(2).errorCode());
        assertEquals(1, model.streamCalls());
    }

    @Test
    void streamIdleTimeoutBeforeFirstTokenCancelsProviderAndEmitsSafeTimeoutError() throws Exception {
        CountDownLatch providerSubscribed = new CountDownLatch(1);
        CountDownLatch providerCancelled = new CountDownLatch(1);
        StubStreamingChatModel model = new StubStreamingChatModel(
                () -> Flux.<ChatResponse>never()
                        .doOnSubscribe(ignored -> providerSubscribed.countDown())
                        .doOnCancel(providerCancelled::countDown));
        AgentService service = service(model);
        service.setStreamIdleTimeout(Duration.ofMillis(100));
        authenticate(42L);

        List<AgentStreamEvent> events = service.chatStream(
                        new AgentChatRequest("首个token超时测试", CONVERSATION_ID))
                .collectList()
                .block(Duration.ofSeconds(2));

        assertTrue(providerSubscribed.await(1, TimeUnit.SECONDS));
        assertTrue(providerCancelled.await(1, TimeUnit.SECONDS));
        assertEquals(1, events.size());
        AgentStreamEvent error = events.getFirst();
        assertEquals(AgentStreamEventType.ERROR, error.type());
        assertEquals(AgentStreamErrorCode.MODEL_TIMEOUT, error.errorCode());
        assertTrue(error.retryable());
        assertEquals("模型服务响应超时，请稍后重试。", error.message());
        assertFalse(error.errorId().isBlank());
        assertFalse(events.stream().anyMatch(event -> event.type() == AgentStreamEventType.COMPLETE));
        assertFalse(events.toString().contains("SECRET"));
        assertEquals(1, model.streamCalls());
    }

    @Test
    void streamIdleTimeoutBetweenTokensCancelsProviderAndEmitsSafeTimeoutErrorWithoutCompletion() throws Exception {
        CountDownLatch providerCancelled = new CountDownLatch(1);
        StubStreamingChatModel model = new StubStreamingChatModel(
                () -> Flux.concat(
                        Flux.just(response("SAFE_TOKEN_BEFORE_STALL")),
                        Flux.<ChatResponse>never().doOnCancel(providerCancelled::countDown)));
        AgentService service = service(model);
        service.setStreamIdleTimeout(Duration.ofMillis(100));
        authenticate(42L);

        List<AgentStreamEvent> events = service.chatStream(
                        new AgentChatRequest("Token间停顿超时测试", CONVERSATION_ID))
                .collectList()
                .block(Duration.ofSeconds(2));

        assertTrue(providerCancelled.await(1, TimeUnit.SECONDS));
        assertEquals(2, events.size());
        assertEquals(AgentStreamEventType.TOKEN, events.get(0).type());
        assertEquals("SAFE_TOKEN_BEFORE_STALL", events.get(0).delta());
        AgentStreamEvent error = events.get(1);
        assertEquals(AgentStreamEventType.ERROR, error.type());
        assertEquals(AgentStreamErrorCode.MODEL_TIMEOUT, error.errorCode());
        assertTrue(error.retryable());
        assertEquals("模型服务响应超时，请稍后重试。", error.message());
        assertFalse(error.errorId().isBlank());
        assertFalse(events.stream().anyMatch(event -> event.type() == AgentStreamEventType.COMPLETE));
        assertEquals(1, model.streamCalls());
    }

    @Test
    void activelyProducingStreamExceedingTotalDurationDoesNotTimeout() {
        StubStreamingChatModel model = new StubStreamingChatModel(() -> Flux.concat(
                Flux.just(response("PART_1")).delayElements(Duration.ofMillis(40)),
                Flux.just(response("PART_2")).delayElements(Duration.ofMillis(40)),
                Flux.just(response("PART_3")).delayElements(Duration.ofMillis(40))));
        AgentService service = service(model);
        service.setStreamIdleTimeout(Duration.ofMillis(100));
        authenticate(42L);

        List<AgentStreamEvent> events = service.chatStream(
                        new AgentChatRequest("持续产出不超时测试", CONVERSATION_ID))
                .collectList()
                .block(Duration.ofSeconds(2));

        assertEquals(4, events.size());
        assertEquals(List.of(
                        AgentStreamEventType.TOKEN,
                        AgentStreamEventType.TOKEN,
                        AgentStreamEventType.TOKEN,
                        AgentStreamEventType.COMPLETE),
                events.stream().map(AgentStreamEvent::type).toList());
        assertEquals("PART_1", events.get(0).delta());
        assertEquals("PART_2", events.get(1).delta());
        assertEquals("PART_3", events.get(2).delta());
        assertEquals(1, model.streamCalls());
    }

    @Test
    void timeoutAfterPartialOutputEmitsSafeTerminalErrorWithoutCompletion() {
        StubStreamingChatModel model = new StubStreamingChatModel(() -> Flux.concat(
                Flux.just(response("SAFE_PARTIAL_TOKEN")),
                Flux.error(new RuntimeException(RAW_SECRET, new TimeoutException(RAW_SECRET)))));
        AgentService service = service(model);
        authenticate(42L);

        List<AgentStreamEvent> events = service.chatStream(
                        new AgentChatRequest("触发部分输出后超时", CONVERSATION_ID))
                .collectList()
                .block();

        assertEquals(2, events.size());
        assertEquals(AgentStreamEventType.TOKEN, events.get(0).type());
        AgentStreamEvent error = events.get(1);
        assertEquals(AgentStreamEventType.ERROR, error.type());
        assertEquals(AgentStreamErrorCode.MODEL_TIMEOUT, error.errorCode());
        assertTrue(error.retryable());
        assertEquals("模型服务响应超时，请稍后重试。", error.message());
        assertFalse(error.errorId().isBlank());
        assertFalse(events.stream().anyMatch(event -> event.type() == AgentStreamEventType.COMPLETE));
        assertFalse(events.toString().contains("SECRET"));
        assertFalse(events.toString().contains("GOOGLE_API_KEY"));
        assertFalse(events.toString().contains("jdbc:mysql"));
    }

    @Test
    void providerFailureBeforeFirstTokenEmitsOnlySafeUnavailableError() {
        StubStreamingChatModel model = new StubStreamingChatModel(
                () -> Flux.error(new IllegalStateException(RAW_SECRET)));
        AgentService service = service(model);
        authenticate(42L);

        List<AgentStreamEvent> events = service.chatStream(
                        new AgentChatRequest("触发首个 token 前的服务失败", CONVERSATION_ID))
                .collectList()
                .block();

        assertEquals(1, events.size());
        AgentStreamEvent error = events.getFirst();
        assertEquals(AgentStreamEventType.ERROR, error.type());
        assertEquals(AgentStreamErrorCode.MODEL_UNAVAILABLE, error.errorCode());
        assertTrue(error.retryable());
        assertEquals("模型服务暂时不可用，请稍后重试。", error.message());
        assertFalse(error.errorId().isBlank());
        assertFalse(events.toString().contains("SECRET"));
        assertFalse(events.stream().anyMatch(event -> event.type() == AgentStreamEventType.COMPLETE));
        assertEquals(1, model.streamCalls());
    }

    @Test
    void guardrailFailureIsTypedAndDoesNotInvokeProvider() {
        StubStreamingChatModel model = new StubStreamingChatModel(
                () -> Flux.error(new AssertionError("provider must not be invoked")));
        AgentService service = service(model);
        authenticate(42L);

        List<AgentStreamEvent> events = service.chatStream(
                        new AgentChatRequest("请执行 rm -rf /", CONVERSATION_ID))
                .collectList()
                .block();

        assertEquals(1, events.size());
        AgentStreamEvent error = events.getFirst();
        assertEquals(AgentStreamEventType.ERROR, error.type());
        assertEquals(AgentStreamErrorCode.INPUT_REJECTED, error.errorCode());
        assertFalse(error.retryable());
        assertEquals(0, model.streamCalls());
    }

    private AgentService service(ChatModel chatModel) {
        DocumentAgentTools tools = new DocumentAgentTools(mock(RagService.class), mock(DocumentRepository.class));
        AgentService service = new AgentService(
                ChatClient.builder(chatModel),
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .build(),
                tools);
        ReflectionTestUtils.setField(
                service,
                "systemPromptResource",
                new ClassPathResource("prompts/agent-system.st"));
        return service;
    }

    private void authenticate(long userId) {
        User user = User.builder()
                .id(userId)
                .username("stream-contract-user")
                .password("not-used")
                .email("stream-contract@example.test")
                .role(User.Role.USER)
                .isDeleted(false)
                .build();
        CustomUserDetails principal = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static final class StubStreamingChatModel implements ChatModel {

        private final Supplier<Flux<ChatResponse>> streamSupplier;
        private final AtomicInteger streamCalls = new AtomicInteger();

        private StubStreamingChatModel(Supplier<Flux<ChatResponse>> streamSupplier) {
            this.streamSupplier = streamSupplier;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return response("unused-sync-response");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamCalls.incrementAndGet();
            return streamSupplier.get();
        }

        private int streamCalls() {
            return streamCalls.get();
        }
    }
}
