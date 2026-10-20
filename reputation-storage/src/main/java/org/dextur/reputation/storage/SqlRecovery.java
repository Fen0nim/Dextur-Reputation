package org.dextur.reputation.storage;
import java.sql.*;
import java.util.*;
import org.dextur.reputation.domain.recovery.*;
import org.dextur.reputation.domain.recovery.RecoveryData.*;
final class SqlRecovery {
    private final SqlStorage owner;
    SqlRecovery(SqlStorage owner){this.owner=owner;}
    static void sqlite(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS dr_recovery(player_uuid TEXT NOT NULL,scope_id TEXT NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,checksum TEXT NOT NULL,PRIMARY KEY(player_uuid,scope_id))");s.execute("PRAGMA user_version=5");}}
    State read(ResultSet r)throws SQLException{try{String payload=r.getString("payload");var state=ProtectionCodec.decode(payload,State.class);if(!payload.equals(ProtectionCodec.encode(state))||!SqlTiers.digest(payload).equals(r.getString("checksum"))||!state.player().toString().equals(r.getString("player_uuid"))||!state.scope().equals(r.getString("scope_id"))||state.revision()!=r.getLong("revision"))throw new IllegalArgumentException("Recovery integrity: canonical="+payload.equals(ProtectionCodec.encode(state))+", checksum="+SqlTiers.digest(payload).equals(r.getString("checksum"))+", identity="+(state.player().toString().equals(r.getString("player_uuid"))&&state.scope().equals(r.getString("scope_id")))+", revision="+(state.revision()==r.getLong("revision")));return state;}catch(RuntimeException e){throw new SQLException("Invalid recovery state",e);}}
    State get(Connection c,UUID id,String scope)throws SQLException{try(var s=c.prepareStatement("SELECT * FROM dr_recovery WHERE player_uuid=? AND scope_id=?")){s.setString(1,id.toString());s.setString(2,scope);try(var r=s.executeQuery()){return r.next()?read(r):null;}}}
    void put(Connection c,State state)throws SQLException{String sql=owner.type()==org.dextur.reputation.application.StorageType.MYSQL?"INSERT INTO dr_recovery VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),payload=VALUES(payload),checksum=VALUES(checksum)":"INSERT INTO dr_recovery VALUES(?,?,?,?,?) ON CONFLICT(player_uuid,scope_id) DO UPDATE SET revision=excluded.revision,payload=excluded.payload,checksum=excluded.checksum";try(var s=c.prepareStatement(sql)){String payload=ProtectionCodec.encode(state);s.setString(1,state.player().toString());s.setString(2,state.scope());s.setLong(3,state.revision());s.setString(4,payload);s.setString(5,SqlTiers.digest(payload));s.executeUpdate();}}
    RecoverySnapshotData snapshot(Connection c)throws SQLException{var rows=new ArrayList<State>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM dr_recovery ORDER BY player_uuid,scope_id")){while(r.next())rows.add(read(r));}return new RecoverySnapshotData(rows);}
}
