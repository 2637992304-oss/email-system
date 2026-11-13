package com.shixun.mail.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 基于多层感知器（MLP）的深度学习垃圾邮件分类引擎。
 *
 * 架构: 输入层(256) → 隐藏层1(128,ReLU) → 隐藏层2(64,ReLU) → 输出层(1,Sigmoid)
 * 特征: 字符级 2-gram + 3-gram 哈希（类似 fastText 的子词嵌入）
 * 训练: 在线随机梯度下降 + 动量（SGD with Momentum）
 * 持久化: 模型权重以 JSON 存入 spam_model 表，重启不丢失
 *
 * 论文参考: "Bag of Tricks for Efficient Text Classification" (Joulin et al., 2017)
 */
@Service
public class SpamDeepLearningEngine {

    private static final Logger log = LoggerFactory.getLogger(SpamDeepLearningEngine.class);

    // ---- 网络架构 ----
    private static final int INPUT_DIM = 256;      // n-gram 哈希特征维度
    private static final int HIDDEN1_DIM = 128;    // 隐藏层1 神经元数
    private static final int HIDDEN2_DIM = 64;     // 隐藏层2 神经元数
    private static final double LEARNING_RATE = 0.01;
    private static final double MOMENTUM = 0.9;
    private static final double SPAM_THRESHOLD = 0.7;      // DL邮件判垃圾阈值
    private static final double DICT_THRESHOLD = 0.9;      // 词进入动态词典阈值
    private static final double WORD_LR = 0.005;           // 词级学习率（已废弃，用随机0.4-0.6/128）

    // ---- 中文分词词典（正向最大匹配 FMM） ----
    private static final Set<String> BASE_WORD_DICT = buildWordDict();
    /** 用户举报动态学习的新词 */
    private final Set<String> learnedSpamWords = new HashSet<>();
    /** LLM提取过的所有词（独立于DICT，供DL概率计算），新词初始概率≈0.5 */
    private final Set<String> dlTrackedWords = new HashSet<>();
    private static final int BASE_MAX_WORD_LEN = BASE_WORD_DICT.stream()
            .mapToInt(String::length).max().orElse(6);

    /** 构建分词词典：垃圾关键词 + 促销词 + 常用词 */
    private static Set<String> buildWordDict() {
        Set<String> dict = new HashSet<>();
        // 垃圾/诈骗关键词
        dict.addAll(Arrays.asList(
            "免费", "中奖", "代办", "发票", "兼职", "日赚", "点击领取", "限时优惠",
            "快速致富", "无抵押贷款", "代开发票", "办证", "恭喜你", "抽中", "注册送",
            "银行卡", "验证码", "转账", "打款", "保证金", "刷单", "返利", "佣金",
            "垫付", "解冻", "网贷", "套现", "征信", "洗钱", "传销", "杀猪盘",
            "资金盘", "投资理财", "高收益", "稳赚不赔", "内幕消息", "老师带单",
            "跟单操作", "充值返现", "漏洞套利", "平台漏洞", "冒充公检法", "安全账户",
            "通缉令", "快递丢失", "退款理赔", "订单异常", "在家赚钱", "月入过万",
            "幸运用户", "中奖通知", "领取奖金", "私募", "原始股", "虚拟币", "炒币",
            "翻倍", "稳赢", "绝对真实", "恭喜你中了", "配合调查", "兼职打字员",
            "杀人", "枪支", "炸弹", "绑架", "勒索", "自杀", "砍人", "追杀",
            "裸聊", "迷药", "春药", "赌场", "赌博"
        ));
        // 促销/营销关键词
        dict.addAll(Arrays.asList(
            "优惠券", "折扣", "限时抢购", "免费试用", "大促", "双十一", "618",
            "满减", "包邮", "秒杀", "促销", "立减", "特价", "砍价", "拼团", "红包"
        ));
        // 英文垃圾关键词
        dict.addAll(Arrays.asList(
            "viagra", "casino", "lottery", "winner", "prize", "spam",
            "click here", "free money", "act now", "limited offer", "guaranteed",
            "advertisement", "bitcoin", "investment", "earn money", "work from home",
            "claim your prize", "free", "win", "bonus", "offer", "discount",
            "congratulations", "selected", "exclusive", "urgent", "money", "cash",
            "credit card", "bank account", "transfer", "western union", "paypal",
            "nigerian", "prince", "inheritance", "million", "dollars", "pounds"
        ));
        // 正常商务/日常高频词（帮助分词，不作为垃圾特征）
        dict.addAll(Arrays.asList(
            "你好", "谢谢", "请问", "公司", "我们", "他们", "可以", "需要",
            "已经", "没有", "什么", "怎么", "为什么", "这个", "那个", "知道",
            "今天", "明天", "昨天", "现在", "下午", "上午", "晚上", "会议",
            "项目", "方案", "报告", "通知", "附件", "请查收", "确认", "回复",
            "联系", "电话", "邮箱", "地址", "收到", "发送", "处理", "完成",
            "进行", "开始", "结束", "问题", "解决", "支持", "提供", "使用",
            "工作", "时间", "安排", "准备", "参加", "帮忙", "通过", "要求",
            "欢迎", "感谢", "各位", "大家", "您好", "麻烦", "希望", "建议",
            "服务", "客户", "订单", "产品", "信息", "管理", "系统", "数据"
        ));
        return dict;
    }

    /**
     * 正向最大匹配（FMM）中文分词。
     * 从文本起始位置，每次尝试匹配词典中最长的词。
     * 匹配成功 → 输出该词，指针后移；失败 → 单字输出，指针+1。
     */
    private List<String> segmentWords(String text) {
        List<String> words = new java.util.ArrayList<>();
        int i = 0;
        int len = text.length();
        Set<String> dict = getFullDict();
        int maxLen = getMaxWordLen();

        while (i < len) {
            char c = text.charAt(i);
            // 中文: FMM分词
            if (c >= 0x4E00 && c <= 0x9FFF) {
                boolean matched = false;
                for (int wlen = Math.min(maxLen, len - i); wlen >= 2; wlen--) {
                    String candidate = text.substring(i, i + wlen);
                    if (dict.contains(candidate)) {
                        words.add(candidate);
                        i += wlen;
                        matched = true;
                        break;
                    }
                }
                if (!matched) { i++; }
            }
            // 英文: 空格分割 + 词典匹配
            else if (Character.isLetter(c)) {
                int start = i;
                while (i < len && Character.isLetter(text.charAt(i))) { i++; }
                String token = text.substring(start, i).toLowerCase();
                if (token.length() >= 2 && dict.contains(token)) {
                    words.add(token);
                }
                // 多词组合 (如 "click here", "free money")
                while (i < len && text.charAt(i) == ' ') {
                    int spaceIdx = i;
                    i++;
                    int wStart = i;
                    while (i < len && Character.isLetter(text.charAt(i))) { i++; }
                    String extra = text.substring(spaceIdx + 1, i).toLowerCase();
                    String combo = token + " " + extra;
                    if (dict.contains(combo)) {
                        words.add(combo);
                    }
                }
            } else {
                i++;
            }
        }
        return words;
    }

    /** 将LLM提取的词列表哈希到特征向量（与 n-gram 特征共用 256 维空间） */
    public double[] extractFeaturesFromWords(java.util.List<String> words) {
        double[] features = new double[INPUT_DIM];
        for (String word : words) {
            word = word.trim().toLowerCase();
            if (word.length() >= 2) {
                int hash = Math.abs(word.hashCode() * 7 + word.length() * 13) % INPUT_DIM;
                features[hash] = 1.0;
            }
        }
        return features;
    }

    /** 对分词结果做哈希，填入特征向量（与 n-gram 特征共用 256 维空间） */
    private void hashWordFeatures(List<String> words, double[] features) {
        for (String word : words) {
            int hash = Math.abs(word.hashCode() * 7 + word.length() * 13) % INPUT_DIM;
            features[hash] = 1.0;
        }
    }

    /** 公开方法：对文本做FMM分词，返回词列表（供外部反向学习等场景使用） */
    public java.util.List<String> segmentTextToWords(String text) {
        return segmentWords(text);
    }

    private Set<String> getFullDict() {
        Set<String> full = new HashSet<>(BASE_WORD_DICT);
        full.addAll(learnedSpamWords);
        return full;
    }

    private int getMaxWordLen() {
        int m = BASE_MAX_WORD_LEN;
        for (String w : learnedSpamWords) m = Math.max(m, w.length());
        return m;
    }

    /** 手动添加垃圾词到词典（不受DL概率控制，用于管理操作） */
    public synchronized void addLearnedWords(java.util.List<String> words, int label) {
        if (label != 1) return; // 仅支持添加垃圾词
        for (String word : words) {
            word = word.trim();
            if (word.length() >= 2 && !BASE_WORD_DICT.contains(word)) {
                learnedSpamWords.add(word);
            }
        }
        if (!words.isEmpty()) {
            saveModel();
        }
    }

    /** 获取单个词的DL垃圾倾向概率（基于W1第一层权重，sigmoid归一化） */
    public double getWordSpamProbability(String word) {
        if (!initialized) return 0.0;
        word = word.trim().toLowerCase();
        if (word.length() < 2) return 0.0;
        int hash = Math.abs(word.hashCode() * 7 + word.length() * 13) % INPUT_DIM;
        double raw = signedRowWeight(W1[hash]);
        return sigmoid(raw); // sigmoid(raw) 比 tanh 更温和，需要更多训练步数才能接近 0/1
    }

    /**
     * 基于DL训练后的词权重更新动态词典。
     * label=1: 词概率>0.5 → 加入 learnedSpamWords
     * label=0: 词概率<0.5 → 从 learnedSpamWords 移除; 概率<0.3 → 加入 learnedHamWords
     */
    public synchronized void updateDictionaryFromWords(java.util.List<String> words, int label) {
        boolean changed = false;
        for (String word : words) {
            word = word.trim();
            if (word.length() < 2 || BASE_WORD_DICT.contains(word)) continue;
            double prob = getWordSpamProbability(word);
            if (label == 1) {
                if (prob > DICT_THRESHOLD && learnedSpamWords.add(word)) {
                    log.info("[DL-Spam] 词'{}'概率={} > 0.9，加入垃圾词典", word, String.format("%.3f", prob));
                    changed = true;
                }
            } else { // label == 0, 反向学习：概率降到0.9以下就从垃圾词典移除
                if (prob < DICT_THRESHOLD && learnedSpamWords.remove(word)) {
                    log.info("[DL-Spam] 词'{}'概率={} < 0.9，从垃圾词典移除", word, String.format("%.3f", prob));
                    changed = true;
                }
            }
        }
        if (changed) {
            saveModel();
        }
    }

    // ---- 权重矩阵（Xavier 初始化） ----
    private double[][] W1, W2, W3;
    private double[] b1, b2, b3;

    // ---- 动量缓存（SGD with Momentum） ----
    private double[][] vW1, vW2, vW3;
    private double[] vb1, vb2, vb3;

    // ---- 训练统计 ----
    private int trainingCount = 0;
    private double emaAccuracy = 0.0;
    private volatile boolean initialized = false;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random rng = new Random(42);

    public SpamDeepLearningEngine(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initWeights();
        loadModel();
    }

    /** 重置模型到Xavier初始状态，清除旧n-gram种子训练数据 */
    public synchronized void resetModel() {
        initWeights();
        learnedSpamWords.clear();
        dlTrackedWords.clear();
        trainingCount = 0;
        emaAccuracy = 0.0;
        initialized = true;
        // 清除DB中的旧模型
        try {
            jdbcTemplate.update("DELETE FROM spam_model WHERE id = 1");
        } catch (Exception ignored) {}
        log.info("[DL-Spam] 模型已重置（W1缩小初始化，词初始概率≈0.5）");
    }

    // ==================== 初始化 ====================

    private void initWeights() {
        W1 = xavierInit(INPUT_DIM, HIDDEN1_DIM);
        // W1缩小10倍：词初始概率集中在0.5附近（±0.02），需多次训练才能偏离
        for (int i = 0; i < INPUT_DIM; i++) {
            for (int j = 0; j < HIDDEN1_DIM; j++) {
                W1[i][j] *= 0.1;
            }
        }
        W2 = xavierInit(HIDDEN1_DIM, HIDDEN2_DIM);
        W3 = xavierInit(HIDDEN2_DIM, 1);
        b1 = new double[HIDDEN1_DIM];
        b2 = new double[HIDDEN2_DIM];
        b3 = new double[1];
        vW1 = zeros(INPUT_DIM, HIDDEN1_DIM);
        vW2 = zeros(HIDDEN1_DIM, HIDDEN2_DIM);
        vW3 = zeros(HIDDEN2_DIM, 1);
        vb1 = new double[HIDDEN1_DIM];
        vb2 = new double[HIDDEN2_DIM];
        vb3 = new double[1];
    }

    /** Xavier 初始化: W ~ U(-sqrt(6/(in+out)), sqrt(6/(in+out))) */
    private double[][] xavierInit(int in, int out) {
        double limit = Math.sqrt(6.0 / (in + out));
        double[][] w = new double[in][out];
        for (int i = 0; i < in; i++) {
            for (int j = 0; j < out; j++) {
                w[i][j] = (rng.nextDouble() * 2 - 1) * limit;
            }
        }
        return w;
    }

    private double[][] zeros(int rows, int cols) {
        return new double[rows][cols];
    }

    // ==================== 特征提取 ====================

    /**
     * 混合特征提取: n-gram + 词典分词。
     *
     * 1. 字符级 2-gram + 3-gram 哈希 → 捕获子词拼写模式
     * 2. 正向最大匹配（FMM）中文分词 → 词典中的词作为语义特征
     * 3. 两种特征哈希到同一 256 维向量，神经网络自动学习权重分配
     */
    public double[] extractFeatures(String subject, String body) {
        String text = (subject != null ? subject : "")
                + " " + (body != null ? body : "");
        text = text.toLowerCase().trim();
        double[] features = new double[INPUT_DIM];

        int len = text.length();
        // ---- 字符 n-gram ----
        for (int i = 0; i < len - 1; i++) {
            int hash = hash2(text.charAt(i), text.charAt(i + 1));
            features[hash] = 1.0;
        }
        for (int i = 0; i < len - 2; i++) {
            int hash = hash3(text.charAt(i), text.charAt(i + 1), text.charAt(i + 2));
            features[hash] = 1.0;
        }

        // ---- 词典分词 ----
        List<String> words = segmentWords(text);
        hashWordFeatures(words, features);

        return features;
    }

    private int hash2(char c1, char c2) {
        return Math.abs((c1 * 31 + c2) % INPUT_DIM);
    }

    private int hash3(char c1, char c2, char c3) {
        return Math.abs((c1 * 961 + c2 * 31 + c3) % INPUT_DIM);
    }

    // ==================== 前向传播 ====================

    /**
     * 完整前向传播: 输入 → H1(ReLU) → H2(ReLU) → 输出(Sigmoid)
     * @return 垃圾邮件概率 [0, 1]
     */
    /**
     * 全量分词预测：FMM分词取邮件中所有可识别词，参与DL平均计算。
     * 词在dlTrackedWords中→用训练概率；不在→自动加入词库给默认0.5。
     */
    public double predict(String subject, String body) {
        if (!initialized) return 0.0;
        String text = (subject != null ? subject : "") + " " + (body != null ? body : "");
        text = text.trim();
        java.util.Set<String> seenWords = new java.util.LinkedHashSet<>();
        double sum = 0;

        // FMM全量分词：邮件中所有可识别词都参与计算
        for (String word : segmentWords(text)) {
            if (word.length() >= 2 && seenWords.add(word)) {
                dlTrackedWords.add(word); // 新词自动入DL词库
                sum += getWordSpamProbability(word);
            }
        }
        // 补充dlTrackedWords子串匹配（FMM可能遗漏的LLM训练词）
        for (String word : dlTrackedWords) {
            if (word.length() >= 2 && text.contains(word) && seenWords.add(word)) {
                sum += getWordSpamProbability(word);
            }
        }

        int count = seenWords.size();
        double result = count == 0 ? 0.5 : sum / count;
        // 日志
        if (log.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder("[DL-Spam] predict 主题=");
            sb.append(subject != null && subject.length() > 30 ? subject.substring(0,30)+"..." : subject);
            sb.append(" 总词=").append(count).append(" DL词库=").append(dlTrackedWords.size())
              .append(" 概率=").append(String.format("%.4f", result));
            if (count > 0) {
                sb.append(" 词=[");
                int n = 0;
                for (String w : seenWords) {
                    sb.append(w).append(":").append(String.format("%.3f", getWordSpamProbability(w))).append(" ");
                    if (++n >= 8) { sb.append("..."); break; }
                }
                sb.append("]");
            }
            log.info(sb.toString());
        }
        return result;
    }

    /** 全连接 + ReLU */
    private double[] forwardReLU(double[] input, double[][] W, double[] b) {
        int outDim = W[0].length;
        double[] output = new double[outDim];
        for (int j = 0; j < outDim; j++) {
            double sum = b[j];
            for (int i = 0; i < input.length; i++) {
                sum += input[i] * W[i][j];
            }
            output[j] = sum > 0 ? sum : 0; // ReLU
        }
        return output;
    }

    /** 全连接 + Sigmoid */
    private double forwardSigmoid(double[] input, double[][] W, double[] b) {
        double sum = b[0];
        for (int i = 0; i < input.length; i++) {
            sum += input[i] * W[i][0];
        }
        return sigmoid(sum);
    }

    private static double sigmoid(double x) {
        // 数值稳定的 sigmoid
        if (x >= 0) {
            return 1.0 / (1.0 + Math.exp(-x));
        } else {
            double expX = Math.exp(x);
            return expX / (1.0 + expX);
        }
    }

    private static double reluDerivative(double activated) {
        return activated > 0 ? 1.0 : 0.0;
    }

    // ==================== 反向传播 + SGD 训练 ====================

    /**
     * 在线训练: 对单封邮件执行一次前向+反向传播，更新所有权重。
     * @param subject 邮件主题
     * @param body    邮件正文
     * @param label   标签: 1=垃圾邮件, 0=正常邮件
     */
    public void train(String subject, String body, int label) {
        train(subject, body, label, 1);
    }

    /**
     * 带重复次数的在线训练。
     * @param repeat 重复训练次数，用户举报建议 5 次以加速学习
     */
    public synchronized void train(String subject, String body, int label, int repeat) {
        for (int r = 0; r < repeat; r++) {
            trainOnce(subject, body, label);
        }
    }

    /**
     * 用LLM提取的关键词直接调整W1权重（不经过W2/W3，避免稀疏特征的随机梯度噪声）。
     * 每个词对其W1行所有权重加减 WORD_LR，直接影响 signedRowWeight。
     * @param words  LLM提取的关键词列表
     * @param label  标签: 1=垃圾 → W1行每个权重+WORD_LR, 0=正常 → -WORD_LR
     * @param repeat 每词重复次数
     */
    public synchronized void trainWithWords(java.util.List<String> words, int label, int repeat) {
        if (words == null || words.isEmpty()) return;
        int trained = 0;
        for (String word : words) {
            word = word.trim().toLowerCase();
            if (word.length() < 2) continue;
            boolean isNewWord = dlTrackedWords.add(word);
            if (isNewWord) {
                // 首次遇到：只加入DL词库，给默认权重（W1≈0, prob≈0.5），不训练
                continue;
            }
            // 再次遇到：训练调整权重
            int hash = Math.abs(word.hashCode() * 7 + word.length() * 13) % INPUT_DIM;
            double rowDelta = (0.4 + rng.nextDouble() * 0.2) * repeat;
            double perWeight = (label == 1 ? rowDelta : -rowDelta) / HIDDEN1_DIM;
            double[] row = W1[hash];
            for (int j = 0; j < HIDDEN1_DIM; j++) {
                row[j] += perWeight;
            }
            trained++;
        }
        trainingCount += trained * repeat;
        if (trainingCount % 10 == 0 && trainingCount > 0) {
            log.info("[DL-Spam] 词级训练: 总步数={}, DL词库={}",
                    trainingCount, dlTrackedWords.size());
        }
        if (trainingCount % 50 == 0) {
            saveModel();
        }
    }

    /** 单步训练（前向+反向+更新） */
    private void trainOnce(String subject, String body, int label) {
        double[] x = extractFeatures(subject, body);
        trainOnceWithFeatures(x, label, subject != null ? subject.substring(0, Math.min(20, subject.length())) : "");
    }

    /** 用预计算的特征向量进行单步训练 */
    private void trainOnceWithFeatures(double[] x, int label, String logInfo) {
        // ---- 前向传播（缓存各层激活值） ----
        double[] h1 = forwardReLU(x, W1, b1);
        double[] h2 = forwardReLU(h1, W2, b2);
        double yPred = forwardSigmoid(h2, W3, b3);

        // ---- 反向传播 ----
        // 输出层梯度: dL/dz3 = yPred - label  (BCE + Sigmoid 合并)
        double delta3 = yPred - label;

        // W3, b3 梯度
        for (int i = 0; i < HIDDEN2_DIM; i++) {
            double grad = h2[i] * delta3;
            vW3[i][0] = MOMENTUM * vW3[i][0] - LEARNING_RATE * grad;
            W3[i][0] += vW3[i][0];
        }
        vb3[0] = MOMENTUM * vb3[0] - LEARNING_RATE * delta3;
        b3[0] += vb3[0];

        // 隐藏层2梯度
        double[] delta2 = new double[HIDDEN2_DIM];
        for (int i = 0; i < HIDDEN2_DIM; i++) {
            delta2[i] = W3[i][0] * delta3 * reluDerivative(h2[i]);
        }

        // W2, b2 梯度
        for (int i = 0; i < HIDDEN1_DIM; i++) {
            for (int j = 0; j < HIDDEN2_DIM; j++) {
                double grad = h1[i] * delta2[j];
                vW2[i][j] = MOMENTUM * vW2[i][j] - LEARNING_RATE * grad;
                W2[i][j] += vW2[i][j];
            }
        }
        for (int j = 0; j < HIDDEN2_DIM; j++) {
            vb2[j] = MOMENTUM * vb2[j] - LEARNING_RATE * delta2[j];
            b2[j] += vb2[j];
        }

        // 隐藏层1梯度
        double[] delta1 = new double[HIDDEN1_DIM];
        for (int i = 0; i < HIDDEN1_DIM; i++) {
            double sum = 0;
            for (int j = 0; j < HIDDEN2_DIM; j++) {
                sum += W2[i][j] * delta2[j];
            }
            delta1[i] = sum * reluDerivative(h1[i]);
        }

        // W1, b1 梯度
        for (int i = 0; i < INPUT_DIM; i++) {
            if (x[i] == 0) continue; // 稀疏优化: 跳过零特征
            for (int j = 0; j < HIDDEN1_DIM; j++) {
                double grad = x[i] * delta1[j];
                vW1[i][j] = MOMENTUM * vW1[i][j] - LEARNING_RATE * grad;
                W1[i][j] += vW1[i][j];
            }
        }
        for (int j = 0; j < HIDDEN1_DIM; j++) {
            vb1[j] = MOMENTUM * vb1[j] - LEARNING_RATE * delta1[j];
            b1[j] += vb1[j];
        }

        // ---- 更新训练统计 ----
        trainingCount++;
        double correct = (yPred >= SPAM_THRESHOLD && label == 1)
                || (yPred < SPAM_THRESHOLD && label == 0) ? 1.0 : 0.0;
        emaAccuracy = 0.95 * emaAccuracy + 0.05 * correct;

        if (trainingCount % 10 == 0) {
            log.info("[DL-Spam] 训练步数={}, 准确率EMA={}%, 本次预测={}, 标签={}",
                    trainingCount, String.format("%.2f", emaAccuracy * 100),
                    String.format("%.4f", yPred), label);
        }

        // 每 50 步持久化一次模型
        if (trainingCount % 50 == 0) {
            saveModel();
        }
    }

    // ==================== 模型持久化 ====================

    private void loadModel() {
        try {
            String json = jdbcTemplate.queryForObject(
                    "SELECT model_json FROM spam_model WHERE id = 1", String.class);
            if (json != null && !json.isEmpty()) {
                Map<String, Object> model = objectMapper.readValue(json,
                        new TypeReference<Map<String, Object>>() {});
                W1 = parseMatrix(model, "W1");
                W2 = parseMatrix(model, "W2");
                W3 = parseMatrix(model, "W3");
                b1 = parseVector(model, "b1");
                b2 = parseVector(model, "b2");
                b3 = parseVector(model, "b3");
                trainingCount = (int) model.getOrDefault("trainingCount", 0);
                emaAccuracy = (double) model.getOrDefault("emaAccuracy", 0.0);
                restoreLearnedWords(model, "learnedSpamWords", learnedSpamWords);
                restoreLearnedWords(model, "dlTrackedWords", dlTrackedWords);
                initialized = true;
                log.info("[DL-Spam] 模型已加载: 训练步数={}, 准确率EMA={}%",
                        trainingCount, String.format("%.2f", emaAccuracy * 100));
            }
        } catch (Exception e) {
            log.warn("[DL-Spam] 未找到已训练模型，使用 Xavier 初始化权重: {}", e.getMessage());
            initialized = true; // 使用初始权重
        }
    }

    public synchronized void saveModel() {
        try {
            Map<String, Object> model = new java.util.HashMap<>();
            model.put("W1", matrixToList(W1));
            model.put("W2", matrixToList(W2));
            model.put("W3", matrixToList(W3));
            model.put("b1", arrayToList(b1));
            model.put("b2", arrayToList(b2));
            model.put("b3", arrayToList(b3));
            model.put("trainingCount", trainingCount);
            model.put("emaAccuracy", emaAccuracy);
            model.put("learnedSpamWords", new java.util.ArrayList<>(learnedSpamWords));
            model.put("dlTrackedWords", new java.util.ArrayList<>(dlTrackedWords));
            model.put("inputDim", INPUT_DIM);
            model.put("hidden1Dim", HIDDEN1_DIM);
            model.put("hidden2Dim", HIDDEN2_DIM);
            String json = objectMapper.writeValueAsString(model);

            jdbcTemplate.update(
                    "INSERT INTO spam_model (id, model_json, input_dim, hidden_dim, training_count, recent_accuracy, updated_at) " +
                    "VALUES (1, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE model_json = VALUES(model_json), " +
                    "training_count = VALUES(training_count), " +
                    "recent_accuracy = VALUES(recent_accuracy), " +
                    "updated_at = VALUES(updated_at)",
                    json, INPUT_DIM, HIDDEN1_DIM, trainingCount, emaAccuracy, LocalDateTime.now());
            log.debug("[DL-Spam] 模型已持久化: 步数={}", trainingCount);
        } catch (Exception e) {
            log.warn("[DL-Spam] 模型持久化失败: {}", e.getMessage());
        }
    }

    private List<List<Double>> matrixToList(double[][] matrix) {
        return java.util.Arrays.stream(matrix)
                .map(this::arrayToList)
                .collect(java.util.stream.Collectors.toList());
    }

    private List<Double> arrayToList(double[] arr) {
        return java.util.Arrays.stream(arr).boxed().collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private double[][] parseMatrix(Map<String, Object> model, String key) {
        List<List<Number>> list = (List<List<Number>>) model.get(key);
        if (list == null || list.isEmpty()) return null;
        int rows = list.size();
        int cols = list.get(0).size();
        double[][] matrix = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                matrix[i][j] = list.get(i).get(j).doubleValue();
            }
        }
        return matrix;
    }

    @SuppressWarnings("unchecked")
    private void restoreLearnedWords(Map<String, Object> model, String key, Set<String> target) {
        Object obj = model.get(key);
        if (obj instanceof List) {
            for (Object item : (List<?>) obj) {
                if (item instanceof String) target.add((String) item);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private double[] parseVector(Map<String, Object> model, String key) {
        List<Number> list = (List<Number>) model.get(key);
        if (list == null || list.isEmpty()) return null;
        double[] vec = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            vec[i] = list.get(i).doubleValue();
        }
        return vec;
    }

    // ==================== 公开接口 ====================

    /** 判断邮件是否为垃圾（基于深度学习预测） */
    public boolean isSpamByDL(String subject, String body) {
        double prob = predict(subject, body);
        return prob >= SPAM_THRESHOLD;
    }

    /** 获取预测概率 */
    public double getSpamProbability(String subject, String body) {
        return predict(subject, body);
    }

    /** 获取训练步数 */
    public int getTrainingCount() { return trainingCount; }

    /** 获取指数滑动平均准确率 */
    public double getEmaAccuracy() { return emaAccuracy; }

    /** 模型是否已初始化 */
    public boolean isInitialized() { return initialized; }

    /** 获取阈值 */
    public static double getSpamThreshold() { return SPAM_THRESHOLD; }

    // ==================== 特征分析 ====================

    /** 特征词及其垃圾倾向 */
    public static class FeatureInfo {
        public final String text;
        public final double weight;       // 有向权重: >0倾向垃圾, <0倾向正常
        public final double probability;  // 归一化后的概率 [0,1]

        public FeatureInfo(String text, double weight, double probability) {
            this.text = text;
            this.weight = weight;
            this.probability = probability;
        }
    }

    /**
     * 获取当前邮件中权重最高的特征词及垃圾倾向概率。
     * 正权重 = 该特征推动判断为垃圾，负权重 = 推动判断为正常。
     */
    public List<FeatureInfo> getTopFeatures(String subject, String body, int topN) {
        String text = (subject != null ? subject : "") + " " + (body != null ? body : "");
        text = text.trim();
        java.util.Map<String, Double> featureWeights = new java.util.LinkedHashMap<>();

        // 只使用词典分词结果（FMM），不做原始n-gram
        for (String word : segmentWords(text)) {
            if (word.length() < 2) continue;
            int hash = Math.abs(word.hashCode() * 7 + word.length() * 13) % INPUT_DIM;
            double w = signedRowWeight(W1[hash]);
            featureWeights.merge(word, w, Double::sum);
        }

        featureWeights.entrySet().removeIf(e -> Math.abs(e.getValue()) < 0.0001);

        return featureWeights.entrySet().stream()
                .sorted((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())))
                .limit(topN)
                .map(e -> {
                    double w = e.getValue();
                    double prob = sigmoid(w); // 使用 sigmoid 保持一致，不做相对归一化
                    return new FeatureInfo(e.getKey(), Math.round(w * 10000.0) / 10000.0,
                            Math.round(prob * 10000.0) / 10000.0);
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /** 有向权重: 正值→垃圾, 负值→正常 */
    private double signedRowWeight(double[] row) {
        double sum = 0;
        for (double v : row) sum += v;
        return sum;
    }

    /** 检查文本是否命中用户举报学到的垃圾词 */
    public boolean matchesLearnedSpamWords(String subject, String body) {
        return !getMatchedLearnedWords(subject, body).isEmpty();
    }

    /** 获取文本中命中的用户举报学到的垃圾词 */
    public java.util.List<String> getMatchedLearnedWords(String subject, String body) {
        String text = (subject != null ? subject : "") + " " + (body != null ? body : "");
        java.util.Set<String> matched = new java.util.LinkedHashSet<>();
        for (String word : segmentWords(text)) {
            if (word.length() >= 2 && learnedSpamWords.contains(word)) {
                matched.add(word);
            }
        }
        // 也做子串匹配（直接检查文本包含）
        for (String word : learnedSpamWords) {
            if (word.length() >= 2 && text.contains(word)) {
                matched.add(word);
            }
        }
        return new java.util.ArrayList<>(matched);
    }

    /** 根据hash获取该特征的DL垃圾倾向权重（供外部查询） */
    public double getSignedRowWeightByHash(int hash) {
        if (hash < 0 || hash >= INPUT_DIM) return 0;
        return signedRowWeight(W1[hash]);
    }

    private static boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
