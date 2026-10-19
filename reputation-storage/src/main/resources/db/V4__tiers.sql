CREATE TABLE IF NOT EXISTS dr_tiers (
 player_uuid CHAR(36) NOT NULL,
 scope_id VARCHAR(32) NOT NULL,
 revision BIGINT NOT NULL,
 sync_revision BIGINT NOT NULL,
 applied_revision BIGINT NOT NULL,
 payload LONGTEXT NOT NULL,
 checksum CHAR(64) NOT NULL,
 PRIMARY KEY(player_uuid,scope_id),
 INDEX dr_tiers_pending(scope_id,applied_revision,sync_revision)
) ENGINE=InnoDB;
