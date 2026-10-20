package org.dextur.reputation.storage;

import java.sql.*;
import java.util.*;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;

final class SqlInvestigation {
    final SqlStorage owner;final SqlVoting voting;
    SqlInvestigation(SqlStorage owner){this.owner=owner;voting=new SqlVoting(owner);}
    static void sqlite(Connection c)throws Exception {
        try(var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS dr_investigation(kind TEXT NOT NULL,record_id TEXT NOT NULL,scope_id TEXT NOT NULL,actor_uuid TEXT NOT NULL,target_uuid TEXT NOT NULL,vote_id TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,created_at TEXT NOT NULL,payload TEXT NOT NULL,checksum TEXT NOT NULL,PRIMARY KEY(kind,record_id))");
            for(var index:Map.of("player","target_uuid","vote","vote_id","status","status","actor","actor_uuid").entrySet())s.execute("CREATE INDEX IF NOT EXISTS dr_investigation_"+index.getKey()+" ON dr_investigation(scope_id,kind,"+index.getValue()+",created_at,record_id)");
            s.execute("CREATE INDEX IF NOT EXISTS dr_investigation_retention ON dr_investigation(kind,created_at)");
            s.execute("PRAGMA user_version=7");
        }
    }
    Record read(ResultSet row)throws Exception {
        String payload=row.getString("payload");if(payload.length()>1000000)throw new SQLException("Oversized investigation record");
        var r=ProtectionCodec.decode(payload,Record.class);
        if(!payload.equals(ProtectionCodec.encode(r))||!SqlTiers.digest(payload).equals(row.getString("checksum"))||!r.id().toString().equals(row.getString("record_id"))||!r.kind().name().equals(row.getString("kind"))||!r.scope().equals(row.getString("scope_id"))||!r.actor().equals(row.getString("actor_uuid"))||!r.target().toString().equals(row.getString("target_uuid"))||!r.vote().toString().equals(row.getString("vote_id"))||!r.status().equals(row.getString("status"))||r.revision()!=row.getLong("revision")||!r.at().equals(voting.time(row,"created_at")))throw new SQLException("Investigation integrity mismatch");
        return r;
    }
    Record get(Connection c,Kind kind,UUID id)throws Exception{try(var q=c.prepareStatement("SELECT * FROM dr_investigation WHERE kind=? AND record_id=?")){q.setString(1,kind.name());q.setString(2,id.toString());try(var rows=q.executeQuery()){return rows.next()?read(rows):null;}}}
    void put(Connection c,Record r)throws Exception {
        if(r.kind()==Kind.AUDIT&&get(c,r.kind(),r.id())!=null)throw new SQLException("Append-only audit identity conflict");
        String suffix=owner.type()==org.dextur.reputation.application.StorageType.MYSQL?" ON DUPLICATE KEY UPDATE status=VALUES(status),revision=VALUES(revision),actor_uuid=VALUES(actor_uuid),created_at=VALUES(created_at),payload=VALUES(payload),checksum=VALUES(checksum)":" ON CONFLICT(kind,record_id) DO UPDATE SET status=excluded.status,revision=excluded.revision,actor_uuid=excluded.actor_uuid,created_at=excluded.created_at,payload=excluded.payload,checksum=excluded.checksum";
        try(var q=c.prepareStatement("INSERT INTO dr_investigation(kind,record_id,scope_id,actor_uuid,target_uuid,vote_id,status,revision,created_at,payload,checksum) VALUES(?,?,?,?,?,?,?,?,?,?,?)"+suffix)){
            String payload=ProtectionCodec.encode(r);q.setString(1,r.kind().name());q.setString(2,r.id().toString());q.setString(3,r.scope());q.setString(4,r.actor());q.setString(5,r.target().toString());q.setString(6,r.vote().toString());q.setString(7,r.status());q.setLong(8,r.revision());voting.time(q,9,r.at());q.setString(10,payload);q.setString(11,SqlTiers.digest(payload));q.executeUpdate();
        }
    }
    List<Record> list(Connection c,Kind kind,String scope,UUID target,UUID vote,int limit,long offset)throws Exception{
        var result=new ArrayList<Record>();String sql="SELECT * FROM dr_investigation WHERE kind=? AND scope_id=?"+(target==null?"":" AND target_uuid=?")+(vote==null?"":" AND vote_id=?")+" ORDER BY created_at DESC,record_id LIMIT ? OFFSET ?";
        try(var q=c.prepareStatement(sql)){int i=1;q.setString(i++,kind.name());q.setString(i++,scope);if(target!=null)q.setString(i++,target.toString());if(vote!=null)q.setString(i++,vote.toString());q.setInt(i++,limit);q.setLong(i,offset);try(var rows=q.executeQuery()){while(rows.next())result.add(read(rows));}}return result;
    }
    InvestigationSnapshotData snapshot(Connection c)throws Exception{var result=new ArrayList<Record>();try(var q=c.createStatement();var rows=q.executeQuery("SELECT * FROM dr_investigation ORDER BY kind,record_id")){while(rows.next())result.add(read(rows));}return new InvestigationSnapshotData(result);}
}
