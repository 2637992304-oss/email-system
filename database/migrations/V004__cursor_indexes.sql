-- Selected against the recorded EXPLAIN ANALYZE plans; see docs/evidence/query-*.json.
CREATE INDEX idx_receiver_cursor ON mail_receiver(receiver_id,is_deleted,receive_time DESC,mail_id DESC);
CREATE INDEX idx_sender_cursor ON mail(sender_id,sender_deleted,mail_status,send_time DESC,mail_id DESC);
