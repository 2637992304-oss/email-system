-- Apply once to an existing schema, before enabling the high-concurrency profile.
ALTER TABLE mail ADD delivery_status VARCHAR(16) NOT NULL DEFAULT 'DELIVERED';
ALTER TABLE attachment MODIFY mail_id BIGINT NULL;
CREATE TABLE mail_submission (
 sender_id BIGINT NOT NULL, request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) NOT NULL, mail_id BIGINT NULL, PRIMARY KEY(sender_id,request_key)
) ENGINE=InnoDB;
CREATE TABLE delivery_task (
 mail_id BIGINT PRIMARY KEY, sender_id BIGINT NOT NULL, recipients JSON NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempt INT NOT NULL DEFAULT 0,
 lease_until DATETIME(6) NULL, failure VARCHAR(255) NULL,
 accepted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), delivered_at DATETIME(6) NULL,
 KEY task_status(status,lease_until)
) ENGINE=InnoDB;
CREATE TABLE mail_outbox (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, mail_id BIGINT NOT NULL, kind VARCHAR(16) NOT NULL DEFAULT 'DELIVERY', attempt INT NOT NULL DEFAULT 0,
 available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_token VARCHAR(36) NULL, lease_until DATETIME(6) NULL, published BOOLEAN NOT NULL DEFAULT FALSE,
 KEY outbox_pending(published,available_at,lease_until)
) ENGINE=InnoDB;
-- Serializes admission capacity checks across backend instances.
CREATE TABLE delivery_admission (id INT PRIMARY KEY);
INSERT INTO delivery_admission VALUES (1);
