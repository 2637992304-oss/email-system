package com.shixun.user.service;

import com.shixun.common.exception.BusinessException;
import com.shixun.common.security.TokenService;
import com.shixun.common.util.PasswordUtils;
import com.shixun.user.dto.LoginRequest;
import com.shixun.user.dto.LoginResponse;
import com.shixun.user.dto.RegisterRequest;
import com.shixun.user.entity.User;
import com.shixun.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuthService {

    private static final int STATUS_NORMAL = 1;

    private final UserMapper userMapper;
    private final TokenService tokenService;

    public AuthService(UserMapper userMapper, TokenService tokenService) {
        this.userMapper = userMapper;
        this.tokenService = tokenService;
    }

    public void register(RegisterRequest request) {
        validateRegisterRequest(request);
        String email = normalizeEmail(request.getEmail());
        if (userMapper.findByEmail(email) != null) {
            throw BusinessException.of(409, "Email already registered");
        }

        User user = new User();
        user.setEmail(email);
        user.setUsername(request.getUsername().trim());
        user.setPassword(PasswordUtils.sha256(request.getPassword()));
        user.setRegisterTime(LocalDateTime.now());
        user.setStatus(STATUS_NORMAL);
        userMapper.insert(user);
    }

    public LoginResponse login(LoginRequest request) {
        validateLoginRequest(request);
        String email = normalizeEmail(request.getEmail());
        User user = userMapper.findByEmail(email);
        if (user == null || !PasswordUtils.matches(request.getPassword(), user.getPassword())) {
            throw BusinessException.of(401, "Invalid email or password");
        }
        if (!Integer.valueOf(STATUS_NORMAL).equals(user.getStatus())) {
            throw BusinessException.of(403, "Account is disabled");
        }

        String token = tokenService.createToken(user.getUserId(), user.getUsername(), "USER");
        return LoginResponse.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .email(user.getEmail())
                .token(token)
                .build();
    }

    private void validateRegisterRequest(RegisterRequest request) {
        if (request == null) {
            throw BusinessException.of(400, "Request body is required");
        }
        validateEmail(request.getEmail());
        requireLength(request.getUsername(), 2, 50, "Username length must be 2 to 50");
        requireLength(request.getPassword(), 6, 32, "Password length must be 6 to 32");
    }

    private void validateLoginRequest(LoginRequest request) {
        if (request == null) {
            throw BusinessException.of(400, "Request body is required");
        }
        validateEmail(request.getEmail());
        requireNotBlank(request.getPassword(), "Password is required");
    }

    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile(
                    "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    private void validateEmail(String email) {
        requireNotBlank(email, "Email is required");
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail == null || !EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw BusinessException.of(400, "Invalid email format");
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private void requireLength(String value, int min, int max, String message) {
        requireNotBlank(value, message);
        int length = value.trim().length();
        if (length < min || length > max) {
            throw BusinessException.of(400, message);
        }
    }

    private void requireNotBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw BusinessException.of(400, message);
        }
    }
}
