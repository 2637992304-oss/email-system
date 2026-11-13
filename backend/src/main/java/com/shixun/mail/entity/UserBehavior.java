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
@TableName("user_behavior")
public class UserBehavior {

    @TableId(value = "id", type = IdType.AUTO)
    private Long behaviorId;

    @TableField("user_id")
    private Long userId;

    @TableField("mail_id")
    private Long mailId;

    /** 发件人ID，用于优先级聚合 — 映射 sender_id */
    @TableField("sender_id")
    private Long senderId;

    @TableField("behavior_type")
    private String behaviorType;

    /** 行为时间 — 映射 created_at */
    @TableField("created_at")
    private LocalDateTime behaviorTime;
}
