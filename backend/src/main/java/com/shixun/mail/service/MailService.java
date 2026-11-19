package com.shixun.mail.service;

import com.shixun.attachment.mapper.AttachmentMapper;
import com.shixun.common.exception.BusinessException;
import com.shixun.common.security.AuthUserContext;
import com.shixun.mail.dto.SendMailRequest;
import com.shixun.mail.entity.Mail;
import com.shixun.mail.entity.MailReceiver;
import com.shixun.mail.entity.UserBehavior;
import com.shixun.mail.mapper.MailMapper;
import com.shixun.mail.mapper.UserBehaviorMapper;
import com.shixun.mail.vo.MailDetailVO;
import com.shixun.mail.vo.MailListVO;
import com.shixun.user.entity.User;
import com.shixun.user.mapper.UserMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MailService {

    private static final int USER_STATUS_NORMAL = 1;
    private static final int MAIL_STATUS_SENT = 1;
    private static final int DEFAULT_PRIORITY = 3;
    private static final int SUBJECT_MAX_LENGTH = 255;
    private static final String RECEIVER_TO = "TO";
    private static final String RECEIVER_CC = "CC";
    private static final String RECEIVER_BCC = "BCC";

    private final MailMapper mailMapper;
    private final UserMapper userMapper;
    private final AttachmentMapper attachmentMapper;
    private final SpamService spamService;
    private final UserBehaviorMapper userBehaviorMapper;
    private final PriorityService priorityService;
    private final JdbcTemplate jdbcTemplate;
    private final NotificationSseHub notificationSseHub;

    public MailService(MailMapper mailMapper, UserMapper userMapper,
                       AttachmentMapper attachmentMapper, SpamService spamService,
                       UserBehaviorMapper userBehaviorMapper,
                       PriorityService priorityService,
                       JdbcTemplate jdbcTemplate,
                       NotificationSseHub notificationSseHub) {
        this.mailMapper = mailMapper;
        this.userMapper = userMapper;
        this.attachmentMapper = attachmentMapper;
        this.spamService = spamService;
        this.userBehaviorMapper = userBehaviorMapper;
        this.priorityService = priorityService;
        this.jdbcTemplate = jdbcTemplate;
        this.notificationSseHub = notificationSseHub;
    }

    @Transactional
    public Long sendMail(SendMailRequest request) {
        Long currentUserId = requireCurrentUserId();
        validateSendMailRequest(request);

        List<String> toEmails = normalizeEmails(request.getTo());
        List<String> ccEmails = normalizeEmails(request.getCc());
        if (toEmails.isEmpty()) {
            throw BusinessException.of(400, "收件人不能为空");
        }

        List<User> toUsers = resolveUsers(toEmails, "收件人");
        Set<Long> toUserIds = toUsers.stream().map(User::getUserId).collect(Collectors.toSet());
        List<User> ccUsers = resolveUsers(ccEmails, "抄送人").stream()
                .filter(user -> !toUserIds.contains(user.getUserId()))
                .collect(Collectors.toList());
        List<String> bccEmails = normalizeEmails(request.getBcc());
        List<User> bccUsers = resolveUsers(bccEmails, "瀵嗛€佷汉");

        Mail mail = new Mail();
        mail.setSenderId(currentUserId);
        mail.setSubject(request.getSubject().trim());
        mail.setContent(request.getContent() == null ? null : request.getContent().trim());
        mail.setHasAttachment(false);
        mail.setSpam(false);
        mail.setPriority(DEFAULT_PRIORITY);
        mail.setMailStatus(MAIL_STATUS_SENT);
        mail.setSenderDeleted(false);
        mailMapper.insertMail(mail);

        if (request.getAttachmentIds()!=null) for (Long id:new java.util.LinkedHashSet<>(request.getAttachmentIds())) {
            if(jdbcTemplate.update("UPDATE attachment SET mail_id=? WHERE attachment_id=? AND uploader_id=? AND mail_id IS NULL",mail.getMailId(),id,currentUserId)!=1)
                throw BusinessException.of(400,"附件无效或不属于当前用户");
            jdbcTemplate.update("UPDATE mail SET has_attachment=TRUE WHERE mail_id=?",mail.getMailId());
        }

        // 收集所有收件人ID
        List<Long> allRecipientIds = new ArrayList<>();
        toUsers.forEach(u -> allRecipientIds.add(u.getUserId()));
        ccUsers.forEach(u -> allRecipientIds.add(u.getUserId()));
        bccUsers.forEach(u -> allRecipientIds.add(u.getUserId()));

        String senderEmail = userMapper.findById(currentUserId).getEmail();

        // 第0层: 黑名单拦截（发件人在任意收件人黑名单中 → 直接垃圾）
        boolean isSpam = spamService.isSenderBlockedByRecipients(senderEmail, allRecipientIds);
        if (isSpam) {
            spamService.recordBlockedSenderSpam(mail.getMailId(), senderEmail);
        }

        // 第1-4层: 关键词/频率/信誉/深度学习检测
        if (!isSpam) {
            isSpam = spamService.isSpam(mail.getMailId(), currentUserId,
                    mail.getSubject(), mail.getContent());
        }

        if (isSpam) {
            mail.setSpam(true);
            mailMapper.updateSpamFlag(mail.getMailId(), true);
            // 为收件人创建垃圾邮件警告（弹窗确认是否拉黑）
            spamService.createSpamWarnings(senderEmail, allRecipientIds, mail.getMailId());
        }

        saveReceivers(mail.getMailId(), toUsers, RECEIVER_TO);
        saveReceivers(mail.getMailId(), ccUsers, RECEIVER_CC);
        saveReceivers(mail.getMailId(), bccUsers, RECEIVER_BCC);

        for (Long recipientId : new HashSet<>(allRecipientIds)) {
            recordBehavior(currentUserId, mail.getMailId(), recipientId, "SEND");
        }

        // 主动推送: 向所有在线收件人推送新邮件通知
        if (!isSpam) {
            String senderName = userMapper.findById(currentUserId).getUsername();
            for (User receiver : toUsers) {
                notificationSseHub.pushNewMailNotification(
                        receiver.getUserId(), senderName,
                        request.getSubject().trim(), mail.getMailId());
            }
            for (User receiver : ccUsers) {
                notificationSseHub.pushNewMailNotification(
                        receiver.getUserId(), senderName,
                        request.getSubject().trim(), mail.getMailId());
            }
        }

        return mail.getMailId();
    }

    public void deliverAccepted(Long mailId, Long senderId, List<java.util.Map<String,Object>> recipients) {
        Mail mail = mailMapper.selectById(mailId);
        User sender = userMapper.findById(senderId);
        List<Long> ids = recipients.stream().map(r -> ((Number)r.get("id")).longValue()).collect(Collectors.toList());
        boolean spam = spamService.isSenderBlockedByRecipients(sender.getEmail(), ids);
        if (spam) spamService.recordBlockedSenderSpam(mailId,sender.getEmail());
        if (!spam) spam = spamService.isSpam(mailId,senderId,mail.getSubject(),mail.getContent());
        mailMapper.updateSpamFlag(mailId,spam);
        if (spam) spamService.createSpamWarnings(sender.getEmail(),ids,mailId);
        jdbcTemplate.batchUpdate("INSERT INTO mail_receiver(mail_id,receiver_id,receiver_type,is_read,is_deleted,receive_time,mail_send_time) VALUES (?,?,?,FALSE,FALSE,NOW(),?)",
            recipients, 100, (ps,r) -> { ps.setLong(1,mailId); ps.setLong(2,((Number)r.get("id")).longValue()); ps.setString(3,(String)r.get("type")); ps.setObject(4,mail.getSendTime()); });
        try {
            jdbcTemplate.batchUpdate(
                "INSERT INTO user_behavior(user_id,mail_id,sender_id,behavior_type,created_at) VALUES (?,?,?,'SEND',NOW())",
                ids, 100, (ps, recipientId) -> {
                    ps.setLong(1, senderId);
                    ps.setLong(2, mailId);
                    ps.setLong(3, recipientId);
                });
        } catch (org.springframework.dao.DataAccessException ignored) {
            // Behavior history is supplementary and does not prevent delivery.
        }
    }

    public com.shixun.mail.vo.CursorPage<MailListVO> page(String folder, String cursor, int size) {
        size = Math.max(1, Math.min(size, 100));
        LocalDateTime time = null; Long id = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = new String(java.util.Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8).split("\\|", -1);
                time = LocalDateTime.parse(parts[0]); id = Long.valueOf(parts[1]);
                if (parts.length != 2 || id <= 0) throw new IllegalArgumentException();
            } catch (Exception e) { throw BusinessException.of(400, "无效分页游标"); }
        }
        List<MailListVO> rows = mailMapper.selectPage(requireCurrentUserId(), folder, time, id, size + 1);
        boolean more = rows.size() > size;
        if (more) rows = new ArrayList<>(rows.subList(0, size));
        if (!rows.isEmpty()) {
            var recipients = mailMapper.selectPageRecipients(rows.stream().map(MailListVO::getMailId).collect(Collectors.toList()));
            for (var row : rows) {
                var matching = recipients.stream().filter(r -> ((Number) r.get("mail_id")).longValue() == row.getMailId()).collect(Collectors.toList());
                row.setReceiverNames(matching.stream().map(r -> (String)r.get("username")).collect(Collectors.toList()));
                row.setReceiverEmails(matching.stream().map(r -> (String)r.get("email")).collect(Collectors.toList()));
            }
        }
        String next = null;
        if (more) {
            var last = rows.get(rows.size()-1);
            var lastTime=java.util.Set.of("inbox","spam").contains(folder)?last.getReceiveTime():last.getSendTime();
            next = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((lastTime+"|"+last.getMailId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new com.shixun.mail.vo.CursorPage<>(rows,next,more);
    }
    public List<MailListVO> getInbox() { return page("inbox",null,20).items(); }
    public List<MailListVO> getSentMails() { return page("sent",null,20).items(); }
    public List<MailListVO> getTrashMails() { return page("trash",null,20).items(); }

    public com.shixun.mail.vo.CursorPage<MailListVO> organizePage(String folder,String cursor,int size) {
        Long user=requireCurrentUserId();
        if(!java.util.Set.of("inbox","sent","trash").contains(folder)) throw BusinessException.of(400,"无效文件夹");
        if(cursor==null || cursor.isBlank()) {
            String next=null;
            do {
                var batch=page(folder,next,100);
                var scored=priorityService.sortMailList(new ArrayList<>(batch.items()),user);
                if(!scored.isEmpty()) jdbcTemplate.batchUpdate("INSERT INTO mail_priority_score(user_id,mail_id,score) VALUES (?,?,?) ON DUPLICATE KEY UPDATE score=VALUES(score)",scored,100,(ps,m)->{ps.setLong(1,user);ps.setLong(2,m.getMailId());ps.setInt(3,m.getPriorityScore());});
                next=batch.nextCursor();
            }while(next!=null);
        }
        Integer score=null;Long id=null;
        if(cursor!=null && !cursor.isBlank()) {
            try {var c=new String(java.util.Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|");score=Integer.valueOf(c[0]);id=Long.valueOf(c[1]);}
            catch(Exception e){throw BusinessException.of(400,"无效分页游标");}
        }
        size=Math.max(1,Math.min(100,size));
        var rows=mailMapper.selectPriorityPage(user,folder,score,id,size+1);
        boolean more=rows.size()>size;if(more)rows=new ArrayList<>(rows.subList(0,size));
        String next=null;
        if(more){var last=rows.get(rows.size()-1);next=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((last.getPriorityScore()+"|"+last.getMailId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        return new com.shixun.mail.vo.CursorPage<>(rows,next,more);
    }

    public java.util.Map<String,Object> folderCounts() {
        Long u=requireCurrentUserId();
        var counts=new java.util.HashMap<String,Object>();
        var inbox=jdbcTemplate.queryForMap("SELECT COUNT(*) total,COALESCE(SUM(m.is_spam),0) spam,COALESCE(SUM(NOT mr.is_read AND NOT m.is_spam),0) unread,COALESCE(SUM(NOT mr.is_read AND m.is_spam),0) spamUnread FROM mail_receiver mr JOIN mail m ON m.mail_id=mr.mail_id WHERE mr.receiver_id=? AND mr.is_deleted=FALSE AND m.mail_status=1",u);
        counts.put("inbox",((Number)inbox.get("total")).longValue()-((Number)inbox.get("spam")).longValue());
        counts.put("spam",inbox.get("spam"));counts.put("unread",inbox.get("unread"));counts.put("spamUnread",inbox.get("spamUnread"));
        counts.put("sent",jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mail WHERE sender_id=? AND sender_deleted=FALSE AND mail_status=1",Long.class,u));
        counts.put("trash",jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (SELECT mail_id FROM mail WHERE sender_id=? AND sender_deleted=TRUE AND mail_status=1 UNION SELECT mr.mail_id FROM mail_receiver mr JOIN mail m ON m.mail_id=mr.mail_id WHERE mr.receiver_id=? AND mr.is_deleted=TRUE AND m.mail_status=1) deleted",Long.class,u,u));
        counts.put("starred",jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mail_star s JOIN mail m ON m.mail_id=s.mail_id WHERE s.user_id=? AND m.mail_status=1 AND ((m.sender_id=? AND m.sender_deleted=FALSE) OR EXISTS(SELECT 1 FROM mail_receiver mr WHERE mr.mail_id=m.mail_id AND mr.receiver_id=? AND mr.is_deleted=FALSE))",Long.class,u,u,u));
        return counts;
    }

    public MailDetailVO getMailDetail(Long mailId) {
        Long currentUserId = requireCurrentUserId();
        requireMailId(mailId);

        if (!mailMapper.existsMailAccess(mailId, currentUserId)) {
            throw BusinessException.of(403, "无权查看该邮件");
        }

        MailDetailVO detail = mailMapper.selectMailDetail(mailId);
        if (detail == null) {
            throw BusinessException.of(404, "邮件不存在");
        }

        detail.setTo(mailMapper.selectToEmailsByMailId(mailId));
        detail.setCc(mailMapper.selectCcEmailsByMailId(mailId));
        detail.setAttachments(attachmentMapper.selectVOByMailId(mailId));

        // 成员6: 记录用户行为（打开邮件）
        if (detail.getSenderId() != null) {
            recordBehavior(currentUserId, mailId, detail.getSenderId(), "OPEN");
        }

        if (mailMapper.existsReceiverRecord(mailId, currentUserId)) {
            mailMapper.updateReadStatus(mailId, currentUserId, true);
            detail.setRead(true);
        }

        return detail;
    }

    @Transactional
    public void deleteMail(Long mailId) {
        Long currentUserId = requireCurrentUserId();
        requireMailId(mailId);

        boolean isSender = mailMapper.existsSender(mailId, currentUserId);
        boolean isReceiver = mailMapper.existsReceiverRecord(mailId, currentUserId);
        if (!isSender && !isReceiver) {
            throw BusinessException.of(403, "无权删除该邮件");
        }

        if (isSender) {
            mailMapper.updateSenderDeleted(mailId, currentUserId);
        }
        if (isReceiver) {
            mailMapper.updateReceiverDeleted(mailId, currentUserId);
        }

        // 成员6: 记录用户行为（删除邮件）
        try {
            Mail mail = mailMapper.selectById(mailId);
            if (mail != null && mail.getSenderId() != null) {
                recordBehavior(currentUserId, mailId, mail.getSenderId(), "DELETE");
            }
        } catch (Exception ignored) {
            // 行为记录失败不影响主流程
        }
    }

    @Transactional
    public void restoreMail(Long mailId) {
        Long currentUserId = requireCurrentUserId();
        requireMailId(mailId);

        boolean isDeletedSender = mailMapper.existsDeletedSender(mailId, currentUserId);
        boolean isDeletedReceiver = mailMapper.existsDeletedReceiverRecord(mailId, currentUserId);
        if (!isDeletedSender && !isDeletedReceiver) {
            throw BusinessException.of(403, "邮件不在当前用户回收站中");
        }

        if (isDeletedSender) {
            mailMapper.restoreSenderDeleted(mailId, currentUserId);
        }
        if (isDeletedReceiver) {
            mailMapper.restoreReceiverDeleted(mailId, currentUserId);
        }
    }

    @Transactional
    public void permanentlyDeleteMail(Long mailId) {
        Long currentUserId = requireCurrentUserId();
        requireMailId(mailId);

        boolean isDeletedSender = mailMapper.existsDeletedSender(mailId, currentUserId);
        boolean isDeletedReceiver = mailMapper.existsDeletedReceiverRecord(mailId, currentUserId);
        if (!isDeletedSender && !isDeletedReceiver) {
            throw BusinessException.of(403, "邮件不在当前用户回收站中");
        }

        if (isDeletedReceiver) {
            mailMapper.deleteReceiverRecord(mailId, currentUserId);
        }

        if (isDeletedSender && mailMapper.existsActiveReceiver(mailId)) {
            throw BusinessException.of(400, "邮件仍有收件人可见，暂不能永久删除");
        }

        if (isDeletedSender || mailMapper.existsDeletedSenderAny(mailId)) {
            if (!mailMapper.existsActiveReceiver(mailId)) {
                mailMapper.updateMailStatus(mailId, 0);
            }
        }
    }

    public void markAsRead(Long mailId) {
        updateReadStatus(mailId, true);
    }

    public void markAsUnread(Long mailId) {
        updateReadStatus(mailId, false);
    }

    private void updateReadStatus(Long mailId, boolean read) {
        Long currentUserId = requireCurrentUserId();
        requireMailId(mailId);

        if (!mailMapper.existsReceiverRecord(mailId, currentUserId)) {
            throw BusinessException.of(403, "无权操作该邮件");
        }
        mailMapper.updateReadStatus(mailId, currentUserId, read);
    }

    private void validateSendMailRequest(SendMailRequest request) {
        if (request == null) {
            throw BusinessException.of(400, "邮件内容不能为空");
        }
        requireNotBlank(request.getSubject(), "邮件主题不能为空");
        if (request.getSubject().trim().length() > SUBJECT_MAX_LENGTH) {
            throw BusinessException.of(400, "邮件主题长度不能超过255个字符");
        }
    }

    private List<String> normalizeEmails(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String email : emails) {
            if (email == null || email.trim().isEmpty()) {
                continue;
            }
            String value = email.trim().toLowerCase(Locale.ROOT);
            validateEmailFormat(value);
            if (seen.add(value)) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private List<User> resolveUsers(List<String> emails, String roleName) {
        List<User> users = new ArrayList<>();
        for (String email : emails) {
            User user = userMapper.findByEmail(email);
            if (user == null) {
                throw BusinessException.of(400, roleName + "不存在: " + email);
            }
            if (!Integer.valueOf(USER_STATUS_NORMAL).equals(user.getStatus())) {
                throw BusinessException.of(400, roleName + "账号不可用: " + email);
            }
            users.add(user);
        }
        return users;
    }

    private void saveReceivers(Long mailId, List<User> users, String receiverType) {
        for (User user : users) {
            MailReceiver receiver = new MailReceiver();
            receiver.setMailId(mailId);
            receiver.setReceiverId(user.getUserId());
            receiver.setReceiverType(receiverType);
            receiver.setRead(false);
            receiver.setDeleted(false);
            mailMapper.insertMailReceiver(receiver);
        }
    }

    private Long requireCurrentUserId() {
        Long userId = AuthUserContext.getUserId();
        if (userId == null) {
            throw BusinessException.of(401, "Unauthorized");
        }
        if (userMapper.findById(userId) == null) {
            throw BusinessException.of(401, "Unauthorized");
        }
        return userId;
    }

    private void requireMailId(Long mailId) {
        if (mailId == null || mailId <= 0) {
            throw BusinessException.of(400, "邮件ID不合法");
        }
    }

    private void requireNotBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw BusinessException.of(400, message);
        }
    }

    private void validateEmailFormat(String email) {
        int atIndex = email.indexOf('@');
        int dotIndex = email.lastIndexOf('.');
        if (atIndex <= 0 || dotIndex <= atIndex + 1 || dotIndex == email.length() - 1) {
            throw BusinessException.of(400, "邮箱格式不正确: " + email);
        }
    }

    // ===== 用户行为记录（规则2 & 规则5 数据来源） =====

    /**
     * 记录用户对邮件的操作行为，用于优先级计算。
     */
    private void recordBehavior(Long userId, Long mailId, Long senderId, String behaviorType) {
        try {
            if ("OPEN".equalsIgnoreCase(behaviorType)
                    && mailId != null
                    && hasExistingBehavior(userId, mailId, "OPEN")) {
                return;
            }
            UserBehavior behavior = UserBehavior.builder()
                    .userId(userId)
                    .mailId(mailId)
                    .senderId(senderId)
                    .behaviorType(behaviorType)
                    .behaviorTime(LocalDateTime.now())
                    .build();
            userBehaviorMapper.insert(behavior);
        } catch (Exception ignored) {
            // 行为记录失败不影响主流程
        }
    }

    private boolean hasExistingBehavior(Long userId, Long mailId, String behaviorType) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(1)
                    FROM user_behavior
                    WHERE user_id = ?
                      AND mail_id = ?
                      AND behavior_type = ?
                    """,
                    Integer.class,
                    userId,
                    mailId,
                    behaviorType
            );
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 记录用户回复邮件的行为（规则2: 回复率信号）。
     * 在用户发送回复邮件时调用。
     */
    public void recordReply(Long userId, Long originalMailId, Long senderId) {
        if (userId == null || originalMailId == null) return;
        try {
            // 查找发件人ID（如果调用方未提供）
            if (senderId == null) {
                Mail mail = mailMapper.selectById(originalMailId);
                if (mail != null) {
                    senderId = mail.getSenderId();
                }
            }
            if (senderId != null) {
                recordBehavior(userId, originalMailId, senderId, "REPLY");
            }
        } catch (Exception ignored) {
            // 行为记录失败不影响主流程
        }
    }

    /**
     * 记录用户搜索行为（规则2: 搜索信号）。
     * 当用户搜索关键词时调用，如果关键词匹配到发件人名称或邮箱，
     * 对该发件人增加权重。
     */
    public void recordSearchBehavior(Long userId, String keyword) {
        if (userId == null || keyword == null || keyword.trim().isEmpty()) return;
        try {
            // 查找关键词是否匹配某个发件人的名称或邮箱
            String sql = """
                SELECT DISTINCT m.sender_id
                FROM mail m
                JOIN mail_receiver mr ON m.mail_id = mr.mail_id
                JOIN user u ON m.sender_id = u.user_id
                WHERE mr.receiver_id = ?
                  AND (u.username LIKE CONCAT('%', ?, '%')
                       OR u.email LIKE CONCAT('%', ?, '%'))
                LIMIT 5
                """;
            List<Long> senderIds = jdbcTemplate.queryForList(
                    sql, Long.class, userId, keyword.trim(), keyword.trim());
            for (Long senderId : senderIds) {
                // 搜索行为记录为轻量 OPEN（表示用户对该发件人感兴趣）
                recordBehavior(userId, null, senderId, "OPEN");
            }
        } catch (Exception ignored) {
            // 行为记录失败不影响主流程
        }
    }

    /**
     * 获取收件箱邮件并按优先级排序（供整理功能使用）。
     */
    public List<MailListVO> getOrganizedInbox() {
        Long currentUserId = requireCurrentUserId();
        List<MailListVO> mails = mailMapper.selectInboxList(currentUserId);
        return priorityService.sortMailList(mails, currentUserId);
    }

    /**
     * 按文件夹获取邮件并按优先级排序。
     * @param folderKey "inbox" / "sent" / "trash"
     */
    public List<MailListVO> getOrganizedFolder(String folderKey) {
        Long currentUserId = requireCurrentUserId();
        List<MailListVO> mails;
        switch (folderKey) {
            case "sent" -> mails = getSentMails();
            case "trash" -> mails = getTrashMails();
            default -> mails = mailMapper.selectInboxList(currentUserId);
        }
        return priorityService.sortMailList(mails, currentUserId);
    }
}
