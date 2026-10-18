CREATE TABLE IF NOT EXISTS dr_votes (
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    sequence_no BIGINT NOT NULL UNIQUE,
    scope_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    voter_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(8) CHARACTER SET ascii NOT NULL,
    vote_type VARCHAR(8) CHARACTER SET ascii NOT NULL,
    reason_id VARCHAR(32) CHARACTER SET ascii NOT NULL,
    comment_text VARCHAR(2000) CHARACTER SET utf8mb4 NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    previous_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    base_weight BIGINT NOT NULL,
    effective_weight BIGINT NOT NULL,
    calculation_version INT NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    community_before BIGINT NOT NULL,
    community_after BIGINT NOT NULL,
    INDEX dr_votes_voter_time(scope_id,voter_uuid,created_at),
    INDEX dr_votes_pair(scope_id,voter_uuid,target_uuid,sequence_no),
    INDEX dr_votes_target(scope_id,target_uuid,sequence_no)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS dr_active_votes (
    scope_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    voter_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    vote_type VARCHAR(8) CHARACTER SET ascii NOT NULL,
    PRIMARY KEY(scope_id,voter_uuid,target_uuid),
    INDEX dr_active_target(scope_id,target_uuid,vote_type)
) ENGINE=InnoDB;
