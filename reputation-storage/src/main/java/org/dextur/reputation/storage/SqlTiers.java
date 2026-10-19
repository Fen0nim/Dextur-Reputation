package org.dextur.reputation.storage;

import java.sql.*;
import java.util.*;
import org.dextur.reputation.domain.tier.TierState;

/** Tier state participates in the caller's vote/review transaction. */
final class SqlTiers {
    private final SqlStorage owner;
    SqlTiers(SqlStorage owner) {this.owner=owner;}
    static void sqlite(Connection c) throws SQLException {
        try(var s=c.createStatement()) {s.execute("CREATE TABLE IF NOT EXISTS dr_tiers(player_uuid TEXT NOT NULL,scope_id TEXT NOT NULL,revision INTEGER NOT NULL,sync_revision INTEGER NOT NULL,applied_revision INTEGER NOT NULL,payload TEXT NOT NULL,checksum TEXT NOT NULL,PRIMARY KEY(player_uuid,scope_id))");s.execute("CREATE INDEX IF NOT EXISTS dr_tiers_pending ON dr_tiers(scope_id,applied_revision,sync_revision)");s.execute("PRAGMA user_version=4");}
    }
    static String digest(String text) {try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    TierState read(ResultSet row) throws SQLException {
        String payload=row.getString("payload");
        try {
            var state=ProtectionCodec.decode(payload,TierState.class);
            if(!payload.equals(ProtectionCodec.encode(state))||!digest(payload).equals(row.getString("checksum"))||!state.player().toString().equals(row.getString("player_uuid"))||!state.scope().equals(row.getString("scope_id"))||state.revision()!=row.getLong("revision")||state.syncRevision()!=row.getLong("sync_revision")||state.appliedRevision()!=row.getLong("applied_revision")) throw new IllegalArgumentException("Tier integrity mismatch");return state;
        }catch(RuntimeException error){throw new SQLException("Invalid tier state",error);}
    }
    TierState get(Connection c,UUID id,String scope) throws SQLException {
        try(var s=c.prepareStatement("SELECT * FROM dr_tiers WHERE player_uuid=? AND scope_id=?")) {s.setString(1,id.toString());s.setString(2,scope);try(var r=s.executeQuery()){return r.next()?read(r):null;}}
    }
    void put(Connection c,TierState state) throws SQLException {
        String sql=owner.type()==org.dextur.reputation.application.StorageType.MYSQL?
            "INSERT INTO dr_tiers VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),sync_revision=VALUES(sync_revision),applied_revision=VALUES(applied_revision),payload=VALUES(payload),checksum=VALUES(checksum)":
            "INSERT INTO dr_tiers VALUES(?,?,?,?,?,?,?) ON CONFLICT(player_uuid,scope_id) DO UPDATE SET revision=excluded.revision,sync_revision=excluded.sync_revision,applied_revision=excluded.applied_revision,payload=excluded.payload,checksum=excluded.checksum";
        try(var s=c.prepareStatement(sql)) {String payload=ProtectionCodec.encode(state);s.setString(1,state.player().toString());s.setString(2,state.scope());s.setLong(3,state.revision());s.setLong(4,state.syncRevision());s.setLong(5,state.appliedRevision());s.setString(6,payload);s.setString(7,digest(payload));s.executeUpdate();}
    }
    TierSnapshot snapshot(Connection c) throws SQLException {var states=new ArrayList<TierState>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM dr_tiers ORDER BY player_uuid,scope_id")){while(r.next())states.add(read(r));}return new TierSnapshot(states);}
}
