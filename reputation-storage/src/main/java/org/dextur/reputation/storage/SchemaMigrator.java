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

/** MySQL DDL auto-commits. Additive versioned migrations retry incomplete DDL and preserve applied checksums. */
public final class SchemaMigrator {
    public void migrate(DataSource source) throws Exception { migrate(source,2); }
    public void protection(DataSource source) throws Exception { migrate(source,3); }
    private void migrate(DataSource source,int target) throws Exception {
        java.util.Map<Integer, byte[]> migrations = new java.util.TreeMap<>();
        java.util.Map<Integer, String> checksums = new java.util.HashMap<>();
        for (var entry : java.util.Map.of(1, "/db/V1__foundation.sql", 2, "/db/V2__voting.sql",3,"/db/V3__protection.sql").entrySet()) {
            try (var in = SchemaMigrator.class.getResourceAsStream(entry.getValue())) {
                if (in == null) throw new IOException("Missing migration " + entry.getKey());
                byte[] sql = in.readAllBytes(); migrations.put(entry.getKey(), sql);
                checksums.put(entry.getKey(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql)));
            }
        }
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
                java.util.Set<Integer> applied = new java.util.HashSet<>();
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS dr_schema_history (version INT PRIMARY KEY, checksum CHAR(64) NOT NULL, applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
                    try (ResultSet history = statement.executeQuery("SELECT version, checksum FROM dr_schema_history")) {
                        while (history.next()) {
                            int version = history.getInt(1);
                            if (!checksums.containsKey(version)) throw new SQLException("Unsupported schema version");
                            if (!checksums.get(version).equals(history.getString(2))) throw new SQLException("Migration checksum mismatch");
                            applied.add(version);
                        }
                    }
                }
                for (var migration : migrations.entrySet()) if (migration.getKey()<=target&&!applied.contains(migration.getKey())) {
                    try (Statement statement = connection.createStatement()) {
                        for (String command : new String(migration.getValue(), StandardCharsets.UTF_8).split(";"))
                            if (!command.isBlank()) executeAdditive(connection,statement,command.strip());
                    }
                    try (var statement = connection.prepareStatement("INSERT INTO dr_schema_history(version, checksum) VALUES(?, ?)")) {
                        statement.setInt(1, migration.getKey()); statement.setString(2, checksums.get(migration.getKey())); statement.executeUpdate();
                    }
                }
            } finally {
                try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    statement.setString(1, lock); statement.execute();
                }
            }
        }
    }
    private static void executeAdditive(Connection c,Statement statement,String command) throws Exception {
        try { statement.execute(command); }
        catch(SQLException error) {
            // MySQL DDL auto-commits: validate an already-created index when retrying incomplete V3.
            if(error.getErrorCode()!=1061||!command.matches("CREATE INDEX dr_votes_protection_(voter|target|scope) ON dr_votes.*")) throw error;
            String name=command.split(" ")[2],field=name.endsWith("voter")?"voter_uuid":"target_uuid";
            var expected=name.endsWith("scope")?java.util.List.of("scope_id","sequence_no"):java.util.List.of("scope_id",field,"created_at","operation_id");
            var columns=new java.util.TreeMap<Integer,String>();
            try(var rows=c.getMetaData().getIndexInfo(c.getCatalog(),null,"dr_votes",false,false)) {
                while(rows.next()) if(name.equals(rows.getString("INDEX_NAME"))) columns.put(rows.getInt("ORDINAL_POSITION"),rows.getString("COLUMN_NAME"));
            }
            if(!java.util.List.copyOf(columns.values()).equals(expected)) throw error;
        }
    }

}
