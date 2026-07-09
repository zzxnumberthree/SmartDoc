package com.spe.smartdocjp.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;


/**
 Global exception handler that intercepts and standardizes exceptions.
 <p>
 Converts exceptions into RFC 7807 (Problem Details) format for consistent API error responses.
 */
@RestControllerAdvice
@Slf4j
// 全局异常处理器, 拦截 Controller 层及 Filter 链中抛出的异常，统一转换为 RFC 7807 格式。
public class GlobalExceptionHandler {

    /**
     Handles file upload size limit exceptions.
     @param exc The thrown MaxUploadSizeExceededException.
     @return A ProblemDetail with HTTP 413 (Payload Too Large).
     */
    // 处理文件上传大小超限异常, 上传文件超过properties中配置的 max-file-size。响应状态：413 Payload Too Large
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxSizeException(MaxUploadSizeExceededException exc) {
        log.warn("File upload size exceeded: {}", exc.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "上传文件大小超过了系统允许的最大限制（10MB）。"
        );
        problemDetail.setTitle("File Upload Limit Exceeded");
        problemDetail.setType(URI.create("https://api.spe.smartdoc.com/errors/upload-size-exceeded"));
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    /**
     Handles 404 resource not found exceptions (e.g. favicon.ico or missing paths).
     @param exc The caught NoResourceFoundException.
     @return ResponseEntity with HTTP 404.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFoundException(org.springframework.web.servlet.resource.NoResourceFoundException exc) {
        log.debug("Resource not found handled gracefully (404): {}", exc.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     Handles 405 method not allowed exceptions.
     @param exc The caught HttpRequestMethodNotSupportedException.
     @return ResponseEntity with HTTP 405.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> handleMethodNotSupportedException(org.springframework.web.HttpRequestMethodNotSupportedException exc) {
        log.debug("Method not supported (405): {}", exc.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /**
     Handles all other unexpected exceptions.
     <p>
     Prevents internal stack traces from being exposed to the client while providing meaningful logs.
     @param exc The caught Exception.
     @return A ProblemDetail with HTTP 500 (Internal Server Error).
     */
    // 处理其他所有未预期的异常, 响应状态：500 Internal Server Error 防止原始堆栈信息泄露给前端，提高安全性。
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneralException(Exception exc) {
        log.error("Unhandled exception intercepted by GlobalExceptionHandler: ", exc);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "服务器内部发生意外错误，请联系管理员。" + (exc.getMessage() != null ? " (" + exc.getMessage() + ")" : "")
        );
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setType(URI.create("zzxnumberthree@gmail.com"));
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    /**
     Handles validation errors from @Valid annotated parameters.
     @param ex The MethodArgumentNotValidException.
     @return A ResponseEntity with HTTP 400 and a map of field errors.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> {
            errors.put(error.getField(), error.getDefaultMessage());
        });

        // 返回 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }


}