package com.spe.smartdocjp.controller;

import com.spe.smartdocjp.common.ApiResponse;
import com.spe.smartdocjp.model.DTO.AuthRequest;
import com.spe.smartdocjp.model.DTO.AuthResponse;
import com.spe.smartdocjp.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户认证", description = "用户注册与登录，获取 JWT Token")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户注册", description = "注册新用户，如果用户名以 'admin' 开头，将自动赋予 ADMIN 权限")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody AuthRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.register(request), "注册成功"));
    }

    @Operation(summary = "用户登录", description = "使用用户名和密码登录，返回 JWT 访问令牌")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody AuthRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(request), "登录成功"));
    }
}
