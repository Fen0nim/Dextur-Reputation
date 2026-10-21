package org.dextur.reputation.storage;

import java.io.IOException;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;

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
    private boolean investigationSchema;
    private Rules investigationRules;
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
        file=StoragePaths.checked(file);
        AtomicFiles.directories(file.getParent());var lease=new StorageLease(file.resolveSibling(file.getFileName()+".owner.lock"));
        var backend=new SqlStorage(null,file,busy,lease);
        try { backend.initialize(migration);return backend; } catch(Exception e) { backend.close();throw e; }
    }
    Connection connect() throws SQLException {
        if(closed) throw new SQLException("Storage closed");
        if(database!=null) { database.ensureOwned();return database.dataSource().getConnection(); }
        try {lease.verify();}catch(java.io.IOException error){throw new SQLException("SQLite owner path changed",error);}
        Properties props=new Properties();props.setProperty("busy_timeout",Integer.toString(busy));props.setProperty("synchronous","FULL");props.setProperty("foreign_keys","true");
        return DriverManager.getConnection("jdbc:sqlite:"+file,props);
    }
    private void initialize(boolean migration) throws Exception {
        try(Connection c=connect();Statement s=c.createStatement()) {
            if(database==null) {
                try(var rows=s.executeQuery("PRAGMA integrity_check")) { if(!rows.next()||!"ok".equals(rows.getString(1))) throw new SQLException("SQLite integrity check failed"); }
                try(var rows=s.executeQuery("PRAGMA user_version")) { if(!rows.next()||rows.getInt(1)>7) throw new SQLException("Unsupported SQLite schema");tierSchema=rows.getInt(1)>=4;recoverySchema=rows.getInt(1)>=5;administrationSchema=rows.getInt(1)>=6;investigationSchema=rows.getInt(1)>=7; }
                try(var rows=s.executeQuery("PRAGMA journal_mode=WAL")) { if(!rows.next()||!"wal".equalsIgnoreCase(rows.getString(1))) throw new SQLException("SQLite WAL unavailable"); }
                c.setAutoCommit(false);
                try {
                    s.execute("CREATE TABLE IF NOT EXISTS dr_profiles(player_uuid TEXT NOT NULL,scope_id TEXT NOT NULL,community_score INTEGER NOT NULL,system_score INTEGER NOT NULL,visible_score INTEGER NOT NULL,tier TEXT NOT NULL,profile_revision INTEGER NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(player_uuid,scope_id))");
                    s.execute("CREATE TABLE IF NOT EXISTS dr_player_preferences(player_uuid TEXT PRIMARY KEY NOT NULL,language TEXT NOT NULL CHECK(language IN ('ru_RU','en_US')),updated_at TEXT NOT NULL)");
                    SqlVoting.initializeSQLite(c);
                    try(var version=c.createStatement();var row=version.executeQuery("PRAGMA user_version")) { if(row.next()&&row.getInt(1)>=3) { SqlProtection.sqlite(c);privateSchema=true; } }
                    if(tierSchema) SqlTiers.sqlite(c);if(recoverySchema)SqlRecovery.sqlite(c);if(administrationSchema)SqlAdministration.sqlite(c);if(investigationSchema)SqlInvestigation.sqlite(c);
                    s.execute("PRAGMA user_version="+(investigationSchema?7:administrationSchema?6:recoverySchema?5:tierSchema?4:privateSchema?3:2)); c.commit();
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
        try(var c=connect();var query=c.prepareStatement(database==null?"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='dr_investigation'":"SELECT COUNT(*) FROM dr_schema_history WHERE version=7");var row=query.executeQuery()){investigationSchema=row.next()&&row.getLong(1)>0;}
        snapshot(); // Validate complete stored records before the backend can be activated.
    }
    @Override public StorageType type() { return database==null?StorageType.SQLITE:StorageType.MYSQL; }
    @Override public synchronized ReputationSnapshot getOrCreate(UUID id,String scope) throws Exception {
        if(!scope.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid scope");
        if(tierRules!=null) return new SqlVoting(this).profile(id,scope);
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
                var snapshot=new StorageSnapshot(profiles,preferences,votes,protection,tierSchema?new SqlTiers(this).snapshot(c):TierSnapshot.empty(),recoverySchema?new SqlRecovery(this).snapshot(c):RecoverySnapshotData.empty(),administrationSchema?new SqlAdministration(this).snapshot(c):AdministrationSnapshotData.empty(),investigationSchema?new SqlInvestigation(this).snapshot(c):InvestigationSnapshotData.empty());c.commit();return snapshot;
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
    @Override public synchronized org.dextur.reputation.api.VoteEntry operation(UUID id) throws Exception { return new SqlVoting(this).operation(id); }
    @Override public synchronized org.dextur.reputation.api.VoteReceipt vote(String scope,org.dextur.reputation.api.VoteRequest request,
            org.dextur.reputation.domain.VoterFacts facts,org.dextur.reputation.domain.VotingRules rules,java.time.Instant now) throws Exception {
        if(tierSchema&&tierRules==null) throw new IllegalStateException("Tier configuration required for voting");
        var access=operation(request.operationId())==null?checkVoting(request.voterId(),scope,now):null;
        rules=org.dextur.reputation.domain.policy.InitialEligibility.rules(rules,access);
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
        if(investigationSchema)try(var c=connect()){var record=new SqlInvestigation(this).get(c,Kind.MODERATION,id);if(record!=null&&record.status().equals("INVALIDATED"))return new VoteProcessingOutcome(id,VoteProcessingOutcome.Status.INVALIDATED);}
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
        if(!snapshot.investigation().isEmpty())enableInvestigationSchema();
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                try(var s=c.createStatement();var rows=s.executeQuery("SELECT pending FROM dr_storage_control WHERE id=1")) { if(!rows.next()||rows.getInt(1)!=1) throw new SQLException("Target not prepared"); }
                for(String table:List.of("dr_profiles","dr_player_preferences","dr_votes","dr_active_votes")) {
                    try(var s=c.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM "+table)) { if(!rows.next()||rows.getLong(1)!=0) throw new SQLException("Migration target is not empty"); }
                }
                if(privateSchema) for(String table:SqlProtection.TABLES) try(var query=c.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM "+table)) { if(!rows.next()||rows.getLong(1)!=0) throw new SQLException("Private migration target is not empty"); }
                for(String table:List.of("dr_tiers","dr_recovery","dr_administration","dr_investigation")){if(table.equals("dr_tiers")&&!tierSchema||table.equals("dr_recovery")&&!recoverySchema||table.equals("dr_administration")&&!administrationSchema||table.equals("dr_investigation")&&!investigationSchema)continue;try(var query=c.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM "+table)){if(!rows.next()||rows.getLong(1)!=0)throw new SQLException("Nonempty migration target");}}
                try(var s=c.prepareStatement("INSERT INTO dr_profiles(player_uuid,scope_id,community_score,system_score,visible_score,tier,profile_revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    int n=0;for(var p:snapshot.profiles()) {
                        s.setString(1,p.playerId().toString());s.setString(2,p.scopeId());s.setLong(3,p.communityScore());s.setLong(4,p.systemScore());s.setLong(5,p.visibleScore());s.setString(6,p.tier());s.setLong(7,p.revision());timestamp(s,8,p.createdAt());timestamp(s,9,p.updatedAt());s.addBatch();if(++n%100==0){s.executeBatch();s.clearBatch();}
                    }if(n%100!=0)s.executeBatch();
                }
                try(var s=c.prepareStatement("INSERT INTO dr_player_preferences(player_uuid,language,updated_at) VALUES(?,?,?)")) {
                    int n=0;for(var p:snapshot.preferences()) { s.setString(1,p.playerId().toString());s.setString(2,p.language().name());timestamp(s,3,p.updatedAt());s.addBatch();if(++n%100==0){s.executeBatch();s.clearBatch();} }if(n%100!=0)s.executeBatch();
                }
                var voting=new SqlVoting(this);
                voting.importVotes(c,snapshot.votes(),snapshot.protection());
                if(privateSchema) {
                    new SqlProtection(this).importState(c,snapshot.protection());
                }
                if(tierSchema) for(var state:snapshot.tiers().states()) new SqlTiers(this).put(c,state);
                if(recoverySchema)for(var row:snapshot.recovery().states())new SqlRecovery(this).put(c,row);
                if(administrationSchema)for(var row:snapshot.administration().states())new SqlAdministration(this).put(c,row);
                if(investigationSchema)for(var row:snapshot.investigation().records())new SqlInvestigation(this).put(c,row);
                c.commit();
                // Bulk imports otherwise leave MySQL's empty-table estimates in use for normal commands.
                if(database!=null)try(var analyze=c.createStatement()) {
                    analyze.execute("ANALYZE TABLE dr_profiles,dr_votes,dr_active_votes"+(investigationSchema?",dr_investigation":""));
                    try(var result=analyze.getResultSet()){while(result.next())if("error".equalsIgnoreCase(result.getString("Msg_type")))throw new SQLException("Optimizer statistics refresh failed");}
                }
            } catch(Exception e) { rollback(c,e);throw e; }
        }
    }
    static void rollback(Connection c,Exception original) {try{c.rollback();}catch(SQLException failure){original.addSuppressed(failure);}}
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
                if(change.trust()!=null&&!change.trust().equals(trust))protection.put(c,"dr_trust",change.trust(),false);repo.put(c,change.state());for(var event:change.state().audit())if(!Set.of("PROGRESS_CHANGED","OBJECTIVE_COMPLETED").contains(event.kind()))captureLegacy(c,InvestigationLegacy.recovery(event,request.player(),request.scope()));
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
    private org.dextur.reputation.domain.policy.AdministrationData.State checkVoting(UUID player,String scope,Instant now)throws Exception{
        if(featuresRules==null&&!administrationSchema)return null;
        org.dextur.reputation.domain.policy.PolicyEngine.Input input;
        try(var c=connect()){c.setAutoCommit(false);try{ReputationSnapshot profile=new ReputationSnapshot(player,scope,0,0,0,"NEUTRAL",0,now,now);try(var s=c.prepareStatement("SELECT * FROM dr_profiles WHERE player_uuid=? AND scope_id=?")){s.setString(1,player.toString());s.setString(2,scope);try(var r=s.executeQuery()){if(r.next())profile=profile(r);}}input=policyInput(c,profile);c.commit();}catch(Exception error){c.rollback();throw error;}}
        if(input.administration()!=null&&input.administration().votingLocked())throw new PolicyDeniedException("policy-reason-voting-lock");
        if(featuresRules!=null){var result=org.dextur.reputation.domain.policy.PolicyEngine.evaluate(input,featuresRules,"reputation.vote",Map.of(),now,true);if(!result.permitted())throw new PolicyDeniedException(result.reasonKey());}
        return input.administration();
    }
    @Override public synchronized org.dextur.reputation.domain.policy.AdministrationData.Change administer(org.dextur.reputation.domain.policy.AdministrationData.Request request)throws Exception{
        Objects.requireNonNull(featuresRules,"Features not configured");if(request.type()==org.dextur.reputation.domain.policy.AdministrationData.Type.OVERRIDE&&!featuresRules.policies().containsKey(request.policy())&&!request.policy().equals(org.dextur.reputation.domain.policy.InitialEligibility.KEY))throw new IllegalArgumentException("ADMIN_POLICY");Objects.requireNonNull(tierRules,"Tiers not configured");
        try(var c=connect()){c.setAutoCommit(false);try{var old=new SqlVoting(this).ensure(c,request.player(),request.scope(),request.at());var repo=new SqlAdministration(this);var change=org.dextur.reputation.domain.policy.AdministrationData.apply(repo.get(c,request.player(),request.scope()),old,request,featuresRules.administration());
            if(!change.duplicate()){var p=change.profile();try(var s=c.prepareStatement("UPDATE dr_profiles SET system_score=?,visible_score=?,profile_revision=?,updated_at=? WHERE player_uuid=? AND scope_id=?")){s.setLong(1,p.systemScore());s.setLong(2,p.visibleScore());s.setLong(3,p.revision());timestamp(s,4,p.updatedAt());s.setString(5,p.playerId().toString());s.setString(6,p.scopeId());if(s.executeUpdate()!=1)throw new SQLException("Administration profile missing");}repo.put(c,change.state());captureLegacy(c,InvestigationLegacy.administration(change.audit()));}
            var result=reconcileTier(c,change.profile());c.commit();return new org.dextur.reputation.domain.policy.AdministrationData.Change(change.state(),result,change.audit(),change.duplicate());
        }catch(Exception error){c.rollback();throw error;}}
    }

    private void enableInvestigationSchema()throws Exception{if(investigationSchema)return;enableAdministrationSchema();if(database!=null)new SchemaMigrator().investigation(database.dataSource());else try(var c=connect()){c.setAutoCommit(false);try{SqlInvestigation.sqlite(c);c.commit();}catch(Exception e){c.rollback();throw e;}}investigationSchema=true;}
    void captureFlag(Connection c,Assessment a)throws Exception{if(!investigationSchema)return;var repo=new SqlInvestigation(this);for(String source:a.signals())if(repo.get(c,Kind.FLAG,InvestigationEngine.flagId(a.voteId(),source))==null){checkInvestigationCapacity(c);repo.put(c,InvestigationReports.flag(a,source));}}
    @Override public synchronized void investigation(Rules rules)throws Exception{enableInvestigationSchema();investigationRules=rules;
        try(var c=connect();var q=c.prepareStatement("DELETE FROM dr_investigation WHERE kind='DIAGNOSTIC' AND created_at<?")){new SqlVoting(this).time(q,1,StorageSnapshot.now().minusSeconds(rules.diagnosticRetentionDays()*86400L));q.executeUpdate();}
        try(var c=connect()){c.setAutoCommit(false);try{var assessments=new SqlProtection(this).snapshot(c).assessments();for(var a:assessments)captureFlag(c,a);c.commit();}catch(Exception e){c.rollback();throw e;}}
        var complete=snapshot();try(var c=connect()){c.setAutoCommit(false);try{for(var audit:InvestigationLegacy.all(complete))captureLegacy(c,audit);for(var marker:InvestigationLegacy.markers(complete))new SqlInvestigation(this).put(c,marker);c.commit();}catch(Exception e){c.rollback();throw e;}}
    }
    void captureLegacy(Connection c,Record record)throws Exception{if(investigationRules==null)return;var repository=new SqlInvestigation(this);if(repository.get(c,Kind.AUDIT,record.id())==null){checkInvestigationCapacity(c);repository.put(c,record);}}
    void captureReview(Connection c,Audit audit,Assessment previous)throws Exception{if(investigationRules==null||previous==null)return;var repository=new SqlInvestigation(this);var old=repository.get(c,Kind.MODERATION,audit.voteId());if(old==null)checkInvestigationCapacity(c);repository.put(c,InvestigationLegacy.marker(audit,previous.status(),old==null?1:old.revision()+1));}
    private void checkInvestigationCapacity(Connection c)throws Exception{if(investigationRules==null)return;try(var q=c.createStatement();var rows=q.executeQuery("SELECT COUNT(*) FROM dr_investigation")){rows.next();if(rows.getLong(1)>=investigationRules.maximumRecords())throw new IllegalStateException("INV_CAPACITY");}}
    @FunctionalInterface private interface InvestigationWork<T>{T run(JdbcInvestigation session)throws Exception;}
    private <T>T investigationTransaction(InvestigationWork<T> work)throws Exception{try(var c=connect()){if(database!=null)c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);c.setAutoCommit(false);try{T result=work.run(new JdbcInvestigation(this,c,investigationRules,protectionRules,tierRules));c.commit();return result;}catch(Exception e){c.rollback();throw e;}}}
    private <T>T investigationWrite(InvestigationWork<T> work)throws Exception{return investigationTransaction(session->{try(var q=session.c.prepareStatement("DELETE FROM dr_investigation WHERE kind='DIAGNOSTIC' AND created_at<?")){new SqlVoting(this).time(q,1,StorageSnapshot.now().minusSeconds(investigationRules.diagnosticRetentionDays()*86400L));q.executeUpdate();}return work.run(session);});}
    @Override public synchronized Report investigate(Query query)throws Exception{return investigationTransaction(session->new InvestigationReports(session).read(query));}
    @Override public synchronized Mutation moderate(Request request)throws Exception{return investigationWrite(session->new InvestigationEngine(session).mutate(request));}
    @Override public synchronized Plan plan(Action action,String scope,Filter filter,String actor,String reason,Instant now)throws Exception{return investigationTransaction(session->new InvestigationBulk(session).plan(action,scope,filter,actor,reason,now));}
    @Override public synchronized Record begin(UUID operation,Plan plan,Instant now)throws Exception{return investigationWrite(session->new InvestigationBulk(session).begin(operation,plan,now));}
    @Override public synchronized Batch advance(UUID operation,String actor,boolean cancel,Instant now)throws Exception{try{return investigationWrite(session->new InvestigationBulk(session).advance(operation,actor,cancel,now));}catch(Exception error){try{investigationWrite(session->{InvestigationFailures.pause(session,operation,actor,error,now);return null;});}catch(Exception logging){error.addSuppressed(logging);}throw error;}}

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
