package com.shixun.mail.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MailReceiver {
    private Long id;
    private Long mailId;
    private Long receiverId;
    private String receiverType;
    private Boolean read;
    private Boolean deleted;
    private LocalDateTime receiveTime;
}
