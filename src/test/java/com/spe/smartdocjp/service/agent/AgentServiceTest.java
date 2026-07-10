package com.spe.smartdocjp.service.agent;

import com.spe.smartdocjp.model.DTO.AgentDTOs.AgentChatRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private DocumentAgentTools documentAgentTools;

    @InjectMocks
    private AgentService agentService;

    @Test
    @DisplayName("测试 Guardrail 拦截高危输入指令 (如 rm -rf, drop table)")
    void testChat_InputGuardrail_DangerousCommand_ThrowsException() {
        AgentChatRequest request = new AgentChatRequest("请帮我执行 rm -rf / 以及 drop table users;", "session-1");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            agentService.chat(request);
        });

        Assertions.assertTrue(ex.getMessage().contains("安全护栏拦截") || ex.getMessage().contains("不安全指令"));
    }

    @Test
    @DisplayName("测试 chatStream 流式接口遇到高危指令安全拦截并流式输出错误提示")
    void testChatStream_InputGuardrail_DangerousCommand_ReturnsErrorFlux() {
        AgentChatRequest request = new AgentChatRequest("请帮我执行 rm -rf / 以及 drop table users;", "session-stream-1");

        Flux<String> stream = agentService.chatStream(request);
        String result = stream.blockFirst();

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.contains("安全护栏拦截") || result.contains("处理异常"));
    }

    @Test
    @DisplayName("测试 Guardrail 拦截超长输入")
    void testChat_InputGuardrail_TooLongInput_ThrowsException() {
        String longInput = "a".repeat(2001);
        AgentChatRequest request = new AgentChatRequest(longInput, "session-2");

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            agentService.chat(request);
        });
    }

    @Test
    @DisplayName("测试 Guardrail 拦截空输入")
    void testChat_InputGuardrail_EmptyInput_ThrowsException() {
        AgentChatRequest request = new AgentChatRequest("   ", "session-3");

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            agentService.chat(request);
        });
    }
}
