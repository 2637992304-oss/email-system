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
@TableName("spam_warning")
public class SpamWarning {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("recipient_id")
    private Long recipientId;

    @TableField("sender_email")
    private String senderEmail;

    @TableField("mail_id")
    private Long mailId;

    /** PENDING / BLOCKED / IGNORED */
    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("resolved_at")
    private LocalDateTime resolvedAt;
}
