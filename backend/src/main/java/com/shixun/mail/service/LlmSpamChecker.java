package com.shixun.mail.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 鎺ュ叆鍏叡澶фā鍨?API 杩涜鍨冨溇閭欢璇嗗埆銆?
 * 鍏煎 OpenAI / DeepSeek / 閫氫箟鍗冮棶 / 鏅鸿氨 绛夋墍鏈?OpenAI 鏍煎紡鐨?API銆?
 *
 * 閰嶇疆鏂瑰紡 (application.yml):
 *   llm:
 *     api-key: "sk-xxx"
 *     base-url: "https://api.deepseek.com"     # 鎴栧叾浠栧吋瀹?API
 *     model: "deepseek-chat"
 *     enabled: true
 */
@Service
public class LlmSpamChecker {

    private static final Logger log = LoggerFactory.getLogger(LlmSpamChecker.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;

    public LlmSpamChecker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${llm.model:deepseek-chat}")
    private String model;

    @Value("${llm.api-path:/chat/completions}")
    private String apiPath;

    @Value("${llm.enabled:false}")
    private boolean enabled;

    /** 绯荤粺鎻愮ず璇嶏細璁╂ā鍨嬪彧杈撳嚭 spam 鎴?ham */
    private static final String SYSTEM_PROMPT =
        "You are a spam detector. Reply ONLY: spam or ham.\n" +
        "Spam: fraud,ad,gambling,adult,violence,phishing,fake prize.\n" +
        "Ham: work,chat,study,notice,order confirm.\n\n" +
        "Reply: spam or ham. No other text.";
    /**
     * 璋冪敤澶фā鍨嬪垽鏂槸鍚︿负鍨冨溇閭欢锛堟棤鐢ㄦ埛涓婁笅鏂囷級銆?
     */
    public SpamResult check(String subject, String body) {
        return check(subject, body, null);
    }

    /**
     * 璋冪敤澶фā鍨嬪垽鏂槸鍚︿负鍨冨溇閭欢锛堝甫鐢ㄦ埛涓炬姤鍘嗗彶鍋?few-shot锛夈€?
     * @param userId 鏀朵欢浜篒D锛岀敤浜庡姞杞藉叾鍘嗗彶涓炬姤鍋忓ソ
     */
    public SpamResult check(String subject, String body, Long userId) {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            return null;
        }

        // 鏋勫缓娑堟伅鍒楄〃: system prompt + few-shot examples + current email
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        // 鍔犺浇鐢ㄦ埛鍘嗗彶鍙嶉浣滀负 few-shot 绀轰緥
        if (userId != null) {
            List<Map<String, String>> examples = loadRecentFeedback(userId, 5);
            messages.addAll(examples);
        }

        String emailContent = "涓婚: " + (subject != null ? subject : "(鏃犱富棰?") + "\n" +
                              "姝ｆ枃: " + (body != null ? body : "(鏃犳鏂?");
        messages.add(Map.of("role", "user", "content", emailContent));

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", messages,
                    "temperature", 0.1,
                    "max_tokens", 10
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + apiPath, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[LLM] API 杩斿洖寮傚父: status={}", response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").get(0)
                    .path("message").path("content").asText().trim().toLowerCase();

            boolean isSpam = content.contains("spam") || content.contains("鍨冨溇");
            log.info("[LLM] 鍒ゅ畾瀹屾垚: subject={}, result={}, raw={}",
                    subject != null ? subject.substring(0, Math.min(30, subject.length())) : "",
                    isSpam ? "SPAM" : "HAM", content);

            return new SpamResult(isSpam, content);

        } catch (Throwable e) {
            log.warn("[LLM] API 璋冪敤澶辫触: {}", e.getMessage());
            return null; // 澶辫触鏃惰繑鍥?null锛岀敱璋冪敤鏂瑰厹搴?
        }
    }

    /** 鍔犺浇鐢ㄦ埛鏈€杩戠殑鍙嶉璁板綍锛屾瀯寤?few-shot 绀轰緥 */
    private List<Map<String, String>> loadRecentFeedback(Long userId, int limit) {
        List<Map<String, String>> examples = new ArrayList<>();
        try {
            String sql = """
                SELECT sf.feedback_type, m.subject, m.content
                FROM spam_feedback sf
                JOIN mail m ON sf.mail_id = m.mail_id
                WHERE sf.user_id = ?
                ORDER BY sf.created_at DESC
                LIMIT ?
                """;
            jdbcTemplate.query(sql, rs -> {
                String type = rs.getString("feedback_type");
                String subject = rs.getString("subject");
                String content = rs.getString("content");
                String label = "REPORT_SPAM".equals(type) ? "spam" : "ham";
                String text = "涓婚: " + (subject != null ? subject : "") + "\n姝ｆ枃: " +
                              (content != null ? (content.length() > 100 ?
                               content.substring(0, 100) + "..." : content) : "");

                // user: 閭欢鍐呭, assistant: 鐢ㄦ埛鐨勫巻鍙插垽鏂?
                examples.add(Map.of("role", "user", "content", text));
                examples.add(Map.of("role", "assistant", "content", label));
            }, userId, limit);
        } catch (DataAccessException e) {
            log.debug("[LLM] 鍔犺浇鐢ㄦ埛鍙嶉澶辫触: {}", e.getMessage());
        }
        return examples;
    }

    public String chat(String systemPrompt, String userPrompt, int maxTokens, double temperature) {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String system = systemPrompt == null ? "" : systemPrompt;
        String user = userPrompt == null ? "" : userPrompt;

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)
                    ),
                    "temperature", temperature,
                    "max_tokens", Math.max(1, maxTokens)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + apiPath, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[LLM] API returned non-success status: {}", response.getStatusCode());
                return null;
            }

            JsonNode choices = objectMapper.readTree(response.getBody()).path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                return null;
            }

            String content = choices.get(0).path("message").path("content").asText("");
            return content == null ? null : content.trim();
        } catch (Throwable e) {
            log.warn("[LLM] chat call failed: {}", e.getMessage());
            return null;
        }
    }

    /** 用LLM从邮件中提取所有有意义的词（逗号分隔），不限于垃圾词 */
    public java.util.List<String> extractKeywords(String subject, String body) {
        String originalText = (subject != null ? subject : "") + " " + (body != null ? body : "");
        String promptText = "主题:" + (subject != null ? subject : "") + " 正文:" + (body != null ? body : "");
        String result = chat("",
            "从以下邮件原文中提取所有有意义的词（包括正常词和垃圾词）。必须只提取原文中实际出现的词，不要编造。用逗号分隔:\n" + promptText,
            150, 0.0);
        log.info("[LLM] extractKeywords raw={}", result);
        if (result == null || result.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(result.split("[,，\\s]+"))
                .map(String::trim)
                .filter(w -> w.length() >= 2)
                .filter(w -> originalText.contains(w)) // 原文验证：只保留邮件中真实存在的词
                .collect(java.util.stream.Collectors.toList());
    }

    public boolean isEnabled() { return enabled; }

    /** 澶фā鍨嬪垽瀹氱粨鏋?*/
    public static class SpamResult {
        public final boolean isSpam;
        public final String rawResponse;

        public SpamResult(boolean isSpam, String rawResponse) {
            this.isSpam = isSpam;
            this.rawResponse = rawResponse;
        }
    }
}
