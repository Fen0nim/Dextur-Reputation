package org.dextur.reputation.storage;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.*;
import org.dextur.reputation.domain.protection.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;

/** Bounded indexed context reads, one connection/transaction for every protected change. */
final class SqlProtection {
    static final List<String> TABLES=List.of("dr_trust","dr_vote_pairs","dr_precision","dr_vote_assessments","dr_protection_audit","dr_protection_scope");
    private final SqlStorage owner;private final SqlVoting voting;
    SqlProtection(SqlStorage owner) { this.owner=owner;voting=new SqlVoting(owner); }
    static void sqlite(Connection c) throws Exception {
        try(var in=SqlProtection.class.getResourceAsStream("/db/V3__protection_sqlite.sql");var s=c.createStatement()) {
            if(in==null) throw new java.io.IOException("Missing SQLite V3");
            for(String command:new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split(";")) if(!command.isBlank()) s.execute(command);
            s.execute("PRAGMA user_version=3");
        }
    }
    record Row(String key,String scope,String actor,String target,long revision,long input,long sequence,String status,Instant updated,String payload) { }
    private static Row row(Object value) {
        return switch(value) {
            case Trust t -> new Row(t.key(),t.scope(),t.player().toString(),"",t.revision(),t.inputRevision(),0,"",t.calculatedAt(),ProtectionCodec.encode(t));
            case Pair p -> new Row(p.key(),p.scope(),p.voter().toString(),p.target().toString(),p.revision(),0,0,"",Instant.parse("2000-01-01T00:00:00Z"),ProtectionCodec.encode(p));
            case Score s -> new Row(s.key(),s.scope(),s.player().toString(),s.player().toString(),s.revision(),0,0,"",Instant.parse("2000-01-01T00:00:00Z"),ProtectionCodec.encode(s));
            case Assessment a -> new Row(a.voteId().toString(),a.scope(),a.voter().toString(),a.target().toString(),a.revision(),a.inputRevision(),a.voteSequence(),a.status().name(),a.evaluatedAt(),ProtectionCodec.encode(a));
            case Audit a -> new Row(a.id().toString(),a.scope(),a.voter().toString(),a.target().toString(),1,a.inputRevision(),a.sequence(),a.status().name(),a.createdAt(),ProtectionCodec.encode(a));
            case ScopeState s -> new Row(s.scope(),s.scope(),"","",s.revision(),s.graphRevision(),s.nextAudit(),"",s.graphAt(),ProtectionCodec.encode(s));
            default -> throw new IllegalArgumentException("Unknown private state");
        };
    }
    private <T> T read(ResultSet result,Class<T> type) throws Exception {
        String payload=result.getString("payload");if(payload.length()>1000000) throw new SQLException("Oversized private record");
        var value=ProtectionCodec.decode(payload,type);var r=row(value);
        if(!r.key().equals(result.getString("record_key"))||!r.scope().equals(result.getString("scope_id"))||!r.actor().equals(result.getString("actor_uuid"))
                ||!r.target().equals(result.getString("target_uuid"))||r.revision()!=result.getLong("revision")||r.input()!=result.getLong("input_revision")
                ||r.sequence()!=result.getLong("sequence_no")||!r.status().equals(result.getString("status"))||!r.updated().equals(voting.time(result,"updated_at"))) throw new SQLException("Private index/payload mismatch");
        return value;
    }
    <T> T get(Connection c,String table,String key,Class<T> type) throws Exception {
        if(!TABLES.contains(table)) throw new IllegalArgumentException("Unknown private table");
        try(var s=c.prepareStatement("SELECT * FROM "+table+" WHERE record_key=?")) { s.setString(1,key);try(var r=s.executeQuery()) { return r.next()?read(r,type):null; } }
    }
    private <T> List<T> all(Connection c,String table,Class<T> type) throws Exception {
        var values=new ArrayList<T>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table)) { while(r.next()) values.add(read(r,type)); }return values;
    }
    ProtectionSnapshot snapshot(Connection c) throws Exception {
        return new ProtectionSnapshot(all(c,TABLES.get(0),Trust.class),all(c,TABLES.get(1),Pair.class),all(c,TABLES.get(2),Score.class),all(c,TABLES.get(3),Assessment.class),all(c,TABLES.get(4),Audit.class),all(c,TABLES.get(5),ScopeState.class));
    }
    void put(Connection c,String table,Object value,boolean insertOnly) throws Exception {
        if(value==null) return;var r=row(value);
        String sql="INSERT INTO "+table+"(record_key,scope_id,actor_uuid,target_uuid,revision,input_revision,sequence_no,status,updated_at,payload) VALUES(?,?,?,?,?,?,?,?,?,?)";
        if(!insertOnly) sql+=owner.type()==org.dextur.reputation.application.StorageType.MYSQL?
                " ON DUPLICATE KEY UPDATE scope_id=VALUES(scope_id),actor_uuid=VALUES(actor_uuid),target_uuid=VALUES(target_uuid),revision=VALUES(revision),input_revision=VALUES(input_revision),sequence_no=VALUES(sequence_no),status=VALUES(status),updated_at=VALUES(updated_at),payload=VALUES(payload)":
                " ON CONFLICT(record_key) DO UPDATE SET scope_id=excluded.scope_id,actor_uuid=excluded.actor_uuid,target_uuid=excluded.target_uuid,revision=excluded.revision,input_revision=excluded.input_revision,sequence_no=excluded.sequence_no,status=excluded.status,updated_at=excluded.updated_at,payload=excluded.payload";
        try(var s=c.prepareStatement(sql)) {
            s.setString(1,r.key());s.setString(2,r.scope());s.setString(3,r.actor());s.setString(4,r.target());s.setLong(5,r.revision());s.setLong(6,r.input());s.setLong(7,r.sequence());s.setString(8,r.status());voting.time(s,9,r.updated());s.setString(10,r.payload());s.executeUpdate();
        }
    }
    View context(Connection c,UUID voter,UUID target,String scope,ProtectionRules rules,Instant now) throws Exception {
        var pair=get(c,TABLES.get(1),scope+"/"+voter+"/"+target,Pair.class);
        var selected=new HashMap<UUID,Sample>();
        // Four bounded index ranges, rather than an OR over an entire scope or full-graph scan.
        for(String column:List.of("voter_uuid","target_uuid")) for(UUID actor:List.of(voter,target).stream().distinct().toList()) {
            String sql="SELECT v.*,a.payload AS evaluation FROM dr_votes v LEFT JOIN dr_vote_assessments a ON a.record_key=v.operation_id WHERE v.scope_id=? AND v."+column+"=? AND v.created_at>=? ORDER BY v.created_at DESC,v.operation_id DESC LIMIT ?";
            try(var s=c.prepareStatement(sql)) {
                s.setString(1,scope);s.setString(2,actor.toString());voting.time(s,3,now.minusSeconds(rules.lookbackDays()*86400L));s.setInt(4,rules.analysis().maxEvents()+1);
                try(var r=s.executeQuery()) { while(r.next()) {
                    String payload=r.getString("evaluation");var a=payload==null?null:ProtectionCodec.decode(payload,Assessment.class);var vote=voting.read(r);
                    selected.put(vote.operationId(),new Sample(vote,a!=null,a==null?0:a.factors().getOrDefault("trust_score",0L),a==null?0:a.factors().getOrDefault("maturity",0L)));
                } }
            }
        }
        var sample=selected.values().stream().sorted(Comparator.comparing((Sample v)->v.vote().createdAt()).thenComparing(v->v.vote().operationId().toString()).reversed()).limit(rules.analysis().maxEvents()+1L).toList();
        var meta=get(c,TABLES.get(5),scope,ScopeState.class);
        return new View(get(c,TABLES.get(0),voter+"/"+scope,Trust.class),pair,get(c,TABLES.get(2),target+"/"+scope,Score.class),pair==null?null:get(c,TABLES.get(3),pair.latestVote().toString(),Assessment.class),
                pair==null||pair.activeVote().isEmpty()?null:get(c,TABLES.get(3),pair.activeVote(),Assessment.class),meta==null?ScopeState.empty(scope):meta,
                sample.stream().limit(rules.analysis().maxEvents()).toList(),sample.size()<=rules.analysis().maxEvents());
    }
    void persist(Connection c,Change change) throws Exception {
        if(change.vote()!=null) voting.insert(c,change.vote());
        put(c,TABLES.get(0),change.trust(),false);put(c,TABLES.get(1),change.pair(),false);put(c,TABLES.get(2),change.score(),false);
        for(var a:change.assessments()) put(c,TABLES.get(3),a,false);put(c,TABLES.get(4),change.audit(),true);put(c,TABLES.get(5),change.scope(),false);
        try(var s=c.prepareStatement("DELETE FROM dr_active_votes WHERE scope_id=? AND voter_uuid=? AND target_uuid=?")) { s.setString(1,change.pair().scope());s.setString(2,change.pair().voter().toString());s.setString(3,change.pair().target().toString());s.executeUpdate(); }
        if(!change.pair().activeVote().isEmpty()) voting.active(c,voting.operation(c,UUID.fromString(change.pair().activeVote())));
        var p=change.profile();
        try(var s=c.prepareStatement("UPDATE dr_profiles SET community_score=?,visible_score=?,profile_revision=?,updated_at=? WHERE player_uuid=? AND scope_id=?")) {
            s.setLong(1,p.communityScore());s.setLong(2,p.visibleScore());s.setLong(3,p.revision());voting.time(s,4,p.updatedAt());s.setString(5,p.playerId().toString());s.setString(6,p.scopeId());if(s.executeUpdate()!=1) throw new SQLException("Profile update failed");
        }
    }
    VoteReceipt apply(String scope,VoteRequest request,VoterFacts facts,VotingRules votingRules,ProtectionRules rules,Instant now) throws Exception {
        try(var c=owner.connect()) {
            if(owner.type()==org.dextur.reputation.application.StorageType.MYSQL) c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            try {
                var duplicate=voting.operation(c,request.operationId());
                if(duplicate!=null) { VotingEngine.checkDuplicate(scope,request,duplicate);var result=new VoteReceipt(duplicate,true,voting.view(c,request.targetId(),scope,now));c.commit();return result; }
                if(get(c,TABLES.get(4),request.operationId().toString(),Audit.class)!=null) throw new VoteRejectedException(VoteRejectedException.Code.ID_CONFLICT);
                var eligibility=voting.context(c,scope,request,votingRules,now);var privateContext=context(c,request.voterId(),request.targetId(),scope,rules,now);
                var change=ProtectionEngine.vote(scope,request,facts,votingRules,rules,now,eligibility,privateContext);persist(c,change);
                var result=new VoteReceipt(change.vote(),false,voting.view(c,request.targetId(),scope,now));c.commit();return result;
            } catch(Exception error) { c.rollback();throw error; }
        }
    }
    ReputationView review(UUID transition,UUID id,UUID operator,String reason,Review action,long revision,ProtectionRules rules,Instant now) throws Exception {
        try(var c=owner.connect()) {
            if(owner.type()==org.dextur.reputation.application.StorageType.MYSQL) c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            try {
                var vote=voting.operation(c,id);if(vote==null) throw new IllegalArgumentException("Unknown vote");
                var duplicate=get(c,TABLES.get(4),transition.toString(),Audit.class);
                if(duplicate!=null) { JournalProtection.duplicateReview(duplicate,id,operator,reason,action,revision);var view=voting.view(c,vote.targetId(),vote.scope(),now);c.commit();return view; }
                if(voting.operation(c,transition)!=null) throw new VoteRejectedException(VoteRejectedException.Code.ID_CONFLICT);
                var state=context(c,vote.voterId(),vote.targetId(),vote.scope(),rules,now);var profile=voting.ensure(c,vote.targetId(),vote.scope(),now);
                var change=ProtectionEngine.review(transition,operator,reason,action,revision,vote,profile,rules,state,now);persist(c,change);
                var result=voting.view(c,vote.targetId(),vote.scope(),now);c.commit();return result;
            } catch(Exception error) { c.rollback();throw error; }
        }
    }
    boolean refresh(UUID player,String scope,long expected,VoterFacts facts,ProtectionRules rules,Instant now) throws Exception {
        try(var c=owner.connect()) {
            if(owner.type()==org.dextur.reputation.application.StorageType.MYSQL) c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            try {
                var prior=get(c,TABLES.get(0),player+"/"+scope,Trust.class);
                if(prior==null&&expected==-1) {
                    voting.ensure(c,player,scope,now);var meta=get(c,TABLES.get(5),scope,ScopeState.class);
                    put(c,TABLES.get(0),TrustModel.initial(player,scope,facts,rules,now,meta==null?0:meta.revision()),true);c.commit();return true;
                }
                if(prior==null||prior.revision()!=expected||now.isBefore(prior.calculatedAt())) { c.rollback();return false; }
                var state=context(c,player,player,scope,rules,now);var next=TrustModel.calculate(player,scope,facts,rules,now,prior,state.scope().revision(),state.recent(),false);
                put(c,TABLES.get(0),next,false);c.commit();return true;
            } catch(Exception error) { c.rollback();throw error; }
        }
    }
    List<Trust> due(String scope,Instant before,int limit) throws Exception {
        if(limit<1||limit>100) throw new IllegalArgumentException("Trust batch bounds");var result=new ArrayList<Trust>();
        try(var c=owner.connect();var s=c.prepareStatement("SELECT * FROM dr_trust WHERE scope_id=? AND updated_at<=? ORDER BY updated_at,record_key LIMIT ?")) {
            s.setString(1,scope);voting.time(s,2,before);s.setInt(3,limit);try(var r=s.executeQuery()) { while(r.next()) result.add(read(r,Trust.class)); }
        }return result;
    }
    List<Audit> audit(String scope,long after,int limit) throws Exception {
        if(after<0||limit<1||limit>100) throw new IllegalArgumentException("Audit page bounds");var result=new ArrayList<Audit>();
        try(var c=owner.connect();var s=c.prepareStatement("SELECT * FROM dr_protection_audit WHERE scope_id=? AND sequence_no>? ORDER BY sequence_no LIMIT ?")) {
            s.setString(1,scope);s.setLong(2,after);s.setInt(3,limit);try(var r=s.executeQuery()) { while(r.next()) result.add(read(r,Audit.class)); }
        }return result;
    }
    boolean analyze(String scope,ProtectionRules rules,Instant now) throws Exception {
        try(var c=owner.connect()) {
            c.setAutoCommit(false);
            try {
                VoteEntry latest;
                try(var s=c.prepareStatement("SELECT * FROM dr_votes WHERE scope_id=? ORDER BY sequence_no DESC LIMIT 1")) {
                    s.setString(1,scope);try(var r=s.executeQuery()) { latest=r.next()?voting.read(r):null; }
                }
                if(latest==null||latest.createdAt().isBefore(now.minusSeconds(rules.lookbackDays()*86400L))) { c.rollback();return false; }
                var view=context(c,latest.voterId(),latest.targetId(),scope,rules,now);
                long before=view.score()==null?org.dextur.reputation.domain.protection.Fixed.units(voting.ensure(c,latest.targetId(),scope,now).communityScore()):view.score().currentUnits();
                var change=JournalProtection.analysis(view,latest,before,rules,now);if(change==null) { c.rollback();return false; }
                put(c,TABLES.get(4),change.event(),true);put(c,TABLES.get(5),change.scope(),false);c.commit();return true;
            } catch(Exception error) { c.rollback();throw error; }
        }
    }
    void importState(Connection c,ProtectionSnapshot state) throws Exception {
        for(var t:state.trusts()) put(c,TABLES.get(0),t,true);for(var p:state.pairs()) put(c,TABLES.get(1),p,true);for(var s:state.scores()) put(c,TABLES.get(2),s,true);
        for(var a:state.assessments()) put(c,TABLES.get(3),a,true);for(var a:state.audit()) put(c,TABLES.get(4),a,true);for(var s:state.scopes()) put(c,TABLES.get(5),s,true);
    }
}
