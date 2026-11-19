-- Search uses original send order, which differs from receive time after delivery delays.
-- Run with application writes stopped. Commit each primary-key range independently.
ALTER TABLE mail_receiver ADD COLUMN mail_send_time DATETIME NULL;

DELIMITER $$
CREATE PROCEDURE backfill_receiver_search_order()
BEGIN
    DECLARE range_start BIGINT;
    DECLARE last_id BIGINT;
    SELECT COALESCE(MIN(id), 0), COALESCE(MAX(id), -1)
      INTO range_start, last_id FROM mail_receiver;
    WHILE range_start <= last_id DO
        UPDATE mail_receiver r FORCE INDEX (PRIMARY)
          STRAIGHT_JOIN mail m ON m.mail_id = r.mail_id
          SET r.mail_send_time = m.send_time
          WHERE r.id >= range_start AND r.id < range_start + 10000
            AND r.mail_send_time IS NULL;
        COMMIT;
        SET range_start = range_start + 10000;
    END WHILE;
END$$
DELIMITER ;

CALL backfill_receiver_search_order();
DROP PROCEDURE backfill_receiver_search_order;
ALTER TABLE mail_receiver MODIFY mail_send_time DATETIME NOT NULL,
    ADD INDEX idx_receiver_search (receiver_id, is_deleted, mail_send_time DESC, mail_id DESC);
