CREATE TABLE IF NOT EXISTS dr_votes (
 operation_id TEXT PRIMARY KEY NOT NULL, sequence_no INTEGER NOT NULL UNIQUE,
 scope_id TEXT NOT NULL, voter_uuid TEXT NOT NULL, target_uuid TEXT NOT NULL,
 action TEXT NOT NULL, vote_type TEXT NOT NULL, reason_id TEXT NOT NULL, comment_text TEXT NOT NULL,
 created_at TEXT NOT NULL, previous_id TEXT, base_weight INTEGER NOT NULL, effective_weight INTEGER NOT NULL,
 calculation_version INTEGER NOT NULL, request_hash TEXT NOT NULL, community_before INTEGER NOT NULL, community_after INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS dr_votes_voter_time ON dr_votes(scope_id,voter_uuid,created_at);
CREATE INDEX IF NOT EXISTS dr_votes_pair ON dr_votes(scope_id,voter_uuid,target_uuid,sequence_no);
CREATE INDEX IF NOT EXISTS dr_votes_target ON dr_votes(scope_id,target_uuid,sequence_no);
CREATE TABLE IF NOT EXISTS dr_active_votes (
 scope_id TEXT NOT NULL, voter_uuid TEXT NOT NULL, target_uuid TEXT NOT NULL,
 operation_id TEXT NOT NULL UNIQUE, vote_type TEXT NOT NULL,
 PRIMARY KEY(scope_id,voter_uuid,target_uuid)
);
CREATE INDEX IF NOT EXISTS dr_active_target ON dr_active_votes(scope_id,target_uuid,vote_type);
