package com.shixun.mail.controller;

import com.shixun.common.api.ApiResponse;
import com.shixun.common.exception.BusinessException;
import com.shixun.mail.entity.SpamRecord;
import com.shixun.mail.service.MailAiService;
import com.shixun.mail.service.MailService;
import com.shixun.mail.service.NotificationSseHub;
import com.shixun.mail.service.NotificationService;
import com.shixun.mail.service.PriorityService;
import com.shixun.mail.service.SpamService;
import com.shixun.mail.vo.MailListVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mail")
public class SmartFeatureController {

    private final PriorityService priorityService;
    private final NotificationService notificationService;
    private final SpamService spamService;
    private final MailService mailService;
    private final MailAiService mailAiService;
    private final NotificationSseHub notificationSseHub;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SmartFeatureController.class);

    public SmartFeatureController(PriorityService priorityService,
                                  NotificationService notificationService,
                                  SpamService spamService,
                                  MailService mailService,
                                  MailAiService mailAiService,
                                  NotificationSseHub notificationSseHub) {
        this.priorityService = priorityService;
        this.notificationService = notificationService;
        this.spamService = spamService;
        this.mailService = mailService;
        this.mailAiService = mailAiService;
        this.notificationSseHub = notificationSseHub;
    }

    /**
     * 邮件优先级排序（旧版，保持兼容）— 成员6
     */
    @GetMapping("/priority")
    public ApiResponse<Map<String, Object>> priority(@RequestParam(required = false) Long userId) {
        Long currentUserId = com.shixun.common.security.AuthUserContext.getUserId();
        if (currentUserId == null) {
            throw BusinessException.of(401, "Unauthorized");
        }
        return ApiResponse.success(priorityService.getBehaviorSummary(currentUserId));
    }

    /**
     * 邮件智能整理 — 按七规则综合评分排序后返回完整邮件列表。
     * 请求体: { "userId": 1, "folderKey": "inbox" | "sent" | "trash" }
     */
    @PostMapping("/organize")
    public ApiResponse<List<MailListVO>> organize(@RequestBody(required = false) Map<String, Object> body) {
        Long userId = com.shixun.common.security.AuthUserContext.getUserId();
        if (userId == null) {
            throw BusinessException.of(401, "Unauthorized");
        }
        String folderKey = body == null ? "inbox" : body.getOrDefault("folderKey", "inbox").toString();

        List<MailListVO> sorted;
        switch (folderKey) {
            case "sent" -> sorted = priorityService.sortMailList(
                    mailService.getSentMails(), userId);
            case "trash" -> sorted = priorityService.sortMailList(
                    mailService.getTrashMails(), userId);
            default -> sorted = priorityService.sortMailList(
                    mailService.getInbox(), userId);
        }
        return ApiResponse.success("整理完成", sorted);
    }

    @PostMapping("/ai/translate")
    public ApiResponse<Map<String, String>> translateMail(@RequestBody Map<String, String> body) {
        if (body == null) {
            throw BusinessException.of(400, "Request body is required");
        }
        String result = mailAiService.translateToChinese(body.get("subject"), body.get("content"));
        return ApiResponse.success(Map.of("result", result));
    }

    @PostMapping("/ai/summary")
    public ApiResponse<Map<String, String>> summarizeMail(@RequestBody Map<String, String> body) {
        if (body == null) {
            throw BusinessException.of(400, "Request body is required");
        }
        String result = mailAiService.summarize(body.get("subject"), body.get("content"));
        return ApiResponse.success(Map.of("result", result));
    }

    /**
     * 新邮件通知查询 — 成员6
     */
    @GetMapping("/notification")
    public ApiResponse<Map<String, Object>> notification(@RequestParam Long userId) {
        return ApiResponse.success(notificationService.getUnreadNotifications(com.shixun.common.security.AuthUserContext.getUserId()));
    }

    /**
     * SSE 实时通知订阅 — 前端通过 EventSource 连接，新邮件到达时自动推送。
     * URL 示例: /api/mail/notification/stream?token=xxx
     * EventSource 无法设置 Header，token 通过 query param 传入。
     */
    @GetMapping("/notification/stream")
    public SseEmitter notificationStream() {
        Long userId = com.shixun.common.security.AuthUserContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("未登录");
        }
        return notificationSseHub.subscribe(userId);
    }

    /**
     * 当前 SSE 在线用户数（调试用）。
     */
    @GetMapping("/notification/online-count")
    public ApiResponse<Map<String, Object>> onlineCount() {
        return ApiResponse.success(Map.of("onlineCount", notificationSseHub.getOnlineCount()));
    }

    /**
     * 垃圾邮件记录查询 — 成员6（额外接口，方便演示）
     */
    @GetMapping("/spam-records")
    public ApiResponse<List<SpamRecord>> spamRecords() {
        return ApiResponse.success(spamService.listAllSpam());
    }

    /**
     * 垃圾邮件 ID 集合（供前端判断用）
     */
    @GetMapping("/spam-ids")
    public ApiResponse<java.util.Set<Long>> spamIds() {
        return ApiResponse.success(spamService.getSpamMailIds());
    }

    // ===================== 用户反馈 + 深度学习在线训练 =====================

    /**
     * 用户举报邮件为垃圾邮件 — 触发深度学习在线学习。
     * URL: POST /api/mail/{mailId}/report-spam
     */
    @PostMapping("/{mailId}/report-spam")
    public ApiResponse<Map<String, Object>> reportSpam(@PathVariable Long mailId) {
        Long userId = com.shixun.common.security.AuthUserContext.getUserId();
        if (userId == null) throw new RuntimeException("未登录");
        try {
            var detail = mailService.getMailDetail(mailId);
            if (detail == null) throw new RuntimeException("邮件不存在");
            String subject = detail.getSubject() != null ? detail.getSubject() : "";
            String body = detail.getContent() != null ? detail.getContent() : "";

            double dlProb = spamService.getDlEngine().getSpamProbability(subject, body);
            var topFeatures = spamService.getDlEngine().getTopFeatures(subject, body, 10);

            // 先提取关键词（只调一次LLM）
            var llmWords = spamService.getLlmChecker().extractKeywords(subject, body);
            if (llmWords == null) llmWords = java.util.List.of();

            // 举报（传入LLM提取的词，避免重复调用API）
            spamService.reportSpamWithWords(mailId, userId, llmWords);
            if (llmWords == null) llmWords = java.util.List.of();
            var llmWordWeights = new java.util.ArrayList<java.util.Map<String, Object>>();
            for (String w : llmWords) {
                int hash = Math.abs(w.hashCode() * 7 + w.length() * 13) % 256;
                double raw = spamService.getDlEngine().getSignedRowWeightByHash(hash);
                double prob = spamService.getDlEngine().getWordSpamProbability(w);
                llmWordWeights.add(java.util.Map.of("text", w, "weight", Math.round(raw*10000.0)/10000.0, "probability", Math.round(prob*10000.0)/10000.0));
            }
            return ApiResponse.success("已举报为垃圾邮件，深度学习模型已更新", Map.of(
                    "mailId", mailId, "action", "REPORT_SPAM",
                    "dlProbability", Math.round(dlProb * 10000) / 100.0,
                    "dlTopFeatures", topFeatures.stream()
                            .map(f -> Map.of("text", f.text, "weight", f.weight, "probability", f.probability))
                            .collect(java.util.stream.Collectors.toList()),
                    "llmExtractedWords", llmWordWeights,
                    "trainingCount", spamService.getDlEngine().getTrainingCount(),
                    "emaAccuracy", Math.round(spamService.getDlEngine().getEmaAccuracy() * 10000) / 100.0
            ));
        } catch (Exception e) {
            log.warn("举报处理异常: {}", e.getMessage());
            return ApiResponse.failure(500, "举报处理失败: " + e.getMessage());
        }
    }

    /**
     * 用户将垃圾邮件标记为正常邮件（撤销误判）— 触发深度学习在线纠错。
     */
    @PostMapping("/{mailId}/report-not-spam")
    public ApiResponse<Map<String, Object>> reportNotSpam(@PathVariable Long mailId) {
        Long userId = com.shixun.common.security.AuthUserContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("未登录");
        }
        var detail = mailService.getMailDetail(mailId);
        if (detail == null) throw new RuntimeException("邮件不存在");
        String subject = detail.getSubject() != null ? detail.getSubject() : "";
        String body = detail.getContent() != null ? detail.getContent() : "";

        double dlProb = spamService.getDlEngine().getSpamProbability(subject, body);
        var topFeatures = spamService.getDlEngine().getTopFeatures(subject, body, 10);

        // LLM提取关键词用于反向学习（label=0, 降低垃圾词权重）
        var llmWords = spamService.getLlmChecker().extractKeywords(subject, body);
        if (llmWords == null) llmWords = java.util.List.of();

        spamService.reportNotSpamWithWords(mailId, userId, llmWords);
        return ApiResponse.success("已标记为正常邮件，深度学习模型已纠错", Map.of(
                "mailId", mailId,
                "action", "NOT_SPAM",
                "dlProbability", Math.round(dlProb * 10000) / 100.0,
                "dlTopFeatures", topFeatures.stream()
                        .map(f -> Map.of("text", f.text, "weight", f.weight, "probability", f.probability))
                        .collect(java.util.stream.Collectors.toList()),
                "trainingCount", spamService.getDlEngine().getTrainingCount(),
                "emaAccuracy", Math.round(spamService.getDlEngine().getEmaAccuracy() * 10000) / 100.0
        ));
    }

    /**
     * 获取某封邮件的DL分析（概率+学到的词）。
     */
    @GetMapping("/{mailId}/dl-analysis")
    public ApiResponse<Map<String, Object>> dlAnalysis(@PathVariable Long mailId) {
        Long userId = com.shixun.common.security.AuthUserContext.getUserId();
        if (userId == null) throw BusinessException.of(401, "Unauthorized");
        var detail = mailService.getMailDetail(mailId);
        if (detail == null) throw BusinessException.of(404, "邮件不存在");
        double prob = spamService.getDlEngine().getSpamProbability(detail.getSubject(), detail.getContent());
        var features = spamService.getDlEngine().getTopFeatures(detail.getSubject(), detail.getContent(), 10);
        return ApiResponse.success(Map.of(
            "mailId", mailId,
            "dlProbability", Math.round(prob * 10000) / 100.0,
            "dlTopFeatures", features.stream()
                .map(f -> Map.of("text", f.text, "weight", f.weight, "probability", f.probability))
                .collect(java.util.stream.Collectors.toList()),
            "trainingCount", spamService.getDlEngine().getTrainingCount(),
            "emaAccuracy", Math.round(spamService.getDlEngine().getEmaAccuracy() * 10000) / 100.0
        ));
    }

    /**
     * 获取深度学习模型状态（训练步数、准确率等）。
     */
    @GetMapping("/dl-model-status")
    public ApiResponse<Map<String, Object>> dlModelStatus() {
        return ApiResponse.success(Map.of(
                "initialized", spamService.getDlEngine().isInitialized(),
                "trainingCount", spamService.getDlEngine().getTrainingCount(),
                "emaAccuracy", Math.round(spamService.getDlEngine().getEmaAccuracy() * 10000) / 100.0
        ));
    }

    /**
     * 清空所有邮件数据和DL模型（测试用）。
     */
    @PostMapping("/dl-reset-all")
    public ApiResponse<String> resetAll() {
        spamService.getDlEngine().resetModel();
        log.info("模型和邮件数据已重置");
        return ApiResponse.success("模型已重置");
    }

    // dl-reseed 已废弃：n-gram种子训练被LLM词级训练取代

    // ===================== 垃圾邮件警告通知 =====================

    /**
     * 获取当前用户的待处理垃圾邮件警告列表。
     * URL: GET /api/mail/spam-warnings
     */
    @GetMapping("/spam-warnings")
    public ApiResponse<List<com.shixun.mail.entity.SpamWarning>> spamWarnings() {
        Long userId = com.shixun.common.security.AuthUserContext.getUserId();
        if (userId == null) throw new RuntimeException("未登录");
        return ApiResponse.success(spamService.getPendingWarnings(userId));
    }

    /**
     * 用户选择拉黑发件人。
     * URL: POST /api/mail/spam-warnings/{warningId}/block
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/spam-warnings/{warningId}/block")
    public ApiResponse<Map<String, Object>> blockSender(@PathVariable Long warningId) {
        Long userId = com.shixun.common.security.AuthUserContext.getUserId();
        if (userId == null) throw BusinessException.of(401, "Unauthorized");
        java.util.Map<String, Object> dlInfo = spamService.blockSenderWithAnalysis(warningId, userId);
        return (ApiResponse<Map<String, Object>>) (Object) ApiResponse.success("已拉黑该发件人", dlInfo);
    }

    /**
     * 用户选择忽略警告。
     * URL: POST /api/mail/spam-warnings/{warningId}/ignore
     */
    @PostMapping("/spam-warnings/{warningId}/ignore")
    public ApiResponse<String> ignoreWarning(@PathVariable Long warningId) {
        Long userId = com.shixun.common.security.AuthUserContext.getUserId();
        if (userId == null) throw BusinessException.of(401, "Unauthorized");
        spamService.ignoreWarning(warningId, userId);
        return ApiResponse.success("已忽略该警告");
    }
}
