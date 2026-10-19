package org.dextur.reputation.paper;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.luckperms.api.*;
import net.luckperms.api.context.*;
import net.luckperms.api.model.group.*;
import net.luckperms.api.model.user.*;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.node.*;
import net.luckperms.api.node.types.*;
import org.dextur.reputation.domain.tier.TierState;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LuckPermsBridgeTest {
    LuckPerms api;GroupManager groups;UserManager users;User user;NodeMap data;List<Node> nodes;InheritanceNode managed,staff,donor;InheritanceNode.Builder builder;
    @BeforeEach void setup() {
        api=mock(LuckPerms.class);groups=mock(GroupManager.class);users=mock(UserManager.class);user=mock(User.class);data=mock(NodeMap.class);when(api.getGroupManager()).thenReturn(groups);when(api.getUserManager()).thenReturn(users);when(api.getContextManager()).thenReturn(mock(ContextManager.class));when(user.data()).thenReturn(data);
        managed=node(true,"reputation_old");staff=node(false,"staff");donor=node(false,"donor");nodes=new ArrayList<>(List.of(managed,staff,donor));
        doAnswer(i->{nodes.removeIf(i.getArgument(0));return null;}).when(data).clear(any(Predicate.class));
        doAnswer(i->{if(!nodes.contains(i.getArgument(0)))nodes.add(i.getArgument(0));return null;}).when(data).add(any());
        when(users.modifyUser(any(),any())).thenAnswer(i->{((Consumer<User>)i.getArgument(1)).accept(user);return CompletableFuture.completedFuture(null);});
        var registry=mock(NodeBuilderRegistry.class);when(api.getNodeBuilderRegistry()).thenReturn(registry);builder=mock(InheritanceNode.Builder.class,RETURNS_SELF);when(registry.forInheritance()).thenReturn(builder);var next=node(true,"reputation_new");when(builder.build()).thenReturn(next);
    }
    private InheritanceNode node(boolean owned,String group) {var node=mock(InheritanceNode.class);when(node.getGroupName()).thenReturn(group);var context=mock(ImmutableContextSet.class);when(context.size()).thenReturn(owned?1:0);when(context.contains(LuckPermsBridge.OWNER,"global")).thenReturn(owned);when(node.getContexts()).thenReturn(context);return node;}
    private TierState state(String group){return new TierState(UUID.randomUUID(),"global","RECOGNIZED","a".repeat(64),1,1,List.of(),group,1,0,List.of("reputation_old"));}
    private Group safeGroup(){var group=mock(Group.class);when(group.getName()).thenReturn("reputation_new");when(group.data()).thenReturn(mock(NodeMap.class));when(group.data().toCollection()).thenReturn(List.of());return group;}
    @Test void upgradeDowngradeAndOfflineSynchronizationPreserveUnrelatedNodesAndPrimaryGroup() throws Exception {
        var group=safeGroup();when(groups.loadGroup("reputation_new")).thenReturn(CompletableFuture.completedFuture(Optional.of(group)));
        try(var bridge=new LuckPermsBridge(api,"global",false)) {
            var state=state("reputation_new");assertEquals(List.of("reputation_new"),bridge.synchronize(state).toCompletableFuture().get());assertFalse(nodes.contains(managed));assertTrue(nodes.contains(staff));assertTrue(nodes.contains(donor));assertEquals(3,nodes.size());
            bridge.synchronize(state).toCompletableFuture().get();assertEquals(3,nodes.size());bridge.synchronize(state("")).toCompletableFuture().get();assertEquals(List.of(staff,donor),nodes);verify(user,never()).setPrimaryGroup(any());
        }verify(api.getContextManager()).unregisterCalculator(any());
    }
    @Test void unsafePermissionGroupsAndMissingGroupsAreRejectedWithoutCreation() throws Exception {
        var group=safeGroup();var permission=mock(PermissionNode.class);when(permission.getKey()).thenReturn("minecraft.command.op");when(group.data().toCollection()).thenReturn(List.of(permission));when(groups.loadGroup("reputation_new")).thenReturn(CompletableFuture.completedFuture(Optional.of(group)));
        try(var bridge=new LuckPermsBridge(api,"global",false)){assertThrows(Exception.class,()->bridge.synchronize(state("reputation_new")).toCompletableFuture().get());verify(users,never()).modifyUser(any(),any());when(groups.loadGroup("reputation_new")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));assertThrows(Exception.class,()->bridge.synchronize(state("reputation_new")).toCompletableFuture().get());verify(groups,never()).createAndLoadGroup(any());}
    }
    @Test void explicitSafeCreationAndFailureRetry() throws Exception {
        when(groups.loadGroup("reputation_new")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));var group=safeGroup();when(groups.createAndLoadGroup("reputation_new")).thenReturn(CompletableFuture.completedFuture(group));
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("simulated failure")),CompletableFuture.completedFuture(null)).when(users).modifyUser(any(),any());
        try(var bridge=new LuckPermsBridge(api,"global",true)){var state=state("reputation_new");assertThrows(Exception.class,()->bridge.synchronize(state).toCompletableFuture().get());assertEquals(List.of("reputation_new"),bridge.synchronize(state).toCompletableFuture().get());}verify(groups,atLeastOnce()).createAndLoadGroup("reputation_new");
    }
}
