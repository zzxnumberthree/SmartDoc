package com.spe.smartdocjp.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("MethodArgumentNotValidException returns RFC 7807 ProblemDetail with structured fieldErrors and retains first duplicate error")
    void handleValidationExceptions_ReturnsProblemDetail() throws Exception {
        Object target = new Object();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "authRequest");
        bindingResult.addError(new FieldError("authRequest", "username", "bad-user", false, null, null, "Username cannot be empty"));
        bindingResult.addError(new FieldError("authRequest", "username", "bad-user", false, null, null, "Duplicate rule should be ignored"));
        bindingResult.addError(new FieldError("authRequest", "password", "secret123", false, null, null, "Password cannot be empty"));

        Method method = this.getClass().getDeclaredMethod("dummyMethod", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ProblemDetail problemDetail = handler.handleValidationExceptions(ex);

        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Validation Failed");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.spe.smartdoc.com/errors/validation-failed"));
        assertThat(problemDetail.getDetail()).isEqualTo("请求参数验证失败");
        assertThat(problemDetail.getProperties()).containsKey("timestamp");
        assertThat(problemDetail.getProperties().get("timestamp")).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, String> fieldErrors = (Map<String, String>) problemDetail.getProperties().get("fieldErrors");
        assertThat(fieldErrors).isNotNull();
        assertThat(fieldErrors).hasSize(2);
        assertThat(fieldErrors.get("username")).isEqualTo("Username cannot be empty");
        assertThat(fieldErrors.get("password")).isEqualTo("Password cannot be empty");

        // Redaction verification: rejected input values must not be exposed in fieldErrors
        assertThat(fieldErrors.values()).doesNotContain("bad-user");
        assertThat(fieldErrors.values()).doesNotContain("secret123");
    }

    @Test
    @DisplayName("DocumentNotFoundException returns 404 ProblemDetail with expected title and message")
    void handleDocumentNotFound_Returns404ProblemDetail() {
        ProblemDetail problemDetail = handler.handleDocumentNotFound(new DocumentNotFoundException());

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Document Not Found");
        assertThat(problemDetail.getDetail()).isEqualTo("文档未找到");
        assertThat(problemDetail.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("AccessDeniedException returns 403 ProblemDetail with expected title and message")
    void handleAccessDenied_Returns403ProblemDetail() {
        ProblemDetail problemDetail = handler.handleAccessDenied(new AccessDeniedException("Access denied"));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Forbidden");
        assertThat(problemDetail.getDetail()).isEqualTo("无权访问该资源");
        assertThat(problemDetail.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("General Exception returns redacted 500 ProblemDetail without exposing raw exception details")
    void handleGeneralException_ReturnsRedacted500ProblemDetail() {
        Exception rawException = new RuntimeException("Sensitive database connection details or stack trace");
        ProblemDetail problemDetail = handler.handleGeneralException(rawException);

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Internal Server Error");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.spe.smartdoc.com/errors/internal-server-error"));
        assertThat(problemDetail.getDetail()).isEqualTo("服务器内部发生意外错误，请联系管理员。");
        assertThat(problemDetail.getDetail()).doesNotContain("Sensitive");
        assertThat(problemDetail.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("MaxUploadSizeExceededException returns 413 ProblemDetail")
    void handleMaxSizeException_Returns413ProblemDetail() {
        ProblemDetail problemDetail = handler.handleMaxSizeException(new MaxUploadSizeExceededException(10 * 1024 * 1024));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(problemDetail.getTitle()).isEqualTo("File Upload Limit Exceeded");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.spe.smartdoc.com/errors/upload-size-exceeded"));
        assertThat(problemDetail.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("IllegalArgumentException returns 400 ProblemDetail")
    void handleIllegalArgumentException_Returns400ProblemDetail() {
        ProblemDetail problemDetail = handler.handleIllegalArgumentException(new IllegalArgumentException("Invalid argument provided"));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Bad Request");
        assertThat(problemDetail.getDetail()).isEqualTo("Invalid argument provided");
        assertThat(problemDetail.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("AuthenticationException returns 401 ProblemDetail")
    void handleAuthenticationException_Returns401ProblemDetail() {
        ProblemDetail problemDetail = handler.handleAuthenticationException(new BadCredentialsException("Bad credentials"));

        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Unauthorized");
        assertThat(problemDetail.getDetail()).isEqualTo("用户名或密码错误");
        assertThat(problemDetail.getProperties()).containsKey("timestamp");
    }

    private void dummyMethod(String param) {}
}
