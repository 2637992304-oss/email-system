-- Run with application writes stopped. Sender IDs are immutable after mail creation.
ALTER TABLE spam_record ADD COLUMN sender_id BIGINT NULL;
UPDATE spam_record sr STRAIGHT_JOIN mail m ON m.mail_id = sr.mail_id
SET sr.sender_id = m.sender_id;
ALTER TABLE spam_record MODIFY sender_id BIGINT NOT NULL,
    ADD INDEX idx_spam_sender_created (sender_id, created_at);
