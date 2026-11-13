package com.shixun.common.security;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class AuthUserContext {
    private static final ThreadLocal<Map<String, Object>> HOLDER = ThreadLocal.withInitial(HashMap::new);

    private AuthUserContext() {
    }

    public static void set(String key, Object value) {
        HOLDER.get().put(key, value);
    }

    public static Object get(String key) {
        return HOLDER.get().get(key);
    }

    public static Long getUserId() {
        Object value = HOLDER.get().get("userId");
        return value == null ? null : (Long) value;
    }

    public static String getUsername() {
        Object value = HOLDER.get().get("username");
        return value == null ? null : value.toString();
    }

    public static Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(HOLDER.get());
    }

    public static void clear() {
        HOLDER.remove();
    }
}
