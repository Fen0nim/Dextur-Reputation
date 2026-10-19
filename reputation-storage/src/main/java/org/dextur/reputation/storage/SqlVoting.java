package org.dextur.reputation.storage;

import java.sql.*;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.*;
import org.dextur.reputation.application.StorageType;

/** Indexed, bounded reads and transactional writes. Caller holds its backend owner monitor. */
final class SqlVoting {
    private final SqlStorage owner;
    private final boolean mysql;
    SqlVoting(SqlStorage owner) { this.owner = owner; this.mysql = owner.type() == StorageType.MYSQL; }
    static void initializeSQLite(Connection connection) throws Exception {
        try (var in = SqlVoting.class.getResourceAsStream("/db/V2__voting_sqlite.sql"); var statement = connection.createStatement()) {
            if (in == null) throw new java.io.IOException("Missing SQLite V2");
            for (String command : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split(";"))
                if (!command.isBlank()) statement.execute(command);
        }
    }
    void time(PreparedStatement s, int index, Instant value) throws SQLException {
        if (mysql) s.setTimestamp(index, Timestamp.from(value));
        else s.setString(index, new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value));
    }
    Instant time(ResultSet r, String column) throws SQLException { return mysql ? r.getTimestamp(column).toInstant() : Instant.parse(r.getString(column)); }
    VoteEntry read(ResultSet r) throws SQLException {
        String previous = r.getString("previous_id");
        return new VoteEntry(UUID.fromString(r.getString("operation_id")), r.getLong("sequence_no"), r.getString("scope_id"),
                UUID.fromString(r.getString("voter_uuid")), UUID.fromString(r.getString("target_uuid")), VoteEntry.Action.valueOf(r.getString("action")),
                VoteType.valueOf(r.getString("vote_type")), r.getString("reason_id"), r.getString("comment_text"), time(r,"created_at"),
                previous == null ? null : UUID.fromString(previous), r.getLong("base_weight"), r.getLong("effective_weight"),
                r.getInt("calculation_version"), r.getString("request_hash"), r.getLong("community_before"), r.getLong("community_after"));
    }
    VoteEntry operation(UUID id) throws Exception {
        try (var c = owner.connect()) { return operation(c, id); }
    }
    VoteEntry operation(Connection c, UUID id) throws SQLException {
        try (var s = c.prepareStatement("SELECT * FROM dr_votes WHERE operation_id=?")) {
            s.setString(1,id.toString()); try (var r=s.executeQuery()) { return r.next()?read(r):null; }
        }
    }
    ReputationSnapshot ensure(Connection c, UUID id, String scope, Instant now) throws SQLException {
        String sql = mysql ? "INSERT INTO dr_profiles(player_uuid,scope_id) VALUES(?,?) ON DUPLICATE KEY UPDATE player_uuid=VALUES(player_uuid)"
                : "INSERT INTO dr_profiles VALUES(?,?,0,0,0,'NEUTRAL',0,?,?) ON CONFLICT(player_uuid,scope_id) DO NOTHING";
        try (var s=c.prepareStatement(sql)) {
            s.setString(1,id.toString());s.setString(2,scope);
            if (!mysql) { time(s,3,now);time(s,4,now); } s.executeUpdate();
        }
        try (var s=c.prepareStatement("SELECT * FROM dr_profiles WHERE player_uuid=? AND scope_id=?"+(mysql?" FOR UPDATE":""))) {
            s.setString(1,id.toString());s.setString(2,scope);
            try (var r=s.executeQuery()) { if(!r.next()) throw new SQLException("Missing profile");return owner.profile(r); }
        }
    }
    ReputationView view(Connection c, UUID id, String scope, Instant now) throws SQLException {
        var profile=ensure(c,id,scope,now);
        long positive,negative,unique,total;
        try(var s=c.prepareStatement("SELECT SUM(CASE WHEN vote_type='POSITIVE' THEN 1 ELSE 0 END),SUM(CASE WHEN vote_type='NEGATIVE' THEN 1 ELSE 0 END) FROM dr_active_votes WHERE scope_id=? AND target_uuid=?")) {
            s.setString(1,scope);s.setString(2,id.toString());try(var r=s.executeQuery()) { r.next();positive=r.getLong(1);negative=r.getLong(2); }
        }
        try(var s=c.prepareStatement("SELECT COUNT(DISTINCT voter_uuid),COUNT(*) FROM dr_votes WHERE scope_id=? AND target_uuid=?")) {
            s.setString(1,scope);s.setString(2,id.toString());try(var r=s.executeQuery()) { r.next();unique=r.getLong(1);total=r.getLong(2); }
        }
        return new ReputationView(profile,new VoteStatistics(positive,negative,unique,total));
    }
    ReputationView view(UUID id,String scope) throws Exception {
        try(var c=owner.connect()) {
            c.setAutoCommit(false);
            try { var result=view(c,id,scope,StorageSnapshot.now());c.commit();return result; }
            catch(Exception e) { c.rollback();throw e; }
        }
    }
    VoteReceipt apply(String scope,VoteRequest request,VoterFacts facts,VotingRules rules,Instant now) throws Exception {
        try(var c=owner.connect()) {
            if(mysql) c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            try {
                VoteEntry existing=operation(c,request.operationId());
                if(existing!=null) {
                    VotingEngine.checkDuplicate(scope,request,existing);
                    var receipt=new VoteReceipt(existing,true,view(c,request.targetId(),scope,now));c.commit();return receipt;
                }
                var context=context(c,scope,request,rules,now);
                var mutation=VotingEngine.evaluate(scope,request,facts,rules,now,context);
                insert(c,mutation.entry());active(c,mutation.entry());
                var profile=mutation.profile();
                try(var s=c.prepareStatement("UPDATE dr_profiles SET community_score=?,visible_score=?,profile_revision=?,updated_at=? WHERE player_uuid=? AND scope_id=?")) {
                    s.setLong(1,profile.communityScore());s.setLong(2,profile.visibleScore());s.setLong(3,profile.revision());time(s,4,profile.updatedAt());
                    s.setString(5,request.targetId().toString());s.setString(6,scope);if(s.executeUpdate()!=1) throw new SQLException("Profile update failed");
                }
                var result=new VoteReceipt(mutation.entry(),false,view(c,request.targetId(),scope,now));c.commit();return result;
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
    VotingEngine.Context context(Connection c,String scope,VoteRequest request,VotingRules rules,Instant now) throws Exception {
                // Fixed row order also protects future adapters which permit more than one transaction at a time.
                UUID first=request.voterId().compareTo(request.targetId())<=0?request.voterId():request.targetId();
                UUID second=first.equals(request.voterId())?request.targetId():request.voterId();
                var a=ensure(c,first,scope,now);var b=ensure(c,second,scope,now);
                var voter=first.equals(request.voterId())?a:b;
                var target=view(c,request.targetId(),scope,now);
                VoteEntry pair=null;Instant last=null;int used;
                try(var s=c.prepareStatement("SELECT * FROM dr_votes WHERE scope_id=? AND voter_uuid=? AND target_uuid=? ORDER BY sequence_no DESC LIMIT 1")) {
                    s.setString(1,scope);s.setString(2,request.voterId().toString());s.setString(3,request.targetId().toString());try(var r=s.executeQuery()) { if(r.next()) pair=read(r); }
                }
                try(var s=c.prepareStatement("SELECT MAX(created_at) AS latest,SUM(CASE WHEN created_at>=? AND created_at<? THEN 1 ELSE 0 END) FROM dr_votes WHERE scope_id=? AND voter_uuid=?")) {
                    time(s,1,rules.dayStart(now));time(s,2,rules.dayEnd(now));s.setString(3,scope);s.setString(4,request.voterId().toString());
                    try(var r=s.executeQuery()) { r.next();if(r.getString(1)!=null) last=time(r,"latest");used=r.getInt(2); }
                }
                long sequence;
                try(var s=c.createStatement();var r=s.executeQuery("SELECT COALESCE(MAX(sequence_no),0) FROM dr_votes")) { r.next();sequence=Math.incrementExact(r.getLong(1)); }
        return new VotingEngine.Context(voter,target,pair,last,used,sequence);
    }
    void insert(Connection c,VoteEntry entry) throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO dr_votes(operation_id,sequence_no,scope_id,voter_uuid,target_uuid,action,vote_type,reason_id,comment_text,created_at,previous_id,base_weight,effective_weight,calculation_version,request_hash,community_before,community_after) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,entry.operationId().toString());s.setLong(2,entry.sequence());s.setString(3,entry.scope());s.setString(4,entry.voterId().toString());s.setString(5,entry.targetId().toString());
            s.setString(6,entry.action().name());s.setString(7,entry.type().name());s.setString(8,entry.reason());s.setString(9,entry.comment());time(s,10,entry.createdAt());
            s.setString(11,entry.previousOperationId()==null?null:entry.previousOperationId().toString());s.setLong(12,entry.baseWeight());s.setLong(13,entry.effectiveWeight());
            s.setInt(14,entry.calculationVersion());s.setString(15,entry.requestHash());s.setLong(16,entry.communityBefore());s.setLong(17,entry.communityAfter());s.executeUpdate();
        }
    }
    void active(Connection c,VoteEntry entry) throws SQLException {
        try(var s=c.prepareStatement("DELETE FROM dr_active_votes WHERE scope_id=? AND voter_uuid=? AND target_uuid=?")) {
            s.setString(1,entry.scope());s.setString(2,entry.voterId().toString());s.setString(3,entry.targetId().toString());s.executeUpdate();
        }
        if(entry.action()!=VoteEntry.Action.REVOKE) try(var s=c.prepareStatement("INSERT INTO dr_active_votes(scope_id,voter_uuid,target_uuid,operation_id,vote_type) VALUES(?,?,?,?,?)")) {
            s.setString(1,entry.scope());s.setString(2,entry.voterId().toString());s.setString(3,entry.targetId().toString());s.setString(4,entry.operationId().toString());s.setString(5,entry.type().name());s.executeUpdate();
        }
    }
    List<VoteEntry> all(Connection c) throws SQLException {
        var entries=new ArrayList<VoteEntry>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM dr_votes ORDER BY sequence_no")) { while(r.next()) entries.add(read(r)); }
        return entries;
    }
    void validateActive(Connection c,List<VoteEntry> entries,ProtectionSnapshot protection) throws SQLException {
        Map<String,VoteEntry> latest=ProtectionValidation.active(entries,protection);
        Set<String> expected=new HashSet<>();for(var v:latest.values()) if(v.action()!=VoteEntry.Action.REVOKE) expected.add(v.pairKey()+"/"+v.operationId()+"/"+v.type());
        Set<String> actual=new HashSet<>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM dr_active_votes")) {
            while(r.next()) actual.add(r.getString("scope_id")+"/"+r.getString("voter_uuid")+"/"+r.getString("target_uuid")+"/"+r.getString("operation_id")+"/"+r.getString("vote_type"));
        }
        if(!expected.equals(actual)) throw new SQLException("Vote active index differs from journal");
    }
    VotePage history(UUID id,String scope,VotePage.Direction direction,int page,int size) throws Exception {
        if(page<1||size<1||size>50) throw new VoteRejectedException(VoteRejectedException.Code.INVALID_PAGE);
        String column=direction==VotePage.Direction.RECEIVED?"target_uuid":"voter_uuid";
        try(var c=owner.connect()) {
            c.setAutoCommit(false);
            try {
                long total;
                try(var s=c.prepareStatement("SELECT COUNT(*) FROM dr_votes WHERE scope_id=? AND "+column+"=?")) {
                    s.setString(1,scope);s.setString(2,id.toString());try(var r=s.executeQuery()) { r.next();total=r.getLong(1); }
                }
                List<VotePage.Item> items=new ArrayList<>();
                try(var s=c.prepareStatement("SELECT v.*,a.operation_id AS active_id FROM dr_votes v LEFT JOIN dr_active_votes a ON a.operation_id=v.operation_id WHERE v.scope_id=? AND v."+column+"=? ORDER BY v.sequence_no DESC LIMIT ? OFFSET ?")) {
                    s.setString(1,scope);s.setString(2,id.toString());s.setInt(3,size);s.setLong(4,Math.multiplyExact((long)page-1,size));
                    try(var r=s.executeQuery()) { while(r.next()) items.add(new VotePage.Item(read(r),r.getString("active_id")!=null)); }
                }
                c.commit();return new VotePage(items,page,size,total);
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
}
