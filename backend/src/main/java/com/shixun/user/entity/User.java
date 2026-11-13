package com.shixun.user.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {
    private Long userId;
    private String email;
    private String username;
    private String password;
    private LocalDateTime registerTime;
    private Integer status;
}
