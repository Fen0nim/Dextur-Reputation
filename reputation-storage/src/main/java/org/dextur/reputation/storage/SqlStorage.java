package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.application.Settings;
import org.dextur.reputation.application.StorageType;
import org.dextur.reputation.domain.PlayerLanguage;

/** SQLite writes are serialized per owner; MySQL retains Phase 1 pooling/repositories/schema. */
public final class SqlStorage implements StorageBackend {
    private final Database database;
    private final Path file;
    private final int busy;
    private final StorageLease lease;
    private boolean closed;
    private SqlStorage(Database database,Path file,int busy,StorageLease lease) { this.database=database;this.file=file;this.busy=busy;this.lease=lease; }
    public static SqlStorage mysql(Settings.Database settings,Map<String,String> environment,boolean migration) throws Exception {
        Database db=new Database();
        try { db.open(settings,environment);db.claimExclusive();var backend=new SqlStorage(db,null,0,null);backend.initialize(migration);return backend; }
        catch(Exception e) { db.close();throw e; }
    }
    public static SqlStorage sqlite(Path file,int busy,boolean migration) throws Exception {
        AtomicFiles.directories(file.getParent());var lease=new StorageLease(file.resolveSibling(file.getFileName()+".owner.lock"));
        var backend=new SqlStorage(null,file,busy,lease);
        try { backend.initialize(migration);return backend; } catch(Exception e) { backend.close();throw e; }
    }
    private Connection connect() throws SQLException {
        if(closed) throw new SQLException("Storage closed");
        if(database!=null) { database.ensureOwned();return database.dataSource().getConnection(); }
        Properties props=new Properties();props.setProperty("busy_timeout",Integer.toString(busy));props.setProperty("synchronous","FULL");props.setProperty("foreign_keys","true");
        return DriverManager.getConnection("jdbc:sqlite:"+file,props);
    }
    private void initialize(boolean migration) throws Exception {
        try(Connection c=connect();Statement s=c.createStatement()) {
            if(database==null) {
                try(var rows=s.executeQuery("PRAGMA integrity_check")) { if(!rows.next()||!"ok".equals(rows.getString(1))) throw new SQLException("SQLite integrity check failed"); }
                try(var rows=s.executeQuery("PRAGMA user_version")) { if(!rows.next()||rows.getInt(1)>1) throw new SQLException("Unsupported SQLite schema"); }
                try(var rows=s.executeQuery("PRAGMA journal_mode=WAL")) { if(!rows.next()||!"wal".equalsIgnoreCase(rows.getString(1))) throw new SQLException("SQLite WAL unavailable"); }
                c.setAutoCommit(false);
                try {
                    s.execute("CREATE TABLE IF NOT EXISTS dr_profiles(player_uuid TEXT NOT NULL,scope_id TEXT NOT NULL,community_score INTEGER NOT NULL,system_score INTEGER NOT NULL,visible_score INTEGER NOT NULL,tier TEXT NOT NULL,profile_revision INTEGER NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(player_uuid,scope_id))");
                    s.execute("CREATE TABLE IF NOT EXISTS dr_player_preferences(player_uuid TEXT PRIMARY KEY NOT NULL,language TEXT NOT NULL CHECK(language IN ('ru_RU','en_US')),updated_at TEXT NOT NULL)");
                    s.execute("PRAGMA user_version=1"); c.commit();
                } catch(Exception e) { c.rollback();throw e; } finally { c.setAutoCommit(true); }
            }
            s.execute("CREATE TABLE IF NOT EXISTS dr_storage_control(id INTEGER PRIMARY KEY,pending INTEGER NOT NULL,format_version INTEGER NOT NULL)");
            String insert=database==null?"INSERT INTO dr_storage_control VALUES(1,0,1) ON CONFLICT(id) DO NOTHING":"INSERT INTO dr_storage_control VALUES(1,0,1) ON DUPLICATE KEY UPDATE id=VALUES(id)";
            s.executeUpdate(insert);
            try(var row=s.executeQuery("SELECT pending,format_version FROM dr_storage_control WHERE id=1")) {
                if(!row.next()||row.getInt(2)!=1||(!migration&&row.getInt(1)!=0)) throw new SQLException("Unverified migration or unsupported storage metadata blocks startup");
            }
        }
        snapshot(); // Validate complete stored records before the backend can be activated.
    }
    @Override public StorageType type() { return database==null?StorageType.SQLITE:StorageType.MYSQL; }
    @Override public synchronized ReputationSnapshot getOrCreate(UUID id,String scope) throws Exception {
        if(database!=null) { database.ensureOwned();return new JdbcProfiles(database::dataSource).getOrCreate(id,scope); }
        if(!scope.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid scope");
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                String now=StorageSnapshot.now().toString();
                try(var insert=c.prepareStatement("INSERT INTO dr_profiles VALUES(?,?,0,0,0,'NEUTRAL',0,?,?) ON CONFLICT(player_uuid,scope_id) DO NOTHING")) {
                    insert.setString(1,id.toString());insert.setString(2,scope);insert.setString(3,now);insert.setString(4,now);insert.executeUpdate();
                }
                ReputationSnapshot profile;
                try(var select=c.prepareStatement("SELECT * FROM dr_profiles WHERE player_uuid=? AND scope_id=?")) {
                    select.setString(1,id.toString());select.setString(2,scope);
                    try(var rows=select.executeQuery()) { if(!rows.next()) throw new SQLException("Missing profile");profile=profile(rows); }
                }
                c.commit();return profile;
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
    @Override public synchronized PlayerLanguage language(UUID id,PlayerLanguage fallback) throws Exception {
        if(database!=null) { database.ensureOwned();return new JdbcProfiles(database::dataSource).language(id,fallback); }
        try(Connection c=connect();var s=c.prepareStatement("SELECT language FROM dr_player_preferences WHERE player_uuid=?")) {
            s.setString(1,id.toString());try(var rows=s.executeQuery()) { return rows.next()?PlayerLanguage.parse(rows.getString(1)):fallback; }
        }
    }
    @Override public synchronized void setLanguage(UUID id,PlayerLanguage language) throws Exception {
        if(database!=null) { database.ensureOwned();new JdbcProfiles(database::dataSource).setLanguage(id,language);return; }
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement("INSERT INTO dr_player_preferences VALUES(?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET language=excluded.language,updated_at=excluded.updated_at WHERE language<>excluded.language")) {
                s.setString(1,id.toString());s.setString(2,language.name());s.setString(3,StorageSnapshot.now().toString());s.executeUpdate();c.commit();
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
    private ReputationSnapshot profile(ResultSet r) throws SQLException {
        return new ReputationSnapshot(UUID.fromString(r.getString("player_uuid")),r.getString("scope_id"),r.getLong("community_score"),r.getLong("system_score"),r.getLong("visible_score"),r.getString("tier"),r.getLong("profile_revision"),instant(r,"created_at"),instant(r,"updated_at"));
    }
    private Instant instant(ResultSet r,String column) throws SQLException { return database==null?Instant.parse(r.getString(column)):r.getTimestamp(column).toInstant(); }
    @Override public synchronized StorageSnapshot snapshot() throws Exception {
        try(Connection c=connect()) {
            if(database!=null) c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                var profiles=new ArrayList<ReputationSnapshot>();var preferences=new ArrayList<StorageSnapshot.Preference>();
                try(var s=c.createStatement();var rows=s.executeQuery("SELECT * FROM dr_profiles")) { while(rows.next()) profiles.add(profile(rows)); }
                try(var s=c.createStatement();var rows=s.executeQuery("SELECT * FROM dr_player_preferences")) {
                    while(rows.next()) preferences.add(new StorageSnapshot.Preference(UUID.fromString(rows.getString("player_uuid")),PlayerLanguage.parse(rows.getString("language")),instant(rows,"updated_at")));
                }
                var snapshot=new StorageSnapshot(profiles,preferences);c.commit();return snapshot;
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
    @Override public synchronized void prepareMigration() throws SQLException {
        try(Connection c=connect();var s=c.createStatement()) { s.executeUpdate("UPDATE dr_storage_control SET pending=1 WHERE id=1"); }
    }
    @Override public synchronized void importEmpty(StorageSnapshot snapshot) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                try(var s=c.createStatement();var rows=s.executeQuery("SELECT pending FROM dr_storage_control WHERE id=1")) { if(!rows.next()||rows.getInt(1)!=1) throw new SQLException("Target not prepared"); }
                for(String table:List.of("dr_profiles","dr_player_preferences")) {
                    try(var s=c.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM "+table)) { if(!rows.next()||rows.getLong(1)!=0) throw new SQLException("Migration target is not empty"); }
                }
                try(var s=c.prepareStatement("INSERT INTO dr_profiles(player_uuid,scope_id,community_score,system_score,visible_score,tier,profile_revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    for(var p:snapshot.profiles()) {
                        s.setString(1,p.playerId().toString());s.setString(2,p.scopeId());s.setLong(3,p.communityScore());s.setLong(4,p.systemScore());s.setLong(5,p.visibleScore());s.setString(6,p.tier());s.setLong(7,p.revision());timestamp(s,8,p.createdAt());timestamp(s,9,p.updatedAt());s.addBatch();
                    }s.executeBatch();
                }
                try(var s=c.prepareStatement("INSERT INTO dr_player_preferences(player_uuid,language,updated_at) VALUES(?,?,?)")) {
                    for(var p:snapshot.preferences()) { s.setString(1,p.playerId().toString());s.setString(2,p.language().name());timestamp(s,3,p.updatedAt());s.addBatch(); }s.executeBatch();
                }
                c.commit();
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
    private void timestamp(PreparedStatement s,int index,Instant value) throws SQLException {
        if(database==null) s.setString(index,value.toString());else s.setTimestamp(index,Timestamp.from(value));
    }
    @Override public synchronized void finishMigration() throws SQLException {
        try(Connection c=connect();var s=c.createStatement()) { s.executeUpdate("UPDATE dr_storage_control SET pending=0 WHERE id=1"); }
    }
    @Override public synchronized void close() throws Exception {
        if(!closed) { closed=true;if(database!=null) database.close();if(lease!=null) lease.close(); }
    }
}
