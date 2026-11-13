package com.shixun.mail.service;

import com.shixun.common.exception.BusinessException;
import com.shixun.common.security.AuthUserContext;
import com.shixun.mail.entity.Mail;
import com.shixun.mail.mapper.MailSearchMapper;
import com.shixun.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MailSearchService {

    private final MailSearchMapper mailSearchMapper;
    private final UserMapper userMapper;
    private final MailService mailService;

    public MailSearchService(MailSearchMapper mailSearchMapper,
                             UserMapper userMapper,
                             MailService mailService) {
        this.mailSearchMapper = mailSearchMapper;
        this.userMapper = userMapper;
        this.mailService = mailService;
    }

    public List<Mail> search(Long userId, String keyword) {
        Long currentUserId = requireCurrentUserId();
        requireSelf(userId, currentUserId);
        String value = keyword == null ? "" : keyword.trim();
        List<Mail> result = mailSearchMapper.search(currentUserId, value);
        mailService.recordSearchBehavior(currentUserId, value);
        return result;
    }

    public List<Mail> filter(Long userId,
                             Boolean isRead,
                             Boolean hasAttachment,
                             LocalDateTime startTime,
                             LocalDateTime endTime) {
        Long currentUserId = requireCurrentUserId();
        requireSelf(userId, currentUserId);
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw BusinessException.of(400, "开始时间不能晚于结束时间");
        }
        return mailSearchMapper.filter(currentUserId, isRead, hasAttachment, startTime, endTime);
    }

    private Long requireCurrentUserId() {
        Long userId = AuthUserContext.getUserId();
        if (userId == null || userMapper.findById(userId) == null) {
            throw BusinessException.of(401, "Unauthorized");
        }
        return userId;
    }

    private void requireSelf(Long requestedUserId, Long currentUserId) {
        if (requestedUserId == null || !requestedUserId.equals(currentUserId)) {
            throw BusinessException.of(403, "无权查询其他用户邮件");
        }
    }
}
