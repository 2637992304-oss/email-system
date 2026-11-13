package com.shixun.mail.vo;

import lombok.Data;

@Data
public class AttachmentVO {
    private Long attachmentId;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String downloadUrl;
}
