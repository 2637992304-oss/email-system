package com.shixun.mail.service;

import com.shixun.common.exception.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class MailAiService {

    private static final int MAX_MAIL_TEXT_LENGTH = 6000;

    private final LlmSpamChecker llmSpamChecker;

    public MailAiService(LlmSpamChecker llmSpamChecker) {
        this.llmSpamChecker = llmSpamChecker;
    }

    public String translateToChinese(String subject, String content) {
        String result = llmSpamChecker.chat(
                "你是邮件翻译助手。请把邮件翻译成自然、准确的中文。保留姓名、邮箱、日期、金额和数字不变。只返回中文译文，不要解释。",
                buildMailText(subject, content),
                900,
                0.2
        );
        return requireAiResult(result);
    }

    public String summarize(String subject, String content) {
        String result = llmSpamChecker.chat(
                "你是邮件阅读助手。请用中文总结邮件内容，控制在3条要点以内；如果有明确事项、时间、金额或行动要求，必须点出。只返回总结。",
                buildMailText(subject, content),
                500,
                0.2
        );
        return requireAiResult(result);
    }

    private String buildMailText(String subject, String content) {
        String plainContent = stripHtml(content).trim();
        if (plainContent.isEmpty() && isBlank(subject)) {
            throw BusinessException.of(400, "邮件内容为空，无法使用AI处理");
        }

        String text = "主题: " + nullToEmpty(subject).trim() + "\n正文: " + plainContent;
        return text.length() > MAX_MAIL_TEXT_LENGTH
                ? text.substring(0, MAX_MAIL_TEXT_LENGTH) + "\n[内容已截断]"
                : text;
    }

    private String requireAiResult(String result) {
        if (result == null || result.isBlank()) {
            throw BusinessException.of(503, "AI服务暂不可用，请稍后再试");
        }
        return result;
    }

    private String stripHtml(String value) {
        return nullToEmpty(value)
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
