package com.shixun;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class MailSystemApplicationTests {

    @DynamicPropertySource
    static void testSecrets(DynamicPropertyRegistry registry) {
        String secret = java.util.UUID.randomUUID().toString();
        registry.add("mail.security.jwt-secret", () -> secret);
    }

    @Test
    void contextLoads() {
    }
}
