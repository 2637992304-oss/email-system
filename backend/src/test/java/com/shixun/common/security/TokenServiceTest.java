package com.shixun.common.security;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TokenServiceTest {
    private JwtProperties properties() {
        JwtProperties properties = new JwtProperties();
        properties.setJwtSecret(UUID.randomUUID().toString());
        return properties;
    }

    @Test
    void refusesMissingOrWeakSecret() {
        JwtProperties p = new JwtProperties();
        assertThrows(IllegalArgumentException.class, () -> new TokenService(p));
        p.setJwtSecret("short");
        assertThrows(IllegalArgumentException.class, () -> new TokenService(p));
    }

    @Test
    void verifiesOwnTokensButRejectsTamperingAndOtherSigningKeys() {
        TokenService service = new TokenService(properties());
        String token = service.createToken(1L, "synthetic-user", "USER");
        assertEquals(1L, service.verify(token).userId());
        String[] parts = token.split("\\.");
        assertNull(service.verify(parts[0] + "." + parts[1] + ".invalid"));
        assertNull(new TokenService(properties()).verify(token));
    }
}
