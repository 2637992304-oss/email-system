package com.shixun.mail.service;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationService {

    private final JdbcTemplate jdbcTemplate;

    private static final String UNREAD_COUNT_SQL =
            "SELECT COUNT(*) FROM mail_receiver mr " +
            "JOIN mail m ON mr.mail_id = m.mail_id " +
            "WHERE mr.receiver_id = ? AND mr.is_read = 0 AND mr.is_deleted = 0";

    private static final String RECENT_UNREAD_SQL =
            "SELECT m.mail_id, m.subject, u.username AS sender_name, u.email AS sender_email, m.send_time " +
            "FROM mail_receiver mr " +
            "JOIN mail m ON mr.mail_id = m.mail_id " +
            "JOIN `user` u ON m.sender_id = u.user_id " +
            "WHERE mr.receiver_id = ? AND mr.is_read = 0 AND mr.is_deleted = 0 " +
            "ORDER BY m.send_time DESC LIMIT ?";

    public NotificationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 获取用户的未读邮件通知。
     * 依赖成员3创建的 mail 和 mail_receiver 表。
     * 如果没有这两个表，会优雅降级返回空结果。
     */
    public Map<String, Object> getUnreadNotifications(Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Integer unreadCount = jdbcTemplate.queryForObject(UNREAD_COUNT_SQL, Integer.class, userId);
            int count = unreadCount != null ? unreadCount : 0;

            List<Map<String, Object>> recentUnread = jdbcTemplate.queryForList(RECENT_UNREAD_SQL, userId, 5);

            result.put("userId", userId);
            result.put("unreadCount", count);
            result.put("hasNewMail", count > 0);
            result.put("recentUnread", recentUnread);
        } catch (DataAccessException e) {
            result.put("userId", userId);
            result.put("unreadCount", 0);
            result.put("hasNewMail", false);
            result.put("recentUnread", List.of());
            result.put("note", "mail/mail_receiver 表尚未创建，暂时返回空结果");
        }
        return result;
    }
}
