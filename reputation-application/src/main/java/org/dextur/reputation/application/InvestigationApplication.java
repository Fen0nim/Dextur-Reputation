package org.dextur.reputation.application;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.investigation.*;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;

/** Private application capability. Sensitive DTOs never enter public services or placeholders. */
public final class InvestigationApplication implements AutoCloseable {
    public record Committed(UUID operation,String kind,UUID target,List<UUID> votes){public Committed{votes=List.copyOf(votes);}}
    private record Token(String actor,Plan plan,Instant expires,UUID operation){}
    private final ReputationApplication app;private final InvestigationRepository repository;private final VotingApplication voting;private final Rules rules;private final String scope;private final Clock clock;private final Consumer<Throwable> errors;
    private final ConcurrentHashMap<String,Token> tokens=new ConcurrentHashMap<>();private final ConcurrentHashMap<UUID,CompletableFuture<Record>> running=new ConcurrentHashMap<>();private final CopyOnWriteArrayList<Consumer<Committed>> listeners=new CopyOnWriteArrayList<>();private final AtomicBoolean closed=new AtomicBoolean();
    public InvestigationApplication(ReputationApplication app,InvestigationRepository repository,VotingApplication voting,Rules rules,String scope,Clock clock,Consumer<Throwable> errors){this.app=app;this.repository=repository;this.voting=voting;this.rules=rules;this.scope=scope;this.clock=clock;this.errors=errors;}
    public Rules rules(){return rules;}
    public CompletionStage<Report> read(Query query){if(closed.get()||!query.scope().equals(scope))return CompletableFuture.failedFuture(new IllegalStateException("INV_UNAVAILABLE"));return app.submit(()->repository.investigate(query));}
    public CompletionStage<Mutation> mutate(Request request){if(closed.get()||!request.scope().equals(scope))return CompletableFuture.failedFuture(new IllegalStateException("INV_UNAVAILABLE"));return app.submit(()->{
        List<UUID> players=new ArrayList<>();if(org.dextur.reputation.domain.investigation.InvestigationData.voteAction(request.action())){var report=repository.investigate(new Query(Section.VOTE,scope,null,request.subject(),Filter.empty(),1,1));var row=report.rows().getFirst();players.add(UUID.fromString(row.values().get("voter")));players.add(UUID.fromString(row.values().get("target")));}else if(request.action()!=Action.RESOLVE_FLAG)players.add(request.subject());
        return request.action()==Action.RESOLVE_FLAG?app.withAllProfiles(()->{var result=repository.moderate(request);committed(result,request.action().name());return result;}):app.withPlayers(players,()->{var result=repository.moderate(request);committed(result,request.action().name());return result;});
    });}
    private void committed(Mutation result,String action){app.updateProfile(result.profile(),!result.duplicate()&&!result.status().equals("NO_CHANGE"));if(result.duplicate()||result.status().equals("NO_CHANGE"))return;for(UUID vote:result.votes())if(voting!=null)try{
        // Read the authoritative current outcome through the existing voting port, rather than inventing a status.
        if(repository instanceof org.dextur.reputation.domain.protection.ProtectionRepository protection&&repository instanceof org.dextur.reputation.domain.VotingRepository profiles)voting.statusCommitted(new VotingService.VoteStatusChanged(result.operation(),vote,protection.outcome(vote).status(),profiles.votingView(result.target(),scope)));
    }catch(Throwable failure){diagnose(failure);}emit(new Committed(result.operation(),switch(action){case "INVALIDATE"->"VoteInvalidated";case "RESTORE"->"VoteRestored";case "APPROVE","REJECT"->"PendingVoteReviewed";case "RECALCULATE_TRUST"->"TrustRecalculated";case "RECALCULATE_REPUTATION"->"ReputationRecalculated";default->action;},result.target(),result.votes()));}
    public CompletionStage<Plan> plan(Action action,Filter filter,String actor,String reason){if(closed.get())return CompletableFuture.failedFuture(new IllegalStateException("INV_UNAVAILABLE"));return app.submit(()->repository.plan(action,scope,filter,actor,reason,now()));}
    public synchronized String token(Plan plan){if(closed.get())throw new IllegalStateException("INV_UNAVAILABLE");tokens.entrySet().removeIf(e->!e.getValue().expires().isAfter(now()));if(tokens.size()>=rules.maximumTokens())throw new IllegalStateException("INV_CAPACITY");String token=UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);tokens.put(token,new Token(plan.actor(),plan,now().plusSeconds(rules.tokenSeconds()),UUID.randomUUID()));return token;}
    /** Wrong actor cannot consume another administrator's token. Authorized confirmation is one-time. */
    public Optional<UUID> confirmationOperation(String token,String actor){var saved=tokens.get(token);return saved!=null&&saved.actor().equals(actor)&&saved.expires().isAfter(now())?Optional.of(saved.operation()):Optional.empty();}
    public Optional<Action> confirmationAction(String token,String actor){var saved=tokens.get(token);return saved!=null&&saved.actor().equals(actor)&&saved.expires().isAfter(now())?Optional.of(saved.plan().action()):Optional.empty();}
    public CompletionStage<Record> confirm(String token,String actor){var saved=tokens.get(token);if(saved==null||!saved.actor().equals(actor)||!saved.expires().isAfter(now())||!tokens.remove(token,saved))return CompletableFuture.failedFuture(new IllegalStateException("INV_TOKEN"));return execute(saved.operation(),saved.plan());}
    public CompletionStage<Record> execute(UUID operation,Plan plan){if(closed.get())return CompletableFuture.failedFuture(new IllegalStateException("INV_UNAVAILABLE"));return stage(operation,plan).thenCompose(record->resume(operation,plan.actor()));}
    public CompletionStage<Record> stage(UUID operation,Plan plan){if(closed.get())return CompletableFuture.failedFuture(new IllegalStateException("INV_UNAVAILABLE"));return app.submit(()->app.withPlayers(plan.players(),()->repository.begin(operation,plan,now())));}
    public CompletionStage<Record> resume(UUID operation,String actor){if(closed.get()||running.size()>=64)return CompletableFuture.failedFuture(new IllegalStateException("INV_UNAVAILABLE"));var result=new CompletableFuture<Record>();var existing=running.putIfAbsent(operation,result);if(existing!=null)return existing;step(operation,actor,result);return result.whenComplete((r,e)->running.remove(operation,result));}
    private void step(UUID operation,String actor,CompletableFuture<Record> result){if(closed.get()){result.completeExceptionally(new IllegalStateException("INV_PAUSED"));return;}app.submit(()->{
        return app.withAllProfiles(()->{var batch=repository.advance(operation,actor,false,now());for(var mutation:batch.mutations())committed(mutation,batch.operation().values().get("action"));return batch;});
    }).whenComplete((batch,error)->{if(error!=null){result.completeExceptionally(error);return;}if(Set.of("COMPLETED","CANCELLED").contains(batch.operation().status())){if(batch.operation().status().equals("COMPLETED")&&!batch.mutations().isEmpty())emit(new Committed(operation,"AdministrationBulkOperationCompleted",org.dextur.reputation.domain.investigation.InvestigationData.NONE,List.of()));result.complete(batch.operation());}else step(operation,actor,result);});}
    public CompletionStage<Record> cancel(UUID operation,String actor){return app.submit(()->repository.advance(operation,actor,true,now()).operation());}
    public AutoCloseable onCommitted(Consumer<Committed> listener){if(listeners.size()>=128||closed.get())throw new IllegalStateException("INV_CAPACITY");listeners.add(listener);return ()->listeners.remove(listener);}
    private void emit(Committed event){for(var listener:listeners)try{listener.accept(event);}catch(Throwable failure){diagnose(failure);}}
    private void diagnose(Throwable failure){try{errors.accept(failure);}catch(Throwable ignored){}}
    public Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    @Override public void close(){closed.set(true);tokens.clear();listeners.clear();}
}
