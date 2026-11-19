package com.shixun.mail.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shixun.mail.entity.UserBehavior;
import com.shixun.mail.mapper.MailMapper;
import com.shixun.mail.mapper.MailStarMapper;
import com.shixun.mail.mapper.UserBehaviorMapper;
import com.shixun.mail.mapper.UserContactListMapper;
import com.shixun.mail.vo.MailListVO;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PriorityService {

    private final UserBehaviorMapper userBehaviorMapper;
    private final JdbcTemplate jdbcTemplate;
    private final MailMapper mailMapper;
    private final SpamService spamService;
    private final MailStarMapper mailStarMapper;
    private final UserContactListMapper userContactListMapper;

    /* ================================================================
     *  规则2 — 行为信号基础分
     * ================================================================ */
    private static final int SCORE_OPEN = 2;
    private static final int SCORE_REPLY = 5;
    private static final int SCORE_DELETE = -3;

    /** 打开延迟分: 收到后5分钟内打开 */
    private static final int SCORE_FAST_OPEN = 5;
    /** 打开延迟分: 1小时内打开 */
    private static final int SCORE_MEDIUM_OPEN = 3;
    /** 打开延迟分: 24小时内打开 */
    private static final int SCORE_SLOW_OPEN = 1;
    /** 高回复率奖励: 回复率 >= 80% */
    private static final int SCORE_HIGH_REPLY_RATE = 10;
    /** 高回复率阈值 */
    private static final double HIGH_REPLY_RATE_THRESHOLD = 0.8;

    /* ================================================================
     *  规则3 — 内容与上下文特征
     * ================================================================ */
    /** 高紧迫度关键词（主题中出现即+8） */
    private static final Set<String> HIGH_URGENCY_KEYWORDS = Set.of(
            "紧急", "asap", "截止", "审批", "请回复", "重要", "立即",
            "urgent", "deadline", "approval", "action required", "请审批",
            "尽快", "马上", "即刻", "加急", "务必", "priority"
    );
    private static final int SCORE_HIGH_URGENCY = 8;

    /** 中等关键词（主题中出现即+3） */
    private static final Set<String> MEDIUM_KEYWORDS = Set.of(
            "会议", "周报", "方案", "邀请", "通知", "报告", "总结",
            "meeting", "report", "proposal", "invitation", "schedule",
            "计划", "需求", "确认", "请查收", "反馈"
    );
    private static final int SCORE_MEDIUM_KEYWORD = 3;

    /** 低紧迫度关键词（主题中出现即-3） */
    private static final Set<String> LOW_URGENCY_KEYWORDS = Set.of(
            "周刊", "newsletter", "更新", "日报", "周报汇总",
            "weekly", "digest", "update", "月刊", "月报"
    );
    private static final int SCORE_LOW_URGENCY = -5;

    /** @提及检测: 名字被 @ 或出现在 "Hi 用户名" 模式中 +2 */
    private static final int SCORE_AT_MENTION = 2;

    /** 附件存在: 有附件 +2 */
    private static final int SCORE_HAS_ATTACHMENT = 2;

    /** 截止日期检测: 匹配到且距今 <= 24小时 +10 */
    private static final int SCORE_DEADLINE_24H = 10;
    /** 截止日期正则 */
    private static final Pattern DEADLINE_PATTERN = Pattern.compile(
            "(?:截止|deadline|due|by)\\s*[:：]?\\s*(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}月\\d{1,2}日|\\d{1,2}/\\d{1,2})",
            Pattern.CASE_INSENSITIVE
    );

    /* ================================================================
     *  规则4 — 社交图谱与交互频率
     * ================================================================ */
    /** 交互频率对数乘数: log(count+1) * 2 */
    private static final double SOCIAL_FREQ_MULTIPLIER = 2.0;
    /** 用户主动发件多于收件 → 关系更紧密 +3 */
    private static final int SCORE_ACTIVE_OUTBOUND = 3;

    /* ================================================================
     *  规则5 — 时间衰减与新鲜度
     * ================================================================ */
    /** 同一发件人1小时内防刷屏阈值 */
    private static final int ANTI_FLOOD_COUNT = 5;
    /** 超出阈值后每封降权 */
    private static final int SCORE_ANTI_FLOOD_PENALTY = -3;

    /* ================================================================
     *  规则6 — 反垃圾与低质量降权（已停用，垃圾检测由SpamService处理）
     * ================================================================ */
    /** 已被标记为垃圾邮件 */
    private static final int SCORE_IS_SPAM = -20;
    /** 命中促销关键词 */
    private static final int SCORE_PROMOTIONAL = -8;
    /** 包含退订链接 */
    private static final int SCORE_UNSUBSCRIBE = -3;
    /** 陌生域名无历史交互 */
    private static final int SCORE_UNKNOWN_DOMAIN = -5;

    /** 促销关键词库（比垃圾关键词库更宽松，仅降权不拦截） */
    private static final Set<String> PROMO_KEYWORDS = Set.of(
            "优惠券", "折扣", "限时抢购", "免费试用", "点击领取",
            "大促", "双十一", "618", "满减", "包邮", "秒杀",
            "coupon", "discount", "free trial", "limited offer", "clearance",
            "promo", "sale", "flash sale", "促销", "立减", "特价"
    );

    /** 退订/营销邮件标识 */
    private static final Set<String> UNSUBSCRIBE_MARKERS = Set.of(
            "退订", "unsubscribe", "不再接收", "退阅", "取消订阅"
    );

    /* ================================================================
     *  规则7 — 用户覆盖（白名单/黑名单/VIP，暂用存根等待建表）
     * ================================================================ */
    private static final int SCORE_WHITELIST = 90;
    private static final int SCORE_BLACKLIST = -20;
    private static final int SCORE_VIP = 15;
    /** 星标基础分（最高优先, +后续规则让星标邮件之间可再排序） */
    private static final int SCORE_STAR = 100;

    // ---- 评分范围 ----
    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 200;

    public PriorityService(UserBehaviorMapper userBehaviorMapper,
                           JdbcTemplate jdbcTemplate,
                           MailMapper mailMapper,
                           SpamService spamService,
                           MailStarMapper mailStarMapper,
                           UserContactListMapper userContactListMapper) {
        this.userBehaviorMapper = userBehaviorMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mailMapper = mailMapper;
        this.spamService = spamService;
        this.mailStarMapper = mailStarMapper;
        this.userContactListMapper = userContactListMapper;
    }

    // ===================== 核心评分方法 =====================

    /**
     * 对邮件列表按综合优先级排序（高到低），并将 priorityScore 写入每封邮件。
     *
     * @param mails  邮件列表（来自收件箱/已发送等）
     * @param userId 当前用户ID
     * @return 按优先级降序排列的邮件列表
     */
    public List<MailListVO> sortMailList(List<MailListVO> mails, Long userId) {
        if (mails == null || mails.isEmpty()) {
            return mails != null ? mails : List.of();
        }

        // ---- 预加载批量数据，避免循环内逐条查库 ----
        SenderBehaviorCache behaviorCache = buildBehaviorCache(userId);
        Set<Long> spamMailIds = mails.stream().filter(m -> Boolean.TRUE.equals(m.getSpamFlag())).map(MailListVO::getMailId).collect(Collectors.toSet());
        Set<Long> starredMailIds = loadStarredMailIds(userId);
        Set<String> whitelistEmails = loadContactEmails(userId, "WHITELIST");
        Set<String> blacklistEmails = loadContactEmails(userId, "BLACKLIST");
        Set<String> vipEmails = loadContactEmails(userId, "VIP");

        // 当前用户名 + 按发件人分组（规则5防刷屏）
        String currentUserName = getCurrentUserName(userId);
        Map<Long, List<MailListVO>> senderGrouped = mails.stream()
                .filter(m -> m.getSenderId() != null)
                .collect(Collectors.groupingBy(MailListVO::getSenderId));

        for (MailListVO mail : mails) {
            int score = computeSingleMailScore(mail, userId, behaviorCache,
                    spamMailIds, currentUserName,
                    starredMailIds, whitelistEmails, blacklistEmails, vipEmails,
                    senderGrouped);
            mail.setPriorityScore(score);
        }

        // 排序: 分数高的在前，同分按时间新的在前
        mails.sort((a, b) -> {
            int scoreA = a.getPriorityScore() != null ? a.getPriorityScore() : 0;
            int scoreB = b.getPriorityScore() != null ? b.getPriorityScore() : 0;
            if (scoreA != scoreB) return Integer.compare(scoreB, scoreA);
            // 时间兜底
            LocalDateTime ta = a.getReceiveTime() != null ? a.getReceiveTime() : a.getSendTime();
            LocalDateTime tb = b.getReceiveTime() != null ? b.getReceiveTime() : b.getSendTime();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        return mails;
    }

    /**
     * 单封邮件综合评分（0-100），流程按照七条规则的优先级顺序执行。
     */
    private int computeSingleMailScore(MailListVO mail,
                                       Long userId,
                                       SenderBehaviorCache behaviorCache,
                                       Set<Long> spamMailIds,
                                       String currentUserName,
                                       Set<Long> starredMailIds,
                                       Set<String> whitelistEmails,
                                       Set<String> blacklistEmails,
                                       Set<String> vipEmails,
                                       Map<Long, List<MailListVO>> senderGrouped) {

        // ====== 规则7: 用户覆盖（最高优先） ======
        String senderEmail = mail.getSenderEmail();

        // 黑名单 → 立即最低分
        if (senderEmail != null && blacklistEmails.contains(senderEmail.toLowerCase())) {
            return MIN_SCORE;
        }

        int score = 0;

        // 星标邮件最高优先级：基础100分 + 后续规则分 → 星标之间可互排序
        if (starredMailIds.contains(mail.getMailId())) {
            score = SCORE_STAR;
        }

        // 白名单: 90分（仅低于星标）
        if (senderEmail != null && whitelistEmails.contains(senderEmail.toLowerCase())) {
            score = Math.max(score, SCORE_WHITELIST);
        }

        // VIP 发件人加成
        if (senderEmail != null && vipEmails.contains(senderEmail.toLowerCase())) {
            score += SCORE_VIP;
        }

        // ====== 规则2: 行为信号 ======
        score += computeBehaviorScore(mail, userId, behaviorCache);

        // ====== 规则3: 内容与上下文特征 ======
        score += computeContentScore(mail, currentUserName);

        // ====== 规则4: 社交图谱 ======
        score += computeSocialGraphScore(mail, behaviorCache);

        // ====== 规则5: 防刷屏 + 时间衰减 ======
        score += computeAntiFloodPenalty(mail, senderGrouped);
        double freshness = computeFreshnessFactor(mail);

        // 应用新鲜度衰减（仅对正面分数部分生效，负面不衰减）
        if (freshness < 1.0 && score > 0) {
            score = (int) Math.round(score * freshness);
        }

        // 夹紧到 [0, 200]
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    // ===================== 规则2: 行为信号 =====================

    private int computeBehaviorScore(MailListVO mail, Long userId,
                                     SenderBehaviorCache cache) {
        int score = 0;
        Long senderId = mail.getSenderId();
        if (senderId == null) return score;

        SenderStats stats = cache.getStats(senderId);

        // 基础行为分: OPEN +2, REPLY +5, DELETE -3
        score += stats.openCount * SCORE_OPEN;
        score += stats.replyCount * SCORE_REPLY;
        score += stats.deleteCount * SCORE_DELETE;

        // 回复率奖励: 过去30天回复率 >= 80% → +10
        if (stats.totalReceived30d > 0) {
            double replyRate = (double) stats.replyCount30d / stats.totalReceived30d;
            if (replyRate >= HIGH_REPLY_RATE_THRESHOLD) {
                score += SCORE_HIGH_REPLY_RATE;
            }
        }

        // 打开延迟奖励
        if (mail.getReceiveTime() != null && stats.firstOpenTime != null) {
            long minutes = ChronoUnit.MINUTES.between(mail.getReceiveTime(), stats.firstOpenTime);
            if (minutes >= 0) {
                if (minutes <= 10) {
                    score += SCORE_FAST_OPEN;
                } else if (minutes <= 60) {
                    score += SCORE_MEDIUM_OPEN;
                } else if (minutes <= 1440) {
                    score += SCORE_SLOW_OPEN;
                }
            }
        }

        return score;
    }

    // ===================== 规则3: 内容特征 =====================

    private int computeContentScore(MailListVO mail, String currentUserName) {
        int score = 0;
        String subject = mail.getSubject() != null ? mail.getSubject().toLowerCase() : "";
        String preview = mail.getPreview() != null ? mail.getPreview().toLowerCase() : "";

        // --- 主题关键词匹配 ---
        for (String kw : HIGH_URGENCY_KEYWORDS) {
            if (subject.contains(kw.toLowerCase())) {
                score += SCORE_HIGH_URGENCY;
                break; // 只加一次
            }
        }
        boolean hasMedium = false;
        for (String kw : MEDIUM_KEYWORDS) {
            if (subject.contains(kw.toLowerCase())) {
                hasMedium = true;
                break;
            }
        }
        if (hasMedium) score += SCORE_MEDIUM_KEYWORD;

        for (String kw : LOW_URGENCY_KEYWORDS) {
            if (subject.contains(kw.toLowerCase())) {
                score += SCORE_LOW_URGENCY;
                break;
            }
        }

        // --- @提及检测 (在主题或预览中) ---
        if (currentUserName != null && !currentUserName.isEmpty()) {
            String nameLower = currentUserName.toLowerCase();
            String combined = subject + " " + preview;
            if (combined.contains("@" + nameLower)
                    || combined.contains("hi " + nameLower)
                    || combined.contains("你好 " + nameLower)
                    || combined.contains("dear " + nameLower)) {
                score += SCORE_AT_MENTION;
            }
        }

        // --- 附件特征 ---
        if (Boolean.TRUE.equals(mail.getHasAttachment())) {
            score += SCORE_HAS_ATTACHMENT;
        }

        // --- 截止日期检测 ---
        if (detectDeadlineWithin24h(subject + " " + preview)) {
            score += SCORE_DEADLINE_24H;
        }

        return score;
    }

    /**
     * 检测文本中的截止日期，并判断是否在24小时内。
     */
    private boolean detectDeadlineWithin24h(String text) {
        Matcher m = DEADLINE_PATTERN.matcher(text);
        while (m.find()) {
            String dateStr = m.group(1);
            LocalDateTime deadline = parseDateString(dateStr);
            if (deadline != null) {
                long hours = ChronoUnit.HOURS.between(LocalDateTime.now(), deadline);
                if (hours >= 0 && hours <= 24) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 简单解析日期字符串为 LocalDateTime。
     * 支持: 2025-03-20, 3月20日, 03/20
     */
    private LocalDateTime parseDateString(String dateStr) {
        try {
            dateStr = dateStr.trim();
            // 数字格式: 2025-03-20 或 2025/03/20
            if (dateStr.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}")) {
                dateStr = dateStr.replace('/', '-');
                return LocalDateTime.parse(dateStr + "T23:59:59");
            }
            // 中文格式: 3月20日
            Matcher cm = Pattern.compile("(\\d{1,2})月(\\d{1,2})日").matcher(dateStr);
            if (cm.find()) {
                int month = Integer.parseInt(cm.group(1));
                int day = Integer.parseInt(cm.group(2));
                int year = LocalDateTime.now().getYear();
                return LocalDateTime.of(year, month, day, 23, 59, 59);
            }
            // 数字斜杠: 3/20
            Matcher sm = Pattern.compile("(\\d{1,2})/(\\d{1,2})").matcher(dateStr);
            if (sm.find()) {
                int month = Integer.parseInt(sm.group(1));
                int day = Integer.parseInt(sm.group(2));
                int year = LocalDateTime.now().getYear();
                return LocalDateTime.of(year, month, day, 23, 59, 59);
            }
        } catch (Exception ignored) {
            // 解析失败，返回 null
        }
        return null;
    }

    // ===================== 规则4: 社交图谱 =====================

    private int computeSocialGraphScore(MailListVO mail, SenderBehaviorCache cache) {
        int score = 0;
        Long senderId = mail.getSenderId();
        if (senderId == null) return score;

        SenderStats stats = cache.getStats(senderId);

        // 过去90天邮件往来总数取对数
        long totalInteractions = stats.totalInteractions90d;
        if (totalInteractions > 0) {
            score += (int) Math.round(Math.log(totalInteractions + 1) * SOCIAL_FREQ_MULTIPLIER);
        }

        // 用户主动发送多于接收 → 紧密关系
        if (stats.outboundCount90d > stats.inboundCount90d) {
            score += SCORE_ACTIVE_OUTBOUND;
        }

        return score;
    }

    // ===================== 规则5: 时间衰减 =====================

    /**
     * 计算新鲜度因子。
     * 公式: freshness = 1 / (hours_since_received + 1)
     * 未读邮件保持 freshness = 1.0（衰减暂停）
     * 返回 0.0 ~ 1.0
     */
    private double computeFreshnessFactor(MailListVO mail) {
        // 未读邮件不衰减
        if (Boolean.FALSE.equals(mail.getRead())) {
            return 1.0;
        }

        LocalDateTime referenceTime = mail.getReceiveTime() != null
                ? mail.getReceiveTime() : mail.getSendTime();
        if (referenceTime == null) return 1.0;

        long hours = ChronoUnit.HOURS.between(referenceTime, LocalDateTime.now());
        if (hours < 0) hours = 0;
        return 1.0 / (hours + 1.0);
    }

    /**
     * 防刷屏降权：同一发件人1小时内 > 5封 → 第6封起每封 -3。
     */
    private int computeAntiFloodPenalty(MailListVO mail,
                                         Map<Long, List<MailListVO>> senderGrouped) {
        Long senderId = mail.getSenderId();
        if (senderId == null) return 0;
        List<MailListVO> sameSender = senderGrouped.get(senderId);
        if (sameSender == null || sameSender.size() <= ANTI_FLOOD_COUNT) return 0;
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        long countInWindow = sameSender.stream()
                .filter(m -> {
                    LocalDateTime t = m.getReceiveTime() != null
                            ? m.getReceiveTime() : m.getSendTime();
                    return t != null && t.isAfter(oneHourAgo);
                }).count();
        if (countInWindow > ANTI_FLOOD_COUNT) {
            LocalDateTime mailTime = mail.getReceiveTime() != null
                    ? mail.getReceiveTime() : mail.getSendTime();
            if (mailTime != null && mailTime.isAfter(oneHourAgo)) {
                return SCORE_ANTI_FLOOD_PENALTY;
            }
        }
        return 0;
    }

    // ===================== 规则6: 反垃圾降权 =====================

    private int computeAntiSpamScore(MailListVO mail, Set<Long> spamMailIds) {
        int score = 0;

        // 已被系统标记为垃圾邮件
        if (Boolean.TRUE.equals(mail.getSpamFlag())
                || spamMailIds.contains(mail.getMailId())) {
            score += SCORE_IS_SPAM;
        }

        String subject = mail.getSubject() != null ? mail.getSubject().toLowerCase() : "";
        String preview = mail.getPreview() != null ? mail.getPreview().toLowerCase() : "";
        String combined = subject + " " + preview;

        // 促销关键词检测
        for (String kw : PROMO_KEYWORDS) {
            if (combined.contains(kw.toLowerCase())) {
                score += SCORE_PROMOTIONAL;
                break;
            }
        }

        // 退订/营销检测
        for (String kw : UNSUBSCRIBE_MARKERS) {
            if (combined.contains(kw.toLowerCase())) {
                score += SCORE_UNSUBSCRIBE;
                break;
            }
        }

        // 陌生域名检测（过去6个月无交互）
        if (mail.getSenderEmail() != null && isUnknownDomain(mail.getSenderEmail())) {
            score += SCORE_UNKNOWN_DOMAIN;
        }

        return score;
    }

    /**
     * 检查发件域名是否陌生（过去6个月无任何收发记录）。
     */
    private boolean isUnknownDomain(String senderEmail) {
        if (senderEmail == null || !senderEmail.contains("@")) return false;
        String domain = senderEmail.substring(senderEmail.indexOf("@") + 1).toLowerCase();
        String sql = """
            SELECT COUNT(1)
            FROM mail m
            JOIN user_behavior ub ON ub.mail_id = m.mail_id
            WHERE m.send_time >= ?
              AND (
                  m.sender_id IN (SELECT user_id FROM user WHERE email LIKE CONCAT('%@', ?))
                  OR ub.user_id IN (SELECT user_id FROM user WHERE email LIKE CONCAT('%@', ?))
              )
            """;
        try {
            LocalDateTime sixMonthsAgo = LocalDateTime.now().minusMonths(6);
            Long count = jdbcTemplate.queryForObject(sql, Long.class, sixMonthsAgo,
                    domain, domain);
            return count == null || count == 0;
        } catch (DataAccessException e) {
            return false; // 查询失败不降权
        }
    }

    // ===================== 规则7 + 规则1 批量加载方法 =====================

    /**
     * 加载用户星标的邮件ID集合（规则1）。
     */
    private Set<Long> loadStarredMailIds(Long userId) {
        try {
            return mailStarMapper.selectStarredMailIds(userId);
        } catch (DataAccessException e) {
            return Set.of();
        }
    }

    /**
     * 加载用户指定名单类型的联系人邮箱集合（规则7）。
     */
    private Set<String> loadContactEmails(Long userId, String listType) {
        try {
            Set<String> emails = userContactListMapper.selectEmailsByType(userId, listType);
            // 转小写方便比较
            Set<String> lower = new HashSet<>();
            for (String email : emails) {
                if (email != null) lower.add(email.toLowerCase());
            }
            return lower;
        } catch (DataAccessException e) {
            return Set.of();
        }
    }

    // ===================== 预加载与缓存 =====================

    /**
     * 预加载当前用户的所有行为数据，按发件人聚合为 SenderStats。
     */
    private SenderBehaviorCache buildBehaviorCache(Long userId) {
        SenderBehaviorCache cache = new SenderBehaviorCache();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime thirtyDaysAgo = now.minusDays(30);
        LocalDateTime ninetyDaysAgo = now.minusDays(90);

        String sql = """
            SELECT ub.sender_id, ub.behavior_type, ub.created_at,
                   m.send_time AS mail_send_time,
                   mr.receive_time AS mail_receive_time
            FROM user_behavior ub
            LEFT JOIN mail m ON ub.mail_id = m.mail_id
            LEFT JOIN mail_receiver mr ON ub.mail_id = mr.mail_id AND mr.receiver_id = ub.user_id
            WHERE ub.user_id = ?
            ORDER BY ub.created_at ASC
            """;

        try {
            jdbcTemplate.query(sql, rs -> {
                long senderId = rs.getLong("sender_id");
                if (rs.wasNull()) return;
                String type = rs.getString("behavior_type");
                LocalDateTime behaviorTime = rs.getTimestamp("created_at")
                        .toLocalDateTime();
                LocalDateTime mailSendTime = rs.getTimestamp("mail_send_time") != null
                        ? rs.getTimestamp("mail_send_time").toLocalDateTime() : null;
                LocalDateTime mailReceiveTime = rs.getTimestamp("mail_receive_time") != null
                        ? rs.getTimestamp("mail_receive_time").toLocalDateTime() : null;

                SenderStats stats = cache.getOrCreate(senderId);

                switch (type.toUpperCase()) {
                    case "OPEN" -> {
                        stats.openCount++;
                        if (stats.firstOpenTime == null
                                || behaviorTime.isBefore(stats.firstOpenTime)) {
                            stats.firstOpenTime = behaviorTime;
                        }
                    }
                    case "REPLY" -> {
                        stats.replyCount++;
                        if (behaviorTime.isAfter(thirtyDaysAgo)) {
                            stats.replyCount30d++;
                        }
                    }
                    case "DELETE" -> stats.deleteCount++;
                    case "SEND" -> {
                        if (behaviorTime.isAfter(ninetyDaysAgo)) {
                            stats.outboundCount90d++;
                        }
                    }
                }

                // 30天内收到该发件人的邮件数
                if (mailSendTime != null && mailSendTime.isAfter(thirtyDaysAgo)) {
                    stats.totalReceived30d++;
                }

                // 90天内往来总数
                if (behaviorTime.isAfter(ninetyDaysAgo)) {
                    stats.totalInteractions90d++;
                }

                // 90天内收到数（区分于发送）
                if (mailReceiveTime != null && mailReceiveTime.isAfter(ninetyDaysAgo)) {
                    stats.inboundCount90d++;
                }
            }, userId);
        } catch (DataAccessException ignored) {
            // 查询失败返回空缓存
        }

        return cache;
    }

    /**
     * 加载所有被标记为垃圾的邮件ID集合（委托给 SpamService）。
     */
    private Set<Long> loadSpamMailIds() {
        return spamService.getSpamMailIds();
    }

    private String getCurrentUserName(Long userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT username FROM user WHERE user_id = ?",
                    String.class, userId);
        } catch (DataAccessException e) {
            return null;
        }
    }

    // ===================== 旧方法（保持向后兼容） =====================

    /**
     * 计算用户对所有发件人的优先级分数（旧版，兼容现有 API）。
     * 分数规则: 每次 OPEN +2, REPLY +5, DELETE -3。
     */
    public Map<Long, Integer> calculateSenderPriorities(Long userId) {
        String sql = """
            SELECT ub.sender_id, ub.behavior_type
            FROM user_behavior ub
            WHERE ub.user_id = ?
            """;

        Map<Long, Integer> scores = new HashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                long senderId = rs.getLong("sender_id");
                String type = rs.getString("behavior_type");
                int delta = switch (type.toUpperCase()) {
                    case "OPEN" -> SCORE_OPEN;
                    case "REPLY" -> SCORE_REPLY;
                    case "DELETE" -> SCORE_DELETE;
                    default -> 0;
                };
                scores.merge(senderId, delta, Integer::sum);
            }, userId);
        } catch (DataAccessException ignored) {
            // 返回空 Map
        }
        return scores;
    }

    /**
     * 获取按优先级排序的发件人ID列表（高优先级在前）。
     */
    public List<Map<String, Object>> getPrioritySortedSenders(Long userId) {
        Map<Long, Integer> scores = calculateSenderPriorities(userId);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("senderId", e.getKey());
                    m.put("priorityScore", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取用户行为统计摘要。
     */
    public Map<String, Object> getBehaviorSummary(Long userId) {
        LambdaQueryWrapper<UserBehavior> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserBehavior::getUserId, userId);
        List<UserBehavior> behaviors = userBehaviorMapper.selectList(wrapper);

        long openCount = behaviors.stream()
                .filter(b -> "OPEN".equalsIgnoreCase(b.getBehaviorType())).count();
        long replyCount = behaviors.stream()
                .filter(b -> "REPLY".equalsIgnoreCase(b.getBehaviorType())).count();
        long deleteCount = behaviors.stream()
                .filter(b -> "DELETE".equalsIgnoreCase(b.getBehaviorType())).count();

        Map<String, Object> summary = new HashMap<>();
        summary.put("userId", userId);
        summary.put("totalInteractions", behaviors.size());
        summary.put("openCount", openCount);
        summary.put("replyCount", replyCount);
        summary.put("deleteCount", deleteCount);
        summary.put("prioritySenders", getPrioritySortedSenders(userId));
        return summary;
    }

    // ===================== 内部缓存类 =====================

    /**
     * 按发件人聚合的行为统计缓存，避免循环内逐条查询数据库。
     */
    private static class SenderBehaviorCache {
        private final Map<Long, SenderStats> map = new HashMap<>();

        SenderStats getOrCreate(Long senderId) {
            return map.computeIfAbsent(senderId, k -> new SenderStats());
        }

        SenderStats getStats(Long senderId) {
            return map.getOrDefault(senderId, new SenderStats());
        }
    }

    /**
     * 单个发件人的聚合行为统计。
     */
    private static class SenderStats {
        int openCount = 0;
        int replyCount = 0;
        int deleteCount = 0;
        int replyCount30d = 0;
        int totalReceived30d = 0;
        int totalInteractions90d = 0;
        int outboundCount90d = 0;
        int inboundCount90d = 0;
        LocalDateTime firstOpenTime = null;
    }
}
