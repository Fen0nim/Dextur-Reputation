package org.dextur.reputation.paper;

import java.util.*;
import java.util.concurrent.*;
import net.luckperms.api.*;
import net.luckperms.api.context.*;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.types.InheritanceNode;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.tier.TierState;

/** Optional class: loaded only when LuckPerms is enabled. Owned nodes carry a reserved context. */
final class LuckPermsBridge implements TierPermissionSync.Permissions {
    static final String OWNER="dexturreputation-owner";
    private final LuckPerms api;
    private final String scope;
    private final boolean create;
    private final ContextCalculator<org.bukkit.entity.Player> calculator;
    private volatile boolean stopped;
    LuckPermsBridge(LuckPerms api,String scope,boolean create) {
        this.api=api;this.scope=scope;this.create=create;
        calculator=(player,consumer)->consumer.accept(OWNER,scope);api.getContextManager().registerCalculator(calculator);
    }
    static boolean safe(Group group) {
        return group.getName().matches("reputation_[a-z0-9_]{1,48}")&&group.data().toCollection().stream().allMatch(node->
            node instanceof net.luckperms.api.node.types.PrefixNode||node instanceof net.luckperms.api.node.types.SuffixNode||node instanceof net.luckperms.api.node.types.WeightNode||node instanceof net.luckperms.api.node.types.MetaNode);
    }
    private CompletionStage<Group> group(String name) {
        if(name.isEmpty())return CompletableFuture.completedFuture(null);
        if(!name.matches("reputation_[a-z0-9_]{1,48}"))return CompletableFuture.failedFuture(new IllegalArgumentException("Unsafe reputation group name"));
        return api.getGroupManager().loadGroup(name).thenCompose(found->found.isPresent()?CompletableFuture.completedFuture(found.get()):
            create?api.getGroupManager().createAndLoadGroup(name):CompletableFuture.failedFuture(new IllegalStateException("Missing reputation group: "+name)))
            .thenApply(group->{if(!safe(group))throw new IllegalStateException("Reputation groups must contain cosmetic nodes only: "+name);return group;});
    }
    @Override public CompletionStage<List<String>> synchronize(TierState state) {
        if(stopped)return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms adapter closed"));
        return group(state.desiredGroup()).thenCompose(group->{
            if(stopped)return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms adapter closed"));
            return api.getUserManager().modifyUser(state.player(),user->{
                if(stopped)throw new IllegalStateException("LuckPerms adapter closed");
                user.data().clear(node->node instanceof InheritanceNode inherited&&inherited.getGroupName().matches("reputation_[a-z0-9_]{1,48}")&&node.getContexts().size()==1&&node.getContexts().contains(OWNER,scope));
                if(group!=null)user.data().add(api.getNodeBuilderRegistry().forInheritance().group(group).withContext(OWNER,scope).build());
            }).thenApply(ignored->group==null?List.<String>of():List.of(group.getName()));
        });
    }
    @Override public void close() {stopped=true;api.getContextManager().unregisterCalculator(calculator);}
}
