# 数据库字段说明

本文档对应 `database/schema.sql`，目标数据库为 MySQL 8.x，字符集为 `utf8mb4`。当前数据库包含用户、邮件、星标、联系人名单、收件关系、附件、垃圾邮件记录、用户反馈、模型持久化、垃圾发件人警告和用户行为等 11 张表。

邮件正文存放在 `mail.content`。附件文件保存到后端本地磁盘，数据库只保存附件元数据和文件路径。

## 1. user 用户表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| user_id | bigint | PK, auto increment | 用户 ID |
| email | varchar(255) | UK, not null | 邮箱地址，用于登录和唯一识别 |
| username | varchar(64) | not null | 用户名 |
| password | varchar(255) | not null | 密码哈希 |
| register_time | datetime | not null, default current timestamp | 注册时间 |
| status | int | not null, default 1, check in (0, 1) | 账号状态：0 禁用，1 正常 |

设计说明：`email` 使用唯一索引 `uk_user_email`，避免重复注册；`status` 使用索引 `idx_user_status`，便于按账号状态过滤。

## 2. mail 邮件表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| mail_id | bigint | PK, auto increment | 邮件 ID |
| sender_id | bigint | FK -> user.user_id, not null | 发件人 ID |
| subject | varchar(255) | not null | 邮件主题 |
| content | text | nullable | 邮件正文 |
| send_time | datetime | not null, default current timestamp | 发送时间 |
| has_attachment | boolean | not null, default false | 是否有附件 |
| sender_deleted | boolean | not null, default false | 发件人侧是否删除 |
| is_spam | boolean | not null, default false | 是否为垃圾邮件 |
| priority | int | not null, default 3, check in (1, 2, 3, 4) | 优先级：1 高，2 中，3 普通，4 低 |
| mail_status | int | not null, default 1, check in (0, 1, 2, 3) | 状态：0 草稿，1 已发送，2 已撤回，3 已删除 |

设计说明：`sender_id + send_time` 使用索引 `idx_mail_sender_time`，用于发件箱按时间查询；`mail_status` 和 `is_spam` 分别建立索引，便于状态过滤和垃圾邮件过滤。

## 3. mail_star 星标邮件表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK, auto increment | 星标记录 ID |
| user_id | bigint | FK -> user.user_id, not null | 用户 ID |
| mail_id | bigint | FK -> mail.mail_id, not null | 邮件 ID |
| created_at | datetime | not null, default current timestamp | 创建时间 |

设计说明：`user_id + mail_id` 使用唯一索引 `uk_user_mail`，确保同一用户对同一封邮件只能星标一次。删除邮件时通过 `fk_mail_star_mail` 级联删除对应星标记录。

## 4. user_contact_list 联系人名单表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK, auto increment | 联系人名单记录 ID |
| user_id | bigint | FK -> user.user_id, not null | 用户 ID |
| contact_email | varchar(255) | not null | 联系人邮箱地址 |
| list_type | varchar(16) | not null, check in (WHITELIST, BLACKLIST, VIP) | 名单类型：白名单、黑名单、VIP |
| created_at | datetime | not null, default current timestamp | 创建时间 |

设计说明：`user_id + contact_email` 使用唯一索引 `uk_user_contact`，避免同一用户重复维护同一个联系人邮箱。

## 5. mail_receiver 收件关系表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK, auto increment | 收件记录 ID |
| mail_id | bigint | FK -> mail.mail_id, not null | 邮件 ID |
| receiver_id | bigint | FK -> user.user_id, not null | 收件人 ID |
| receiver_type | varchar(8) | not null, check in (TO, CC, BCC) | 收件类型：收件、抄送、密送 |
| is_read | boolean | not null, default false | 是否已读 |
| is_deleted | boolean | not null, default false | 收件人侧是否删除 |
| receive_time | datetime | not null, default current timestamp | 接收时间 |

设计说明：一封邮件可对应多条收件记录。`mail_id + receiver_id + receiver_type` 使用唯一索引 `uk_mail_receiver_type`，避免同一用户在同一收件类型下重复接收。删除邮件时级联删除对应收件关系。

## 6. attachment 附件表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| attachment_id | bigint | PK, auto increment | 附件 ID |
| mail_id | bigint | FK -> mail.mail_id, not null | 所属邮件 ID |
| uploader_id | bigint | FK -> user.user_id, not null | 上传者 ID |
| file_name | varchar(255) | not null | 原始文件名 |
| file_path | varchar(512) | not null | 服务端文件路径 |
| file_size | bigint | not null, check >= 0 | 文件大小，单位字节 |
| file_type | varchar(128) | nullable | 文件类型或 MIME 类型 |
| upload_time | datetime | not null, default current timestamp | 上传时间 |

设计说明：附件与邮件是一对多关系。删除邮件时级联删除附件元数据，物理文件删除由业务代码根据 `file_path` 处理。

## 7. spam_record 垃圾邮件记录表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK, auto increment | 垃圾邮件记录 ID |
| mail_id | bigint | FK -> mail.mail_id, UK, not null | 邮件 ID |
| sender_info | varchar(128) | default empty string | 发件人信息 |
| spam_type | varchar(32) | not null, default empty string, check in ('', KEYWORD, FREQUENCY, SENDER, DL, USER_REPORT) | 垃圾邮件类型 |
| keywords | text | nullable | 命中的垃圾邮件关键词 |
| reason | varchar(255) | not null, default empty string | 判定为垃圾邮件的原因 |
| created_at | datetime | not null, default current timestamp | 创建时间 |

设计说明：当前按一封邮件最多一条垃圾邮件记录设计，因此 `mail_id` 使用唯一索引 `uk_spam_mail`。删除邮件时通过 `fk_spam_mail` 级联删除对应垃圾邮件记录。

## 8. spam_feedback 垃圾邮件反馈表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK, auto increment | 反馈记录 ID |
| user_id | bigint | FK -> user.user_id, not null | 反馈用户 ID |
| mail_id | bigint | FK -> mail.mail_id, not null | 被反馈邮件 ID |
| feedback_type | varchar(16) | not null, check in (REPORT_SPAM, NOT_SPAM) | 反馈类型：举报垃圾邮件、标记非垃圾邮件 |
| created_at | datetime | not null, default current timestamp | 反馈时间 |

设计说明：`user_id + mail_id` 使用唯一索引 `uk_user_mail_feedback`，确保同一用户对同一封邮件只有一条反馈记录。该表为深度学习垃圾邮件识别提供训练反馈数据。

## 9. spam_model 垃圾邮件模型持久化表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | int | PK, default 1 | 单例模型记录 ID |
| model_json | longtext | not null | 序列化后的模型权重 JSON |
| input_dim | int | not null, default 256 | 输入特征维度 |
| hidden_dim | int | not null, default 128 | 隐藏层神经元数量 |
| training_count | int | not null, default 0 | 已处理训练样本总数 |
| recent_accuracy | double | not null, default 0 | 最近训练准确率的滑动值 |
| updated_at | datetime | not null, default current timestamp on update current timestamp | 更新时间 |

设计说明：该表用于持久化深度学习模型状态，重启后可恢复模型权重。当前按单例记录设计，主键通常固定为 `1`。

## 10. spam_warning 垃圾发件人警告表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK, auto increment | 警告记录 ID |
| recipient_id | bigint | FK -> user.user_id, not null | 接收警告的用户 ID |
| sender_email | varchar(255) | not null | 触发警告的垃圾邮件发件人邮箱 |
| mail_id | bigint | not null | 触发警告的垃圾邮件 ID |
| status | varchar(16) | not null, default PENDING, check in (PENDING, BLOCKED, IGNORED) | 处理状态：待处理、已拉黑、已忽略 |
| created_at | datetime | not null, default current timestamp | 创建时间 |
| resolved_at | datetime | nullable | 处理时间 |

设计说明：`recipient_id + status` 使用索引 `idx_warning_recipient_status`，用于查询某个用户待处理或已处理的警告。`recipient_id + sender_email + status` 使用唯一索引 `uk_recipient_sender_pending`，避免同一用户对同一发件人在同一状态下产生重复警告。

## 11. user_behavior 用户行为表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK, auto increment | 行为 ID |
| user_id | bigint | FK -> user.user_id, not null | 用户 ID |
| mail_id | bigint | FK -> mail.mail_id, not null | 邮件 ID |
| sender_id | bigint | nullable | 用于优先级聚合的发件人 ID |
| behavior_type | varchar(32) | not null, check in (OPEN, REPLY, DELETE, SEND) | 行为类型 |
| created_at | datetime | not null, default current timestamp | 创建时间 |

设计说明：该表用于记录打开、回复、删除、发送等用户行为，可为统计分析、优先级聚合或智能推荐提供基础数据。删除邮件时通过 `fk_behavior_mail` 级联删除对应行为记录。

## 表关系汇总

| 关系 | 类型 | 说明 |
| --- | --- | --- |
| user -> mail | 1:N | 一个用户可以发送多封邮件 |
| user -> mail_star | 1:N | 一个用户可以星标多封邮件 |
| mail -> mail_star | 1:N | 一封邮件可以被多个用户星标 |
| user -> user_contact_list | 1:N | 一个用户可以维护多条联系人名单记录 |
| mail -> mail_receiver | 1:N | 一封邮件可以发送给多个用户 |
| user -> mail_receiver | 1:N | 一个用户可以接收多封邮件 |
| mail -> attachment | 1:N | 一封邮件可以有多个附件 |
| user -> attachment | 1:N | 一个用户可以上传多个附件 |
| mail -> spam_record | 1:1 | 一封邮件最多一条垃圾邮件记录 |
| user -> spam_feedback | 1:N | 一个用户可以提交多条垃圾邮件反馈 |
| mail -> spam_feedback | 1:N | 一封邮件可以收到多个用户反馈 |
| user -> spam_warning | 1:N | 一个用户可以收到多条垃圾发件人警告 |
| user/mail -> user_behavior | 1:N | 用户针对邮件产生多条行为记录 |
| spam_model | singleton | 模型持久化单例表，不直接依赖其他业务表 |
