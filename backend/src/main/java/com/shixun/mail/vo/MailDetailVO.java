package com.shixun.mail.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MailDetailVO {
    private Long mailId;
    private Long senderId;
    private String senderName;
    private String senderEmail;
    private List<String> to;
    private List<String> cc;
    private String subject;
    private String content;
    private LocalDateTime sendTime;
    @JsonProperty("isRead")
    private Boolean read;
    private Boolean hasAttachment;
    private Integer priority;
    private Boolean spamFlag;
    /** 垃圾邮件识别类型: KEYWORD / FREQUENCY / SENDER / DL / USER_REPORT */
    private String spamType;
    /** 垃圾邮件识别原因描述 */
    private String spamReason;
    private List<AttachmentVO> attachments;
}
