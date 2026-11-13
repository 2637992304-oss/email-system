package com.shixun.mail.dto;

import lombok.Data;
import java.util.List;

@Data
public class SendMailRequest {
    private List<String> to;
    private List<String> cc;
    private List<String> bcc;
    private String subject;
    private String content;
}
