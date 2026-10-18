package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import javax.sql.DataSource;

/** MySQL DDL auto-commits. V1 is additive/idempotent and safely retries incomplete creation. */
public final class SchemaMigrator {
    public void migrate(DataSource source) throws Exception {
        byte[] sql;
        try (var in = SchemaMigrator.class.getResourceAsStream("/db/V1__foundation.sql")) {
            if (in == null) throw new IOException("Missing schema migration V1");
            sql = in.readAllBytes();
        }
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql));
        try (Connection connection = source.getConnection()) {
            String lock = "dr-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(connection.getCatalog().getBytes(StandardCharsets.UTF_8))).substring(0, 48);
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, 10)")) {
                statement.setString(1, lock);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) throw new SQLException("Schema migration lock unavailable");
                }
            }
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS dr_schema_history (version INT PRIMARY KEY, checksum CHAR(64) NOT NULL, applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
                    try (ResultSet history = statement.executeQuery("SELECT version, checksum FROM dr_schema_history")) {
                        boolean applied = false;
                        while (history.next()) {
                            if (history.getInt(1) != 1) throw new SQLException("Unsupported schema version; use a matching plugin release");
                            if (!checksum.equals(history.getString(2))) throw new SQLException("Migration checksum mismatch; restore the original migration");
                            applied = true;
                        }
                        if (applied) return;
                    }
                    for (String command : new String(sql, StandardCharsets.UTF_8).split(";")) {
                        if (!command.isBlank()) statement.execute(command);
                    }
                }
                try (var statement = connection.prepareStatement("INSERT INTO dr_schema_history(version, checksum) VALUES(1, ?)")) {
                    statement.setString(1, checksum);
                    statement.executeUpdate();
                }
            } finally {
                try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    statement.setString(1, lock); statement.execute();
                }
            }
        }
    }
}
