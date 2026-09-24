package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.model.DTO.AgentDTOs.*;
import com.spe.smartdocjp.service.agent.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentControllerStreamTest {

    private static final String CONVERSATION_ID = "stream-test-conv";

    @Mock
    private AgentService agentService;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(agentService);
    }

    @Test
    @DisplayName("Heartbeat comment frame has correct shape with comment: heartbeat and no data or event name")
    void heartbeatCommentFrameHasCorrectShape() {
        controller.setHeartbeatInterval(Duration.ofMillis(30));
        when(agentService.chatStream(any(AgentChatRequest.class))).thenReturn(Flux.never());

        AgentChatRequest request = new AgentChatRequest("测试心跳形状", CONVERSATION_ID);

        ServerSentEvent<AgentStreamEvent> sse = controller.chatStreamPost(request)
                .blockFirst(Duration.ofSeconds(1));
        assertNotNull(sse);
        assertEquals("heartbeat", sse.comment());
        assertNull(sse.event());
        assertNull(sse.data());
        assertNull(sse.id());
    }

    @Test
    @DisplayName("Delayed service stream emits heartbeats while waiting and preserves token order and complete event")
    void delayedServiceStreamEmitsHeartbeatsAndPreservesTokenOrderAndComplete() {
        controller.setHeartbeatInterval(Duration.ofMillis(30));

        Flux<AgentStreamEvent> serviceFlux = Flux.concat(
                Flux.just(AgentStreamEvent.token(CONVERSATION_ID, "PART_1")).delayElements(Duration.ofMillis(80)),
                Flux.just(AgentStreamEvent.token(CONVERSATION_ID, "PART_2")).delayElements(Duration.ofMillis(80)),
                Flux.just(AgentStreamEvent.complete(CONVERSATION_ID))
        );
        when(agentService.chatStream(any(AgentChatRequest.class))).thenReturn(serviceFlux);

        AgentChatRequest request = new AgentChatRequest("延迟流测试", CONVERSATION_ID);

        List<ServerSentEvent<AgentStreamEvent>> received = controller.chatStreamPost(request)
                .collectList().block(Duration.ofSeconds(3));
        assertNotNull(received);

        List<ServerSentEvent<AgentStreamEvent>> dataEvents = received.stream()
                .filter(sse -> sse.event() != null)
                .toList();

        assertEquals(3, dataEvents.size());
        assertEquals("token", dataEvents.get(0).event());
        assertEquals("PART_1", dataEvents.get(0).data().delta());
        assertEquals("token", dataEvents.get(1).event());
        assertEquals("PART_2", dataEvents.get(1).data().delta());
        assertEquals("complete", dataEvents.get(2).event());

        List<ServerSentEvent<AgentStreamEvent>> heartbeats = received.stream()
                .filter(sse -> "heartbeat".equals(sse.comment()))
                .toList();

        assertFalse(heartbeats.isEmpty(), "Heartbeats should be emitted during delays");
        for (ServerSentEvent<AgentStreamEvent> hb : heartbeats) {
            assertEquals("heartbeat", hb.comment());
            assertNull(hb.event());
            assertNull(hb.data());
        }

        assertEquals("complete", received.getLast().event());
    }

    @Test
    @DisplayName("Heartbeat stops immediately on terminal complete event and does not outlive it")
    void heartbeatStopsOnCompleteEvent() {
        controller.setHeartbeatInterval(Duration.ofMillis(20));

        when(agentService.chatStream(any(AgentChatRequest.class))).thenReturn(Flux.concat(
                Flux.just(AgentStreamEvent.token(CONVERSATION_ID, "INSTANT_TOKEN")),
                Flux.just(AgentStreamEvent.complete(CONVERSATION_ID))
        ));

        AgentChatRequest request = new AgentChatRequest("立即完成测试", CONVERSATION_ID);

        List<ServerSentEvent<AgentStreamEvent>> events = controller.chatStreamPost(request)
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("token", events.get(0).event());
        assertEquals("complete", events.get(1).event());
        assertFalse(events.stream().anyMatch(e -> "heartbeat".equals(e.comment())));
    }

    @Test
    @DisplayName("Heartbeat stops immediately on terminal error event and provider timeout produces terminal error")
    void heartbeatStopsOnErrorEventAndPreservesErrorShape() {
        controller.setHeartbeatInterval(Duration.ofMillis(30));

        AgentStreamEvent timeoutError = AgentStreamEvent.error(
                CONVERSATION_ID,
                AgentStreamErrorCode.MODEL_TIMEOUT,
                "模型服务响应超时，请稍后重试。",
                true,
                "err-timeout-123"
        );

        Flux<AgentStreamEvent> serviceFlux = Flux.concat(
                Flux.just(AgentStreamEvent.token(CONVERSATION_ID, "BEFORE_TIMEOUT")),
                Flux.just(timeoutError).delayElements(Duration.ofMillis(80))
        );
        when(agentService.chatStream(any(AgentChatRequest.class))).thenReturn(serviceFlux);

        AgentChatRequest request = new AgentChatRequest("超时错误测试", CONVERSATION_ID);

        List<ServerSentEvent<AgentStreamEvent>> received = controller.chatStreamPost(request)
                .collectList().block(Duration.ofSeconds(3));
        assertNotNull(received);

        ServerSentEvent<AgentStreamEvent> lastEvent = received.getLast();
        assertEquals("error", lastEvent.event());
        assertNotNull(lastEvent.data());
        assertEquals(AgentStreamErrorCode.MODEL_TIMEOUT, lastEvent.data().errorCode());
        assertEquals("err-timeout-123", lastEvent.data().errorId());
        assertTrue(lastEvent.data().retryable());

        assertTrue(received.stream().anyMatch(sse -> "heartbeat".equals(sse.comment())));
        assertEquals("error", received.getLast().event());
        assertFalse(received.stream().anyMatch(sse -> sse.event() != null && "complete".equals(sse.event())));
    }

    @Test
    @DisplayName("Downstream cancellation stops heartbeats and propagates cancellation cleanup to upstream service stream")
    void downstreamCancellationCleansUpUpstreamAndStopsHeartbeats() throws Exception {
        controller.setHeartbeatInterval(Duration.ofMillis(25));

        CountDownLatch serviceSubscribed = new CountDownLatch(1);
        CountDownLatch serviceCancelled = new CountDownLatch(1);

        Flux<AgentStreamEvent> neverFlux = Flux.<AgentStreamEvent>never()
                .doOnSubscribe(s -> serviceSubscribed.countDown())
                .doOnCancel(serviceCancelled::countDown);

        when(agentService.chatStream(any(AgentChatRequest.class))).thenReturn(neverFlux);

        AgentChatRequest request = new AgentChatRequest("取消清理测试", CONVERSATION_ID);

        Flux<ServerSentEvent<AgentStreamEvent>> stream = controller.chatStreamPost(request);

        AtomicInteger heartbeatsReceived = new AtomicInteger();
        Disposable subscription = stream.subscribe(sse -> {
            if ("heartbeat".equals(sse.comment())) {
                heartbeatsReceived.incrementAndGet();
            }
        });

        assertTrue(serviceSubscribed.await(1, TimeUnit.SECONDS), "Upstream service stream should be subscribed");

        Thread.sleep(40);

        subscription.dispose();

        assertTrue(serviceCancelled.await(1, TimeUnit.SECONDS), "Upstream service stream must receive cancellation");

        int countAtDispose = heartbeatsReceived.get();
        Thread.sleep(60);
        assertEquals(countAtDispose, heartbeatsReceived.get(), "No heartbeats should be emitted after disposal");
    }

    @Test
    @DisplayName("Service stream is subscribed to exactly once per client subscription")
    void serviceStreamSubscribedExactlyOnce() {
        controller.setHeartbeatInterval(Duration.ofMillis(30));

        AtomicInteger subscriptions = new AtomicInteger();
        Flux<AgentStreamEvent> serviceFlux = Flux.concat(
                Flux.just(AgentStreamEvent.token(CONVERSATION_ID, "TOKEN_ONE")),
                Flux.just(AgentStreamEvent.complete(CONVERSATION_ID))
        ).doOnSubscribe(s -> subscriptions.incrementAndGet());

        when(agentService.chatStream(any(AgentChatRequest.class))).thenReturn(serviceFlux);

        AgentChatRequest request = new AgentChatRequest("单次订阅测试", CONVERSATION_ID);

        List<ServerSentEvent<AgentStreamEvent>> received = controller.chatStreamPost(request)
                .collectList().block(Duration.ofSeconds(1));
        assertNotNull(received);
        assertEquals(List.of("token", "complete"), received.stream()
                .map(ServerSentEvent::event).toList());

        assertEquals(1, subscriptions.get(), "Service stream must be subscribed to exactly once");
    }

    @Test
    @DisplayName("chatStreamGet uses shared transport mapper and emits heartbeats and named events")
    void chatStreamGetUsesSharedTransportMapper() {
        controller.setHeartbeatInterval(Duration.ofMillis(30));

        Flux<AgentStreamEvent> serviceFlux = Flux.concat(
                Flux.just(AgentStreamEvent.token(CONVERSATION_ID, "GET_TOKEN")).delayElements(Duration.ofMillis(70)),
                Flux.just(AgentStreamEvent.complete(CONVERSATION_ID))
        );
        when(agentService.chatStream(any(AgentChatRequest.class))).thenReturn(serviceFlux);

        List<ServerSentEvent<AgentStreamEvent>> received = controller
                .chatStreamGet("GET请求测试", CONVERSATION_ID)
                .collectList().block(Duration.ofSeconds(3));
        assertNotNull(received);
        assertTrue(received.stream().anyMatch(sse -> "heartbeat".equals(sse.comment())));
        List<ServerSentEvent<AgentStreamEvent>> dataEvents = received.stream()
                .filter(sse -> sse.event() != null).toList();
        assertEquals(List.of("token", "complete"), dataEvents.stream()
                .map(ServerSentEvent::event).toList());
        assertEquals("GET_TOKEN", dataEvents.getFirst().data().delta());

        verify(agentService, times(1)).chatStream(argThat(req ->
                "GET请求测试".equals(req.message()) && CONVERSATION_ID.equals(req.conversationId())));
    }

    @Test
    @DisplayName("Invalid or non-positive heartbeat interval safely falls back to default 15s")
    void invalidOrNonPositiveIntervalSafelyFallsBackToDefault() {
        controller.setHeartbeatInterval(Duration.ZERO);
        assertEquals(Duration.ofSeconds(15), controller.getHeartbeatInterval());

        controller.setHeartbeatInterval(Duration.ofSeconds(-10));
        assertEquals(Duration.ofSeconds(15), controller.getHeartbeatInterval());

        controller.setHeartbeatInterval(null);
        assertEquals(Duration.ofSeconds(15), controller.getHeartbeatInterval());

        controller.setHeartbeatInterval(Duration.ofSeconds(5));
        assertEquals(Duration.ofSeconds(5), controller.getHeartbeatInterval());
    }

    @Test
    @DisplayName("Heartbeat does not emit immediately on subscription, only after interval")
    void heartbeatDoesNotEmitImmediately() {
        controller.setHeartbeatInterval(Duration.ofMillis(200));

        Flux<AgentStreamEvent> fastService = Flux.concat(
                Flux.just(AgentStreamEvent.token(CONVERSATION_ID, "FAST_TOKEN")),
                Flux.just(AgentStreamEvent.complete(CONVERSATION_ID))
        );
        when(agentService.chatStream(any(AgentChatRequest.class))).thenReturn(fastService);

        List<ServerSentEvent<AgentStreamEvent>> events = controller.chatStreamPost(
                        new AgentChatRequest("快速响应测试", CONVERSATION_ID))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("token", events.get(0).event());
        assertEquals("complete", events.get(1).event());
        assertEquals(0, events.stream().filter(e -> e.comment() != null).count());
    }
}
