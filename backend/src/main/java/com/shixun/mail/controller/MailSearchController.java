package com.shixun.mail.controller;

import com.shixun.common.api.ApiResponse;
import com.shixun.mail.entity.Mail;
import com.shixun.mail.service.MailSearchService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/mail")
public class MailSearchController {

    private final MailSearchService mailSearchService;

    public MailSearchController(MailSearchService mailSearchService) {
        this.mailSearchService = mailSearchService;
    }

    @GetMapping("/search")
    public ApiResponse<List<Mail>> search(
            @RequestParam Long userId,
            @RequestParam String keyword) {
        return ApiResponse.success(mailSearchService.search(userId, keyword));
    }

    @GetMapping("/filter")
    public ApiResponse<List<Mail>> filter(
            @RequestParam Long userId,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(required = false) Boolean hasAttachment,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime startTime,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime endTime) {
        return ApiResponse.success(mailSearchService.filter(userId, isRead, hasAttachment, startTime, endTime));
    }
}
