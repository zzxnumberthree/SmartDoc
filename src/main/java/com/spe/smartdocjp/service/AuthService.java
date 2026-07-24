package com.spe.smartdocjp.service;

import com.spe.smartdocjp.model.DTO.AuthRequest;
import com.spe.smartdocjp.model.DTO.AuthResponse;
import com.spe.smartdocjp.model.entity.User;
import com.spe.smartdocjp.repository.UserRepository;
import com.spe.smartdocjp.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(AuthRequest request) {
        log.info("Registering new user: {}", request.getUsername());
        
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already exists");
        }

        User.Role role = request.getUsername().toLowerCase().startsWith("admin") ? User.Role.ADMIN : User.Role.USER;

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail() != null ? request.getEmail() : request.getUsername() + "@example.com")
                .role(role)
                .isDeleted(false)
                .build();

        user = userRepository.save(user);
        
        String token = jwtUtil.generateToken(user.getUsername(), user.getId(), user.getRole().name());
        
        return AuthResponse.builder()
                .token(token)
                .username(user.getUsername())
                .userId(user.getId())
                .role(user.getRole().name())
                .build();
    }

    public AuthResponse login(AuthRequest request) {
        log.info("Authenticating user: {}", request.getUsername());
        
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));

        String token = jwtUtil.generateToken(user.getUsername(), user.getId(), user.getRole().name());

        return AuthResponse.builder()
                .token(token)
                .username(user.getUsername())
                .userId(user.getId())
                .role(user.getRole().name())
                .build();
    }
}
