package com.shixun.mail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 连接中心 — 管理所有在线用户的实时推送通道。
 * 新邮件到达时，通过此类向对应收件人推送通知。
 */
@Service
public class NotificationSseHub {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseHub.class);

    /** userId → SseEmitter，一个用户同时只能有一个 SSE 连接 */
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 用户订阅 SSE 通道。
     * @param userId 当前用户ID
     * @return SseEmitter 实例，前端通过 EventSource 接收
     */
    public SseEmitter subscribe(Long userId) {
        // 如果已有连接，先关闭旧连接
        SseEmitter old = emitters.remove(userId);
        if (old != null) {
            try { old.complete(); } catch (Exception ignored) {}
        }

        // 30 分钟超时，到时前端会自动重连
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitters.put(userId, emitter);

        emitter.onCompletion(() -> {
            emitters.remove(userId);
            log.debug("SSE completed for userId={}", userId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(userId);
            log.debug("SSE timeout for userId={}", userId);
        });
        emitter.onError(e -> {
            emitters.remove(userId);
            log.debug("SSE error for userId={}: {}", userId, e.getMessage());
        });

        // 连接成功后立即发送一条心跳，让前端知道连接已建立
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("message", "SSE 连接已建立", "timestamp", LocalDateTime.now().toString())));
        } catch (IOException e) {
            emitters.remove(userId);
        }

        return emitter;
    }

    /**
     * 向指定用户推送新邮件通知。
     * @param receiverId 收件人ID
     * @param senderName 发件人名称
     * @param subject    邮件主题
     * @param mailId     邮件ID
     */
    public void pushNewMailNotification(Long receiverId, String senderName,
                                         String subject, Long mailId) {
        SseEmitter emitter = emitters.get(receiverId);
        if (emitter == null) return; // 用户不在线，无需推送

        Map<String, Object> eventData = Map.of(
                "mailId", mailId,
                "senderName", senderName != null ? senderName : "未知",
                "subject", subject != null ? subject : "(无主题)",
                "timestamp", LocalDateTime.now().toString()
        );

        try {
            emitter.send(SseEmitter.event()
                    .name("new-mail")
                    .data(eventData));
        } catch (IOException e) {
            emitters.remove(receiverId);
        }
    }

    /**
     * 获取当前在线用户数（调试用）。
     */
    public int getOnlineCount() {
        return emitters.size();
    }
}
