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
@TableName("spam_feedback")
public class SpamFeedback {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("mail_id")
    private Long mailId;

    /** REPORT_SPAM / NOT_SPAM */
    @TableField("feedback_type")
    private String feedbackType;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
