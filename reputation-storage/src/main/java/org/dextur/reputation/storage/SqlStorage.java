package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.protection.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;
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
    private boolean privateSchema;
    private boolean tierSchema;
    private boolean recoverySchema;
    private boolean administrationSchema;
    private org.dextur.reputation.domain.policy.PolicyRules featuresRules;
    private org.dextur.reputation.domain.recovery.RecoveryRules recoveryRules;
    private org.dextur.reputation.domain.tier.TierRules tierRules;
    private ProtectionRules protectionRules;
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
    Connection connect() throws SQLException {
        if(closed) throw new SQLException("Storage closed");
        if(database!=null) { database.ensureOwned();return database.dataSource().getConnection(); }
        Properties props=new Properties();props.setProperty("busy_timeout",Integer.toString(busy));props.setProperty("synchronous","FULL");props.setProperty("foreign_keys","true");
        return DriverManager.getConnection("jdbc:sqlite:"+file,props);
    }
    private void initialize(boolean migration) throws Exception {
        try(Connection c=connect();Statement s=c.createStatement()) {
            if(database==null) {
                try(var rows=s.executeQuery("PRAGMA integrity_check")) { if(!rows.next()||!"ok".equals(rows.getString(1))) throw new SQLException("SQLite integrity check failed"); }
                try(var rows=s.executeQuery("PRAGMA user_version")) { if(!rows.next()||rows.getInt(1)>6) throw new SQLException("Unsupported SQLite schema");tierSchema=rows.getInt(1)>=4;recoverySchema=rows.getInt(1)>=5;administrationSchema=rows.getInt(1)>=6; }
                try(var rows=s.executeQuery("PRAGMA journal_mode=WAL")) { if(!rows.next()||!"wal".equalsIgnoreCase(rows.getString(1))) throw new SQLException("SQLite WAL unavailable"); }
                c.setAutoCommit(false);
                try {
                    s.execute("CREATE TABLE IF NOT EXISTS dr_profiles(player_uuid TEXT NOT NULL,scope_id TEXT NOT NULL,community_score INTEGER NOT NULL,system_score INTEGER NOT NULL,visible_score INTEGER NOT NULL,tier TEXT NOT NULL,profile_revision INTEGER NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(player_uuid,scope_id))");
                    s.execute("CREATE TABLE IF NOT EXISTS dr_player_preferences(player_uuid TEXT PRIMARY KEY NOT NULL,language TEXT NOT NULL CHECK(language IN ('ru_RU','en_US')),updated_at TEXT NOT NULL)");
                    SqlVoting.initializeSQLite(c);
                    try(var version=c.createStatement();var row=version.executeQuery("PRAGMA user_version")) { if(row.next()&&row.getInt(1)>=3) { SqlProtection.sqlite(c);privateSchema=true; } }
                    if(tierSchema) SqlTiers.sqlite(c);if(recoverySchema)SqlRecovery.sqlite(c);if(administrationSchema)SqlAdministration.sqlite(c);
                    s.execute("PRAGMA user_version="+(administrationSchema?6:recoverySchema?5:tierSchema?4:privateSchema?3:2)); c.commit();
                } catch(Exception e) { c.rollback();throw e; } finally { c.setAutoCommit(true); }
            }
            s.execute("CREATE TABLE IF NOT EXISTS dr_storage_control(id INTEGER PRIMARY KEY,pending INTEGER NOT NULL,format_version INTEGER NOT NULL)");
            String insert=database==null?"INSERT INTO dr_storage_control VALUES(1,0,1) ON CONFLICT(id) DO NOTHING":"INSERT INTO dr_storage_control VALUES(1,0,1) ON DUPLICATE KEY UPDATE id=VALUES(id)";
            s.executeUpdate(insert);
            try(var row=s.executeQuery("SELECT pending,format_version FROM dr_storage_control WHERE id=1")) {
                if(!row.next()||row.getInt(2)!=1||(!migration&&row.getInt(1)!=0)) throw new SQLException("Unverified migration or unsupported storage metadata blocks startup");
            }
        }
        try(var c=connect();var query=c.prepareStatement(database==null?"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='dr_protection_scope'":"SELECT COUNT(*) FROM dr_schema_history WHERE version=3");var row=query.executeQuery()) { privateSchema=row.next()&&row.getLong(1)>0; }
        try(var c=connect();var query=c.prepareStatement(database==null?"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='dr_tiers'":"SELECT COUNT(*) FROM dr_schema_history WHERE version=4");var row=query.executeQuery()) { tierSchema=row.next()&&row.getLong(1)>0; }
        try(var c=connect();var query=c.prepareStatement(database==null?"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='dr_recovery'":"SELECT COUNT(*) FROM dr_schema_history WHERE version=5");var row=query.executeQuery()){recoverySchema=row.next()&&row.getLong(1)>0;}
        try(var c=connect();var query=c.prepareStatement(database==null?"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='dr_administration'":"SELECT COUNT(*) FROM dr_schema_history WHERE version=6");var row=query.executeQuery()){administrationSchema=row.next()&&row.getLong(1)>0;}
        snapshot(); // Validate complete stored records before the backend can be activated.
    }
    @Override public StorageType type() { return database==null?StorageType.SQLITE:StorageType.MYSQL; }
    @Override public synchronized ReputationSnapshot getOrCreate(UUID id,String scope) throws Exception {
        if(tierRules!=null) return new SqlVoting(this).view(id,scope).profile();
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
    ReputationSnapshot profile(ResultSet r) throws SQLException {
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
                var voting=new SqlVoting(this);var votes=voting.all(c);var protection=privateSchema?new SqlProtection(this).snapshot(c):ProtectionSnapshot.empty();voting.validateActive(c,votes,protection);
                var snapshot=new StorageSnapshot(profiles,preferences,votes,protection,tierSchema?new SqlTiers(this).snapshot(c):TierSnapshot.empty(),recoverySchema?new SqlRecovery(this).snapshot(c):RecoverySnapshotData.empty(),administrationSchema?new SqlAdministration(this).snapshot(c):AdministrationSnapshotData.empty());c.commit();return snapshot;
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
    @Override public synchronized org.dextur.reputation.api.VoteEntry operation(UUID id) throws Exception { return new SqlVoting(this).operation(id); }
    @Override public synchronized org.dextur.reputation.api.VoteReceipt vote(String scope,org.dextur.reputation.api.VoteRequest request,
            org.dextur.reputation.domain.VoterFacts facts,org.dextur.reputation.domain.VotingRules rules,java.time.Instant now) throws Exception {
        if(tierSchema&&tierRules==null) throw new IllegalStateException("Tier configuration required for voting");
        if(operation(request.operationId())==null)checkVoting(request.voterId(),scope,now);
        if(protectionRules!=null) return new SqlProtection(this).apply(scope,request,facts,rules,protectionRules,now);
        if(privateSchema&&!snapshot().protection().isEmpty()) throw new IllegalStateException("Protection configuration required for voting");
        return new SqlVoting(this).apply(scope,request,facts,rules,now);
    }
    @Override public synchronized org.dextur.reputation.api.ReputationView votingView(UUID player,String scope) throws Exception { return new SqlVoting(this).view(player,scope); }
    @Override public synchronized org.dextur.reputation.api.VotePage votingHistory(UUID player,String scope,org.dextur.reputation.api.VotePage.Direction direction,int page,int size) throws Exception {
        return new SqlVoting(this).history(player,scope,direction,page,size);
    }
    private void enableProtectionSchema() throws Exception {
        if(privateSchema) return;
        if(database!=null) new SchemaMigrator().protection(database.dataSource());
        else try(var c=connect()) { c.setAutoCommit(false);try { SqlProtection.sqlite(c);c.commit(); } catch(Exception error) { c.rollback();throw error; } }
        privateSchema=true;
    }
    @Override public synchronized void protection(ProtectionRules rules) throws Exception { enableProtectionSchema();protectionRules=Objects.requireNonNull(rules); }
    @Override public synchronized Trust inspectTrust(UUID id,String scope) throws Exception {
        if(!privateSchema) return null;try(var c=connect()) { return new SqlProtection(this).get(c,"dr_trust",id+"/"+scope,Trust.class); }
    }
    @Override public synchronized Audit inspectTransition(UUID id) throws Exception {
        if(!privateSchema) return null;try(var c=connect()) { return new SqlProtection(this).get(c,"dr_protection_audit",id.toString(),Audit.class); }
    }
    @Override public synchronized Assessment inspectVote(UUID id) throws Exception {
        if(!privateSchema) return null;try(var c=connect()) { return new SqlProtection(this).get(c,"dr_vote_assessments",id.toString(),Assessment.class); }
    }
    @Override public synchronized VoteProcessingOutcome outcome(UUID id) throws Exception {
        var assessment=inspectVote(id);if(assessment!=null) return new VoteProcessingOutcome(id,JournalProtection.publicStatus(assessment.status()));
        var vote=operation(id);if(vote==null) throw new IllegalArgumentException("Unknown vote");boolean active;
        try(var c=connect();var s=c.prepareStatement("SELECT COUNT(*) FROM dr_active_votes WHERE operation_id=?")) { s.setString(1,id.toString());try(var r=s.executeQuery()) { r.next();active=r.getLong(1)>0; } }
        return new VoteProcessingOutcome(id,vote.action()==VoteEntry.Action.REVOKE?VoteProcessingOutcome.Status.REVOKED:active?VoteProcessingOutcome.Status.ACCEPTED:VoteProcessingOutcome.Status.SUPERSEDED);
    }
    @Override public synchronized List<Audit> audit(String scope,long after,int limit) throws Exception { return privateSchema?new SqlProtection(this).audit(scope,after,limit):List.of(); }
    @Override public synchronized ReputationView review(UUID transition,UUID vote,UUID operator,String reason,Review action,long expected,Instant now) throws Exception {
        if(tierSchema&&tierRules==null) throw new IllegalStateException("Tier configuration required for review");
        return new SqlProtection(this).review(transition,vote,operator,reason,action,expected,Objects.requireNonNull(protectionRules,"Protection not configured"),now);
    }
    @Override public synchronized boolean refreshTrust(UUID player,String scope,long expected,org.dextur.reputation.domain.VoterFacts facts,Instant now) throws Exception {
        return new SqlProtection(this).refresh(player,scope,expected,facts,Objects.requireNonNull(protectionRules,"Protection not configured"),now);
    }
    @Override public synchronized List<Trust> dueTrust(String scope,Instant before,int limit) throws Exception { return privateSchema?new SqlProtection(this).due(scope,before,limit):List.of(); }
    @Override public synchronized boolean analyze(String scope,Instant now) throws Exception { return new SqlProtection(this).analyze(scope,Objects.requireNonNull(protectionRules,"Protection not configured"),now); }
    @Override public synchronized long storageBytes() throws Exception { return database==null?Files.size(file):0; }
    @Override public synchronized void prepareMigration() throws SQLException {
        try(Connection c=connect();var s=c.createStatement()) { s.executeUpdate("UPDATE dr_storage_control SET pending=1 WHERE id=1"); }
    }
    @Override public synchronized void importEmpty(StorageSnapshot snapshot) throws Exception {
        if(!snapshot.protection().isEmpty()) enableProtectionSchema();
        if(!snapshot.tiers().isEmpty()) enableTierSchema();
        if(!snapshot.recovery().isEmpty())enableRecoverySchema();
        if(!snapshot.administration().isEmpty())enableAdministrationSchema();
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                try(var s=c.createStatement();var rows=s.executeQuery("SELECT pending FROM dr_storage_control WHERE id=1")) { if(!rows.next()||rows.getInt(1)!=1) throw new SQLException("Target not prepared"); }
                for(String table:List.of("dr_profiles","dr_player_preferences","dr_votes","dr_active_votes")) {
                    try(var s=c.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM "+table)) { if(!rows.next()||rows.getLong(1)!=0) throw new SQLException("Migration target is not empty"); }
                }
                if(privateSchema) for(String table:SqlProtection.TABLES) try(var query=c.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM "+table)) { if(!rows.next()||rows.getLong(1)!=0) throw new SQLException("Private migration target is not empty"); }
                for(String table:List.of("dr_tiers","dr_recovery","dr_administration")){if(table.equals("dr_tiers")&&!tierSchema||table.equals("dr_recovery")&&!recoverySchema||table.equals("dr_administration")&&!administrationSchema)continue;try(var query=c.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM "+table)){if(!rows.next()||rows.getLong(1)!=0)throw new SQLException("Nonempty migration target");}}
                try(var s=c.prepareStatement("INSERT INTO dr_profiles(player_uuid,scope_id,community_score,system_score,visible_score,tier,profile_revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    for(var p:snapshot.profiles()) {
                        s.setString(1,p.playerId().toString());s.setString(2,p.scopeId());s.setLong(3,p.communityScore());s.setLong(4,p.systemScore());s.setLong(5,p.visibleScore());s.setString(6,p.tier());s.setLong(7,p.revision());timestamp(s,8,p.createdAt());timestamp(s,9,p.updatedAt());s.addBatch();
                    }s.executeBatch();
                }
                try(var s=c.prepareStatement("INSERT INTO dr_player_preferences(player_uuid,language,updated_at) VALUES(?,?,?)")) {
                    for(var p:snapshot.preferences()) { s.setString(1,p.playerId().toString());s.setString(2,p.language().name());timestamp(s,3,p.updatedAt());s.addBatch(); }s.executeBatch();
                }
                var voting=new SqlVoting(this);
                for(var vote:snapshot.votes()) { voting.insert(c,vote);voting.active(c,vote); }
                if(privateSchema) {
                    new SqlProtection(this).importState(c,snapshot.protection());
                    try(var clear=c.createStatement()) { clear.executeUpdate("DELETE FROM dr_active_votes"); }
                    for(var vote:ProtectionValidation.active(snapshot.votes(),snapshot.protection()).values()) voting.active(c,vote);
                }
                if(tierSchema) for(var state:snapshot.tiers().states()) new SqlTiers(this).put(c,state);
                if(recoverySchema)for(var row:snapshot.recovery().states())new SqlRecovery(this).put(c,row);
                if(administrationSchema)for(var row:snapshot.administration().states())new SqlAdministration(this).put(c,row);
                c.commit();
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
    private void enableTierSchema() throws Exception {
        if(tierSchema) return;enableProtectionSchema();
        if(database!=null) new SchemaMigrator().tiers(database.dataSource());
        else try(var c=connect()) {c.setAutoCommit(false);try {SqlTiers.sqlite(c);c.commit();}catch(Exception e){c.rollback();throw e;}}
        tierSchema=true;
    }
    ReputationSnapshot reconcileTier(Connection c,ReputationSnapshot profile) throws SQLException {
        if(tierRules==null) return profile;
        var repository=new SqlTiers(this);var previous=repository.get(c,profile.playerId(),profile.scopeId());var next=TierSnapshot.reconcile(profile,previous,tierRules);
        if(!next.equals(previous)) repository.put(c,next);
        if(!profile.tier().equals(next.tier())) try(var s=c.prepareStatement("UPDATE dr_profiles SET tier=? WHERE player_uuid=? AND scope_id=?")) {s.setString(1,next.tier());s.setString(2,profile.playerId().toString());s.setString(3,profile.scopeId());if(s.executeUpdate()!=1) throw new SQLException("Tier profile missing");}
        return TierSnapshot.profile(profile,next);
    }
    @Override public synchronized void tiers(org.dextur.reputation.domain.tier.TierRules rules) throws Exception {
        enableTierSchema();tierRules=Objects.requireNonNull(rules);
        // Bounded keyset batches. Every profile is consistent before readiness; interrupted startup resumes.
        String afterPlayer="",afterScope="";
        while(true) {
            var rows=new ArrayList<ReputationSnapshot>();try(var c=connect()) {c.setAutoCommit(false);try {
                try(var s=c.prepareStatement("SELECT * FROM dr_profiles WHERE (player_uuid,scope_id)>(?,?) ORDER BY player_uuid,scope_id LIMIT 100")) {s.setString(1,afterPlayer);s.setString(2,afterScope);try(var r=s.executeQuery()){while(r.next()) rows.add(profile(r));}}
                for(var p:rows) reconcileTier(c,p);c.commit();
            }catch(Exception e){c.rollback();throw e;}}
            if(rows.size()<100) break;var last=rows.getLast();afterPlayer=last.playerId().toString();afterScope=last.scopeId();
        }
    }
    @Override public synchronized org.dextur.reputation.domain.tier.TierState tierState(UUID player,String scope) throws Exception {if(!tierSchema)return null;try(var c=connect()){return new SqlTiers(this).get(c,player,scope);}}
    @Override public synchronized List<org.dextur.reputation.domain.tier.TierState> pendingTierSyncAfter(String scope,String after,int limit) throws Exception {
        if(limit<1||limit>100) throw new IllegalArgumentException("Tier sync batch bounds");if(!tierSchema)return List.of();var rows=new ArrayList<org.dextur.reputation.domain.tier.TierState>();
        try(var c=connect();var s=c.prepareStatement("SELECT * FROM dr_tiers WHERE scope_id=? AND player_uuid>? AND applied_revision<sync_revision ORDER BY player_uuid LIMIT ?")) {s.setString(1,scope);s.setString(2,after);s.setInt(3,limit);try(var r=s.executeQuery()){while(r.next())rows.add(new SqlTiers(this).read(r));}}return List.copyOf(rows);
    }
    @Override public synchronized boolean tierSynced(UUID player,String scope,long expected,List<String> owned) throws Exception {
        if(!tierSchema)return false;try(var c=connect()) {c.setAutoCommit(false);try {var repo=new SqlTiers(this);var old=repo.get(c,player,scope);if(old==null||old.syncRevision()!=expected){c.rollback();return false;}repo.put(c,old.synced(expected,owned));c.commit();return true;}catch(Exception e){c.rollback();throw e;}}
    }
    private void enableRecoverySchema()throws Exception{if(recoverySchema)return;enableTierSchema();if(database!=null)new SchemaMigrator().recovery(database.dataSource());else try(var c=connect()){c.setAutoCommit(false);try{SqlRecovery.sqlite(c);c.commit();}catch(Exception e){c.rollback();throw e;}}recoverySchema=true;}
    @Override public synchronized void recovery(org.dextur.reputation.domain.recovery.RecoveryRules rules)throws Exception{enableRecoverySchema();recoveryRules=Objects.requireNonNull(rules);
        if(!rules.enabled()){String afterPlayer="",afterScope="";while(true){var rows=new ArrayList<org.dextur.reputation.domain.recovery.RecoveryData.State>();try(var c=connect();var s=c.prepareStatement("SELECT * FROM dr_recovery WHERE (player_uuid,scope_id)>(?,?) ORDER BY player_uuid,scope_id LIMIT 100")){s.setString(1,afterPlayer);s.setString(2,afterScope);try(var r=s.executeQuery()){while(r.next())rows.add(new SqlRecovery(this).read(r));}}for(var row:rows)if(row.current()!=null&&row.current().status()==org.dextur.reputation.api.RecoverySnapshot.Status.ACTIVE)recover(org.dextur.reputation.domain.recovery.RecoveryData.disabled(row,StorageSnapshot.now()));if(rows.size()<100)break;var last=rows.getLast();afterPlayer=last.player().toString();afterScope=last.scope();}}
    }
    @Override public synchronized org.dextur.reputation.domain.recovery.RecoveryRules recoveryDefinition(UUID player,String scope)throws Exception{if(!recoverySchema)return recoveryRules;try(var c=connect()){var state=new SqlRecovery(this).get(c,player,scope);return state==null||state.current()==null?recoveryRules:state.current().definition();}}
    @Override public synchronized org.dextur.reputation.domain.recovery.RecoveryData.Change recover(org.dextur.reputation.domain.recovery.RecoveryData.Request request)throws Exception{
        Objects.requireNonNull(recoveryRules,"Recovery not configured");Objects.requireNonNull(tierRules,"Tiers not configured");
        try(var c=connect()){c.setAutoCommit(false);try{
            var profile=new SqlVoting(this).ensure(c,request.player(),request.scope(),request.now());var repo=new SqlRecovery(this);var old=repo.get(c,request.player(),request.scope());var protection=new SqlProtection(this);var trust=protection.get(c,"dr_trust",request.player()+"/"+request.scope(),Trust.class);
            var change=org.dextur.reputation.domain.recovery.RecoveryEngine.evaluate(old,profile,trust,recoveryRules,request,administrationSchema?java.util.Optional.ofNullable(new SqlAdministration(this).get(c,request.player(),request.scope())).map(org.dextur.reputation.domain.policy.AdministrationData.State::contribution).orElse(0L):0L);
            if(!change.state().equals(old)){
                var p=change.profile();try(var s=c.prepareStatement("UPDATE dr_profiles SET system_score=?,visible_score=?,profile_revision=?,updated_at=? WHERE player_uuid=? AND scope_id=?")){s.setLong(1,p.systemScore());s.setLong(2,p.visibleScore());s.setLong(3,p.revision());timestamp(s,4,p.updatedAt());s.setString(5,p.playerId().toString());s.setString(6,p.scopeId());if(s.executeUpdate()!=1)throw new SQLException("Missing recovery profile");}
                if(change.trust()!=null&&!change.trust().equals(trust))protection.put(c,"dr_trust",change.trust(),false);repo.put(c,change.state());
            }
            var projected=reconcileTier(c,change.profile());c.commit();return new org.dextur.reputation.domain.recovery.RecoveryData.Change(change.state(),projected,change.trust(),change.snapshot(),change.events());
        }catch(Exception e){c.rollback();throw e;}}
    }
    private void enableAdministrationSchema()throws Exception{
        if(administrationSchema)return;enableRecoverySchema();if(database!=null)new SchemaMigrator().administration(database.dataSource());else try(var c=connect()){c.setAutoCommit(false);try{SqlAdministration.sqlite(c);c.commit();}catch(Exception error){c.rollback();throw error;}}administrationSchema=true;
    }
    @Override public synchronized void features(org.dextur.reputation.domain.policy.PolicyRules rules)throws Exception{enableAdministrationSchema();featuresRules=Objects.requireNonNull(rules);}
    private org.dextur.reputation.domain.policy.PolicyEngine.Input policyInput(Connection c,ReputationSnapshot profile)throws Exception{
        var administration=administrationSchema?new SqlAdministration(this).get(c,profile.playerId(),profile.scopeId()):null;
        var recovery=recoverySchema?new SqlRecovery(this).get(c,profile.playerId(),profile.scopeId()):null;
        return new org.dextur.reputation.domain.policy.PolicyEngine.Input(profile,administration,recovery==null||recovery.current()==null?RecoverySnapshot.Status.AVAILABLE:recovery.current().status(),recovery==null?0:recovery.revision());
    }
    @Override public synchronized org.dextur.reputation.domain.policy.PolicyEngine.Input policyInput(UUID player,String scope)throws Exception{
        try(var c=connect()){c.setAutoCommit(false);try{var profile=new SqlVoting(this).ensure(c,player,scope,StorageSnapshot.now());var result=policyInput(c,profile);c.commit();return result;}catch(Exception error){c.rollback();throw error;}}
    }
    private void checkVoting(UUID player,String scope,Instant now)throws Exception{
        if(featuresRules==null&&!administrationSchema)return;
        org.dextur.reputation.domain.policy.PolicyEngine.Input input;
        try(var c=connect()){c.setAutoCommit(false);try{ReputationSnapshot profile=new ReputationSnapshot(player,scope,0,0,0,"NEUTRAL",0,now,now);try(var s=c.prepareStatement("SELECT * FROM dr_profiles WHERE player_uuid=? AND scope_id=?")){s.setString(1,player.toString());s.setString(2,scope);try(var r=s.executeQuery()){if(r.next())profile=profile(r);}}input=policyInput(c,profile);c.commit();}catch(Exception error){c.rollback();throw error;}}
        if(input.administration()!=null&&input.administration().votingLocked())throw new PolicyDeniedException("policy-reason-voting-lock");
        if(featuresRules!=null){var result=org.dextur.reputation.domain.policy.PolicyEngine.evaluate(input,featuresRules,"reputation.vote",Map.of(),now,true);if(!result.permitted())throw new PolicyDeniedException(result.reasonKey());}
    }
    @Override public synchronized org.dextur.reputation.domain.policy.AdministrationData.Change administer(org.dextur.reputation.domain.policy.AdministrationData.Request request)throws Exception{
        Objects.requireNonNull(featuresRules,"Features not configured");if(request.type()==org.dextur.reputation.domain.policy.AdministrationData.Type.OVERRIDE&&!featuresRules.policies().containsKey(request.policy()))throw new IllegalArgumentException("ADMIN_POLICY");Objects.requireNonNull(tierRules,"Tiers not configured");
        try(var c=connect()){c.setAutoCommit(false);try{var old=new SqlVoting(this).ensure(c,request.player(),request.scope(),request.at());var repo=new SqlAdministration(this);var change=org.dextur.reputation.domain.policy.AdministrationData.apply(repo.get(c,request.player(),request.scope()),old,request,featuresRules.administration());
            if(!change.duplicate()){var p=change.profile();try(var s=c.prepareStatement("UPDATE dr_profiles SET system_score=?,visible_score=?,profile_revision=?,updated_at=? WHERE player_uuid=? AND scope_id=?")){s.setLong(1,p.systemScore());s.setLong(2,p.visibleScore());s.setLong(3,p.revision());timestamp(s,4,p.updatedAt());s.setString(5,p.playerId().toString());s.setString(6,p.scopeId());if(s.executeUpdate()!=1)throw new SQLException("Administration profile missing");}repo.put(c,change.state());}
            var result=reconcileTier(c,change.profile());c.commit();return new org.dextur.reputation.domain.policy.AdministrationData.Change(change.state(),result,change.audit(),change.duplicate());
        }catch(Exception error){c.rollback();throw error;}}
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
