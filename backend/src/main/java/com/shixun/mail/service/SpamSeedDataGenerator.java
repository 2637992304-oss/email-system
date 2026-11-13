package com.shixun.mail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 垃圾邮件种子训练数据生成器。
 *
 * 数据来源:
 * 1. 中文诈骗类: 基于常见诈骗话术模板 + 关键词组合生成 (~400条)
 * 2. 中文暴力类: 基于暴力威胁话术模板 + 关键词组合生成 (~100条)
 * 3. 英文诈骗类: 基于 SMS Spam Collection 真实样本模式 (~200条)
 * 4. 正常邮件: 常见商务/个人邮件模板 (~700条)
 *
 * 共计约 1400 条标记样本用于深度学习模型冷启动训练。
 */
@Component
public class SpamSeedDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(SpamSeedDataGenerator.class);
    private final Random rng = new Random(42);

    // ==================== 中文诈骗邮件模板 ====================
    private static final String[] SCAM_TEMPLATES = {
        "您好，恭喜您成为我们的幸运用户！{keyword}活动正在进行中，点击链接领取奖品。",
        "【紧急通知】您的账户存在异常，请立即{keyword}以保障资金安全。",
        "好消息！{keyword}，月入过万不是梦，在家就能轻松赚钱。",
        "尊敬的客户，您有一笔{keyword}待领取，请尽快联系客服办理。",
        "本公司提供{keyword}服务，价格优惠，质量保证，欢迎咨询。",
        "通知：您申请的{keyword}已审核通过，请提供银行卡信息以便放款。",
        "限时优惠！{keyword}，仅剩最后3天，错过不再有！",
        "您中奖了！{keyword}，请提供个人信息以便我们安排发货。",
        "【系统通知】您的账号已被{keyword}，请点击下方链接解除限制。",
        "专业{keyword}，100%成功率，价格公道，先到先得。",
        "各位用户请注意，近期{keyword}事件频发，请务必核实身份。",
        "恭喜！您在{keyword}活动中获得一等奖，奖金已到账。",
        "免费领取{keyword}，数量有限，先到先得！点击参与。",
        "您的快递因{keyword}原因被扣留，请联系客服处理。",
        "兼职信息：{keyword}，日赚500元，当天结算，无需经验。",
        "特大喜讯！{keyword}正式上线，注册即送现金红包！",
        "我们正在招募{keyword}合伙人，零投资高回报，名额有限。",
        "尊敬的投资人，{keyword}项目即将启动，保本年化30%以上。",
        "您有一条新消息：{keyword}，请24小时内处理，否则将影响您的权益。",
        "【客服】您的订单{keyword}，如需退款请提供支付宝账号。",
        "通知：{keyword}需要您配合调查，请准备好身份证件。",
        "本公司代开{keyword}，正规渠道，可验证，欢迎联系。",
        "【财务部】{keyword}款项已到账，请确认收款账户信息。",
        "绝密：{keyword}内幕消息，跟单操作保证盈利，名额有限。",
        "恭喜你抽中了{keyword}大奖，请尽快联系我们办理领奖手续。",
        "紧急求助：本人因{keyword}需要资金周转，急需5万元，事后必有重谢。",
        "您的{keyword}资格已确认，请提供验证码完成最后一步操作。",
        "全网最低价{keyword}，正品保证，支持货到付款。",
        "【公安局】您涉嫌{keyword}案件，请配合调查，否则将发布通缉令。",
        "招{keyword}代理，无需囤货，一件代发，月利润5万起。",
    };

    // ==================== 中文暴力威胁模板 ====================
    private static final String[] VIOLENCE_TEMPLATES = {
        "我知道你家的地址，如果不想{keyword}，就按我说的做。",
        "你得罪人了，有人出钱让我们{keyword}，想摆平就转账。",
        "我们有你的{keyword}资料，不想被公开就配合。",
        "警告：如果再不停手，我们会采取{keyword}手段。",
        "你的家人已经被我们监控，不想他们{keyword}就老实点。",
        "听说你很嚣张？信不信我找人{keyword}你？",
        "给你最后一次机会，否则后果自负——{keyword}。",
    };

    // ==================== 英文垃圾邮件模板 (基于SMS Spam Collection真实模式) ====================
    private static final String[] ENGLISH_SPAM_TEMPLATES = {
        "Congratulations! You have won a free {keyword}. Claim your prize now!",
        "URGENT: Your {keyword} has been compromised. Click here to secure your account.",
        "FREE {keyword} waiting for you! Limited time offer, act now!",
        "You are a WINNER! {keyword} prize worth $1000. Call to claim.",
        "Dear customer, your {keyword} application has been approved. No credit check needed.",
        "Earn extra cash with {keyword}. Work from home, $500/day guaranteed!",
        "Our {keyword} records indicate you are owed compensation. Reply for details.",
        "HOT deal: Get {keyword} for 90% off! This offer expires tonight.",
        "Important message about your {keyword}. Reply YES to confirm receipt.",
        "You have been selected for {keyword} trial. Risk-free investment opportunity!",
        "Hi! I'm looking for {keyword} partners. Make money fast with no experience.",
        "Private invitation: Exclusive {keyword} event this weekend. VIP access only.",
        "Your {keyword} payment is pending. Verify your bank details to proceed.",
        "Limited slots available! {keyword} course enrollment closes at midnight.",
        "We buy {keyword} for cash! Top prices paid. Contact us today.",
    };

    // ==================== 中文正常邮件模板 ====================
    // ---- 短文本正常邮件（防止模型把短邮件误判为垃圾） ----
    private static final String[] SHORT_HAM = {
        "好的", "收到", "谢谢", "ok", "test", "hi", "hello",
        "在吗", "稍等", "知道了", "没问题", "确认", "已处理",
        "明天见", "好的谢谢", "got it", "thanks", "sure",
        "行", "可以", "没问题", "嗯嗯", "好滴", "好",
        "了解", "明白", "稍后回复", "还在吗", "okok",
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "你好", "早", "晚安", "明天聊", "周末愉快",
        "有事留言", "晚点联系", "已收到", "请查收", "是的",
        "a", "b", "c", "d", "no", "yes", "n", "y",
        "? ", "...", "哈哈", "嗯", "哦", "啊", "哎",
        "好的好的", "okk", "行吧", "回头聊", "改天见",
        "在忙", "开会中", "稍后回", "有空吗", "在不在",
        "发你了", "看了", "没问题吧", "怎么样", "可以的",
        "不太行", "再看看", "明天吧", "下次一定", "安排",
        "搞定了", "辛苦了", "不客气", "没事", "没关系",
        "挺好的", "还行", "一般般", "还不错", "不错哦",
    };
    private static final String[] HAM_TEMPLATES = {
        "会议通知：本周五下午2点在A308会议室召开项目评审会，请大家准时参加。",
        "附件是本月的工作总结报告，请查收。如有疑问请随时联系我。",
        "张经理，关于上次讨论的项目方案，我已经修改好了，请您过目。",
        "各位同事，下周一的团建活动安排如下：上午9点集合，请提前准备。",
        "你好，我是新来的实习生李明，很高兴加入这个团队，请多多指教。",
        "关于昨天的客户反馈，我们已经做了相应调整，最新版本已上传。",
        "李老师，学生王芳的期末论文已提交，附件中是论文终稿。",
        "感谢您的来信，关于产品定价的问题，我们的建议如下。",
        "王总，这是本周的销售数据分析，整体趋势向好，详情见附件。",
        "您的快递已签收，如有任何问题请联系快递员。祝您生活愉快！",
        "小明，妈妈今天加班，晚饭在冰箱里，你热一下吃。记得写作业。",
        "亲爱的，周末有空吗？我们一起去新开的那个餐厅试试吧。",
        "老师好，我想请教一下关于微积分作业第三题，我的解法对吗？",
        "各位家长，下周三学校举办家长会，时间是下午2点，请准时参加。",
        "技术支持：您的问题已收到，我们会尽快处理。工单编号：TK2024001。",
        "生日聚会通知：本周六晚上7点，在老地方见！别忘了准备礼物哦~",
        "项目进度汇报：第一阶段已完成，第二阶段预计下周启动。",
        "今天的午间分享主题是'高效沟通技巧'，地点在3楼培训室，欢迎大家参加。",
        "关于薪资调整的通知已发布，请登录公司系统查看具体调整方案。",
        "您好，面试通知：请于下周一上午10点携带简历到公司参加面试。",
        "文件已打印好放在你桌上了，一共三份，封面是蓝色的。",
        "小张，麻烦帮我看一下这份合同，确认无误后发给我。谢谢！",
        "本周五的羽毛球预约已确认，场地号3号，时间段18:00-19:00。",
        "爸，我今天考试考了全班第三！老师还表扬我了呢！",
        "数据已备份到云盘，链接和提取码发到你邮箱了。",
        "同学聚会定在下周六，地点待定。有建议的可以群里讨论。",
        "尊敬的会员，您的健身卡即将到期，续费可享受8折优惠。",
        "学长，考研复试的经验能分享一下吗？我现在很紧张。",
        "你好，我在二手平台上看到了你发布的相机，想问一下具体成色。",
        "老板，明天的客户会议改到下午3点了，地点不变。",
        "今天中午食堂有红烧肉，据说味道不错，一起去吃吧！",
        "小刘，请假申请已批准，假期从下周一开始共5天。",
        "图书馆通知：您借阅的图书即将到期，请及时归还或续借。",
        "妈妈生日快乐！今晚我们定了蛋糕，7点到家给你惊喜！",
        "招聘启事：本公司诚聘软件工程师2名，要求3年以上Java开发经验。",
        "各位，下周的需求评审会议请提前准备各自模块的文档。",
        "老王，周末钓鱼去不去？我找到了一个特别好的新钓点。",
        "您的体检报告已出，各项指标正常，建议继续保持锻炼习惯。",
        "老师，我们小组的课题研究已初步完成，请您给些指导意见。",
    };

    // ==================== 英文正常邮件模板 ====================
    private static final String[] ENGLISH_HAM_TEMPLATES = {
        "Hi John, can we reschedule our meeting to Thursday afternoon? Let me know.",
        "Thanks for the update. The report looks good, just a few minor changes needed.",
        "Hey, are you free for lunch tomorrow? The new Italian place near the office.",
        "Please find the attached invoice for the services rendered last month.",
        "Hi team, the Q2 results are in. Overall we exceeded our targets by 12%.",
        "Could you please send me the latest version of the presentation slides?",
        "Mom, I'll be home for dinner tonight. Do you need me to pick up anything?",
        "Dear Professor Smith, I have a question about the assignment due next week.",
        "Happy birthday! Hope you have a wonderful day. Let's celebrate this weekend!",
        "The server maintenance is scheduled for Sunday 2am-4am EST. Please plan accordingly.",
        "I've reviewed your pull request and left some comments. Overall great work!",
        "Kids, don't forget to bring your permission slips for the field trip on Friday.",
        "Your appointment with Dr. Chen is confirmed for Tuesday at 10:30am.",
        "The package has been shipped. Tracking number: 1Z999AA10123456784.",
        "Could you recommend a good restaurant for a business dinner? Around 8 people.",
        "Hi Sarah, just checking in. Haven't heard from you in a while, hope all is well.",
        "Reminder: the quarterly review deadline is next Friday. Please submit your inputs.",
        "Thanks everyone for attending the workshop. The recording is now available.",
        "Grandma is feeling much better today. She sends her love to everyone.",
        "I'm working from home today due to the heavy snow. Available on Slack if needed.",
    };

    // ==================== 诈骗关键词池（从SPAM_KEYWORDS中精选高频词） ====================
    private static final String[] SCAM_KEYWORDS = {
        "免费领取", "中奖通知", "限时优惠", "100%", "绝对真实", "快速致富",
        "刷单", "返利", "佣金", "网贷", "套现", "投资理财", "高收益",
        "稳赚不赔", "内幕消息", "代开发票", "办证", "恭喜你", "注册送",
        "银行卡", "验证码", "转账", "快递丢失", "退款理赔", "订单异常",
        "在家赚钱", "月入过万", "幸运用户", "领取奖金", "充值返现",
        "无抵押贷款", "兼职", "日赚", "抽中", "保证金", "解冻",
        "杀猪盘", "资金盘", "庞氏", "虚拟币", "炒币", "挖矿",
    };

    private static final String[] ENGLISH_SCAM_KEYWORDS = {
        "prize", "winner", "free", "click here", "act now", "limited offer",
        "guaranteed", "lottery", "casino", "viagra", "earn money",
        "work from home", "claim your prize", "free money", "investment",
    };

    // ==================== 生成方法 ====================

    /** 生成全部种子训练样本 */
    public List<SeedSample> generateAllSamples() {
        List<SeedSample> samples = new ArrayList<>();

        // 1. 中文诈骗样本 (~400条)
        for (String template : SCAM_TEMPLATES) {
            for (String kw : pickRandom(SCAM_KEYWORDS, 10)) {
                String text = template.replace("{keyword}", kw);
                samples.add(new SeedSample(text, 1));
            }
        }

        // 2. 中文暴力样本 (~70条)
        for (String template : VIOLENCE_TEMPLATES) {
            String[] violenceKws = {"被曝光", "被伤害", "被绑架", "被勒索",
                    "被威胁", "被打", "被追杀", "被报复", "被捅", "被砍"};
            for (String kw : pickRandom(violenceKws, 8)) {
                String text = template.replace("{keyword}", kw);
                samples.add(new SeedSample(text, 1));
            }
        }

        // 3. 英文诈骗样本 (~150条)
        for (String template : ENGLISH_SPAM_TEMPLATES) {
            for (String kw : pickRandom(ENGLISH_SCAM_KEYWORDS, 8)) {
                String text = template.replace("{keyword}", kw);
                samples.add(new SeedSample(text, 1));
            }
        }

        // 4. 短文本正常邮件 (~40条，防止模型把短邮件误判为垃圾)
        for (String template : SHORT_HAM) {
            samples.add(new SeedSample(template, 0));
        }

        // 5. 中文正常邮件 (~400条)
        for (String template : HAM_TEMPLATES) {
            samples.add(new SeedSample(template, 0));
            for (int i = 0; i < 6; i++) {
                samples.add(new SeedSample(generateHamVariant(template), 0));
            }
        }

        // 6. 英文正常邮件 (~200条)
        for (String template : ENGLISH_HAM_TEMPLATES) {
            samples.add(new SeedSample(template, 0));
            for (int i = 0; i < 6; i++) {
                samples.add(new SeedSample(generateEnglishHamVariant(template), 0));
            }
        }

        // 打乱
        java.util.Collections.shuffle(samples, rng);

        log.info("[SeedData] 种子训练集生成完毕: 总计={}条, 垃圾={}条, 正常={}条",
                samples.size(),
                samples.stream().filter(s -> s.label == 1).count(),
                samples.stream().filter(s -> s.label == 0).count());

        return samples;
    }

    /** 为正常邮件生成简单变体（增加多样性） */
    private String generateHamVariant(String template) {
        String[] subjects = {"关于", "Re:", "Fwd:", "", "请查收：", "您好，"};
        String prefix = subjects[rng.nextInt(subjects.length)];
        String[] suffixes = {"。谢谢！", "。", "。辛苦了", "。收到请回复", "。以上。"};
        String suffix = suffixes[rng.nextInt(suffixes.length)];
        return prefix + template + suffix;
    }

    private String generateEnglishHamVariant(String template) {
        String[] subjects = {"Re: ", "Fwd: ", "About ", ""};
        String prefix = subjects[rng.nextInt(subjects.length)];
        String[] suffixes = {". Thanks!", ".", ". Best regards", ". Regards"};
        String suffix = suffixes[rng.nextInt(suffixes.length)];
        return prefix + template + suffix;
    }

    private String[] pickRandom(String[] arr, int count) {
        java.util.List<String> list = new java.util.ArrayList<>(java.util.Arrays.asList(arr));
        java.util.Collections.shuffle(list, rng);
        return list.subList(0, Math.min(count, list.size())).toArray(new String[0]);
    }

    /** 种子样本结构 */
    public static class SeedSample {
        public final String text;   // 邮件全文（主题+正文模拟）
        public final int label;     // 1=垃圾, 0=正常

        public SeedSample(String text, int label) {
            this.text = text;
            this.label = label;
        }
    }
}
