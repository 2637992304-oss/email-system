package com.shixun.common.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TokenService {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JwtProperties properties;
    private final ObjectMapper objectMapper;

    public TokenService(JwtProperties properties) {
        if (properties.getJwtSecret() == null || properties.getJwtSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
    }

    public String createToken(Long userId, String username, String role) {
        long now = Instant.now().getEpochSecond();
        long expireAt = now + properties.getTokenExpireMinutes() * 60;

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", userId);
        payload.put("username", username);
        payload.put("role", role);
        payload.put("iat", now);
        payload.put("exp", expireAt);

        String headerPart = base64UrlEncode(toJsonBytes(header));
        String payloadPart = base64UrlEncode(toJsonBytes(payload));
        String unsignedToken = headerPart + "." + payloadPart;
        String signature = sign(unsignedToken);
        return unsignedToken + "." + signature;
    }

    public TokenPayload verify(String token) {
        try {
            if (token == null || token.isBlank()) {
                return null;
            }

            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }

            String unsignedToken = parts[0] + "." + parts[1];
            String expectedSignature = sign(unsignedToken);
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                return null;
            }

            Map<String, Object> payload = fromJson(base64UrlDecode(parts[1]));
            long expireAt = getLong(payload.get("exp"));
            if (expireAt < Instant.now().getEpochSecond()) {
                return null;
            }

            return new TokenPayload(
                    getLong(payload.get("sub")),
                    getString(payload.get("username")),
                    getString(payload.get("role")),
                    expireAt * 1000
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private byte[] toJsonBytes(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JWT json serialize failed", ex);
        }
    }

    private Map<String, Object> fromJson(byte[] json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("JWT json parse failed", ex);
        }
    }

    private String sign(String unsignedToken) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec key = new SecretKeySpec(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(key);
            return base64UrlEncode(mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT sign failed", ex);
        }
    }

    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private boolean constantTimeEquals(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        if (leftBytes.length != rightBytes.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < leftBytes.length; i++) {
            result |= leftBytes[i] ^ rightBytes[i];
        }
        return result == 0;
    }

    private long getLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String getString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record TokenPayload(Long userId, String username, String role, long expireAt) {
    }
}
