package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.model.DTO.AuthRequest;
import com.spe.smartdocjp.service.AuthService;
import com.spe.smartdocjp.support.DeterministicAiTestConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("deterministic-test")
@Import(DeterministicAiTestConfiguration.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    @DisplayName("Registration OpenAPI @Operation description does not advertise username-based administrator assignment")
    void register_OpenApiDescription_DoesNotClaimAdminPrivilege() throws Exception {
        Method method = AuthController.class.getMethod("register", AuthRequest.class);
        Operation operation = method.getAnnotation(Operation.class);

        assertThat(operation).isNotNull();
        assertThat(operation.description())
                .doesNotContain("admin")
                .doesNotContain("ADMIN");
        assertThat(operation.description()).isEqualTo("注册新用户并分配标准 USER 角色");
    }

    @Test
    @DisplayName("POST /api/auth/register with empty body produces HTTP 400 ProblemDetail with fieldErrors and timestamp")
    void register_WhenEmptyBody_ReturnsProblemDetail400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.type").value("https://api.spe.smartdoc.com/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("请求参数验证失败"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors.username").value("Username cannot be empty"))
                .andExpect(jsonPath("$.fieldErrors.password").value("Password cannot be empty"));
    }

    @Test
    @DisplayName("POST /api/auth/register with invalid email produces 400 ProblemDetail and does not echo rejected values")
    void register_WhenInvalidEmail_ReturnsFieldErrorsWithoutEchoingRejectedValues() throws Exception {
        String invalidEmail = "not-a-valid-email-format-12345";
        String sensitivePassword = "MySecretPassword123!";
        String payload = """
                {
                    "username": "validUser",
                    "password": "%s",
                    "email": "%s"
                }
                """.formatted(sensitivePassword, invalidEmail);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.type").value("https://api.spe.smartdoc.com/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.email").value("Email should be valid"))
                .andExpect(content().string(not(containsString(invalidEmail))))
                .andExpect(content().string(not(containsString("MethodArgumentNotValidException"))))
                .andExpect(content().string(not(containsString("org.springframework"))));
    }

    @Test
    @DisplayName("POST /api/auth/login with invalid input produces HTTP 400 ProblemDetail")
    void login_WhenInvalidInput_ReturnsProblemDetail400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.type").value("https://api.spe.smartdoc.com/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("请求参数验证失败"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors.username").value("Username cannot be empty"))
                .andExpect(jsonPath("$.fieldErrors.password").value("Password cannot be empty"));
    }
}
