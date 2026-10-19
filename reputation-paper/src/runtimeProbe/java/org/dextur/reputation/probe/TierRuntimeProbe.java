package org.dextur.reputation.probe;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.dextur.reputation.api.*;

/** Isolated real clients and real API. Custom QA thresholds are 2/4, with margin 1. */
final class TierRuntimeProbe {
    private final JavaPlugin plugin;
    private final TierService tiers;
    private final VotingService voting;
    private Player a,b,target;
    private Team foreign;
    private Scoreboard custom;
    private boolean restarted;
    private final List<TierService.TierChanged> events=new CopyOnWriteArrayList<>();
    TierRuntimeProbe(JavaPlugin plugin) {this.plugin=plugin;tiers=plugin.getServer().getServicesManager().load(TierService.class);voting=plugin.getServer().getServicesManager().load(VotingService.class);}
    void start(Runnable success,Consumer<Throwable> failed) {
        Path marker=plugin.getDataFolder().toPath().resolve("tier-restart.txt");restarted=Files.exists(marker);
        AutoCloseable listener=tiers.onTierChanged(events::add);
        poll(()->onMain(()->{a=plugin.getServer().getPlayerExact("Phase4A");b=plugin.getServer().getPlayerExact("Phase4B");target=plugin.getServer().getPlayerExact("Phase4Target");return a!=null&&b!=null&&target!=null;}),Boolean::booleanValue,0)
        .thenCompose(ignored->tiers.getTier(target.getUniqueId())).thenCompose(tier->{
            check(tier.score()==3,"Tier baseline lost on restart");check(tier.id().equals(restarted?"RESPECTED":"RECOGNIZED"),"Hysteresis tier not retained across restart");
            return onMain(()->{custom=plugin.getServer().getScoreboardManager().getNewScoreboard();b.setScoreboard(custom);var board=a.getScoreboard();foreign=board.getTeam("phase4_foreign");if(foreign==null)foreign=board.registerNewTeam("phase4_foreign");foreign.prefix(Component.text("Staff "));foreign.suffix(Component.text(" Foreign"));foreign.addEntry(a.getName());a.playerListName(Component.text("Foreign TAB"));a.performCommand("rep language en_US");b.performCommand("rep language ru_RU");return true;});
        }).thenCompose(ignored->poll(()->onMain(()->{
            var team=target.getScoreboard().getEntryTeam(target.getName());return team!=null&&plain(team.suffix()).equals(" (+3)");}),Boolean::booleanValue,0))
        .thenCompose(ignored->{
            check(events.isEmpty(),"Cache warming replayed tier events");var request=cast(a,target,VoteType.POSITIVE);return voting.submit(request).thenCompose(receipt->{check(receipt.currentView().profile().tier().equals("RESPECTED"),"Positive entry threshold");return voting.submit(request);}).thenApply(duplicate->{check(duplicate.duplicate(),"Duplicate vote not recognized");return duplicate;});
        }).thenCompose(ignored->voting.submit(revoke(a,target)))
        .thenCompose(receipt->{check(receipt.currentView().profile().tier().equals("RESPECTED"),"Inclusive hysteresis exit");return voting.submit(cast(b,target,VoteType.NEGATIVE));})
        .thenCompose(receipt->{check(receipt.currentView().profile().tier().equals("RECOGNIZED"),"Hysteresis exit below 3");return voting.submit(revoke(b,target));})
        .thenCompose(ignored->voting.submit(cast(a,target,VoteType.POSITIVE)))
        .thenCompose(ignored->voting.submit(cast(a,target,VoteType.NEGATIVE)))
        .thenCompose(receipt->{check(receipt.currentView().profile().tier().equals("RECOGNIZED"),"Replacement downgrade");return voting.submit(revoke(a,target));})
        .thenCompose(ignored->voting.submit(cast(a,target,VoteType.POSITIVE)))
        .thenCompose(ignored->voting.submit(revoke(a,target)))
        .thenCompose(ignored->voting.submit(cast(a,b,VoteType.NEGATIVE)))
        .thenCompose(ignored->voting.submit(cast(target,b,VoteType.NEGATIVE)))
        .thenCompose(receipt->{check(receipt.currentView().profile().tier().equals("SUSPICIOUS"),"Negative tier entry");return voting.submit(revoke(a,b));})
        .thenCompose(receipt->{check(receipt.currentView().profile().tier().equals("SUSPICIOUS"),"Negative hysteresis persistence");return voting.submit(revoke(target,b));})
        .thenCompose(receipt->{check(receipt.currentView().profile().tier().equals("NEUTRAL"),"Return to neutral");return onMain(()->{a.performCommand("rep gui Phase4Target");return true;});})
        .thenCompose(ignored->poll(()->onMain(()->{
            var item=a.getOpenInventory().getTopInventory().getItem(40);return item!=null&&item.getItemMeta().lore()!=null&&item.getItemMeta().lore().stream().anyMatch(c->plain(c).contains("Respected"));}),Boolean::booleanValue,0))
        .thenCompose(ignored->poll(()->onMain(()->plain(target.playerListName()).contains("Уважаемый")),Boolean::booleanValue,0))
        .thenCompose(ignored->onMain(()->{
            check(foreign.getEntries().contains(a.getName())&&plain(foreign.suffix()).equals(" Foreign"),"Foreign scoreboard team modified");check(b.getScoreboard()==custom,"Custom scoreboard replaced");check(a.getOpenInventory().getTopInventory().getItem(40).getItemMeta().lore().stream().anyMatch(c->plain(c).contains("250")),"Progress must point beyond the already retained tier");check(plain(a.playerListName()).equals("Foreign TAB"),"Foreign TAB ownership overwritten");check(plain(target.playerListName()).contains("Уважаемый"),"Localized direct TAB missing: "+plain(target.playerListName()));
            verifyPlaceholders();return true;
        })).thenCompose(ignored->plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")?new LuckPermsRuntimeProbe(plugin).verifyOnline(target.getUniqueId()):CompletableFuture.completedFuture(null))
        .thenCompose(ignored->onMain(()->{target.kick(Component.text("QA offline tier synchronization"));return true;}))
        .thenCompose(ignored->poll(()->onMain(()->plugin.getServer().getPlayer(target.getUniqueId())==null),Boolean::booleanValue,0))
        .thenCompose(ignored->onMain(()->{check(target.getScoreboard().getEntryTeam(target.getName())==null,"Nameplate remained after quit");return true;}))
        .thenCompose(ignored->tiers.getTier(target.getUniqueId()))
        .thenCompose(ignored->{
            if(!plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")) {plugin.getLogger().info("PROBE_LUCKPERMS_ABSENT_PASS: tiers and display work without LuckPerms");return CompletableFuture.completedFuture(null);}
            return new LuckPermsRuntimeProbe(plugin).verify(target.getUniqueId());
        }).whenComplete((ignored,error)->onMain(()->{
            try {listener.close();if(error!=null){failed.accept(error);return null;}
                check(events.stream().filter(e->e.tier().playerId().equals(target.getUniqueId())).count()==(restarted?4:5)+(plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")?2:0),"Duplicated/missing tier transitions");long previous=0;for(var event:events)if(event.tier().playerId().equals(target.getUniqueId())){check(event.tier().revision()>previous,"Tier event ordering");previous=event.tier().revision();}
                Files.writeString(marker,"score=3; tier=RESPECTED\n");plugin.getLogger().info("PROBE_TIERS_PASS: real votes, entry/exit, replacement/revoke, negative recovery, restart, cache events, foreign teams/TAB and localized GUI");success.run();
            }catch(Throwable failure){failed.accept(failure);}return null;
        }));
    }
    private void verifyPlaceholders() throws Exception {
        if(!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"))return;
        var papi=plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");var type=Class.forName("me.clip.placeholderapi.PlaceholderAPI",true,papi.getClass().getClassLoader());var method=type.getMethod("setPlaceholders",org.bukkit.OfflinePlayer.class,String.class);
        for(String key:List.of("score","score_formatted","tier","tier_name","prefix","suffix","color","positive","negative","reputation_formatted","tier_id")) {
            String value=(String)method.invoke(null,target,"%dexturreputation_"+key+"%");check(!value.contains("%dexturreputation_"),"Unresolved placeholder: "+key);if(!key.equals("suffix"))check(!value.isEmpty(),"Empty cached placeholder: "+key);
        }
        check(method.invoke(null,target,"%dexturreputation_tier%").equals("Уважаемый"),"PAPI localized retained tier");check(method.invoke(null,target,"%dexturreputation_positive%").equals("true"),"PAPI sign");
        plugin.getLogger().info("PROBE_TIER_PAPI_PASS: full PAPI parser resolved all nine required and two additional placeholders");
    }
    private static VoteRequest cast(Player voter,Player target,VoteType type) {return new VoteRequest(UUID.randomUUID(),voter.getUniqueId(),target.getUniqueId(),VoteRequest.Action.SUBMIT,type,type==VoteType.POSITIVE?"helpful":"rude","");}
    private static VoteRequest revoke(Player voter,Player target) {return new VoteRequest(UUID.randomUUID(),voter.getUniqueId(),target.getUniqueId(),VoteRequest.Action.REVOKE,null,"","");}
    private static String plain(Component component){return PlainTextComponentSerializer.plainText().serialize(component);}
    private <T> CompletableFuture<T> onMain(Callable<T> action) {var result=new CompletableFuture<T>();Runnable run=()->{try{result.complete(action.call());}catch(Throwable e){result.completeExceptionally(e);}};if(org.bukkit.Bukkit.isPrimaryThread())run.run();else plugin.getServer().getScheduler().runTask(plugin,run);return result;}
    private <T> CompletionStage<T> poll(Supplier<? extends CompletionStage<T>> read,Predicate<T> ready,int attempt) {return read.get().thenCompose(value->{if(ready.test(value))return CompletableFuture.completedFuture(value);if(attempt>150)return CompletableFuture.failedFuture(new AssertionError("Tier runtime wait timed out"));var result=new CompletableFuture<T>();plugin.getServer().getScheduler().runTaskLater(plugin,()->poll(read,ready,attempt+1).whenComplete((next,error)->{if(error!=null)result.completeExceptionally(error);else result.complete(next);}),2L);return result;});}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
