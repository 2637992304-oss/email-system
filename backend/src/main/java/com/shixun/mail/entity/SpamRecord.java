package com.shixun.mail.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("spam_record")
public class SpamRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long spamId;

    @TableField("mail_id")
    private Long mailId;

    /** 发件人信息 — 映射 sender_info */
    @TableField("sender_info")
    private String senderInfo;

    /** 垃圾类型: KEYWORD / FREQUENCY / SENDER — 映射 spam_type */
    @TableField("spam_type")
    private String spamType;

    /** 命中的关键词 — 映射 keywords */
    @TableField("keywords")
    private String hitKeyword;

    /** 垃圾判定原因 — 映射 reason */
    @TableField("reason")
    private String riskReason;

    /** 检测时间 — 映射 created_at */
    @TableField("created_at")
    private LocalDateTime detectTime;

    /** 风险评分（仅内存计算，不持久化到数据库） */
    @TableField(exist = false)
    private Integer riskScore;
}
