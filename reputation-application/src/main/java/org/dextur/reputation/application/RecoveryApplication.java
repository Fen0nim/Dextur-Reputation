package org.dextur.reputation.application;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.recovery.*;
import org.dextur.reputation.domain.recovery.RecoveryData.*;

/** Shares the bounded database executor and profile locks with voting/tier services. */
public final class RecoveryApplication implements RecoveryService,AutoCloseable {
    private static final UUID ZERO=new UUID(0,0);
    private final ReputationApplication app;private final RecoveryRepository repository;private final Settings settings;private final RecoveryRules rules;private final Function<UUID,CompletionStage<Facts>> facts;private final Clock clock;private final Consumer<Throwable> errors;
    private final BoundedCache<UUID,RecoverySnapshot> cache;private final CopyOnWriteArrayList<Consumer<Changed>> listeners=new CopyOnWriteArrayList<>();private final ConcurrentHashMap<String,Handle> providers=new ConcurrentHashMap<>();private final ConcurrentHashMap<String,RewardProvider> rewardProviders=new ConcurrentHashMap<>();private final Semaphore rewardCallbacks=new Semaphore(128);private final Semaphore admission;private volatile boolean closed;
    public RecoveryApplication(ReputationApplication app,RecoveryRepository repository,Settings settings,RecoveryRules rules,Function<UUID,CompletionStage<Facts>> facts,Clock clock,Consumer<Throwable> errors){this.app=app;this.repository=repository;this.settings=settings;this.rules=rules;this.facts=facts;this.clock=clock;this.errors=errors;cache=new BoundedCache<>(settings.cacheSize(),Duration.ofSeconds(settings.cacheSeconds()));admission=new Semaphore(settings.queueSize());}
    @Override public CompletionStage<RecoverySnapshot> status(UUID id){return action(id,Command.READ,"PLAYER","");}
    @Override public CompletionStage<RecoverySnapshot> start(UUID id){return action(id,Command.START,"PLAYER","");}
    @Override public CompletionStage<RecoverySnapshot> cancel(UUID id){return action(id,Command.CANCEL,"PLAYER","");}
    /** Internal administrative capability; not part of the public player-facing service. */
    public CompletionStage<RecoverySnapshot> administer(UUID id,Command command,String actor,String reason){if(!Set.of(Command.START,Command.READ,Command.PAUSE,Command.RESUME,Command.RESET_STAGE,Command.CANCEL,Command.LOCK,Command.UNLOCK).contains(command))return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid recovery administration"));return action(id,command,actor,reason);}
    private CompletionStage<RecoverySnapshot> action(UUID id,Command command,String actor,String reason){return run(id,()->facts.apply(id),f->request(id,command,f,ZERO,0,0,false,ZERO,"",0,"",actor,reason));}
    public CompletionStage<RecoverySnapshot> sample(UUID id,Facts facts,UUID session,long connected,long active,boolean login){return run(id,()->CompletableFuture.completedFuture(facts),f->request(id,Command.SAMPLE,f,session,connected,active,login,ZERO,"",0,"","SERVER",""));}
    private Request request(UUID id,Command command,Facts f,UUID session,long connected,long active,boolean login,UUID enrollment,String objective,long progress,String provider,String actor,String reason){return new Request(UUID.randomUUID(),id,settings.scope(),command,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),f,session,connected,active,login,enrollment,objective,progress,provider,actor,reason,available());}
    private Set<String> available(){return available(rules);}
    private Set<String> available(RecoveryRules definition){var set=new HashSet<String>();for(var h:providers.values())if(h.active&&h.provider.available()){boolean valid=true;for(var stage:definition.program().stages())for(var o:stage.objectives())if(o.type()==RecoveryRules.Type.EXTERNAL&&o.provider().equals(h.provider.id())){try{if(o.providerVersion()!=h.provider.modelVersion())valid=false;else h.provider.validate(o.id(),o.configuration());}catch(RuntimeException ex){valid=false;errors.accept(ex);}}if(valid)set.add(h.provider.id()+"@"+h.provider.modelVersion());}return Set.copyOf(set);}
    private CompletionStage<RecoverySnapshot> run(UUID id,Supplier<CompletionStage<Facts>> source,Function<Facts,Request> request){
        Objects.requireNonNull(id);if(closed||!admission.tryAcquire())return CompletableFuture.failedFuture(new RejectedExecutionException("Recovery unavailable"));
        CompletableFuture<RecoverySnapshot> internal;try{internal=source.get().thenCompose(f->app.submit(()->app.withProfiles(id,id,()->{if(closed)throw new IllegalStateException("Recovery closed");var base=request.apply(f);var definition=repository.recoveryDefinition(id,settings.scope());var accepted=available(definition==null?rules:definition);var validated=new Request(base.operation(),base.player(),base.scope(),base.command(),base.now(),base.facts(),base.session(),base.connected(),base.active(),base.login(),base.enrollment(),base.objective(),base.progress(),base.provider(),base.actor(),base.reason(),accepted);
            var result=repository.recover(validated);app.updateProfile(result.profile(),result.events().stream().anyMatch(event->event.kind()==Kind.REWARD_GRANTED));cache.put(id,result.snapshot());for(var event:result.events()){
                if(event.kind()==Kind.REWARD_GRANTED)for(var provider:rewardProviders.values()){
                    var reward=result.snapshot().rewards().stream().filter(v->v.stageId().equals(event.stageId())).findFirst().orElseThrow();
                    boolean permit=false;try{if(provider.available()){permit=rewardCallbacks.tryAcquire();if(!permit){errors.accept(new RejectedExecutionException("Reward extension saturated"));continue;}Objects.requireNonNull(provider.granted(result.snapshot(),reward)).whenComplete((v,error)->{rewardCallbacks.release();if(error!=null)errors.accept(error);});permit=false;}}catch(RuntimeException error){if(permit)rewardCallbacks.release();errors.accept(error);}
                }
                if(event.kind()==Kind.PROGRAM_CANCELLED)for(var provider:providers.values())try{provider.provider.cancelled(UUID.fromString(event.snapshot().enrollmentId()));}catch(RuntimeException ex){errors.accept(ex);}
                for(var listener:listeners)try{listener.accept(event);}catch(RuntimeException ex){errors.accept(ex);}
            }return result.snapshot();}))).toCompletableFuture();}catch(Throwable ex){admission.release();return CompletableFuture.failedFuture(ex);}
        internal.whenComplete((v,e)->admission.release());var caller=new CompletableFuture<RecoverySnapshot>();internal.whenComplete((v,e)->{if(e==null)caller.complete(v);else caller.completeExceptionally(e);});return caller;
    }
    @Override public Optional<RecoverySnapshot> cached(UUID id){return closed?Optional.empty():cache.get(id);}
    public void forget(UUID id){cache.remove(id);}
    @Override public synchronized AutoCloseable onChanged(Consumer<Changed> listener){if(closed||listeners.size()>=128)throw new IllegalStateException("Recovery listener limit");listeners.add(Objects.requireNonNull(listener));return ()->listeners.remove(listener);}
    @Override public synchronized ProviderHandle registerProvider(ObjectiveProvider provider){Objects.requireNonNull(provider);RecoveryRules.identifier(provider.id());if(closed||provider.modelVersion()<1||providers.size()>=64)throw new IllegalStateException("Recovery provider unavailable");for(var s:rules.program().stages())for(var o:s.objectives())if(o.type()==RecoveryRules.Type.EXTERNAL&&o.provider().equals(provider.id())){if(o.providerVersion()!=provider.modelVersion())throw new IllegalArgumentException("Provider version mismatch");provider.validate(o.id(),o.configuration());}var handle=new Handle(provider);if(providers.putIfAbsent(provider.id(),handle)!=null)throw new IllegalArgumentException("Duplicate provider");return handle;}
    @Override public synchronized AutoCloseable registerRewardProvider(RewardProvider provider){Objects.requireNonNull(provider);RecoveryRules.identifier(provider.id());if(closed||provider.modelVersion()<1||rewardProviders.size()>=64)throw new IllegalStateException("Reward provider limit");if(rewardProviders.putIfAbsent(provider.id(),provider)!=null)throw new IllegalArgumentException("Duplicate reward provider");return ()->rewardProviders.remove(provider.id(),provider);}
    private final class Handle implements ProviderHandle {
        final ObjectiveProvider provider;volatile boolean active=true;Handle(ObjectiveProvider provider){this.provider=provider;}
        @Override public CompletionStage<RecoverySnapshot> submit(UUID operation,UUID player,UUID enrollment,String objective,long progress){if(!active||closed)return CompletableFuture.failedFuture(new IllegalStateException("Provider closed"));return run(player,()->facts.apply(player),f->{if(!active||!provider.available())throw new IllegalStateException("Provider unavailable");var base=request(player,Command.EXTERNAL,f,ZERO,0,0,false,enrollment,objective,progress,provider.id(),"PROVIDER:"+provider.id(),"");return new Request(operation,base.player(),base.scope(),base.command(),base.now(),base.facts(),base.session(),0,0,false,enrollment,objective,progress,base.provider(),base.actor(),"",base.availableProviders());});}
        @Override public void close(){active=false;providers.remove(provider.id(),this);}
    }
    @Override public synchronized void close(){closed=true;providers.values().forEach(Handle::close);rewardProviders.clear();listeners.clear();cache.clear();}
}
