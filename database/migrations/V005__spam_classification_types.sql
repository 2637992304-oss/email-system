-- Preserve existing dictionary/feedback classification paths during async delivery.
ALTER TABLE spam_record DROP CHECK ck_spam_type;
ALTER TABLE spam_record ADD CONSTRAINT ck_spam_type CHECK (spam_type IN ('','KEYWORD','FREQUENCY','SENDER','DL','USER_REPORT','DICT','USER_BLOCK'));
