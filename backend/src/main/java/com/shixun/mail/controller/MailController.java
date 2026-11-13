package com.shixun.mail.controller;

import com.shixun.common.api.ApiResponse;
import com.shixun.mail.dto.SendMailRequest;
import com.shixun.mail.service.MailService;
import com.shixun.mail.vo.MailDetailVO;
import com.shixun.mail.vo.MailListVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mail")
public class MailController {

    private final MailService mailService;

    public MailController(MailService mailService) {
        this.mailService = mailService;
    }

    @PostMapping("/send")
    public ApiResponse<Map<String, Long>> sendMail(@RequestBody SendMailRequest request) {
        Long mailId = mailService.sendMail(request);
        return ApiResponse.success("发送成功", Map.of("mailId", mailId));
    }

    @GetMapping("/inbox")
    public ApiResponse<List<MailListVO>> getInbox() {
        return ApiResponse.success(mailService.getInbox());
    }

    @GetMapping("/sent")
    public ApiResponse<List<MailListVO>> getSentMails() {
        return ApiResponse.success(mailService.getSentMails());
    }

    @GetMapping("/trash")
    public ApiResponse<List<MailListVO>> getTrashMails() {
        return ApiResponse.success(mailService.getTrashMails());
    }

    @GetMapping("/detail/{mailId}")
    public ApiResponse<MailDetailVO> getMailDetail(@PathVariable Long mailId) {
        return ApiResponse.success(mailService.getMailDetail(mailId));
    }

    @DeleteMapping("/{mailId}")
    public ApiResponse<Map<String, Long>> deleteMail(@PathVariable Long mailId) {
        mailService.deleteMail(mailId);
        return ApiResponse.success("删除成功", Map.of("mailId", mailId));
    }

    @PutMapping("/restore/{mailId}")
    public ApiResponse<Map<String, Long>> restoreMail(@PathVariable Long mailId) {
        mailService.restoreMail(mailId);
        return ApiResponse.success("恢复成功", Map.of("mailId", mailId));
    }

    @DeleteMapping("/{mailId}/permanent")
    public ApiResponse<Map<String, Long>> permanentlyDeleteMail(@PathVariable Long mailId) {
        mailService.permanentlyDeleteMail(mailId);
        return ApiResponse.success("永久删除成功", Map.of("mailId", mailId));
    }

    @PutMapping("/read/{mailId}")
    public ApiResponse<Map<String, Object>> markAsRead(@PathVariable Long mailId) {
        mailService.markAsRead(mailId);
        return ApiResponse.success("已标记为已读", Map.of("mailId", mailId, "isRead", true));
    }

    @PutMapping("/unread/{mailId}")
    public ApiResponse<Map<String, Object>> markAsUnread(@PathVariable Long mailId) {
        mailService.markAsUnread(mailId);
        return ApiResponse.success("已标记为未读", Map.of("mailId", mailId, "isRead", false));
    }
}
