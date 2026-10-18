package org.dextur.reputation.probe;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.dextur.reputation.api.*;
import net.kyori.adventure.text.Component;

/** Exercises production VotingService, native Player commands and real inventory events. */
final class VotingRuntimeProbe {
    private final JavaPlugin plugin;
    private final VotingService voting;
    private final List<VotingService.VoteCommitted> events=new CopyOnWriteArrayList<>();
    private Player voter;
    private UUID target;
    private long baseline;
    private int run;
    private AutoCloseable subscription;
    VotingRuntimeProbe(JavaPlugin plugin) { this.plugin=plugin;this.voting=plugin.getServer().getServicesManager().load(VotingService.class); }
    void start(Runnable success,Consumer<Throwable> failed) {
        if(voting==null) { failed.accept(new AssertionError("VotingService absent"));return; }
        Path state=plugin.getDataFolder().toPath().resolve("voting-runs.txt");
        try { run=Files.exists(state)?Integer.parseInt(Files.readString(state))+1:1;subscription=voting.onCommitted(events::add); }
        catch(Exception error) { failed.accept(error);return; }
        waitFor(()->onMain(()->{
            voter=plugin.getServer().getPlayerExact("Phase2Voter");var player=plugin.getServer().getPlayerExact("Phase2Target");
            if(voter==null||player==null) return false;
            target=player.getUniqueId();player.kick(Component.text("QA: test known offline target"));return true;
        }),Boolean::booleanValue,0)
        .thenCompose(ignored->waitFor(()->onMain(()->plugin.getServer().getPlayer(target)==null),Boolean::booleanValue,0))
        .thenCompose(ignored->voting.history(target,VotePage.Direction.RECEIVED,1))
        .thenCompose(page->{
            baseline=page.totalEntries();check(baseline==(long)(run-1)*5,"Journal did not survive restart");
            return voting.view(target);
        }).thenCompose(view->{
            check(view.profile().visibleScore()==0,"Revoked score did not survive restart");
            return onMain(()->{
                check(plugin.getServer().getOfflinePlayer(target).hasPlayedBefore(),"Target is not known offline");
                voter.performCommand("rep language en_US");
                voter.performCommand("like Phase2Target helpful run"+run);return null;
            });
        }).thenCompose(ignored->waitHistory(baseline+1))
        .thenCompose(page->{
            check(page.entries().getFirst().active(),"Cast not active");var e=page.entries().getFirst().entry();
            return voting.submit(new VoteRequest(e.operationId(),e.voterId(),e.targetId(),VoteRequest.Action.SUBMIT,e.type(),e.reason(),e.comment()));
        }).thenCompose(receipt->{
            check(receipt.duplicate()&&receipt.currentView().profile().visibleScore()==1,"Duplicate applied twice");
            return rejected(voting.submit(new VoteRequest(receipt.entry().operationId(),voter.getUniqueId(),target,VoteRequest.Action.SUBMIT,VoteType.NEGATIVE,"rude","changed")),VoteRejectedException.Code.ID_CONFLICT);
        }).thenCompose(ignored->{
            var request=new VoteRequest(UUID.randomUUID(),voter.getUniqueId(),target,VoteRequest.Action.SUBMIT,VoteType.NEGATIVE,"rude","parallel"+run);
            List<CompletableFuture<VoteReceipt>> calls=new ArrayList<>();for(int i=0;i<12;i++) calls.add(voting.submit(request).toCompletableFuture());
            return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).thenApply(done->{
                check(calls.stream().map(CompletableFuture::join).filter(r->!r.duplicate()).count()==1,"Concurrent request duplicated");return null;
            });
        }).thenCompose(ignored->voting.view(target)).thenCompose(view->{
            check(view.profile().visibleScore()==-1&&view.statistics().negative()==1,"Replacement calculation incorrect");
            return onMain(()->{ voter.performCommand("rep revoke Phase2Target runtime revoke");return null; });
        }).thenCompose(ignored->waitHistory(baseline+3))
        .thenCompose(ignored->onMain(()->{
            voter.performCommand("like Phase2Voter helpful");voter.performCommand("like zzz_unknown_741 helpful");
            voter.performCommand("rep history");voter.performCommand("rep given");voter.performCommand("rep reasons");
            var command=plugin.getServer().getPluginCommand("like");
            check(command.tabComplete(voter,"like",new String[]{"Phase2Target","he"}).contains("helpful"),"Reason completion absent");
            voter.performCommand("rep gui Phase2Target");return null;
        })).thenCompose(ignored->waitFor(()->onMain(()->isGui()&&voter.getOpenInventory().getTopInventory().getItem(38)!=null),Boolean::booleanValue,0))
        .thenCompose(ignored->onMain(()->{
            // Shift transfers, number keys, double clicks and bottom slots are all cancelled.
            for(var type:List.of(ClickType.SHIFT_LEFT,ClickType.NUMBER_KEY,ClickType.DOUBLE_CLICK)) click(38,type);
            click(55,ClickType.LEFT);click(38,ClickType.LEFT);click(38,ClickType.LEFT);return null;
        })).thenCompose(ignored->waitFor(()->onMain(()->isGui()&&voter.getOpenInventory().getTopInventory().getItem(0)!=null),Boolean::booleanValue,0))
        .thenCompose(ignored->onMain(()->{ click(0,ClickType.LEFT);click(0,ClickType.LEFT);return null; }))
        .thenCompose(ignored->waitHistory(baseline+4))
        .thenCompose(ignored->voting.view(target)).thenCompose(view->{
            check(view.profile().visibleScore()==1&&view.statistics().positive()==1,"GUI vote failed");
            return onMain(()->{
                var papi=plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
                if(papi!=null&&papi.isEnabled()) {
                    var manager=papi.getClass().getMethod("getLocalExpansionManager").invoke(papi);
                    var expansion=manager.getClass().getMethod("getExpansion",String.class).invoke(manager,"dexturreputation");
                    var request=expansion.getClass().getSuperclass().getMethod("onRequest",org.bukkit.OfflinePlayer.class,String.class);
                    check("1".equals(request.invoke(expansion,plugin.getServer().getOfflinePlayer(target),"score")),"Placeholder cache stale after vote");
                }
                voter.closeInventory();voter.performCommand("rep gui Phase2Target");voter.closeInventory();
                var attachment=voter.addAttachment(plugin,"dexturreputation.like",false);return attachment;
            });
        }).thenCompose(attachment->rejected(voting.submit(new VoteRequest(UUID.randomUUID(),voter.getUniqueId(),target,VoteRequest.Action.SUBMIT,VoteType.POSITIVE,"helpful","denied")),VoteRejectedException.Code.PERMISSION)
                .thenCompose(ignored->onMain(()->{ voter.removeAttachment(attachment);check(!isGui(),"Closed GUI reopened by stale callback");return null; })))
        .thenCompose(ignored->voting.submit(new VoteRequest(UUID.randomUUID(),voter.getUniqueId(),target,VoteRequest.Action.REVOKE,null,"","final revoke")))
        .thenCompose(receipt->{
            check(receipt.currentView().profile().visibleScore()==0,"Revocation failed");
            check(receipt.currentView().statistics().equals(new VoteStatistics(0,0,1,baseline+5)),"Final statistics incorrect");
            check(events.size()==5,"Events emitted for duplicate or rejected operation");
            return voting.history(voter.getUniqueId(),VotePage.Direction.GIVEN,1);
        }).whenComplete((page,error)->onMain(()->{
            try {
                subscription.close();
                if(error!=null) { failed.accept(error);return null; }
                check(page.totalEntries()==baseline+5,"Given history mismatch");Files.writeString(state,Integer.toString(run));
                plugin.getLogger().info("PROBE_VOTING_PASS: native player, offline target, commands, permissions, idempotency, 12 concurrent retries, replacement/revocation, GUI events, cache, history, restart, five committed events");
                voter.performCommand("rep gui Phase2Target");
                check(isGui(),"Shutdown GUI was not opened");
                success.run();
            } catch(Throwable failure) { failed.accept(failure); }
            return null;
        }));
    }
    private boolean isGui() { return voter.getOpenInventory().getTopInventory().getHolder()!=null&&voter.getOpenInventory().getTopInventory().getHolder().getClass().getName().endsWith("VotingGui$Session"); }
    private void click(int slot,ClickType type) {
        InventoryView view=voter.getOpenInventory();var event=new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,slot,type,InventoryAction.PICKUP_ALL);
        plugin.getServer().getPluginManager().callEvent(event);check(event.isCancelled(),"GUI event allowed item transfer");
    }
    private CompletionStage<VotePage> waitHistory(long count) { return waitFor(()->voting.history(target,VotePage.Direction.RECEIVED,1),p->p.totalEntries()==count,0); }
    private <T> CompletionStage<T> waitFor(Supplier<CompletionStage<T>> read,Predicate<T> predicate,int attempt) {
        return read.get().thenCompose(value->{
            if(predicate.test(value)) return CompletableFuture.completedFuture(value);
            if(attempt>100) return CompletableFuture.failedFuture(new AssertionError("Runtime condition timed out"));
            CompletableFuture<T> result=new CompletableFuture<>();
            plugin.getServer().getScheduler().runTaskLater(plugin,()->waitFor(read,predicate,attempt+1).whenComplete((next,error)->{
                if(error!=null) result.completeExceptionally(error);else result.complete(next);
            }),2L);return result;
        });
    }
    private <T> CompletionStage<T> onMain(Callable<T> action) {
        var result=new CompletableFuture<T>();
        plugin.getServer().getScheduler().runTask(plugin,()->{try { result.complete(action.call()); }catch(Throwable error) { result.completeExceptionally(error); }});return result;
    }
    private CompletionStage<Void> rejected(CompletionStage<?> stage,VoteRejectedException.Code code) {
        return stage.handle((value,error)->{
            Throwable cause=error;while(cause!=null&&cause.getCause()!=null) cause=cause.getCause();
            check(cause instanceof VoteRejectedException rejection&&rejection.code()==code,"Expected rejection "+code+", got "+cause);return null;
        });
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
