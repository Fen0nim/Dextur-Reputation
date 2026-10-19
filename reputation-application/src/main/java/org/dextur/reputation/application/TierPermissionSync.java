package org.dextur.reputation.application;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.dextur.reputation.domain.tier.*;

/** One bounded sequential batch, including offline players. Durable intent survives failed effects. */
public final class TierPermissionSync implements AutoCloseable {
    public interface Permissions extends AutoCloseable { CompletionStage<List<String>> synchronize(TierState state); }
    private final ReputationApplication application;
    private final TierRepository repository;
    private final String scope;
    private final int batch;
    private final Permissions permissions;
    private final Consumer<Throwable> errors;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile boolean stopped;
    private String cursor="";
    private CompletableFuture<Void> current=CompletableFuture.completedFuture(null);
    public TierPermissionSync(ReputationApplication application,TierRepository repository,String scope,int batch,Permissions permissions,Consumer<Throwable> errors) {
        this.application=application;this.repository=repository;this.scope=scope;this.batch=batch;this.permissions=permissions;this.errors=errors;
    }
    public synchronized CompletionStage<Void> tick() {
        if(stopped)return CompletableFuture.completedFuture(null);
        if(!running.compareAndSet(false,true))return current;
        var result=new CompletableFuture<Void>();current=result;
        application.submit(()->{
            var rows=repository.pendingTierSyncAfter(scope,cursor,batch);
            if(rows.isEmpty()&&!cursor.isEmpty()){cursor="";rows=repository.pendingTierSyncAfter(scope,cursor,batch);}
            if(!rows.isEmpty())cursor=rows.getLast().player().toString();return rows;
        }).thenCompose(rows->{
            CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
            for(var state:rows) chain=chain.thenCompose(ignored->{
                if(stopped)return CompletableFuture.completedFuture(null);
                return permissions.synchronize(state).toCompletableFuture().thenCompose(owned->application.submit(()->{
                    if(!stopped)repository.tierSynced(state.player(),scope,state.syncRevision(),owned);return (Void)null;
                })).exceptionally(error->{errors.accept(error);return null;});
            });return chain;
        }).whenComplete((ignored,error)->{running.set(false);if(error!=null){errors.accept(error);result.completeExceptionally(error);}else result.complete(null);});
        return result;
    }
    @Override public void close() {stopped=true;try{permissions.close();}catch(Exception error){errors.accept(error);}}
}
