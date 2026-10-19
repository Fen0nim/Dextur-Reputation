CREATE TABLE IF NOT EXISTS dr_trust (
    record_key TEXT PRIMARY KEY NOT NULL, scope_id TEXT NOT NULL, actor_uuid TEXT NOT NULL, target_uuid TEXT NOT NULL,
    revision INTEGER NOT NULL, input_revision INTEGER NOT NULL, sequence_no INTEGER NOT NULL, status TEXT NOT NULL,
    updated_at TEXT NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS dr_trust_actor ON dr_trust(scope_id,actor_uuid);
CREATE INDEX IF NOT EXISTS dr_trust_target ON dr_trust(scope_id,target_uuid,sequence_no);
CREATE INDEX IF NOT EXISTS dr_trust_due ON dr_trust(scope_id,updated_at);
CREATE INDEX IF NOT EXISTS dr_trust_status ON dr_trust(scope_id,status);
CREATE TABLE IF NOT EXISTS dr_vote_pairs (
    record_key TEXT PRIMARY KEY NOT NULL, scope_id TEXT NOT NULL, actor_uuid TEXT NOT NULL, target_uuid TEXT NOT NULL,
    revision INTEGER NOT NULL, input_revision INTEGER NOT NULL, sequence_no INTEGER NOT NULL, status TEXT NOT NULL,
    updated_at TEXT NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS dr_vote_pairs_actor ON dr_vote_pairs(scope_id,actor_uuid);
CREATE INDEX IF NOT EXISTS dr_vote_pairs_target ON dr_vote_pairs(scope_id,target_uuid,sequence_no);
CREATE INDEX IF NOT EXISTS dr_vote_pairs_due ON dr_vote_pairs(scope_id,updated_at);
CREATE INDEX IF NOT EXISTS dr_vote_pairs_status ON dr_vote_pairs(scope_id,status);
CREATE TABLE IF NOT EXISTS dr_precision (
    record_key TEXT PRIMARY KEY NOT NULL, scope_id TEXT NOT NULL, actor_uuid TEXT NOT NULL, target_uuid TEXT NOT NULL,
    revision INTEGER NOT NULL, input_revision INTEGER NOT NULL, sequence_no INTEGER NOT NULL, status TEXT NOT NULL,
    updated_at TEXT NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS dr_precision_actor ON dr_precision(scope_id,actor_uuid);
CREATE INDEX IF NOT EXISTS dr_precision_target ON dr_precision(scope_id,target_uuid,sequence_no);
CREATE INDEX IF NOT EXISTS dr_precision_due ON dr_precision(scope_id,updated_at);
CREATE INDEX IF NOT EXISTS dr_precision_status ON dr_precision(scope_id,status);
CREATE TABLE IF NOT EXISTS dr_vote_assessments (
    record_key TEXT PRIMARY KEY NOT NULL, scope_id TEXT NOT NULL, actor_uuid TEXT NOT NULL, target_uuid TEXT NOT NULL,
    revision INTEGER NOT NULL, input_revision INTEGER NOT NULL, sequence_no INTEGER NOT NULL, status TEXT NOT NULL,
    updated_at TEXT NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS dr_vote_assessments_actor ON dr_vote_assessments(scope_id,actor_uuid);
CREATE INDEX IF NOT EXISTS dr_vote_assessments_target ON dr_vote_assessments(scope_id,target_uuid,sequence_no);
CREATE INDEX IF NOT EXISTS dr_vote_assessments_due ON dr_vote_assessments(scope_id,updated_at);
CREATE INDEX IF NOT EXISTS dr_vote_assessments_status ON dr_vote_assessments(scope_id,status);
CREATE TABLE IF NOT EXISTS dr_protection_audit (
    record_key TEXT PRIMARY KEY NOT NULL, scope_id TEXT NOT NULL, actor_uuid TEXT NOT NULL, target_uuid TEXT NOT NULL,
    revision INTEGER NOT NULL, input_revision INTEGER NOT NULL, sequence_no INTEGER NOT NULL, status TEXT NOT NULL,
    updated_at TEXT NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS dr_protection_audit_actor ON dr_protection_audit(scope_id,actor_uuid);
CREATE INDEX IF NOT EXISTS dr_protection_audit_target ON dr_protection_audit(scope_id,target_uuid,sequence_no);
CREATE INDEX IF NOT EXISTS dr_protection_audit_due ON dr_protection_audit(scope_id,updated_at);
CREATE INDEX IF NOT EXISTS dr_protection_audit_status ON dr_protection_audit(scope_id,status);
CREATE TABLE IF NOT EXISTS dr_protection_scope (
    record_key TEXT PRIMARY KEY NOT NULL, scope_id TEXT NOT NULL, actor_uuid TEXT NOT NULL, target_uuid TEXT NOT NULL,
    revision INTEGER NOT NULL, input_revision INTEGER NOT NULL, sequence_no INTEGER NOT NULL, status TEXT NOT NULL,
    updated_at TEXT NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS dr_protection_scope_actor ON dr_protection_scope(scope_id,actor_uuid);
CREATE INDEX IF NOT EXISTS dr_protection_scope_target ON dr_protection_scope(scope_id,target_uuid,sequence_no);
CREATE INDEX IF NOT EXISTS dr_protection_scope_due ON dr_protection_scope(scope_id,updated_at);
CREATE INDEX IF NOT EXISTS dr_protection_scope_status ON dr_protection_scope(scope_id,status);
CREATE UNIQUE INDEX IF NOT EXISTS dr_protection_audit_sequence ON dr_protection_audit(scope_id,sequence_no);
CREATE INDEX IF NOT EXISTS dr_votes_protection_voter ON dr_votes(scope_id,voter_uuid,created_at,operation_id);
CREATE INDEX IF NOT EXISTS dr_votes_protection_target ON dr_votes(scope_id,target_uuid,created_at,operation_id);
CREATE INDEX IF NOT EXISTS dr_votes_protection_scope ON dr_votes(scope_id,sequence_no);
