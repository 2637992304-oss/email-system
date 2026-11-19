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

    private final java.util.concurrent.Semaphore searchSlots = new java.util.concurrent.Semaphore(2);
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

    public com.shixun.mail.vo.CursorPage<Mail> search(Long userId, String keyword, String cursor, int size) {
        Long currentUserId = requireCurrentUserId();
        requireSelf(userId, currentUserId);
        String value = keyword == null ? "" : keyword.trim();
        var point = point(cursor);
        if (!searchSlots.tryAcquire()) throw BusinessException.of(429, "搜索繁忙，请稍后重试");
        List<Mail> result;
        try {
            result = mailSearchMapper.search(currentUserId, value, point.time(), point.id(), Math.min(100, Math.max(1,size))+1);
        } catch (org.springframework.dao.QueryTimeoutException ex) {
            throw BusinessException.of(503, "搜索超时，请缩小搜索范围后重试");
        } finally {
            searchSlots.release();
        }
        mailService.recordSearchBehavior(currentUserId, value);
        return page(result,size);
    }

    public com.shixun.mail.vo.CursorPage<Mail> filter(Long userId,
                             Boolean isRead,
                             Boolean hasAttachment,
                             LocalDateTime startTime,
                             LocalDateTime endTime, String folder, String cursor, int size) {
        Long currentUserId = requireCurrentUserId();
        requireSelf(userId, currentUserId);
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw BusinessException.of(400, "开始时间不能晚于结束时间");
        }
        if(folder!=null && !java.util.Set.of("inbox","sent","spam","trash","starred").contains(folder)) throw BusinessException.of(400,"无效文件夹");
        var point = point(cursor);
        return page(mailSearchMapper.filter(currentUserId, isRead, hasAttachment, startTime, endTime, folder, point.time(), point.id(), Math.min(100,Math.max(1,size))+1),size);
    }

    private record Point(LocalDateTime time,Long id){}
    private Point point(String cursor) {
        if(cursor==null || cursor.isBlank())return new Point(null,null);
        try {var p=new String(java.util.Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8).split("\\|");
            if(p.length!=2) throw new IllegalArgumentException();
            return new Point(LocalDateTime.parse(p[0]),Long.valueOf(p[1]));
        }catch(Exception e){throw BusinessException.of(400,"无效分页游标");}
    }
    private com.shixun.mail.vo.CursorPage<Mail> page(List<Mail> rows,int size) {
        size=Math.min(100,Math.max(1,size));boolean more=rows.size()>size;
        if(more) rows=new java.util.ArrayList<>(rows.subList(0,size));
        String next=null;
        if(more) {var m=rows.get(rows.size()-1);next=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((m.getSendTime()+"|"+m.getMailId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        return new com.shixun.mail.vo.CursorPage<>(rows,next,more);
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
