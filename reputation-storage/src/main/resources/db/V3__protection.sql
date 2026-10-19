CREATE TABLE IF NOT EXISTS dr_trust (
    record_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    scope_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL, input_revision BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL, status VARCHAR(16) CHARACTER SET ascii NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL, payload LONGTEXT CHARACTER SET utf8mb4 NOT NULL,
    INDEX dr_trust_actor(scope_id,actor_uuid),
    INDEX dr_trust_target(scope_id,target_uuid,sequence_no),
    INDEX dr_trust_due(scope_id,updated_at),
    INDEX dr_trust_status(scope_id,status)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS dr_vote_pairs (
    record_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    scope_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL, input_revision BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL, status VARCHAR(16) CHARACTER SET ascii NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL, payload LONGTEXT CHARACTER SET utf8mb4 NOT NULL,
    INDEX dr_vote_pairs_actor(scope_id,actor_uuid),
    INDEX dr_vote_pairs_target(scope_id,target_uuid,sequence_no),
    INDEX dr_vote_pairs_due(scope_id,updated_at),
    INDEX dr_vote_pairs_status(scope_id,status)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS dr_precision (
    record_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    scope_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL, input_revision BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL, status VARCHAR(16) CHARACTER SET ascii NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL, payload LONGTEXT CHARACTER SET utf8mb4 NOT NULL,
    INDEX dr_precision_actor(scope_id,actor_uuid),
    INDEX dr_precision_target(scope_id,target_uuid,sequence_no),
    INDEX dr_precision_due(scope_id,updated_at),
    INDEX dr_precision_status(scope_id,status)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS dr_vote_assessments (
    record_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    scope_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL, input_revision BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL, status VARCHAR(16) CHARACTER SET ascii NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL, payload LONGTEXT CHARACTER SET utf8mb4 NOT NULL,
    INDEX dr_vote_assessments_actor(scope_id,actor_uuid),
    INDEX dr_vote_assessments_target(scope_id,target_uuid,sequence_no),
    INDEX dr_vote_assessments_due(scope_id,updated_at),
    INDEX dr_vote_assessments_status(scope_id,status)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS dr_protection_audit (
    record_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    scope_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL, input_revision BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL, status VARCHAR(16) CHARACTER SET ascii NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL, payload LONGTEXT CHARACTER SET utf8mb4 NOT NULL,
    UNIQUE INDEX dr_protection_audit_sequence(scope_id,sequence_no),
    INDEX dr_protection_audit_actor(scope_id,actor_uuid),
    INDEX dr_protection_audit_target(scope_id,target_uuid,sequence_no),
    INDEX dr_protection_audit_due(scope_id,updated_at),
    INDEX dr_protection_audit_status(scope_id,status)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS dr_protection_scope (
    record_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    scope_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL, input_revision BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL, status VARCHAR(16) CHARACTER SET ascii NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL, payload LONGTEXT CHARACTER SET utf8mb4 NOT NULL,
    INDEX dr_protection_scope_actor(scope_id,actor_uuid),
    INDEX dr_protection_scope_target(scope_id,target_uuid,sequence_no),
    INDEX dr_protection_scope_due(scope_id,updated_at),
    INDEX dr_protection_scope_status(scope_id,status)
) ENGINE=InnoDB;

CREATE INDEX dr_votes_protection_voter ON dr_votes(scope_id,voter_uuid,created_at,operation_id);
CREATE INDEX dr_votes_protection_target ON dr_votes(scope_id,target_uuid,created_at,operation_id);
CREATE INDEX dr_votes_protection_scope ON dr_votes(scope_id,sequence_no);
