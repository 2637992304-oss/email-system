package com.shixun.common.security;

import com.shixun.common.api.ApiResponse;
import com.shixun.common.util.JsonUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;

    public AuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = request.getHeader("Authorization");
        if (token == null || token.isBlank()) {
            // SSE / EventSource 无法设置 Header，降级从 query param 获取
            token = request.getParameter("token");
        }
        if (token == null || token.isBlank()) {
            writeUnauthorized(response);
            return false;
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        TokenService.TokenPayload payload = tokenService.verify(token);
        if (payload == null) {
            writeUnauthorized(response);
            return false;
        }
        AuthUserContext.set("userId", payload.userId());
        AuthUserContext.set("username", payload.username());
        AuthUserContext.set("role", payload.role());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthUserContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(JsonUtils.toJson(ApiResponse.failure(401, "Unauthorized")));
    }
}
