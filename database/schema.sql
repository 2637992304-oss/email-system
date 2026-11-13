-- Intelligent Mail Management System schema
-- Target database: MySQL 8.x
-- Charset: utf8mb4

SET NAMES utf8mb4;


CREATE TABLE `user` (
    `user_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'User ID',
    `email` VARCHAR(255) NOT NULL COMMENT 'Email address',
    `username` VARCHAR(64) NOT NULL COMMENT 'Username',
    `password` VARCHAR(255) NOT NULL COMMENT 'Password hash',
    `register_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Registration time',
    `status` INT NOT NULL DEFAULT 1 COMMENT 'Account status: 0 disabled, 1 active',
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uk_user_email` (`email`),
    KEY `idx_user_status` (`status`),
    CONSTRAINT `ck_user_status` CHECK (`status` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User table';

CREATE TABLE `mail` (
    `mail_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Mail ID',
    `sender_id` BIGINT NOT NULL COMMENT 'Sender user ID',
    `subject` VARCHAR(255) NOT NULL COMMENT 'Mail subject',
    `content` TEXT COMMENT 'Mail content',
    `send_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Send time',
    `has_attachment` BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether mail has attachments',
    `sender_deleted` BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether sender deleted the mail',
    `is_spam` BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether mail is spam',
    `priority` INT NOT NULL DEFAULT 3 COMMENT 'Priority: 1 high, 2 medium, 3 normal, 4 low',
    `mail_status` INT NOT NULL DEFAULT 1 COMMENT 'Status: 0 draft, 1 sent, 2 recalled, 3 deleted',
    PRIMARY KEY (`mail_id`),
    KEY `idx_mail_sender_time` (`sender_id`, `send_time`),
    KEY `idx_mail_status` (`mail_status`),
    KEY `idx_mail_spam` (`is_spam`),
    CONSTRAINT `fk_mail_sender` FOREIGN KEY (`sender_id`) REFERENCES `user` (`user_id`),
    CONSTRAINT `ck_mail_priority` CHECK (`priority` IN (1, 2, 3, 4)),
    CONSTRAINT `ck_mail_status` CHECK (`mail_status` IN (0, 1, 2, 3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Mail table';

CREATE TABLE `mail_star` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Star record ID',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `mail_id` BIGINT NOT NULL COMMENT 'Mail ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_mail` (`user_id`, `mail_id`),
    CONSTRAINT `fk_mail_star_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`user_id`),
    CONSTRAINT `fk_mail_star_mail` FOREIGN KEY (`mail_id`) REFERENCES `mail` (`mail_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Mail star table';

CREATE TABLE `user_contact_list` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Contact list record ID',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `contact_email` VARCHAR(255) NOT NULL COMMENT 'Contact email address',
    `list_type` VARCHAR(16) NOT NULL COMMENT 'List type: WHITELIST/BLACKLIST/VIP',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_contact` (`user_id`, `contact_email`),
    CONSTRAINT `fk_user_contact_list_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`user_id`),
    CONSTRAINT `ck_user_contact_list_type` CHECK (`list_type` IN ('WHITELIST', 'BLACKLIST', 'VIP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User contact list table';

CREATE TABLE `mail_receiver` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Receiver record ID',
    `mail_id` BIGINT NOT NULL COMMENT 'Mail ID',
    `receiver_id` BIGINT NOT NULL COMMENT 'Receiver user ID',
    `receiver_type` VARCHAR(8) NOT NULL COMMENT 'Receiver type: TO/CC/BCC',
    `is_read` BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether mail is read',
    `is_deleted` BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether receiver deleted the mail',
    `receive_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Receive time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mail_receiver_type` (`mail_id`, `receiver_id`, `receiver_type`),
    KEY `idx_receiver_inbox` (`receiver_id`, `is_deleted`, `receive_time`),
    KEY `idx_receiver_read` (`receiver_id`, `is_read`),
    CONSTRAINT `fk_mail_receiver_mail` FOREIGN KEY (`mail_id`) REFERENCES `mail` (`mail_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_mail_receiver_user` FOREIGN KEY (`receiver_id`) REFERENCES `user` (`user_id`),
    CONSTRAINT `ck_mail_receiver_type` CHECK (`receiver_type` IN ('TO', 'CC', 'BCC'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Mail receiver table';

CREATE TABLE `attachment` (
    `attachment_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Attachment ID',
    `mail_id` BIGINT NOT NULL COMMENT 'Mail ID',
    `uploader_id` BIGINT NOT NULL COMMENT 'Uploader user ID',
    `file_name` VARCHAR(255) NOT NULL COMMENT 'Original file name',
    `file_path` VARCHAR(512) NOT NULL COMMENT 'Server file path',
    `file_size` BIGINT NOT NULL COMMENT 'File size in bytes',
    `file_type` VARCHAR(128) COMMENT 'File type or MIME type',
    `upload_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Upload time',
    PRIMARY KEY (`attachment_id`),
    KEY `idx_attachment_mail` (`mail_id`),
    KEY `idx_attachment_uploader` (`uploader_id`, `upload_time`),
    CONSTRAINT `fk_attachment_mail` FOREIGN KEY (`mail_id`) REFERENCES `mail` (`mail_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_attachment_uploader` FOREIGN KEY (`uploader_id`) REFERENCES `user` (`user_id`),
    CONSTRAINT `ck_attachment_file_size` CHECK (`file_size` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Attachment table';

CREATE TABLE `spam_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Spam record ID',
    `mail_id` BIGINT NOT NULL COMMENT 'Mail ID',
    `sender_info` VARCHAR(128) DEFAULT '' COMMENT 'Sender information',
    `spam_type` VARCHAR(32) NOT NULL DEFAULT '' COMMENT 'Type: KEYWORD/FREQUENCY/SENDER/DL/USER_REPORT',
    `keywords` TEXT COMMENT 'Matched spam keywords',
    `reason` VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'Spam reason',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_spam_mail` (`mail_id`),
    KEY `idx_spam_type` (`spam_type`),
    KEY `idx_spam_created_at` (`created_at`),
    CONSTRAINT `fk_spam_mail` FOREIGN KEY (`mail_id`) REFERENCES `mail` (`mail_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_spam_type` CHECK (`spam_type` IN ('', 'KEYWORD', 'FREQUENCY', 'SENDER', 'DL', 'USER_REPORT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Spam record table';

CREATE TABLE `spam_feedback` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Feedback ID',
    `user_id` BIGINT NOT NULL COMMENT 'User who reported',
    `mail_id` BIGINT NOT NULL COMMENT 'Reported mail ID',
    `feedback_type` VARCHAR(16) NOT NULL COMMENT 'REPORT_SPAM / NOT_SPAM',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Feedback time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_mail_feedback` (`user_id`, `mail_id`),
    KEY `idx_feedback_type` (`feedback_type`),
    CONSTRAINT `fk_feedback_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`user_id`),
    CONSTRAINT `fk_feedback_mail` FOREIGN KEY (`mail_id`) REFERENCES `mail` (`mail_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_feedback_type` CHECK (`feedback_type` IN ('REPORT_SPAM', 'NOT_SPAM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User spam feedback for deep learning training';

CREATE TABLE `spam_model` (
    `id` INT NOT NULL DEFAULT 1 COMMENT 'Singleton row ID',
    `model_json` LONGTEXT NOT NULL COMMENT 'Serialized model weights (JSON)',
    `input_dim` INT NOT NULL DEFAULT 256 COMMENT 'Input feature dimension',
    `hidden_dim` INT NOT NULL DEFAULT 128 COMMENT 'Hidden layer neurons',
    `training_count` INT NOT NULL DEFAULT 0 COMMENT 'Total training samples processed',
    `recent_accuracy` DOUBLE NOT NULL DEFAULT 0 COMMENT 'Recent training accuracy (EMA)',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Deep learning model persistence';

CREATE TABLE `spam_warning` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Warning ID',
    `recipient_id` BIGINT NOT NULL COMMENT 'Recipient user ID',
    `sender_email` VARCHAR(255) NOT NULL COMMENT 'Spam sender email',
    `mail_id` BIGINT NOT NULL COMMENT 'Spam mail ID that triggered the warning',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / BLOCKED / IGNORED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `resolved_at` DATETIME NULL COMMENT 'Resolved time',
    PRIMARY KEY (`id`),
    KEY `idx_warning_recipient_status` (`recipient_id`, `status`),
    UNIQUE KEY `uk_recipient_sender_pending` (`recipient_id`, `sender_email`, `status`),
    CONSTRAINT `fk_warning_recipient` FOREIGN KEY (`recipient_id`) REFERENCES `user` (`user_id`),
    CONSTRAINT `ck_warning_status` CHECK (`status` IN ('PENDING', 'BLOCKED', 'IGNORED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Spam sender warning before blocking';

CREATE TABLE `user_behavior` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Behavior ID',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `mail_id` BIGINT NOT NULL COMMENT 'Mail ID',
    `sender_id` BIGINT COMMENT 'Sender user ID for priority aggregation',
    `behavior_type` VARCHAR(32) NOT NULL COMMENT 'Behavior type: OPEN/REPLY/DELETE/SEND',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    PRIMARY KEY (`id`),
    KEY `idx_behavior_user_time` (`user_id`, `created_at`),
    KEY `idx_behavior_mail` (`mail_id`),
    KEY `idx_behavior_sender` (`sender_id`),
    KEY `idx_behavior_type` (`behavior_type`),
    CONSTRAINT `fk_behavior_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`user_id`),
    CONSTRAINT `fk_behavior_mail` FOREIGN KEY (`mail_id`) REFERENCES `mail` (`mail_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_behavior_type` CHECK (`behavior_type` IN ('OPEN', 'REPLY', 'DELETE', 'SEND'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User behavior table';
