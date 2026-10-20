CREATE TABLE IF NOT EXISTS dr_administration (
    player_uuid VARCHAR(36) NOT NULL,
    scope_id VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL,
    payload LONGTEXT NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    PRIMARY KEY (player_uuid, scope_id)
) ENGINE=InnoDB;
