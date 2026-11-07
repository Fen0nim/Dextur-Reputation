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
        plugin=mock(DexturReputationPlugin.class);var server=mock(Server.class);when(plugin.getServer()).thenReturn(server);when(plugin.ready()).thenReturn(true);var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);when(server.getScheduler()).thenReturn(scheduler);doAnswer(i->{i.<Runnable>getArgument(1).run();return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        doAnswer(i->{i.<Runnable>getArgument(0).run();return null;}).when(plugin).onMain(any());
        var known=mock(OfflinePlayer.class);when(known.getName()).thenReturn("Target");when(server.getOfflinePlayer(any(UUID.class))).thenReturn(known);when(server.createProfile(any(UUID.class))).thenAnswer(i->{UUID id=i.getArgument(0);var profile=mock(com.destroystokyo.paper.profile.PlayerProfile.class);when(profile.getName()).thenAnswer(x->server.getOfflinePlayer(id).getName());return profile;});when(plugin.tierLabel(anyString(),any())).thenReturn("Нейтральный");
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
        gui.open(player,target,Section.INSPECT,1);assertEquals(27,top.get().getSize());var slots=contents.get(top.get());assertTrue(slots.values().stream().map(ItemStack::getType).distinct().count()>5);
        for(var item:slots.values()){assertNotNull(item.getItemMeta().displayName());assertFalse(item.getItemMeta().lore().isEmpty());assertTrue(item.getItemMeta().lore().size()<=7);String text=item.getItemMeta().lore().stream().map(PlainTextComponentSerializer.plainText()::serialize).collect(java.util.stream.Collectors.joining());assertFalse(text.contains(target.toString()));assertFalse(text.contains("0.500000"));}
        click(20);assertEquals(InvestigationGui.View.ACCESS,session().view);click(11);assertEquals(InvestigationGui.View.NORMAL,session().view);assertEquals(Section.INSPECT,session().section);
    }
    @Test void forceNeedsExplicitConfirmationAndRechecksPermission(){
        gui.open(player,target,Section.INSPECT,1);click(20);click(7);assertTrue(session().awaiting);verify(policies,never()).administer(any());
        when(player.hasPermission("dexturreputation.admin.force")).thenReturn(false);click(4);verify(policies,never()).administer(any());
    }
    @Test void confirmedForceRunsOnlyOnce(){
        gui.open(player,target,Section.INSPECT,1);click(20);click(7);var confirm=session();click(4);confirm.actions.get(4).accept(ClickType.LEFT);
        var request=ArgumentCaptor.forClass(AdministrationData.Request.class);verify(policies,times(1)).administer(request.capture());assertEquals(InitialEligibility.KEY,request.getValue().policy());assertEquals(AdministrationData.Override.FORCE_ALLOW,request.getValue().override());
    }
    @Test void staleConfirmationCannotApplyForce(){
        gui.open(player,target,Section.INSPECT,1);click(20);click(7);fingerprint.set("changed");click(4);verify(policies,never()).administer(any());
    }
    @Test void pendingApprovalIsRoutedThroughConfirmedMutationWithItsOwnPermission(){
        gui.open(player,target,Section.PENDING,1);click(0);assertTrue(session().awaiting);verify(app,never()).mutate(any());click(4);
        var request=ArgumentCaptor.forClass(Request.class);verify(app).mutate(request.capture());assertEquals(Action.APPROVE,request.getValue().action());assertEquals(vote,request.getValue().subject());
    }
    @Test void voteConfirmationNamesTheAffectedPlayerInsteadOfTheInspectedHistoryOwner(){
        UUID other=UUID.randomUUID();var recipient=mock(OfflinePlayer.class);when(recipient.getName()).thenReturn("AffectedPlayer");when(plugin.getServer().getOfflinePlayer(other)).thenReturn(recipient);
        doReturn(CompletableFuture.completedFuture(new Report(Map.of(),List.of(new Row(vote,Map.of("voter",owner.toString(),"target",other.toString(),"status","PENDING","revision","1"))),1,"same"))).when(app).read(any());
        gui.open(player,target,Section.PENDING,1);click(0);var confirmation=top.get().getItem(4);String lore=confirmation.getItemMeta().lore().stream().map(PlainTextComponentSerializer.plainText()::serialize).collect(java.util.stream.Collectors.joining(" | "));assertTrue(lore.contains("AffectedPlayer"));assertFalse(lore.contains("Target"));verify(app,never()).mutate(any());
    }
    @Test void deniedForceIsNotRenderedAndCannotBeDispatched(){
        when(player.hasPermission("dexturreputation.admin.force")).thenReturn(false);gui.open(player,target,Section.INSPECT,1);click(20);assertFalse(session().actions.containsKey(7));click(7);verify(policies,never()).administer(any());
    }
    @Test void submenusAreDedicatedAdaptiveAndBackReturnsToTheMainMenu(){
        gui.open(player,target,Section.PENDING,1);assertEquals(18,top.get().getSize());assertTrue(session().actions.keySet().stream().noneMatch(i->i>=1&&i<=9));
        click(11);assertEquals(27,top.get().getSize());assertEquals(Section.INSPECT,session().section);
        click(21);assertEquals(18,top.get().getSize());assertEquals(InvestigationGui.View.ADJUST,session().view);assertFalse(session().actions.containsKey(9));
    }
    @Test void historyBackPreservesTheInspectedPage(){
        UUID id=UUID.randomUUID();doReturn(CompletableFuture.completedFuture(new Report(Map.of(),List.of(new Row(id,Map.of("voter",owner.toString(),"target",target.toString(),"type","POSITIVE","reason","friendly","status","ACCEPTED","revision","1"))),40,"same"))).when(app).read(any());
        gui.open(player,target,Section.VOTES,2);click(0);assertEquals(Section.VOTE,session().section);click(session().layout.cancel());assertEquals(Section.VOTES,session().section);assertEquals(2,session().page);
    }
    @Test void reloadInvalidatesEvenPreviouslyCapturedConfirmationActions(){
        gui.open(player,target,Section.INSPECT,1);click(20);click(7);var action=session().actions.get(4);gui.invalidate();action.accept(ClickType.LEFT);verify(policies,never()).administer(any());
    }
    @Test void auditSecondPageShowsBothParticipantsWithTheirOwnNamesAndUuids(){
        UUID administrator=UUID.randomUUID();var admin=mock(OfflinePlayer.class);when(admin.getName()).thenReturn("AdminName");when(plugin.getServer().getOfflinePlayer(administrator)).thenReturn(admin);
        doReturn(CompletableFuture.completedFuture(new Report(Map.of(),List.of(new Row(UUID.randomUUID(),Map.of("action","ADD","actor",administrator.toString(),"target",target.toString(),"before","5","after","6","reason","review","timestamp","2026-11-07T10:00:00Z"))),30,"same"))).when(app).read(any());
        gui.open(player,target,Section.AUDIT,2);String lore=top.get().getItem(0).getItemMeta().lore().stream().map(PlainTextComponentSerializer.plainText()::serialize).collect(java.util.stream.Collectors.joining(" | "));
        assertTrue(lore.contains("Администратор"));assertTrue(lore.contains("AdminName"));assertTrue(lore.contains(administrator.toString()));assertTrue(lore.contains("Игрок"));assertTrue(lore.contains("Target"));assertTrue(lore.contains(target.toString()));assertTrue(lore.contains("Было"));assertTrue(lore.contains("Стало"));
    }
    @Test void permissionIsCheckedAgainAfterTheClickIsQueued(){
        gui.open(player,target,Section.INSPECT,1);click(20);click(7);var pending=new ArrayList<Runnable>();var scheduler=plugin.getServer().getScheduler();doAnswer(i->{pending.add(i.getArgument(1));return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        click(4);when(player.hasPermission("dexturreputation.admin.force")).thenReturn(false);pending.getFirst().run();verify(policies,never()).administer(any());
    }
    @Test void rapidClicksQueueOnlyOneConfirmationDispatch(){
        gui.open(player,target,Section.INSPECT,1);click(20);click(7);var pending=new ArrayList<Runnable>();var scheduler=plugin.getServer().getScheduler();doAnswer(i->{pending.add(i.getArgument(1));return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        click(4);click(4);assertEquals(1,pending.size());pending.getFirst().run();verify(policies,times(1)).administer(any());
    }

    record HistoryHarness(ReasonHistoryGui gui,org.dextur.reputation.paper.VotingCommands commands,List<org.dextur.reputation.domain.ReasonHistory.Query> queries){}
    HistoryHarness history(org.dextur.reputation.domain.PlayerLanguage locale)throws Exception {
        var messages=Messages.bundled(locale);var commands=mock(org.dextur.reputation.paper.VotingCommands.class);when(commands.allowed(any(),anyString())).thenReturn(true);when(commands.language(any())).thenReturn(locale);when(commands.name(any())).thenReturn("VoteAuthor");when(commands.name(any(),any())).thenReturn("VoteAuthor");when(commands.type(any(),any())).thenAnswer(i->messages.plain(i.getArgument(0),"vote-type-"+i.<org.dextur.reputation.api.VoteType>getArgument(1).name().toLowerCase(java.util.Locale.ROOT)));when(commands.action(any(),any())).thenAnswer(i->messages.plain(i.getArgument(0),"vote-action-"+i.<org.dextur.reputation.api.VoteEntry.Action>getArgument(1).name().toLowerCase(java.util.Locale.ROOT)));
        try(var in=getClass().getResourceAsStream("/voting.yml")){java.nio.file.Files.write(directory.resolve("voting.yml"),in.readAllBytes());}
        var config=VotingConfiguration.load(directory,messages);var queries=new ArrayList<org.dextur.reputation.domain.ReasonHistory.Query>();
        var groups=List.of(new org.dextur.reputation.domain.ReasonHistory.Group("friendly",org.dextur.reputation.api.VoteType.POSITIVE,1,20),new org.dextur.reputation.domain.ReasonHistory.Group("rude",org.dextur.reputation.api.VoteType.NEGATIVE,0,10),new org.dextur.reputation.domain.ReasonHistory.Group("retired_reason",org.dextur.reputation.api.VoteType.POSITIVE,0,1),new org.dextur.reputation.domain.ReasonHistory.Group("",org.dextur.reputation.api.VoteType.POSITIVE,0,1));
        var rows=new ArrayList<org.dextur.reputation.api.VotePage.Item>();
        for(int i=30;i>=1;i--){var e=new org.dextur.reputation.api.VoteEntry(UUID.randomUUID(),i,"global",owner,target,org.dextur.reputation.api.VoteEntry.Action.CAST,i%3==0?org.dextur.reputation.api.VoteType.NEGATIVE:org.dextur.reputation.api.VoteType.POSITIVE,i%3==0?"rude":"friendly","",Instant.EPOCH.plusSeconds(i),null,1,1,1,"0".repeat(64),0,1);rows.add(new org.dextur.reputation.api.VotePage.Item(e,i==1));}
        var history=new ReasonHistoryGui(plugin,commands,messages,config,(id,q)->{queries.add(q);var filtered=rows.stream().filter(r->(q.type()==null||q.type()==r.entry().type())&&(q.reason()==null||q.reason().equals(r.entry().reason()))&&(!q.activeOnly()||r.active())).toList();return CompletableFuture.completedFuture(new org.dextur.reputation.domain.ReasonHistory.Page(groups,filtered.stream().skip((long)(q.page()-1)*q.size()).limit(q.size()).toList(),filtered.size(),q));});
        return new HistoryHarness(history,commands,queries);
    }
    void historyClick(HistoryHarness h,int slot){var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(ClickType.LEFT);when(event.getRawSlot()).thenReturn(slot);h.gui().click(event);verify(event).setCancelled(true);}
    @Test void reasonFiltersUseCanonicalIdsAndBackPreservesTheSelectedFilter()throws Exception {
        var h=history(PlayerLanguage.ru_RU);h.gui().open(player,target,"Target",org.dextur.reputation.api.VotePage.Direction.RECEIVED,()->{});
        historyClick(h,1);assertEquals(org.dextur.reputation.api.VoteType.POSITIVE,h.queries().getLast().type());historyClick(h,9);assertEquals("friendly",h.queries().getLast().reason());
        historyClick(h,MenuGeometry.next(top.get().getSize()));assertEquals(2,h.queries().getLast().page(),h.queries().toString());assertEquals("friendly",h.queries().getLast().reason());
        historyClick(h,MenuGeometry.back(top.get().getSize()));assertEquals(org.dextur.reputation.api.VoteType.POSITIVE,h.queries().getLast().type());assertNull(h.queries().getLast().reason());
        historyClick(h,6);assertTrue(h.queries().getLast().activeOnly());assertEquals(org.dextur.reputation.api.VoteType.POSITIVE,h.queries().getLast().type());
    }
    @Test void reasonNamesAreLocalizedWithoutChangingHistoricalCanonicalIds()throws Exception {
        var h=history(PlayerLanguage.ru_RU);assertTrue(h.gui().reason(PlayerLanguage.ru_RU,"friendly").contains("Дружелюб"));assertTrue(h.gui().reason(PlayerLanguage.en_US,"friendly").contains("Friendliness"));assertEquals("Причина не указана",h.gui().reason(PlayerLanguage.ru_RU,""));assertTrue(h.gui().reason(PlayerLanguage.ru_RU,"retired_reason").contains("retired_reason"));assertTrue(h.gui().reason(PlayerLanguage.en_US,"retired_reason").contains("Archived"));
    }
    @Test void historyPermissionRevocationPreventsEvenReadNavigation()throws Exception {
        var h=history(PlayerLanguage.en_US);h.gui().open(player,target,"Target",org.dextur.reputation.api.VotePage.Direction.RECEIVED,()->{});int reads=h.queries().size();when(h.commands().allowed(player,"history")).thenReturn(false);historyClick(h,1);assertEquals(reads,h.queries().size());
    }
    @Test void publicHistoryDoesNotExposeAuditUuidsOrTrust()throws Exception {
        var h=history(PlayerLanguage.ru_RU);h.gui().open(player,target,"Target",org.dextur.reputation.api.VotePage.Direction.RECEIVED,()->{});historyClick(h,8);
        for(var item:contents.get(top.get()).values()){String lore=item.getItemMeta().lore().stream().map(PlainTextComponentSerializer.plainText()::serialize).collect(java.util.stream.Collectors.joining(" | "));assertFalse(lore.contains(owner.toString()));assertFalse(lore.contains(target.toString()));assertFalse(lore.contains("Администратор"));assertFalse(lore.contains("Trust"));}
    }

    @Test void profileTechnicalViewLabelsThePlayerUuidWithoutInventingAnAuditRecord(){
        gui.open(player,target,Section.INSPECT,1);click(23);var text=new StringBuilder();for(var item:contents.get(top.get()).values()){text.append(PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()));for(var line:item.getItemMeta().lore())text.append(PlainTextComponentSerializer.plainText().serialize(line));}
        assertTrue(text.toString().contains(target.toString()));assertFalse(text.toString().contains("ID записи"));
    }

    @Test void technicalAuditPreservesTheCompleteUnicodeReason(){
        String reason="Ж".repeat(200)+"😀"+"Я".repeat(100);doReturn(CompletableFuture.completedFuture(new Report(Map.of(),List.of(new Row(UUID.randomUUID(),Map.of("action","ADD","actor",owner.toString(),"target",target.toString(),"before","0","after","1","reason",reason))),1,"same"))).when(app).read(any());
        gui.open(player,target,Section.ADMIN,1);var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(ClickType.RIGHT);when(event.getRawSlot()).thenReturn(0);gui.click(event);
        String full=contents.get(top.get()).entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).filter(i->PlainTextComponentSerializer.plainText().serialize(i.getItemMeta().displayName()).contains("Причина")).flatMap(i->i.getItemMeta().lore().stream()).map(PlainTextComponentSerializer.plainText()::serialize).collect(java.util.stream.Collectors.joining());assertEquals(reason,full);
    }

}
