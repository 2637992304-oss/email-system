package com.shixun.mail.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MailListVO {
    private Long mailId;
    private Long senderId;
    private String senderName;
    private String senderEmail;
    private List<String> receiverNames;
    private List<String> receiverEmails;
    private String subject;
    private String preview;
    @JsonProperty("isRead")
    private Boolean read;
    private Boolean hasAttachment;
    private LocalDateTime receiveTime;
    private LocalDateTime sendTime;
    private Integer priority;
    private Integer priorityScore;
    private Boolean spamFlag;
}
