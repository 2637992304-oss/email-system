package com.shixun.attachment.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Attachment {
    private Long attachmentId;
    private Long mailId;
    private Long uploaderId;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String fileType;
    private LocalDateTime uploadTime;
}
