package org.dextur.reputation.probe;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.dextur.reputation.domain.policy.InitialEligibility;

/** Opt-in QA only: real players, inventories, commands, network messages and restart persistence. */
final class AdminUxRuntimeProbe {
    private final JavaPlugin plugin;
    private Player admin,voter,target;
    private VotingService voting;
    private PolicyApplication policies;
    private ReputationApplication profiles;
    private final List<String> inventoryProof=new ArrayList<>();
    private String positive,negative;private org.bukkit.permissions.PermissionAttachment denied;
    AdminUxRuntimeProbe(JavaPlugin plugin){this.plugin=plugin;}
    void start(Runnable success,Consumer<Throwable> failure){
        poll(()->main(()->{admin=plugin.getServer().getPlayerExact("UxAdmin");voter=plugin.getServer().getPlayerExact("UxVoter");target=plugin.getServer().getPlayerExact("Fen0nim");return admin!=null&&voter!=null&&target!=null;}),Boolean::booleanValue,0)
        .thenCompose(v->main(()->{
            profiles=(ReputationApplication)plugin.getServer().getServicesManager().load(ReputationService.class);voting=plugin.getServer().getServicesManager().load(VotingService.class);policies=(PolicyApplication)plugin.getServer().getServicesManager().load(ReputationPolicyService.class);
            var permissions=admin.addAttachment(plugin);for(var p:plugin.getServer().getPluginManager().getPermissions())if(p.getName().startsWith("dexturreputation.admin."))permissions.setPermission(p.getName(),true);permissions.setPermission("dexturreputation.recovery.admin",true);
            var messages=Messages.bundled(PlayerLanguage.ru_RU);var owner=plugin.getServer().getPluginManager().getPlugin("DexturReputation");var config=VotingConfiguration.load(owner.getDataFolder().toPath(),messages);
            check(config.rules().minimumPlaytimeSeconds()>=18000,"Initial playtime threshold weakened in QA");
            positive=config.rules().reasons().values().stream().filter(r->r.types().contains(VoteType.POSITIVE)).findFirst().orElseThrow().id();negative=config.rules().reasons().values().stream().filter(r->r.types().contains(VoteType.NEGATIVE)).findFirst().orElseThrow().id();
            for(var player:List.of(admin,voter,target)){player.setInvulnerable(true);player.setAllowFlight(true);player.setFlying(true);player.teleport(new Location(player.getWorld(),0,200,0));player.sendMessage(Component.text("UX_WIRE_MARKER"));}
            return true;
        }))
        .thenCompose(v->profiles.setLanguage(admin.getUniqueId(),PlayerLanguage.ru_RU)).thenCompose(v->profiles.setLanguage(voter.getUniqueId(),PlayerLanguage.ru_RU)).thenCompose(v->profiles.setLanguage(target.getUniqueId(),PlayerLanguage.ru_RU))
        .thenCompose(v->profiles.getProfile(voter.getUniqueId())).thenCompose(v->profiles.getProfile(target.getUniqueId()))
        .thenCompose(v->System.getProperty("dextur.probe.ux-run","1").equals("1")?firstRun():restart())
        .thenCompose(v->main(()->{
            Path output=plugin.getDataFolder().toPath().resolve("ux-inventories-"+System.getProperty("dextur.probe.ux-run","1")+".txt");Files.createDirectories(output.getParent());Files.write(output,inventoryProof);
            plugin.getLogger().info("PROBE_UX_PASS: readable inventories, routes/confirmations, sign votes, restrictions, force persistence; run="+System.getProperty("dextur.probe.ux-run","1"));success.run();return true;
        })).exceptionally(error->{main(()->{Path proof=plugin.getDataFolder().toPath().resolve("ux-inventories-failed.txt");Files.createDirectories(proof.getParent());Files.write(proof,inventoryProof);failure.accept(error);return true;});return null;});
    }
    private CompletionStage<Boolean> firstRun(){
        return rejected(VoteRejectedException.Code.PLAYTIME).thenCompose(v->command(admin,"repadmin force UxVoter"))
        .thenCompose(v->poll(()->policies.administration(voter.getUniqueId()),InitialEligibility::enabled,0))
        .thenCompose(v->command(voter,"like Fen0nim "+positive)).thenCompose(v->historySize(1))
        .thenCompose(v->ticks(45)).thenCompose(v->command(voter,"dislike Fen0nim "+negative)).thenCompose(v->historySize(2))
        .thenCompose(v->ticks(45)).thenCompose(v->command(voter,"rep revoke Fen0nim")).thenCompose(v->historySize(3))
        .thenCompose(v->command(admin,"repadmin voting lock UxVoter UX verification"))
        .thenCompose(v->poll(()->policies.administration(voter.getUniqueId()),s->s!=null&&s.votingLocked(),0))
        .thenCompose(v->voting.submit(request()).handle((receipt,error)->{check(root(error) instanceof PolicyDeniedException,"Force bypassed voting lock");return true;}))
        .thenCompose(v->open("UxVoter")).thenCompose(v->click(20)).thenCompose(v->inspect())
        .thenCompose(v->click(4)).thenCompose(v->assertNoChange(true,true)).thenCompose(v->click(4))
        .thenCompose(v->poll(()->policies.administration(voter.getUniqueId()),s->s!=null&&!s.votingLocked(),0))
        .thenCompose(v->ready()).thenCompose(v->click(7)).thenCompose(v->assertNoChange(false,true)).thenCompose(v->back())
        .thenCompose(v->ready()).thenCompose(v->click(7)).thenCompose(v->click(4))
        .thenCompose(v->poll(()->policies.administration(voter.getUniqueId()),s->!InitialEligibility.enabled(s),0))
        .thenCompose(v->rejected(VoteRejectedException.Code.PLAYTIME))
        .thenCompose(v->command(admin,"repadmin force UxVoter on"))
        .thenCompose(v->poll(()->policies.administration(voter.getUniqueId()),InitialEligibility::enabled,0))
        .thenCompose(v->command(admin,"repadmin force UxVoter status"))
        .thenCompose(v->open("UxVoter")).thenCompose(v->inspectSections())
        .thenCompose(v->open("UxVoter")).thenCompose(v->click(21)).thenCompose(v->click(1)).thenCompose(v->back())
        .thenCompose(v->ready()).thenCompose(v->back())
        .thenCompose(v->ticks(45)).thenCompose(v->command(voter,"like Fen0nim "+positive)).thenCompose(v->historySize(4))
        .thenCompose(v->open("UxVoter")).thenCompose(v->click(19)).thenCompose(v->ready()).thenCompose(v->inspect())
        .thenCompose(v->click(0)).thenCompose(v->ready()).thenCompose(v->inspect())
        .thenCompose(v->click(0)).thenCompose(v->confirmationRecipient("Fen0nim")).thenCompose(v->click(4))
        .thenCompose(v->poll(()->voting.history(voter.getUniqueId(),VotePage.Direction.GIVEN,1),p->!p.entries().getFirst().active(),0))
        .thenCompose(v->ready()).thenCompose(v->click(0)).thenCompose(v->confirmationRecipient("Fen0nim")).thenCompose(v->click(4))
        .thenCompose(v->poll(()->voting.history(voter.getUniqueId(),VotePage.Direction.GIVEN,1),p->p.entries().getFirst().active(),0))
        .thenCompose(v->open("UxVoter")).thenCompose(v->click(23)).thenCompose(v->inspect()).thenCompose(v->back())
        .thenCompose(v->command(admin,"repadmin force UxAdmin on"))
        .thenCompose(v->poll(()->policies.administration(admin.getUniqueId()),InitialEligibility::enabled,0))
        .thenCompose(v->command(admin,"dislike Fen0nim "+negative))
        .thenCompose(v->poll(()->voting.history(target.getUniqueId(),VotePage.Direction.RECEIVED,1),p->p.entries().stream().anyMatch(e->!e.active()&&e.entry().voterId().equals(admin.getUniqueId())),0))
        .thenCompose(v->open("Fen0nim")).thenCompose(v->click(10)).thenCompose(v->ready()).thenCompose(v->inspect())
        .thenCompose(v->click(0)).thenCompose(v->assertConfirmation()).thenCompose(v->click(4))
        .thenCompose(v->ticks(30)).thenCompose(v->command(admin,"rep language en_US"))
        .thenCompose(v->open("UxVoter")).thenCompose(v->inspect()).thenCompose(v->command(admin,"repadmin force UxVoter status"))
        .thenCompose(v->redesignChecks()).thenCompose(v->ticks(25));
    }
    private CompletionStage<Boolean> redesignChecks(){
        CompletionStage<Boolean> chain=CompletableFuture.completedFuture(true);
        for(int i=0;i<16;i++){chain=chain.thenCompose(v->command(admin,"repadmin add UxVoter 1 QA audit identity"));}
        chain=chain.thenCompose(v->main(()->{check(plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),"repadmin add UxVoter 1 QA console"),"Console command failed");return true;})).thenCompose(v->ticks(10));
        chain=chain.thenCompose(v->permissionCheck());
        chain=chain.thenCompose(v->open("UxVoter")).thenCompose(v->click(15)).thenCompose(v->ready()).thenCompose(v->auditIdentity(true)).thenCompose(v->nextPage()).thenCompose(v->ready()).thenCompose(v->auditIdentity(false));
        chain=chain.thenCompose(v->open("UxVoter")).thenCompose(v->click(16)).thenCompose(v->ready()).thenCompose(v->nextPage()).thenCompose(v->ready()).thenCompose(v->auditIdentity(false));
        for(int i=0;i<10;i++){final int n=i;chain=chain.thenCompose(v->ticks(23)).thenCompose(v->voting.submit(new VoteRequest(UUID.randomUUID(),voter.getUniqueId(),target.getUniqueId(),VoteRequest.Action.SUBMIT,VoteType.POSITIVE,positive,"history page "+n))).thenApply(v->true);}
        chain=chain.thenCompose(v->((VotingApplication)voting).reasonHistory(target.getUniqueId(),new org.dextur.reputation.domain.ReasonHistory.Query(VotePage.Direction.RECEIVED,null,null,false,1,9))).thenApply(page->{check(page.total()>9,"History pagination fixture missing");check(page.groups().stream().mapToLong(org.dextur.reputation.domain.ReasonHistory.Group::historical).sum()==page.total(),"Reason journal counts differ");check(page.groups().stream().mapToLong(org.dextur.reputation.domain.ReasonHistory.Group::active).sum()<page.total(),"Inactive revisions counted as current ratings");return true;});
        chain=chain.thenCompose(v->command(voter,"rep gui Fen0nim")).thenCompose(v->publicReady(false)).thenCompose(v->playerClick(voter,13)).thenCompose(v->publicReady(true)).thenCompose(v->playerClick(voter,1)).thenCompose(v->publicReady(true)).thenCompose(v->playerClick(voter,9)).thenCompose(v->publicReady(true)).thenCompose(v->publicLocale(false)).thenCompose(v->publicFilter(1)).thenCompose(v->playerNext()).thenCompose(v->publicReady(true)).thenCompose(v->publicFilter(2)).thenCompose(v->playerBack()).thenCompose(v->publicReady(true));
        chain=chain.thenCompose(v->command(voter,"rep language en_US")).thenCompose(v->command(voter,"rep gui Fen0nim")).thenCompose(v->publicReady(false)).thenCompose(v->playerClick(voter,13)).thenCompose(v->publicReady(true));
        chain=chain.thenCompose(v->publicLocale(true)).thenCompose(v->command(voter,"rep gui")).thenCompose(v->publicReady(false)).thenCompose(v->playerClick(voter,29)).thenCompose(v->otherPublic("RecoveryGui$Session")).thenCompose(v->playerBack()).thenCompose(v->publicReady(false)).thenCompose(v->playerClick(voter,33)).thenCompose(v->otherPublic("PolicyEffects$Panel")).thenCompose(v->playerBack()).thenCompose(v->publicReady(false));
        return chain.thenCompose(v->open("UxVoter")).thenCompose(v->command(admin,"repadmin reload")).thenCompose(v->main(()->{check(admin.getOpenInventory().getTopInventory().getType()==InventoryType.CRAFTING,"Reload kept a stale admin session");check(voter.getOpenInventory().getTopInventory().getType()==InventoryType.CRAFTING,"Reload kept a stale history session");return true;})).thenCompose(v->open("UxVoter")).thenCompose(v->inspect());
    }
    private CompletionStage<Boolean> back(){return main(()->admin.getOpenInventory().getTopInventory().getSize()-7).thenCompose(this::click);}
    private CompletionStage<Boolean> nextPage(){return main(()->admin.getOpenInventory().getTopInventory().getSize()-1).thenCompose(this::click);}
    private CompletionStage<Boolean> auditIdentity(boolean console){return main(()->{var inventory=admin.getOpenInventory().getTopInventory();var item=inventory.getItem(0);check(item!=null,"Audit record missing");String lore=item.getItemMeta().lore().stream().map(AdminUxRuntimeProbe::plain).collect(java.util.stream.Collectors.joining(" | "));check(lore.contains(voter.getName())&&lore.contains(voter.getUniqueId().toString()),"Affected player nickname/UUID missing: "+lore);if(!console)check(lore.contains(admin.getName())&&lore.contains(admin.getUniqueId().toString()),"Administrator nickname/UUID missing on page 2: "+lore);else check(lore.contains("Console")||lore.contains("Консоль"),"Console identity missing: "+lore);return true;}).thenCompose(v->inspect());}
    private CompletionStage<Boolean> permissionCheck(){return open("UxVoter").thenCompose(v->click(20)).thenCompose(v->main(()->{denied=admin.addAttachment(plugin,"dexturreputation.admin.force",false);check(!admin.hasPermission("dexturreputation.admin.force"),"QA permission override failed");return true;})).thenCompose(v->click(7)).thenCompose(v->policies.administration(voter.getUniqueId())).thenApply(state->{check(InitialEligibility.enabled(state),"Permission revocation did not guard a rendered force button");return true;}).thenCompose(v->main(()->{denied.remove();return true;}));}
    private CompletionStage<Boolean> publicLocale(boolean english){return main(()->{String title=plain(voter.getOpenInventory().title());check(title.contains(english?"History":"История"),"History title locale mismatch: "+title);return true;});}
    private CompletionStage<Boolean> otherPublic(String suffix){return poll(()->main(()->{var inventory=voter.getOpenInventory().getTopInventory();return inventory.getHolder()!=null&&inventory.getHolder().getClass().getName().endsWith(suffix)&&inventory.getSize()<=27&&java.util.Arrays.stream(inventory.getContents()).anyMatch(java.util.Objects::nonNull);}),Boolean::booleanValue,0);}
    private CompletionStage<Boolean> playerClick(Player player,int slot){return ticks(7).thenCompose(v->main(()->{var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL);plugin.getServer().getPluginManager().callEvent(event);check(event.isCancelled(),"Public history transfer not cancelled");return true;})).thenCompose(v->ticks(5));}
    private CompletionStage<Boolean> playerNext(){return main(()->voter.getOpenInventory().getTopInventory().getSize()-1).thenCompose(slot->playerClick(voter,slot));}
    private CompletionStage<Boolean> playerBack(){return main(()->voter.getOpenInventory().getTopInventory().getSize()-7).thenCompose(slot->playerClick(voter,slot));}
    private CompletionStage<Boolean> publicReady(boolean history){return poll(()->main(()->{var holder=voter.getOpenInventory().getTopInventory().getHolder();if(holder==null||!holder.getClass().getName().endsWith(history?"ReasonHistoryGui$Session":"VotingGui$Session"))return false;var busy=holder.getClass().getDeclaredField("busy");busy.setAccessible(true);return !busy.getBoolean(holder);}),Boolean::booleanValue,0);}
    private CompletionStage<Boolean> publicFilter(int page){return main(()->{var holder=voter.getOpenInventory().getTopInventory().getHolder();var field=holder.getClass().getDeclaredField("query");field.setAccessible(true);var q=(org.dextur.reputation.domain.ReasonHistory.Query)field.get(holder);check(q.type()==VoteType.POSITIVE&&positive.equals(q.reason())&&q.page()==page,"Canonical reason/page filter lost: "+q);var inventory=voter.getOpenInventory().getTopInventory();inventoryProof.add("PUBLIC VIEW: "+plain(voter.getOpenInventory().title())+" size="+inventory.getSize()+" filter="+q);for(var item:inventory.getContents())if(item!=null&&item.getItemMeta().hasLore()){String lore=item.getItemMeta().lore().stream().map(AdminUxRuntimeProbe::plain).collect(java.util.stream.Collectors.joining(" | "));check(!lore.contains(admin.getUniqueId().toString()),"Private administrator UUID in public history");inventoryProof.add(plain(item.getItemMeta().displayName())+" | "+lore);}return true;});}
    private CompletionStage<Boolean> restart(){return poll(()->policies.administration(voter.getUniqueId()),InitialEligibility::enabled,0).thenCompose(v->command(admin,"repadmin force UxVoter status")).thenCompose(v->open("UxVoter")).thenCompose(v->inspect()).thenCompose(v->ticks(25));}
    private VoteRequest request(){return new VoteRequest(UUID.randomUUID(),voter.getUniqueId(),target.getUniqueId(),VoteRequest.Action.SUBMIT,VoteType.POSITIVE,positive,"UX eligibility check");}
    private CompletionStage<Boolean> rejected(VoteRejectedException.Code code){return voting.submit(request()).handle((receipt,error)->{var cause=root(error);check(cause instanceof VoteRejectedException r&&r.code()==code,"Expected "+code+", got "+cause);return true;});}
    private CompletionStage<Boolean> assertNoChange(boolean lock,boolean force){return policies.administration(voter.getUniqueId()).thenApply(s->{check(s.votingLocked()==lock&&InitialEligibility.enabled(s)==force,"Confirmation applied before affirmative click");return true;});}
    private CompletionStage<Boolean> historySize(int size){return poll(()->voting.history(voter.getUniqueId(),VotePage.Direction.GIVEN,1),p->p.totalEntries()==size,0).thenApply(p->true);}
    private CompletionStage<Boolean> open(String name){return command(admin,"repadmin gui "+name).thenCompose(v->ready()).thenCompose(v->main(()->{var inventory=admin.getOpenInventory().getTopInventory();check(inventory.getSize()==27,"Main admin menu is not 27 slots: "+name);var item=inventory.getItem(4);check(item!=null&&item.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta,"Selected player head missing");var skull=(org.bukkit.inventory.meta.SkullMeta)item.getItemMeta();check(skull.getOwningPlayer()!=null&&name.equals(skull.getOwningPlayer().getName()),"Wrong selected player head");return true;}));}
    private CompletionStage<Boolean> ready(){return poll(()->main(()->{var holder=admin.getOpenInventory().getTopInventory().getHolder();if(holder==null||!holder.getClass().getName().endsWith("InvestigationGui$Session"))return false;var busy=holder.getClass().getDeclaredField("busy");busy.setAccessible(true);return !busy.getBoolean(holder)&&java.util.Arrays.stream(admin.getOpenInventory().getTopInventory().getContents()).anyMatch(java.util.Objects::nonNull);}),Boolean::booleanValue,0);}
    private CompletionStage<Boolean> click(int slot){return ticks(7).thenCompose(v->main(()->{var view=admin.getOpenInventory();var event=new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL);plugin.getServer().getPluginManager().callEvent(event);check(event.isCancelled(),"GUI transfer not cancelled");return true;})).thenCompose(v->ticks(5));}
    private CompletionStage<Boolean> confirmationRecipient(String name){return main(()->{var item=admin.getOpenInventory().getTopInventory().getItem(4);check(item!=null,"Missing confirmation");String lore=item.getItemMeta().lore().stream().map(AdminUxRuntimeProbe::plain).collect(java.util.stream.Collectors.joining(" | "));check(lore.contains(name),"Confirmation did not identify affected recipient: "+lore);return true;}).thenCompose(v->inspect());}
    private CompletionStage<Boolean> assertConfirmation(){return main(()->{var holder=admin.getOpenInventory().getTopInventory().getHolder();var field=holder.getClass().getDeclaredField("awaiting");field.setAccessible(true);check(field.getBoolean(holder),"No confirmation shown");return true;});}
    private CompletionStage<Boolean> inspectSections(){CompletionStage<Boolean> chain=CompletableFuture.completedFuture(true);for(int slot=9;slot<=17;slot++){final int index=slot;chain=chain.thenCompose(v->open("UxVoter")).thenCompose(v->main(()->{check(admin.getOpenInventory().getTopInventory().getSize()==27,"Main admin menu must have exactly 27 slots");return true;})).thenCompose(v->click(index)).thenCompose(v->ready()).thenCompose(v->dedicated()).thenCompose(v->inspect());}return chain;}
    private CompletionStage<Boolean> dedicated(){return main(()->{var inventory=admin.getOpenInventory().getTopInventory();var holder=inventory.getHolder();var field=holder.getClass().getDeclaredField("actions");field.setAccessible(true);var actions=(java.util.Map<?,?>)field.get(holder);var messages=Messages.bundled(profiles.cachedLanguage(admin.getUniqueId()));var unrelated=new java.util.HashSet<String>();for(String section:java.util.List.of("inspect","votes","pending","trust","flags","relations","recovery","admin","audit","recalculate"))unrelated.add(messages.plain(profiles.cachedLanguage(admin.getUniqueId()),"admin7-section-"+section));for(var entry:actions.keySet()){var item=inventory.getItem((Integer)entry);if(item!=null)check(!unrelated.contains(plain(item.getItemMeta().displayName())),"Repeated unrelated section navigation");}return true;});}
    private CompletionStage<Boolean> inspect(){return main(()->{
        var inventory=admin.getOpenInventory().getTopInventory();inventoryProof.add("VIEW: "+plain(admin.getOpenInventory().title())+" size="+inventory.getSize());
        for(int slot=0;slot<inventory.getSize();slot++){var item=inventory.getItem(slot);if(item==null||item.getType().isAir())continue;var meta=item.getItemMeta();check(meta.displayName()!=null&&!plain(meta.displayName()).isBlank(),"Unnamed item "+slot);var lore=meta.lore();check(lore==null||lore.size()<=24,"Missing/oversized lore "+slot);String body=plain(meta.displayName())+" | "+(lore==null?java.util.List.<Component>of():lore).stream().map(AdminUxRuntimeProbe::plain).collect(java.util.stream.Collectors.joining(" | "));inventoryProof.add(slot+" "+item.getType()+" "+body);boolean debug=plain(admin.getOpenInventory().title()).toLowerCase(Locale.ROOT).contains("техническ")||plain(admin.getOpenInventory().title()).toLowerCase(Locale.ROOT).contains("technical");if(!debug){check(!body.matches(".*[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}.*")||body.contains("UUID:"),"Unlabeled UUID in primary screen");check(!body.contains("reputation.restore.pending")&&!body.contains("0.500000"),"Internal values in primary screen");}}
        return true;
    });}
    private CompletionStage<Boolean> command(Player player,String text){return main(()->{check(player.performCommand(text),"Command refused: "+text);return true;}).thenCompose(v->ticks(5));}
    private <T> CompletableFuture<T> main(Callable<T> work){var result=new CompletableFuture<T>();Runnable task=()->{try{result.complete(work.call());}catch(Throwable error){result.completeExceptionally(error);}};if(Bukkit.isPrimaryThread())task.run();else plugin.getServer().getScheduler().runTask(plugin,task);return result;}
    private CompletionStage<Boolean> ticks(long count){var result=new CompletableFuture<Boolean>();plugin.getServer().getScheduler().runTaskLater(plugin,()->result.complete(true),count);return result;}
    private <T> CompletionStage<T> poll(Supplier<? extends CompletionStage<T>> read,Predicate<T> ready,int count){return read.get().thenCompose(value->{if(ready.test(value))return CompletableFuture.completedFuture(value);if(count>150)return CompletableFuture.failedFuture(new AssertionError("UX timeout: "+value));return ticks(2).thenCompose(v->poll(read,ready,count+1));});}
    private static Throwable root(Throwable error){if(error==null)return null;while(error.getCause()!=null&&error.getCause()!=error)error=error.getCause();return error;}
    private static String plain(Component value){return PlainTextComponentSerializer.plainText().serialize(value);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
