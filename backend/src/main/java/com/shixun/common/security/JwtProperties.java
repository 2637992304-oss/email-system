package com.shixun.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "mail.security")
public class JwtProperties {
    private String jwtSecret;
    private Long tokenExpireMinutes = 120L;
    private List<String> whiteList;
}
