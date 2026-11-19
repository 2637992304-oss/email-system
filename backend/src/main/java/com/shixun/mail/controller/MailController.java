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
import org.springframework.web.bind.annotation.RequestParam;
import com.shixun.mail.vo.CursorPage;
import java.util.Map;

@RestController
@RequestMapping("/api/mail")
public class MailController {

    private final MailService mailService;

    public MailController(MailService mailService) {
        this.mailService = mailService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.shixun.concurrency.ReliableDelivery> delivery;
    @PostMapping("/send")
    public org.springframework.http.ResponseEntity<?> sendMail(@RequestBody SendMailRequest request,
        @org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String key) {
        var reliable=delivery.getIfAvailable();
        if(reliable!=null) return org.springframework.http.ResponseEntity.accepted().body(ApiResponse.success("已提交",reliable.submit(request,key)));
        Long mailId=mailService.sendMail(request);
        return org.springframework.http.ResponseEntity.ok(ApiResponse.success("已送达",Map.of("mailId",mailId,"status","DELIVERED")));
    }
    @GetMapping("/{mailId}/status")
    public ApiResponse<?> deliveryStatus(@PathVariable Long mailId) {
        var reliable=delivery.getIfAvailable();
        if(reliable==null) throw com.shixun.common.exception.BusinessException.of(503,"异步投递未启用");
        return ApiResponse.success(reliable.status(mailId,com.shixun.common.security.AuthUserContext.getUserId()));
    }

    @GetMapping("/inbox")
    public ApiResponse<CursorPage<MailListVO>> getInbox(@RequestParam(required=false) String cursor, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(mailService.page("inbox", cursor, size));
    }

    @GetMapping("/sent")
    public ApiResponse<CursorPage<MailListVO>> getSentMails(@RequestParam(required=false) String cursor, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(mailService.page("sent", cursor, size));
    }

    @GetMapping("/trash")
    public ApiResponse<CursorPage<MailListVO>> getTrashMails(@RequestParam(required=false) String cursor, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(mailService.page("trash", cursor, size));
    }

    @GetMapping("/counts")
    public ApiResponse<?> folderCounts(){return ApiResponse.success(mailService.folderCounts());}

    @GetMapping("/spam")
    public ApiResponse<?> spamPage(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(mailService.page("spam",cursor,size));
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
