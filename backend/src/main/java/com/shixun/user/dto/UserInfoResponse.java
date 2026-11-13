package com.shixun.user.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserInfoResponse {
    private Long userId;
    private String email;
    private String username;
    private Integer status;
    private LocalDateTime registerTime;
}
