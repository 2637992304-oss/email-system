package com.shixun.mail.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shixun.common.exception.BusinessException;
import com.shixun.mail.entity.SpamFeedback;
import com.shixun.mail.entity.SpamRecord;
import com.shixun.mail.entity.SpamWarning;
import com.shixun.mail.entity.UserContactList;
import com.shixun.mail.mapper.SpamFeedbackMapper;
import com.shixun.mail.mapper.SpamRecordMapper;
import com.shixun.mail.mapper.SpamWarningMapper;
import com.shixun.mail.mapper.UserContactListMapper;
import javax.annotation.PostConstruct;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SpamService {

    private final SpamRecordMapper spamRecordMapper;
    private final SpamFeedbackMapper spamFeedbackMapper;
    private final UserContactListMapper userContactListMapper;
    private final SpamWarningMapper spamWarningMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SpamDeepLearningEngine dlEngine;
    private final SpamSeedDataGenerator seedDataGenerator;
    private final LlmSpamChecker llmSpamChecker;

    private static final Set<String> SPAM_KEYWORDS = new HashSet<>(Arrays.asList(
            // === 诈骗类 ===
            "免费", "中奖", "代办", "发票", "兼职", "日赚",
            "点击领取", "限时优惠", "100%", "绝对真实",
            "快速致富", "无抵押贷款", "代开发票", "办证",
            "恭喜你", "抽中", "金币", "元宝", "注册送",
            "银行卡", "验证码", "转账", "打款", "保证金",
            "刷单", "返利", "佣金", "垫付", "解冻",
            "网贷", "套现", "征信", "洗钱", "传销",
            "杀猪盘", "资金盘", "庞氏", "互助盘", "拆分盘",
            "冒充公检法", "安全账户", "涉嫌洗钱", "通缉令",
            "逮捕令", "资金冻结", "配合调查", "转账到安全账户",
            "快递丢失", "退款理赔", "订单异常", "系统出错",
            "兼职打字员", "在家赚钱", "月入过万", "日结",
            "恭喜你中了", "幸运用户", "中奖通知", "领取奖金",
            "投资理财", "高收益", "稳赚不赔", "保本年化",
            "内幕消息", "老师带单", "跟单操作", "翻倍",
            "私募", "原始股", "虚拟币", "炒币", "挖矿",
            "充值返现", "漏洞套利", "平台漏洞", "稳赢",
            "裸聊", "视频录屏", "通讯录", "给钱就删",
            "viagra", "casino", "lottery", "winner", "prize",
            "click here", "free money", "act now", "limited offer",
            "guaranteed", "spam", "advertisement",
            "bitcoin investment", "earn money fast", "work from home",
            "congratulations you won", "claim your prize", "you are a winner",
            "bank account", "transfer money", "western union",
            // === 暴力类 ===
            "杀人", "暗杀", "雇凶", "买凶", "灭口",
            "枪支", "手枪", "步枪", "弹药", "炸药",
            "炸弹", "引爆", "爆炸物", "雷管", "手榴弹",
            "恐怖", "恐怖袭击", "圣战", "极端组织", "ISIS",
            "绑架", "勒索", "恐吓信", "人身威胁",
            "强暴", "强奸", "轮奸", "迷药", "春药",
            "逼供", "刑讯", "虐待", "折磨",
            "自杀", "自残", "割腕", "跳楼",
            "追杀", "追杀令", "黑社会", "帮派",
            "人体炸弹", "汽车炸弹", "毒气", "生化武器",
            "砍人", "捅人", "打架斗殴", "群殴",
            "校园暴力", "校园霸凌", "持刀", "持枪",
            "火拼", "枪战", "买枪", "制枪"
    ));

    private static final int FREQUENCY_THRESHOLD = 5;

    // ---- 规则6: 促销关键词库（独立于垃圾关键词，仅降权不拦截） ----
    private static final Set<String> PROMO_KEYWORDS = new HashSet<>(Arrays.asList(
            "优惠券", "折扣", "限时抢购", "免费试用", "点击领取",
            "大促", "双十一", "618", "满减", "包邮", "秒杀",
            "coupon", "discount", "free trial", "limited offer", "clearance",
            "promo", "sale", "flash sale", "促销", "立减", "特价",
            "砍价", "拼团", "返利", "红包"
    ));

    // ---- 规则6: 退订/营销链接标识 ----
    private static final Set<String> UNSUBSCRIBE_MARKERS = new HashSet<>(Arrays.asList(
            "退订", "unsubscribe", "不再接收", "退阅", "取消订阅",
            "opt-out", "opt out", "邮件订阅", "subscription"
    ));

    public SpamService(SpamRecordMapper spamRecordMapper,
                       SpamFeedbackMapper spamFeedbackMapper,
                       UserContactListMapper userContactListMapper,
                       SpamWarningMapper spamWarningMapper,
                       JdbcTemplate jdbcTemplate,
                       SpamDeepLearningEngine dlEngine,
                       SpamSeedDataGenerator seedDataGenerator,
                       LlmSpamChecker llmSpamChecker) {
        this.spamRecordMapper = spamRecordMapper;
        this.spamFeedbackMapper = spamFeedbackMapper;
        this.userContactListMapper = userContactListMapper;
        this.spamWarningMapper = spamWarningMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.dlEngine = dlEngine;
        this.seedDataGenerator = seedDataGenerator;
        this.llmSpamChecker = llmSpamChecker;
    }

    /**
     * 启动时加载已持久化的DL模型。
     * 模型通过LLM关键词+用户反馈在线学习，权重和动态词典从 spam_model 表恢复。
     */
    @PostConstruct
    public void seedFromHistoryOnStartup() {
        if (dlEngine.getTrainingCount() > 0) {
            log.info("[DL-Spam] 模型已加载: 训练步数={}, 准确率EMA={}%",
                    dlEngine.getTrainingCount(),
                    String.format("%.2f", dlEngine.getEmaAccuracy() * 100));
        } else {
            log.info("[DL-Spam] 模型未训练（Xavier初始化），等待LLM关键词+用户反馈在线学习");
        }
    }

    /**
     * 检查邮件是否为垃圾邮件。命中关键词或短时间内大量发送则判定为垃圾。
     */
    public boolean isSpam(Long mailId, Long senderId, String subject, String body) {
        String fullText = (subject + " " + body).toLowerCase();

        List<String> matched = SPAM_KEYWORDS.stream()
                .filter(kw -> fullText.contains(kw.toLowerCase()))
                .collect(Collectors.toList());

        if (!matched.isEmpty()) {
            SpamRecord record = SpamRecord.builder()
                    .mailId(mailId)
                    .spamType("KEYWORD")
                    .hitKeyword(String.join(",", matched))
                    .riskScore(80)
                    .riskReason("命中垃圾关键词: " + String.join(",", matched))
                    .detectTime(LocalDateTime.now())
                    .build();
            spamRecordMapper.insertForMail(record);
            return true;
        }

        long recentCount = countRecentFromSenderSeconds(senderId, 1);
        if (recentCount >= FREQUENCY_THRESHOLD) {
            SpamRecord record = SpamRecord.builder()
                    .mailId(mailId)
                    .spamType("FREQUENCY")
                    .riskScore(Math.min((int) recentCount * 20, 100))
                    .riskReason("发件频率异常: 1秒内发送" + recentCount + "封")
                    .detectTime(LocalDateTime.now())
                    .build();
            spamRecordMapper.insertForMail(record);
            return true;
        }

        // 发件人身份认证: 信誉评分检测
        if (isSuspiciousSender(senderId)) {
            SpamRecord record = SpamRecord.builder()
                    .mailId(mailId)
                    .spamType("SENDER")
                    .riskScore(70)
                    .riskReason(buildSenderRiskReason(senderId))
                    .detectTime(LocalDateTime.now())
                    .build();
            spamRecordMapper.insertForMail(record);
            return true;
        }

        // 动态词典命中: 静态+动态垃圾词直接判定（高置信度）
        if (dlEngine.matchesLearnedSpamWords(subject, body)) {
            var dictMatched = dlEngine.getMatchedLearnedWords(subject, body);
            SpamRecord record = SpamRecord.builder()
                    .mailId(mailId).spamType("DICT")
                    .hitKeyword(String.join(",", dictMatched)).riskScore(85)
                    .riskReason("命中垃圾词: " + String.join(",", dictMatched))
                    .detectTime(LocalDateTime.now()).build();
            spamRecordMapper.insertForMail(record);
            return true;
        }

        // 深度学习概率兜底: 词典未命中时用DL模型判断
        if (dlEngine.isSpamByDL(subject, body)) {
            double prob = dlEngine.getSpamProbability(subject, body);
            SpamRecord record = SpamRecord.builder()
                    .mailId(mailId)
                    .spamType("DL")
                    .hitKeyword("深度学习特征匹配")
                    .riskScore((int) Math.round(prob * 100))
                    .riskReason(String.format(
                            "深度学习模型判定为垃圾邮件 (概率: %.1f%%, 训练步数: %d, 准确率EMA: %.1f%%)",
                            prob * 100, dlEngine.getTrainingCount(),
                            dlEngine.getEmaAccuracy() * 100))
                    .detectTime(LocalDateTime.now())
                    .build();
            spamRecordMapper.insertForMail(record);
            return true;
        }

        return false;
    }

    /**
     * 查询某个邮件的垃圾识别结果
     */
    public SpamRecord getSpamResult(Long mailId) {
        LambdaQueryWrapper<SpamRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SpamRecord::getMailId, mailId);
        return spamRecordMapper.selectOne(wrapper);
    }

    /**
     * 查询所有被标记为垃圾的邮件记录
     */
    public List<SpamRecord> listAllSpam() {
        LambdaQueryWrapper<SpamRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(SpamRecord::getDetectTime);
        return spamRecordMapper.selectList(wrapper);
    }

    // ===================== 规则6: 促销/营销检测（降权但不拦截） =====================

    /**
     * 检测邮件是否为促销/营销邮件。
     * 与 isSpam 不同，此方法只降权，不将邮件扔进垃圾箱。
     */
    public boolean isPromotional(String subject, String body) {
        String fullText = ((subject != null ? subject : "")
                + " " + (body != null ? body : "")).toLowerCase();
        return PROMO_KEYWORDS.stream().anyMatch(kw -> fullText.contains(kw.toLowerCase()));
    }

    /**
     * 检测邮件是否包含退订/营销订阅标识。
     */
    public boolean hasUnsubscribeMarker(String subject, String body) {
        String fullText = ((subject != null ? subject : "")
                + " " + (body != null ? body : "")).toLowerCase();
        return UNSUBSCRIBE_MARKERS.stream().anyMatch(kw -> fullText.contains(kw.toLowerCase()));
    }

    /**
     * 获取发件人的垃圾邮件举报次数（过去90天）。
     */
    public int getSpamReportCount(Long senderId) {
        String sql = """
            SELECT COUNT(*)
            FROM spam_record
            WHERE sender_id = ? AND created_at >= ?
            """;
        try {
            Long count = jdbcTemplate.queryForObject(sql, Long.class,
                    senderId, LocalDateTime.now().minusDays(90));
            return count != null ? count.intValue() : 0;
        } catch (DataAccessException e) {
            return 0;
        }
    }

    /**
     * 获取所有被标记为垃圾的邮件ID集合（供 PriorityService 批量使用）。
     */
    public java.util.Set<Long> getSpamMailIds() {
        String sql = "SELECT mail_id FROM spam_record";
        try {
            return new java.util.HashSet<>(
                    jdbcTemplate.queryForList(sql, Long.class));
        } catch (DataAccessException e) {
            return java.util.Set.of();
        }
    }

    public void recordBlockedSenderSpam(Long mailId, String senderEmail) {
        ensureSpamRecord(mailId, "SENDER", "Sender is in recipient blacklist: " + senderEmail);
    }

    // ---- 促销关键词 getter ----
    public static Set<String> getPromoKeywords() {
        return PROMO_KEYWORDS;
    }

    /**
     * 检查发件人邮箱是否在任意收件人的黑名单中。
     * 用于 sendMail 时拦截黑名单发件人的邮件。
     * @return true 表示已被黑名单拦截，应标记为垃圾
     */
    /**
     * 检查发件人是否在所有收件人的白名单中。
     * @return true 表示所有收件人都将该发件人列入了白名单 → 跳过垃圾检测
     */
    public boolean isSenderWhitelistedByAllRecipients(String senderEmail, java.util.List<Long> recipientIds) {
        if (senderEmail == null || recipientIds == null || recipientIds.isEmpty()) return false;
        String email = senderEmail.toLowerCase().trim();
        for (Long recipientId : new HashSet<>(recipientIds)) {
            if (!userContactListMapper.existsByEmailAndType(recipientId, email, "WHITELIST")) {
                return false; // 有任意一个收件人未加白名单 → 不豁免
            }
        }
        return true;
    }

    public boolean isSenderBlockedByRecipients(String senderEmail, java.util.List<Long> recipientIds) {
        if (senderEmail == null || recipientIds == null || recipientIds.isEmpty()) {
            return false;
        }
        String email = senderEmail.toLowerCase().trim();
        // 用 IN 查询批量检查所有收件人
        String placeholders = recipientIds.stream()
                .map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = String.format("""
            SELECT COUNT(1)
            FROM user_contact_list
            WHERE list_type = 'BLACKLIST'
              AND LOWER(contact_email) = ?
              AND user_id IN (%s)
            """, placeholders);

        Object[] params = new Object[recipientIds.size() + 1];
        params[0] = email;
        for (int i = 0; i < recipientIds.size(); i++) {
            params[i + 1] = recipientIds.get(i);
        }

        try {
            Long count = jdbcTemplate.queryForObject(sql, Long.class, params);
            return count != null && count > 0;
        } catch (DataAccessException e) {
            return false;
        }
    }

    /**
     * 为垃圾邮件发送者创建警告通知（替代自动拉黑）。
     * 已拉黑该发件人的收件人 → 跳过（无需再警告）
     * 已有 PENDING 警告的 → 跳过（避免重复弹窗）
     * 否则 → 创建 PENDING 警告，等待用户决定是否拉黑
     */
    public void createSpamWarnings(String senderEmail, java.util.List<Long> recipientIds, Long mailId) {
        if (senderEmail == null || recipientIds == null || recipientIds.isEmpty()) return;
        String email = senderEmail.toLowerCase().trim();
        List<Long> recipients = recipientIds.stream().distinct().sorted().collect(Collectors.toList());
        String placeholders = String.join(",", Collections.nCopies(recipients.size(), "?"));
        List<Object> lookupArgs = new ArrayList<>();
        lookupArgs.add(email);
        lookupArgs.addAll(recipients);
        try {
            Set<Long> blocked = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT user_id FROM user_contact_list WHERE contact_email=? AND list_type='BLACKLIST' "
                    + "AND user_id IN (" + placeholders + ")", Long.class, lookupArgs.toArray()));
            List<Long> eligible = recipients.stream().filter(id -> !blocked.contains(id)).collect(Collectors.toList());
            if (eligible.isEmpty()) return;

            List<Object> deleteArgs = new ArrayList<>();
            deleteArgs.add(email);
            deleteArgs.addAll(eligible);
            jdbcTemplate.update("DELETE FROM spam_warning WHERE sender_email=? AND recipient_id IN ("
                + String.join(",", Collections.nCopies(eligible.size(), "?")) + ")", deleteArgs.toArray());
            jdbcTemplate.batchUpdate(
                "INSERT INTO spam_warning(recipient_id,sender_email,mail_id,status,created_at) VALUES (?,?,?,'PENDING',NOW())",
                eligible, 100, (statement, recipientId) -> {
                    statement.setLong(1, recipientId);
                    statement.setString(2, email);
                    statement.setLong(3, mailId);
                });
        } catch (DataAccessException ignored) {
            // Warning writes are supplementary and do not prevent delivery.
        }
    }

    /**
     * 获取用户的待处理垃圾邮件警告列表。
     */
    public List<SpamWarning> getPendingWarnings(Long userId) {
        LambdaQueryWrapper<SpamWarning> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SpamWarning::getRecipientId, userId)
               .eq(SpamWarning::getStatus, "PENDING")
               .orderByDesc(SpamWarning::getCreatedAt);
        return spamWarningMapper.selectList(wrapper);
    }

    /**
     * 用户选择拉黑发件人。
     */
    public void blockSender(Long warningId, Long userId) {
        try {
            SpamWarning warning = spamWarningMapper.selectById(warningId);
            if (warning == null) {
                throw BusinessException.of(404, "Spam warning not found");
            }
            if (!warning.getRecipientId().equals(userId)) {
                throw BusinessException.of(403, "No permission to handle this spam warning");
            }

            forceAddToBlacklist(userId, warning.getSenderEmail());
            // 标记该邮件为垃圾邮件并训练深度学习模型
            ensureSpamRecord(warning.getMailId(), "USER_BLOCK", "用户拉黑发件人");
            updateMailSpamFlag(warning.getMailId(), true);
            trainOnMail(warning.getMailId(), 1);

            warning.setStatus("BLOCKED");
            warning.setResolvedAt(LocalDateTime.now());
            spamWarningMapper.updateById(warning);
        } catch (DataAccessException e) {
            log.warn("拉黑发件人失败 (warningId={}, userId={}): {}", warningId, userId, e.getMessage());
            throw new RuntimeException("拉黑操作失败，请稍后重试", e);
        }
    }

    /**
     * 拉黑发件人 + LLM关键词提取 + 深度学习训练，返回完整的DL分析和LLM关键词。
     * 与 reportSpam 流程一致：先拉黑，再用LLM提取关键词加入动态词典，然后训练DL模型。
     * @return 包含 dlProbability, dlTopFeatures, llmExtractedWords 等的 Map
     */
    public java.util.Map<String, Object> blockSenderWithAnalysis(Long warningId, Long userId) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        try {
            // 1. 先获取警告信息（需要在blockSender之前拿到mailId）
            SpamWarning warning = spamWarningMapper.selectById(warningId);
            if (warning == null) return result;
            Long mailId = warning.getMailId();

            // 2. 执行拉黑和基础训练（含 ensureSpamRecord + trainOnMail）
            blockSender(warningId, userId);

            // 3. 获取邮件内容
            String sql = "SELECT subject, content FROM mail WHERE mail_id = ?";
            var row = jdbcTemplate.queryForMap(sql, mailId);
            if (row == null) return result;
            String subject = (String) row.getOrDefault("subject", "");
            String body = (String) row.getOrDefault("content", "");

            // 4. 用LLM提取关键词，走新DL逻辑：trainWithWords + updateDictionaryFromWords
            var llmWords = llmSpamChecker.extractKeywords(subject, body);
            if (llmWords == null) llmWords = java.util.List.of();
            if (!llmWords.isEmpty()) {
                dlEngine.trainWithWords(llmWords, 1, 1);
                dlEngine.updateDictionaryFromWords(llmWords, 1);
            }

            // 5. 辅助：原始文本n-gram训练
            trainOnMail(mailId, 1);

            // 6. 获取训练后的DL分析
            double prob = dlEngine.getSpamProbability(subject, body);
            var features = dlEngine.getTopFeatures(subject, body, 8);
            result.put("dlProbability", Math.round(prob * 10000) / 100.0);
            result.put("dlTopFeatures", features.stream()
                    .map(f -> java.util.Map.of("text", f.text, "weight", f.weight, "probability", f.probability))
                    .collect(java.util.stream.Collectors.toList()));

            // 7. LLM关键词的DL概率（使用新sigmoid概率）
            var llmWordWeights = new java.util.ArrayList<java.util.Map<String, Object>>();
            for (String w : llmWords) {
                int hash = Math.abs(w.hashCode() * 7 + w.length() * 13) % 256;
                double raw = dlEngine.getSignedRowWeightByHash(hash);
                double wProb = dlEngine.getWordSpamProbability(w);
                llmWordWeights.add(java.util.Map.of(
                        "text", w,
                        "weight", Math.round(raw * 10000.0) / 10000.0,
                        "probability", Math.round(wProb * 10000.0) / 10000.0
                ));
            }
            result.put("llmExtractedWords", llmWordWeights);

            result.put("trainingCount", dlEngine.getTrainingCount());
            result.put("emaAccuracy", Math.round(dlEngine.getEmaAccuracy() * 10000) / 100.0);

        } catch (DataAccessException e) {
            log.warn("拉黑并分析失败 (warningId={}): {}", warningId, e.getMessage());
        }
        return result;
    }

    /**
     * 强制将邮箱加入黑名单（黑名单最高优先级）。
     * 如果该邮箱已在白名单/VIP中，先移除再加入黑名单。
     */
    public void forceAddToBlacklist(Long userId, String email) {
        String normalizedEmail = email.toLowerCase().trim();
        // 不能拉黑自己
        if (isOwnEmail(userId, normalizedEmail)) return;
        try {
            LambdaQueryWrapper<UserContactList> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserContactList::getUserId, userId)
                   .eq(UserContactList::getContactEmail, normalizedEmail);
            userContactListMapper.delete(wrapper);

            UserContactList contact = UserContactList.builder()
                    .userId(userId)
                    .contactEmail(normalizedEmail)
                    .listType("BLACKLIST")
                    .createdAt(LocalDateTime.now())
                    .build();
            userContactListMapper.insert(contact);
        } catch (DataAccessException ignored) {}
    }

    /** 获取邮件的第一个收件人ID */
    private Long getFirstRecipientId(Long mailId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT receiver_id FROM mail_receiver WHERE mail_id = ? LIMIT 1",
                    Long.class, mailId);
        } catch (DataAccessException e) {
            return null;
        }
    }

    /** 检查 email 是否就是 userId 自己的邮箱 */
    private boolean isOwnEmail(Long userId, String email) {
        try {
            String ownEmail = jdbcTemplate.queryForObject(
                    "SELECT email FROM user WHERE user_id = ?", String.class, userId);
            return ownEmail != null && ownEmail.equalsIgnoreCase(email);
        } catch (DataAccessException e) {
            return false;
        }
    }

    /**
     * 用户选择忽略警告（不拉黑）。
     */
    public void ignoreWarning(Long warningId, Long userId) {
        try {
            SpamWarning warning = spamWarningMapper.selectById(warningId);
            if (warning == null) {
                throw BusinessException.of(404, "Spam warning not found");
            }
            if (!warning.getRecipientId().equals(userId)) {
                throw BusinessException.of(403, "No permission to handle this spam warning");
            }

            warning.setStatus("IGNORED");
            warning.setResolvedAt(LocalDateTime.now());
            spamWarningMapper.updateById(warning);
        } catch (DataAccessException e) {
            log.warn("忽略警告失败 (warningId={}, userId={}): {}", warningId, userId, e.getMessage());
            throw new RuntimeException("忽略操作失败，请稍后重试", e);
        }
    }

    // ---- 退订标识 getter ----
    public static Set<String> getUnsubscribeMarkers() {
        return UNSUBSCRIBE_MARKERS;
    }

    /** 获取深度学习引擎（供 Controller 查询模型状态） */
    public SpamDeepLearningEngine getDlEngine() { return dlEngine; }

    /** 获取大模型检测器（供 Controller 提取关键词） */
    public LlmSpamChecker getLlmChecker() { return llmSpamChecker; }

    /** 获取某条警告对应的DL学习分析 */
    public java.util.Map<String, Object> getDlAnalysisByWarning(Long warningId) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        try {
            // 查 mail_id
            SpamWarning w = spamWarningMapper.selectById(warningId);
            if (w == null) return result;
            // 查邮件内容
            String sql = "SELECT subject, content FROM mail WHERE mail_id = ?";
            var row = jdbcTemplate.queryForMap(sql, w.getMailId());
            if (row == null) return result;
            String subject = (String) row.getOrDefault("subject", "");
            String body = (String) row.getOrDefault("content", "");
            double prob = dlEngine.getSpamProbability(subject, body);
            var features = dlEngine.getTopFeatures(subject, body, 8);
            result.put("dlProbability", Math.round(prob * 10000) / 100.0);
            result.put("dlTopFeatures", features.stream()
                    .map(f -> java.util.Map.of("text", f.text, "weight", f.weight, "probability", f.probability))
                    .collect(java.util.stream.Collectors.toList()));
            result.put("trainingCount", dlEngine.getTrainingCount());
            result.put("emaAccuracy", Math.round(dlEngine.getEmaAccuracy() * 10000) / 100.0);
        } catch (DataAccessException e) {
            log.warn("获取DL分析失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 发件人身份认证: 信誉评分检测，两项指标任一命中即判定为可疑。
     *   指标1: 新注册账号 (24h内) 且 1分钟内发送 >50 封 → 批量注册骚扰号
     *   指标2: 90天内有垃圾前科 ≥ 50 次
     */
    private boolean isSuspiciousSender(Long senderId) {
        try {
            // 指标1: 新注册账号 + 1分钟内大量发件 (>50封)
            String newAccountSql = """
                SELECT COUNT(*)
                FROM mail m
                JOIN user u ON m.sender_id = u.user_id
                WHERE m.sender_id = ?
                  AND u.register_time >= ?
                  AND m.send_time >= ?
                  AND m.mail_status = 1
                """;
            LocalDateTime oneDayAgo = LocalDateTime.now().minusHours(24);
            LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
            Long newAccountFloodCount = jdbcTemplate.queryForObject(
                    newAccountSql, Long.class, senderId, oneDayAgo, oneMinuteAgo);
            if (newAccountFloodCount != null && newAccountFloodCount > 50) {
                return true;
            }

            // 指标2: 90天内有垃圾前科 ≥ 50 次
            String spamHistorySql = """
                SELECT COUNT(*)
                FROM spam_record
                WHERE sender_id = ? AND created_at >= ?
                """;
            LocalDateTime ninetyDaysAgo = LocalDateTime.now().minusDays(90);
            Long spamHistoryCount = jdbcTemplate.queryForObject(
                    spamHistorySql, Long.class, senderId, ninetyDaysAgo);
            if (spamHistoryCount != null && spamHistoryCount >= 50) {
                return true;
            }
        } catch (DataAccessException e) {
            return false;
        }
        return false;
    }

    /**
     * 构建发件人风险原因描述。
     */
    private String buildSenderRiskReason(Long senderId) {
        StringBuilder sb = new StringBuilder("发件人身份异常: ");
        try {
            // 新号刷屏检查
            LocalDateTime oneDayAgo = LocalDateTime.now().minusHours(24);
            LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
            Long floodCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM mail m JOIN user u ON m.sender_id = u.user_id WHERE m.sender_id = ? AND u.register_time >= ? AND m.send_time >= ? AND m.mail_status = 1",
                    Long.class, senderId, oneDayAgo, oneMinuteAgo);
            if (floodCount != null && floodCount > 50) {
                sb.append("新注册账号1分钟内发件").append(floodCount).append("封; ");
            }

            // 垃圾前科检查
            Long spamCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM spam_record WHERE sender_id = ? AND created_at >= ?",
                    Long.class, senderId, LocalDateTime.now().minusDays(90));
            if (spamCount != null && spamCount >= 50) {
                sb.append("有垃圾邮件前科(").append(spamCount).append("次); ");
            }
        } catch (DataAccessException ignored) {
            sb.append("检测异常");
        }
        return sb.toString();
    }

    /**
     * 统计最近 N 分钟内某个发件人发送的垃圾邮件数
     */
    private long countRecentFromSender(Long senderId, int minutes) {
        String sql = """
            SELECT COUNT(*)
            FROM spam_record
            WHERE sender_id = ? AND created_at >= ?
            """;
        try {
            Long count = jdbcTemplate.queryForObject(sql, Long.class,
                    senderId, LocalDateTime.now().minusMinutes(minutes));
            return count != null ? count : 0;
        } catch (DataAccessException e) {
            return 0;
        }
    }

    private long countRecentFromSenderSeconds(Long senderId, int seconds) {
        String sql = """
            SELECT COUNT(*)
            FROM spam_record
            WHERE sender_id = ? AND created_at >= ?
            """;
        try {
            Long count = jdbcTemplate.queryForObject(sql, Long.class,
                    senderId, LocalDateTime.now().minusSeconds(seconds));
            return count != null ? count : 0;
        } catch (DataAccessException e) {
            return 0;
        }
    }

    // ===================== 种子训练 + 深度学习在线训练 =====================

    /**
     * 种子训练深度学习模型。
     * 1. 使用 SpamSeedDataGenerator 生成 ~1400 条合成训练数据作为基础
     * 2. 使用历史上已标记的垃圾邮件 + 正常邮件作为补充
     * 3. 打乱后分 3 个 epoch 训练
     *
     * 训练结束后持久化模型，重启后无需重新训练。
     */
    public void seedFromHistory() {
        try {
            // ---- 1. 合成种子数据（主要训练数据来源） ----
            List<SpamSeedDataGenerator.SeedSample> syntheticSamples =
                    seedDataGenerator.generateAllSamples();

            // ---- 2. 历史标记数据作为补充 ----
            List<MailText> historySpam = loadHistorySpam();
            List<MailText> historyHam = loadHistoryHam(historySpam.size());

            // ---- 3. 合并所有样本 ----
            List<int[]> schedule = new java.util.ArrayList<>();
            for (SpamSeedDataGenerator.SeedSample s : syntheticSamples) {
                schedule.add(new int[]{s.label, schedule.size()});
            }
            // 将合成样本的文本也存下来用于训练
            java.util.List<String> allTexts = new java.util.ArrayList<>();
            java.util.List<Integer> allLabels = new java.util.ArrayList<>();
            for (SpamSeedDataGenerator.SeedSample s : syntheticSamples) {
                allTexts.add(s.text);
                allLabels.add(s.label);
            }

            int synthTotal = syntheticSamples.size();
            log.info("[DL-Spam] 种子训练开始: 合成数据={}条, 历史垃圾={}条, 历史正常={}条",
                    synthTotal, historySpam.size(), historyHam.size());

            // ---- 4. 训练: 3 个 epoch，每个 epoch 随机打乱 ----
            int totalTrained = 0;
            for (int epoch = 0; epoch < 3; epoch++) {
                // 合并合成 + 历史数据索引
                schedule.clear();
                for (int i = 0; i < allTexts.size(); i++) {
                    schedule.add(new int[]{allLabels.get(i), i, 0}); // 0=合成
                }
                for (int i = 0; i < historySpam.size(); i++) {
                    schedule.add(new int[]{1, i, 1}); // 1=历史
                }
                for (int i = 0; i < historyHam.size(); i++) {
                    schedule.add(new int[]{0, i, 2}); // 2=历史
                }
                java.util.Collections.shuffle(schedule);

                for (int[] entry : schedule) {
                    int label = entry[0];
                    int idx = entry[1];
                    int source = entry[2];

                    String text;
                    if (source == 0) {
                        text = allTexts.get(idx);
                    } else if (source == 1) {
                        MailText mt = historySpam.get(idx);
                        text = mt.subject + " " + mt.content;
                    } else {
                        MailText mt = historyHam.get(idx);
                        text = mt.subject + " " + mt.content;
                    }
                    // 种子数据 text 本身已包含主题+正文，直接用作训练
                    dlEngine.train(text, "", label);
                    totalTrained++;
                }
            }

            // 持久化
            dlEngine.saveModel();
            log.info("[DL-Spam] 种子训练完成: 总步数={}, 准确率EMA={:.2f}%",
                    totalTrained, dlEngine.getEmaAccuracy() * 100);

        } catch (DataAccessException e) {
            log.warn("[DL-Spam] 种子训练失败（DB异常）: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[DL-Spam] 种子训练失败: {}", e.getMessage());
        }
    }

    private List<MailText> loadHistorySpam() {
        try {
            String sql = """
                SELECT m.subject, m.content
                FROM spam_record sr JOIN mail m ON sr.mail_id = m.mail_id
                WHERE sr.spam_type IN ('KEYWORD', 'FREQUENCY', 'SENDER')
                """;
            return jdbcTemplate.query(sql, (rs, rowNum) -> new MailText(
                    rs.getString("subject"), rs.getString("content")));
        } catch (DataAccessException e) {
            return java.util.List.of();
        }
    }

    private List<MailText> loadHistoryHam(int limit) {
        if (limit <= 0) limit = 100;
        try {
            String sql = """
                SELECT m.subject, m.content FROM mail m
                WHERE m.mail_id NOT IN (SELECT mail_id FROM spam_record)
                  AND m.mail_status = 1
                ORDER BY RAND() LIMIT ?
                """;
            return jdbcTemplate.query(sql, (rs, rowNum) -> new MailText(
                    rs.getString("subject"), rs.getString("content")), limit);
        } catch (DataAccessException e) {
            return java.util.List.of();
        }
    }

    /** 内部类: 邮件文本载体 */
    private static class MailText {
        final String subject;
        final String content;
        MailText(String subject, String content) {
            this.subject = subject != null ? subject : "";
            this.content = content != null ? content : "";
        }
    }

    /**
     * 用户举报邮件为垃圾邮件。
     * 1. 记录反馈到 spam_feedback 表
     * 2. 如果邮件尚未被标记为垃圾，创建 spam_record（类型 USER_REPORT）
     * 3. 将邮件内容送入深度学习引擎进行在线训练（label=1）
     * 4. 持久化更新后的模型
     */
    /** 举报并传入LLM提取的关键词（只调一次LLM，避免重复API调用） */
    public void reportSpamWithWords(Long mailId, Long userId, java.util.List<String> llmWords) {
        requireExistingAccessibleMail(mailId, userId);
        saveFeedback(userId, mailId, "REPORT_SPAM");
        ensureSpamRecord(mailId, "USER_REPORT", "用户举报为垃圾邮件");
        updateMailSpamFlag(mailId, true);
        // LLM提取的词 → DL训练 → 概率>0.5才加入动态词典
        if (llmWords != null && !llmWords.isEmpty()) {
            dlEngine.trainWithWords(llmWords, 1, 1);
            dlEngine.updateDictionaryFromWords(llmWords, 1);
        }
        // 辅助：原始文本n-gram训练
        trainOnMail(mailId, 1);
    }

    public void reportSpam(Long mailId, Long userId) {
        requireExistingAccessibleMail(mailId, userId);
        // 记录用户反馈
        saveFeedback(userId, mailId, "REPORT_SPAM");

        // 如果还没有 spam_record，补充一条
        ensureSpamRecord(mailId, "USER_REPORT", "用户举报为垃圾邮件");
        updateMailSpamFlag(mailId, true);

        // 深度学习在线学习: label=1 (垃圾)
        trainOnMail(mailId, 1);
    }

    /**
     * 用户将垃圾邮件标记为正常邮件（撤销误判）。
     * 1. 记录反馈到 spam_feedback 表
     * 2. 删除该邮件的 spam_record（如果有）
     * 3. 将邮件内容送入深度学习引擎进行在线训练（label=0）
     * 4. 持久化更新后的模型
     */
    public void reportNotSpam(Long mailId, Long userId) {
        requireExistingAccessibleMail(mailId, userId);
        // 记录用户反馈
        saveFeedback(userId, mailId, "NOT_SPAM");

        // 删除误判的 spam_record
        LambdaQueryWrapper<SpamRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SpamRecord::getMailId, mailId);
        spamRecordMapper.delete(wrapper);
        updateMailSpamFlag(mailId, false);

        // 深度学习在线学习: label=0 (非垃圾)
        trainOnMail(mailId, 0);
    }

    /**
     * 用户将垃圾邮件标记为正常 — 带LLM关键词的反向学习。
     * 1. LLM提取垃圾信号词 → 反向训练(label=0)
     * 2. 同时把邮件中所有可识别词（FMM分词）也做反向训练，确保正常词不被误判
     */
    public void reportNotSpamWithWords(Long mailId, Long userId, java.util.List<String> llmWords) {
        requireExistingAccessibleMail(mailId, userId);
        saveFeedback(userId, mailId, "NOT_SPAM");

        LambdaQueryWrapper<SpamRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SpamRecord::getMailId, mailId);
        spamRecordMapper.delete(wrapper);
        updateMailSpamFlag(mailId, false);

        // 收集所有需要反向训练的词：LLM词 + 邮件FMM分词
        java.util.Set<String> allWords = new java.util.LinkedHashSet<>();
        if (llmWords != null) allWords.addAll(llmWords);
        // 补充邮件原文FMM分词（正常邮件LLM可能提取不到垃圾词，但所有词都该学）
        try {
            String sql = "SELECT subject, content FROM mail WHERE mail_id = ?";
            var row = jdbcTemplate.queryForMap(sql, mailId);
            if (row != null) {
                String text = (row.get("subject") != null ? row.get("subject").toString() : "")
                        + " " + (row.get("content") != null ? row.get("content").toString() : "");
                allWords.addAll(dlEngine.segmentTextToWords(text));
            }
        } catch (DataAccessException ignored) {}

        if (!allWords.isEmpty()) {
            java.util.List<String> wordsList = new java.util.ArrayList<>(allWords);
            dlEngine.trainWithWords(wordsList, 0, 1);
            dlEngine.updateDictionaryFromWords(wordsList, 0);
        }
        // 辅助：原始文本n-gram训练
        trainOnMail(mailId, 0);
    }

    /**
     * 查询某封邮件被举报的次数。
     */
    public int getSpamReportCountByMail(Long mailId) {
        String sql = "SELECT COUNT(*) FROM spam_feedback WHERE mail_id = ? AND feedback_type = 'REPORT_SPAM'";
        try {
            Long count = jdbcTemplate.queryForObject(sql, Long.class, mailId);
            return count != null ? count.intValue() : 0;
        } catch (DataAccessException e) {
            return 0;
        }
    }

    /** 持久化用户反馈记录（幂等：同一用户对同一邮件只保留一条） */
    private void saveFeedback(Long userId, Long mailId, String type) {
        try {
            // 先删旧记录（如果有），实现覆盖式更新
            String delSql = "DELETE FROM spam_feedback WHERE user_id = ? AND mail_id = ?";
            jdbcTemplate.update(delSql, userId, mailId);

            SpamFeedback feedback = SpamFeedback.builder()
                    .userId(userId)
                    .mailId(mailId)
                    .feedbackType(type)
                    .createdAt(LocalDateTime.now())
                    .build();
            spamFeedbackMapper.insert(feedback);
        } catch (DataAccessException e) {
            log.warn("保存用户反馈失败 (userId={}, mailId={}): {}", userId, mailId, e.getMessage());
        }
    }

    /** 同步 mail.is_spam，确保前端列表刷新后能立即反映用户反馈。 */
    private void updateMailSpamFlag(Long mailId, boolean spam) {
        try {
            jdbcTemplate.update("UPDATE mail SET is_spam = ? WHERE mail_id = ?", spam, mailId);
        } catch (DataAccessException e) {
            log.warn("更新邮件垃圾标记失败 (mailId={}): {}", mailId, e.getMessage());
        }
    }

    /** 确保 spam_record 存在（避免重复插入） */
    private void ensureSpamRecord(Long mailId, String spamType, String reason) {
        LambdaQueryWrapper<SpamRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SpamRecord::getMailId, mailId);
        if (spamRecordMapper.selectCount(wrapper) > 0) return;

        SpamRecord record = SpamRecord.builder()
                .mailId(mailId)
                .spamType(spamType)
                .riskScore(85)
                .riskReason(reason)
                .detectTime(LocalDateTime.now())
                .build();
        try {
            spamRecordMapper.insertForMail(record);
        } catch (DataAccessException ignored) {
            // 并发插入导致的唯一键冲突，忽略
        }
    }

    /** 获取邮件内容并送入深度学习引擎训练 */
    private void trainOnMail(Long mailId, int label) {
        try {
            String sql = "SELECT subject, content FROM mail WHERE mail_id = ?";
            var row = jdbcTemplate.queryForMap(sql, mailId);
            if (row != null) {
                String subject = (String) row.getOrDefault("subject", "");
                String body = (String) row.getOrDefault("content", "");
                dlEngine.train(subject, body, label);
            }
        } catch (DataAccessException e) {
            log.warn("深度学习训练失败 (mailId={}): {}", mailId, e.getMessage());
        }
    }

    private void requireExistingAccessibleMail(Long mailId, Long userId) {
        if (mailId == null || mailId <= 0) {
            throw BusinessException.of(400, "Invalid mail ID");
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM mail WHERE mail_id = ? AND mail_status = 1",
                Integer.class, mailId);
        if (exists == null || exists == 0) {
            throw BusinessException.of(404, "Mail not found");
        }
        Integer access = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM mail m
                WHERE m.mail_id = ?
                  AND (
                      m.sender_id = ?
                      OR EXISTS (
                          SELECT 1
                          FROM mail_receiver mr
                          WHERE mr.mail_id = m.mail_id
                            AND mr.receiver_id = ?
                      )
                  )
                """, Integer.class, mailId, userId, userId);
        if (access == null || access == 0) {
            throw BusinessException.of(403, "No permission to operate this mail");
        }
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SpamService.class);
}
