package org.dextur.reputation.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.domain.PlayerLanguage;
import org.dextur.reputation.domain.ProfileRepository;

public final class JdbcProfiles implements ProfileRepository {
    private final Supplier<? extends DataSource> source;
    public JdbcProfiles(Supplier<? extends DataSource> source) { this.source = source; }

    @Override public ReputationSnapshot getOrCreate(UUID playerId, String scope) throws SQLException {
        if (!scope.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid scope");
        try (Connection connection = source.get().getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                // A no-op duplicate update preserves scores, revision and timestamps; no INSERT IGNORE error masking.
                try (var insert = connection.prepareStatement("INSERT INTO dr_profiles(player_uuid, scope_id) VALUES(?, ?) ON DUPLICATE KEY UPDATE player_uuid=VALUES(player_uuid)")) {
                    insert.setString(1, playerId.toString()); insert.setString(2, scope); insert.executeUpdate();
                }
                ReputationSnapshot profile;
                try (var select = connection.prepareStatement("SELECT * FROM dr_profiles WHERE player_uuid=? AND scope_id=?")) {
                    select.setString(1, playerId.toString()); select.setString(2, scope);
                    try (ResultSet row = select.executeQuery()) {
                        if (!row.next()) throw new SQLException("Profile missing after creation");
                        profile = new ReputationSnapshot(playerId, scope, row.getLong("community_score"),
                                row.getLong("system_score"), row.getLong("visible_score"), row.getString("tier"),
                                row.getLong("profile_revision"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
                    }
                }
                connection.commit();
                return profile;
            } catch (SQLException | RuntimeException e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }

    @Override public PlayerLanguage language(UUID playerId, PlayerLanguage fallback) throws SQLException {
        try (Connection connection = source.get().getConnection();
                var select = connection.prepareStatement("SELECT language FROM dr_player_preferences WHERE player_uuid=?")) {
            select.setString(1, playerId.toString());
            try (ResultSet row = select.executeQuery()) { return row.next() ? PlayerLanguage.parse(row.getString(1)) : fallback; }
        }
    }

    @Override public void setLanguage(UUID playerId, PlayerLanguage language) throws SQLException {
        try (Connection connection = source.get().getConnection();
                var update = connection.prepareStatement("INSERT INTO dr_player_preferences(player_uuid, language) VALUES(?, ?) ON DUPLICATE KEY UPDATE updated_at=IF(language=VALUES(language), updated_at, CURRENT_TIMESTAMP(6)), language=VALUES(language)")) {
            update.setString(1, playerId.toString()); update.setString(2, language.name()); update.executeUpdate();
        }
    }
}
