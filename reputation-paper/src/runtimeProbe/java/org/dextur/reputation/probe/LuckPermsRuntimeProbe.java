package org.dextur.reputation.probe;

import java.util.*;
import java.util.concurrent.*;
import net.luckperms.api.*;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.plugin.java.JavaPlugin;
import org.dextur.reputation.application.TierPermissionSync;
import org.dextur.reputation.api.*;

/** Loaded only with the complete real LuckPerms plugin. */
final class LuckPermsRuntimeProbe {
    private final JavaPlugin plugin;
    private final LuckPerms api=LuckPermsProvider.get();
    LuckPermsRuntimeProbe(JavaPlugin plugin){this.plugin=plugin;}
    CompletionStage<Void> verifyOnline(UUID target) {
        return CompletableFuture.allOf(api.getGroupManager().createAndLoadGroup("reputation_recognized"),api.getGroupManager().createAndLoadGroup("reputation_respected"),api.getGroupManager().createAndLoadGroup("reputation_suspicious"))
            .thenCompose(ignored->tick()).thenCompose(ignored->tick()).thenCompose(ignored->api.getUserManager().loadUser(target)).thenCompose(user->{
                verifyOwned(user,"reputation_respected");var result=new CompletableFuture<Void>();plugin.getServer().getScheduler().runTask(plugin,()->{
                    try {
                        var player=plugin.getServer().getPlayer(target);if(player==null)throw new AssertionError("Online LP subject missing");
                        var context=api.getContextManager().getContext(player);if(!context.contains("dexturreputation-owner","global"))throw new AssertionError("Managed LuckPerms context missing");
                        if(user.getInheritedGroups(api.getContextManager().getQueryOptions(player)).stream().noneMatch(g->g.getName().equals("reputation_respected")))throw new AssertionError("Managed group not effective online");
                        plugin.getLogger().info("PROBE_LUCKPERMS_ONLINE_PASS: owned group is effective in the real player's active context");result.complete(null);
                    }catch(Throwable failure){result.completeExceptionally(failure);}
                });return result;
            });
    }
    CompletionStage<Void> verify(UUID target) {
        CompletableFuture<?>[] groups=java.util.stream.Stream.of("reputation_recognized","reputation_respected","reputation_suspicious","phase4_staff","phase4_donor").map(name->api.getGroupManager().createAndLoadGroup(name)).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(groups).thenCompose(ignored->api.getUserManager().loadUser(target)).thenCompose(user->{
            String primary=user.getPrimaryGroup();
            return api.getUserManager().modifyUser(target,u->{u.data().add(InheritanceNode.builder("phase4_staff").build());u.data().add(InheritanceNode.builder("phase4_donor").build());u.data().add(InheritanceNode.builder("reputation_recognized").build());})
                .thenCompose(ignored->tick()).thenCompose(ignored->tick())
                .thenCompose(ignored->actors()).thenCompose(actors->{
                    var voting=plugin.getServer().getServicesManager().load(VotingService.class);
                    return voting.submit(vote(actors[1],target,VoteType.NEGATIVE)).thenCompose(receipt->{
                        if(!receipt.currentView().profile().tier().equals("RECOGNIZED"))throw new AssertionError("Offline LP downgrade tier");return tick();
                    }).thenCompose(ignored->tick()).thenCompose(ignored->api.getUserManager().loadUser(target)).thenCompose(downgraded->{
                        verifyOwned(downgraded,"reputation_recognized");return voting.submit(revoke(actors[1],target));
                    }).thenCompose(ignored->voting.submit(vote(actors[0],target,VoteType.POSITIVE))).thenCompose(ignored->voting.submit(revoke(actors[0],target)))
                    .thenCompose(ignored->tick()).thenCompose(ignored->tick());
                }).thenCompose(ignored->api.getUserManager().loadUser(target)).thenApply(updated->{
                    var nodes=updated.data().toCollection();
                    for(String group:List.of("phase4_staff","phase4_donor","reputation_recognized"))if(nodes.stream().noneMatch(n->n instanceof InheritanceNode i&&i.getGroupName().equals(group)&&n.getContexts().isEmpty()))throw new AssertionError("Unrelated LuckPerms membership removed: "+group);
                    if(!updated.getPrimaryGroup().equals(primary))throw new AssertionError("LuckPerms primary group changed");
                    if(nodes.stream().noneMatch(n->n instanceof InheritanceNode i&&i.getGroupName().equals("reputation_respected")&&n.getContexts().contains("dexturreputation-owner","global")))throw new AssertionError("Owned tier group missing");
                    verifyOwned(updated,"reputation_respected");
                    plugin.getLogger().info("PROBE_LUCKPERMS_PASS: real offline downgrade/upgrade, async save/retry, owned context, unrelated staff/donor/same-group nodes and primary group preserved");return (Void)null;
                });
        });
    }
    private CompletionStage<UUID[]> actors() {
        var result=new CompletableFuture<UUID[]>();plugin.getServer().getScheduler().runTask(plugin,()->result.complete(new UUID[]{plugin.getServer().getPlayerExact("Phase4A").getUniqueId(),plugin.getServer().getPlayerExact("Phase4B").getUniqueId()}));return result;
    }
    private static VoteRequest vote(UUID actor,UUID target,VoteType type){return new VoteRequest(UUID.randomUUID(),actor,target,VoteRequest.Action.SUBMIT,type,type==VoteType.POSITIVE?"helpful":"rude","");}
    private static VoteRequest revoke(UUID actor,UUID target){return new VoteRequest(UUID.randomUUID(),actor,target,VoteRequest.Action.REVOKE,null,"","");}
    private static void verifyOwned(net.luckperms.api.model.user.User user,String group){
        var owned=user.data().toCollection().stream().filter(n->n instanceof InheritanceNode&&n.getContexts().contains("dexturreputation-owner","global")).map(n->((InheritanceNode)n).getGroupName()).toList();if(!owned.equals(List.of(group)))throw new AssertionError("Incorrect owned groups: "+owned);
    }
    private CompletionStage<Void> tick() {
        try{var owner=plugin.getServer().getPluginManager().getPlugin("DexturReputation");var accessor=owner.getClass().getDeclaredMethod("permissionSynchronizer");accessor.setAccessible(true);var synchronizer=(TierPermissionSync)accessor.invoke(owner);if(synchronizer==null)throw new AssertionError("LuckPerms adapter absent");return synchronizer.tick();}catch(Exception e){return CompletableFuture.failedFuture(e);}
    }
}
