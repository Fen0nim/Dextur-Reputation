CREATE TABLE IF NOT EXISTS dr_investigation (
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    record_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    vote_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    payload LONGTEXT CHARACTER SET utf8mb4 NOT NULL,
    checksum CHAR(64) CHARACTER SET ascii NOT NULL,
    PRIMARY KEY(kind,record_id),
    INDEX dr_investigation_player(scope_id,kind,target_uuid,created_at,record_id),
    INDEX dr_investigation_vote(scope_id,kind,vote_id,created_at,record_id),
    INDEX dr_investigation_status(scope_id,kind,status,created_at,record_id),
    INDEX dr_investigation_retention(kind,created_at),
    INDEX dr_investigation_actor(scope_id,kind,actor_uuid,created_at,record_id)
) ENGINE=InnoDB;
