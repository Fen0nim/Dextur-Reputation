package org.dextur.reputation.application;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.*;
import org.dextur.reputation.domain.tier.*;

/** Receives only committed profiles, inside the application's per-player lock. */
public final class TierApplication implements TierService,AutoCloseable {
    private final ReputationApplication application;
    private final TierRepository repository;
    private final Settings settings;
    private final TierRules rules;
    private final DisplayFormatter formatter;
    private final Consumer<Throwable> errors;
    private final BoundedCache<UUID,ReputationTierSnapshot> cache;
    private final BoundedCache<UUID,Long> observed;
    private final CopyOnWriteArrayList<Consumer<TierChanged>> listeners=new CopyOnWriteArrayList<>();
    private volatile boolean stopped;
    public TierApplication(ReputationApplication application,TierRepository repository,Settings settings,TierRules rules,DisplayFormatter formatter,Consumer<Throwable> errors) {
        this.application=application;this.repository=repository;this.settings=settings;this.rules=rules;this.formatter=formatter;this.errors=errors;
        cache=new BoundedCache<>(settings.cacheSize(),Duration.ofSeconds(settings.cacheSeconds()));
        observed=new BoundedCache<>(settings.cacheSize(),Duration.ofDays(365));
        application.observeProfiles(this::observe);
    }
    private ReputationTierSnapshot dto(ReputationSnapshot profile,TierState state) {
        var tier=rules.byId(state.tier());if(tier==null) throw new IllegalStateException("Tier policy not reconciled");
        Long next=rules.definitions().stream().filter(d->d.enabled()&&d.threshold()>0&&d.threshold()>Math.max(profile.visibleScore(),tier.threshold())).map(TierRules.Definition::threshold).min(Long::compare).orElse(null);
        return new ReputationTierSnapshot(profile.playerId(),profile.scopeId(),tier.id(),profile.visibleScore(),state.revision(),profile.revision(),tier.names(),tier.color(),tier.prefix(),tier.suffix(),next,tier.priority());
    }
    private void observe(ReputationSnapshot profile,boolean committed) {
        if(stopped) return;
        try {
            var state=repository.tierState(profile.playerId(),profile.scopeId());if(state==null||state.profileRevision()!=profile.revision()||!state.tier().equals(profile.tier())) return;
            var tier=dto(profile,state);cache.put(profile.playerId(),tier);
            long previous=observed.get(profile.playerId()).orElse(0L);observed.put(profile.playerId(),state.revision());
            if(committed) for(var event:state.history()) if(event.revision()>previous&&event.profileRevision()==profile.revision()) {
                var change=new TierChanged(event.id(),tier,event.from(),event.notifyPlayer()&&rules.byId(tier.id()).notifications());
                for(var listener:listeners) try {listener.accept(change);}catch(Throwable error){errors.accept(error);}
            }
        } catch(Throwable error) {errors.accept(error);} // committed writes cannot become failures because an effect failed
    }
    @Override public CompletionStage<ReputationTierSnapshot> getTier(UUID player) {
        if(stopped) return CompletableFuture.failedFuture(new IllegalStateException("Tier service closed"));
        var hit=cachedTier(player);if(hit.isPresent()) return CompletableFuture.completedFuture(hit.get());
        return application.submit(()->application.withProfiles(player,player,()->{
            var profile=((ProfileRepository)repository).getOrCreate(player,settings.scope());application.updateProfile(profile,false);
            return cache.get(player).orElseThrow(()->new IllegalStateException("Tier unavailable"));
        }));
    }
    @Override public Optional<ReputationTierSnapshot> cachedTier(UUID player) {
        var cachedProfile=application.cachedProfile(player);
        if(stopped||cachedProfile.isEmpty()) return Optional.empty();
        var profile=cachedProfile.get();return cache.get(player).filter(t->t.profileRevision()==profile.revision()&&t.id().equals(profile.tier()));
    }
    @Override public String format(UUID player,String language) {return cachedTier(player).map(t->DisplayFormatter.mini(formatter.score(t,PlayerLanguage.parse(language)))).orElse("");}
    @Override public AutoCloseable onTierChanged(Consumer<TierChanged> listener) {if(stopped) throw new IllegalStateException("Tier service closed");if(listeners.size()>=128)throw new IllegalStateException("Tier listener limit");listeners.add(Objects.requireNonNull(listener));return ()->listeners.remove(listener);}
    public void forget(UUID player) {cache.remove(player);}
    @Override public void close() {stopped=true;listeners.clear();cache.clear();observed.clear();}
}
