package org.dextur.reputation.paper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.policy.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminGuiUxTest {
    @TempDir Path directory;
    DexturReputationPlugin plugin;InvestigationApplication app;PolicyApplication policies;Player player;InvestigationGui gui;
    UUID owner=UUID.randomUUID(),target=UUID.randomUUID(),vote=UUID.randomUUID();
    final AtomicReference<Inventory> top=new AtomicReference<>();final Map<Inventory,Map<Integer,ItemStack>> contents=new IdentityHashMap<>();
    final AtomicReference<String> fingerprint=new AtomicReference<>("original");
    MockedStatic<Bukkit> bukkit;MockedConstruction<ItemStack> stacks;InventoryView view;
    @BeforeEach void setup()throws Exception {
        plugin=mock(DexturReputationPlugin.class);var server=mock(Server.class);when(plugin.getServer()).thenReturn(server);when(plugin.ready()).thenReturn(true);
        doAnswer(i->{i.<Runnable>getArgument(0).run();return null;}).when(plugin).onMain(any());
        var known=mock(OfflinePlayer.class);when(known.getName()).thenReturn("Target");when(server.getOfflinePlayer(any(UUID.class))).thenReturn(known);when(plugin.tierLabel(anyString(),any())).thenReturn("Нейтральный");
        player=mock(Player.class);when(player.isOnline()).thenReturn(true);when(player.getUniqueId()).thenReturn(owner);when(player.hasPermission(anyString())).thenReturn(true);
        view=mock(InventoryView.class);when(view.getTopInventory()).thenAnswer(i->top.get());when(player.getOpenInventory()).thenReturn(view);
        when(player.openInventory(any(Inventory.class))).thenAnswer(i->{top.set(i.getArgument(0));return view;});
        bukkit=mockStatic(Bukkit.class);bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),anyInt(),any(Component.class))).thenAnswer(i->{
            var inventory=mock(Inventory.class);InventoryHolder holder=i.getArgument(0);int size=i.getArgument(1);var slots=new HashMap<Integer,ItemStack>();contents.put(inventory,slots);
            when(inventory.getHolder()).thenReturn(holder);when(inventory.getSize()).thenReturn(size);when(inventory.getItem(anyInt())).thenAnswer(x->slots.get(x.getArgument(0)));
            doAnswer(x->{slots.put(x.getArgument(0),x.getArgument(1));return null;}).when(inventory).setItem(anyInt(),any());doAnswer(x->{slots.clear();return null;}).when(inventory).clear();return inventory;
        });
        stacks=mockConstruction(ItemStack.class,(stack,context)->{
            when(stack.getType()).thenReturn((Material)context.arguments().getFirst());var meta=mock(ItemMeta.class);when(stack.getItemMeta()).thenReturn(meta);
            var title=new AtomicReference<Component>();var lore=new AtomicReference<List<Component>>(List.of());
            doAnswer(i->{title.set(i.getArgument(0));return null;}).when(meta).displayName(any());when(meta.displayName()).thenAnswer(i->title.get());
            doAnswer(i->{lore.set(i.getArgument(0));return null;}).when(meta).lore(any());when(meta.lore()).thenAnswer(i->lore.get());
        });
        var profiles=mock(ReputationApplication.class);when(profiles.cachedLanguage(any())).thenReturn(PlayerLanguage.ru_RU);var settings=mock(Settings.class);when(settings.defaultLanguage()).thenReturn(PlayerLanguage.ru_RU);when(settings.scope()).thenReturn("global");
        policies=mock(PolicyApplication.class);when(policies.administration(any())).thenReturn(CompletableFuture.completedFuture(AdministrationData.State.empty(target,"global")));
        when(policies.evaluate(any(),eq("reputation.vote"))).thenReturn(CompletableFuture.completedFuture(new org.dextur.reputation.api.ReputationPolicyResult(target,"reputation.vote",org.dextur.reputation.api.ReputationPolicyResult.Decision.ALLOW,100,"NEUTRAL","",null,java.math.BigDecimal.ONE,null,"policy-reason-configured",new org.dextur.reputation.api.ReputationPolicyResult.Revision(1,0,0,"rules"),Instant.EPOCH,true)));
        when(policies.configuredVoting(any())).thenAnswer(i->policies.evaluate(i.getArgument(0),"reputation.vote"));
        when(policies.administer(any())).thenReturn(new CompletableFuture<>());
        var messages=Messages.bundled(PlayerLanguage.ru_RU);var previous=new PolicyCommands(plugin,profiles,policies,settings,messages);
        app=mock(InvestigationApplication.class);when(app.rules()).thenReturn(AdministrationConfiguration.load(directory));when(app.now()).thenReturn(Instant.parse("2026-10-21T12:00:00Z"));
        when(app.read(any())).thenAnswer(i->{Query q=i.getArgument(0);var summary=Map.of("visible","100","tier","NEUTRAL","trust","0.50","flags","0","recovery","AVAILABLE","revision","1");
            var rows=q.section()==Section.PENDING?List.of(new Row(vote,Map.of("voter",owner.toString(),"target",target.toString(),"type","POSITIVE","reason","help","status","PENDING","revision","1"))):List.<Row>of();return CompletableFuture.completedFuture(new Report(summary,rows,rows.size(),fingerprint.get()));});
        when(app.mutate(any())).thenReturn(new CompletableFuture<>());
        var commands=new InvestigationCommands(plugin,profiles,app,settings,messages,previous);
        gui=new InvestigationGui(plugin,app,commands,messages,new InvestigationGui.Layout(54,Material.BOOK,Material.RED_CONCRETE,java.util.stream.IntStream.range(18,32).boxed().toList(),java.util.stream.IntStream.range(0,10).boxed().toList(),List.of(36,37,38,39),45,53,49,50,40,42));
    }
    @AfterEach void close(){if(stacks!=null)stacks.close();if(bukkit!=null)bukkit.close();}
    InvestigationGui.Session session(){return (InvestigationGui.Session)top.get().getHolder();}
    void click(int slot){var s=session();s.lastClick=0;var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(ClickType.LEFT);when(event.getRawSlot()).thenReturn(slot);gui.click(event);verify(event).setCancelled(true);}
    @Test void overviewUsesDistinctIconsShortHumanLoreAndClickableNavigation(){
        gui.open(player,target,Section.INSPECT,1);var slots=contents.get(top.get());assertTrue(slots.values().stream().map(ItemStack::getType).distinct().count()>5);
        for(var item:slots.values()){assertNotNull(item.getItemMeta().displayName());assertFalse(item.getItemMeta().lore().isEmpty());assertTrue(item.getItemMeta().lore().size()<=7);String text=item.getItemMeta().lore().stream().map(PlainTextComponentSerializer.plainText()::serialize).collect(java.util.stream.Collectors.joining());assertFalse(text.contains(target.toString()));assertFalse(text.contains("0.500000"));}
        click(19);assertEquals(InvestigationGui.View.ACCESS,session().view);click(42);assertEquals(InvestigationGui.View.NORMAL,session().view);assertEquals(Section.INSPECT,session().section);
    }
    @Test void forceNeedsExplicitConfirmationAndRechecksPermission(){
        gui.open(player,target,Section.INSPECT,1);click(19);click(20);assertTrue(session().awaiting);verify(policies,never()).administer(any());
        when(player.hasPermission("dexturreputation.admin.force")).thenReturn(false);click(40);verify(policies,never()).administer(any());
    }
    @Test void confirmedForceRunsOnlyOnce(){
        gui.open(player,target,Section.INSPECT,1);click(19);click(20);var confirm=session();click(40);confirm.actions.get(40).accept(ClickType.LEFT);
        var request=ArgumentCaptor.forClass(AdministrationData.Request.class);verify(policies,times(1)).administer(request.capture());assertEquals(InitialEligibility.KEY,request.getValue().policy());assertEquals(AdministrationData.Override.FORCE_ALLOW,request.getValue().override());
    }
    @Test void staleConfirmationCannotApplyForce(){
        gui.open(player,target,Section.INSPECT,1);click(19);click(20);fingerprint.set("changed");click(40);verify(policies,never()).administer(any());
    }
    @Test void pendingApprovalIsRoutedThroughConfirmedMutationWithItsOwnPermission(){
        gui.open(player,target,Section.PENDING,1);click(18);assertTrue(session().awaiting);verify(app,never()).mutate(any());click(40);
        var request=ArgumentCaptor.forClass(Request.class);verify(app).mutate(request.capture());assertEquals(Action.APPROVE,request.getValue().action());assertEquals(vote,request.getValue().subject());
    }
    @Test void voteConfirmationNamesTheAffectedPlayerInsteadOfTheInspectedHistoryOwner(){
        UUID other=UUID.randomUUID();var recipient=mock(OfflinePlayer.class);when(recipient.getName()).thenReturn("AffectedPlayer");when(plugin.getServer().getOfflinePlayer(other)).thenReturn(recipient);
        doReturn(CompletableFuture.completedFuture(new Report(Map.of(),List.of(new Row(vote,Map.of("voter",owner.toString(),"target",other.toString(),"status","PENDING","revision","1"))),1,"same"))).when(app).read(any());
        gui.open(player,target,Section.PENDING,1);click(18);var confirmation=top.get().getItem(40);String lore=confirmation.getItemMeta().lore().stream().map(PlainTextComponentSerializer.plainText()::serialize).collect(java.util.stream.Collectors.joining(" | "));assertTrue(lore.contains("AffectedPlayer"));assertFalse(lore.contains("Target"));verify(app,never()).mutate(any());
    }
    @Test void deniedForceIsNotRenderedAndCannotBeDispatched(){
        when(player.hasPermission("dexturreputation.admin.force")).thenReturn(false);gui.open(player,target,Section.INSPECT,1);click(19);assertFalse(session().actions.containsKey(20));click(20);verify(policies,never()).administer(any());
    }
}
