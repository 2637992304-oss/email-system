package com.shixun.mail.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mail")
public class Mail {

    @TableId(value = "mail_id", type = IdType.AUTO)
    private Long mailId;

    private Long senderId;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    private String senderName;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    private String senderEmail;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    private Boolean starred;

    private String subject;

    private String content;

    private LocalDateTime sendTime;

    private Boolean hasAttachment;

    /** 是否为垃圾邮件 — 映射 is_spam */
    @com.baomidou.mybatisplus.annotation.TableField("is_spam")
    private Boolean spam;

    private Integer priority;

    private Integer mailStatus;

    private Boolean senderDeleted;
}
