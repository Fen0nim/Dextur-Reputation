CREATE TABLE IF NOT EXISTS dr_profiles (
    player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    community_score BIGINT NOT NULL DEFAULT 0,
    system_score BIGINT NOT NULL DEFAULT 0,
    visible_score BIGINT NOT NULL DEFAULT 0,
    tier VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NEUTRAL',
    profile_revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (player_uuid, scope_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS dr_player_preferences (
    player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    language VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT dr_language_supported CHECK (language IN ('ru_RU', 'en_US'))
) ENGINE=InnoDB;
