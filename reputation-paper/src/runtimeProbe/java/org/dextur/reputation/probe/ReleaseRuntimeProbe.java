package org.dextur.reputation.probe;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;

/** Clean installation, four optional combinations, real GUI, physical restore and repeated JVM cycles. */
final class ReleaseRuntimeProbe {
    private final JavaPlugin plugin;
    private final ReputationApplication profiles;
    private Player operator,target,ordinary;
    private final int run=Integer.getInteger("dextur.probe.release-run",1);
    private final NamespacedKey foreign=new NamespacedKey("releaseqa","foreign-health"),own=new NamespacedKey("dexturreputation","reputation-health");
    ReleaseRuntimeProbe(JavaPlugin plugin){this.plugin=plugin;profiles=(ReputationApplication)plugin.getServer().getServicesManager().load(ReputationService.class);}
    void start(Runnable success,Consumer<Throwable> failure){
        poll(()->main(()->{operator=plugin.getServer().getPlayerExact("ReleaseAdmin");target=plugin.getServer().getPlayerExact("ReleaseTarget");ordinary=plugin.getServer().getPlayerExact("ReleaseUser");return operator!=null&&target!=null&&ordinary!=null;}),Boolean::booleanValue,0)
        .thenCompose(v->profiles.getProfile(target.getUniqueId())).thenCompose(profile->main(()->{
            var owner=plugin.getServer().getPluginManager().getPlugin("DexturReputation");check(owner.getPluginMeta().getVersion().equals("1.0.1"),"Release version metadata");
            for(String root:List.of("dexturreputation","rep","repadmin","like","dislike"))check(plugin.getServer().getPluginCommand(root)!=null,"Missing command "+root);
            check(plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")==Boolean.getBoolean("dextur.probe.expect-papi"),"Unexpected PAPI presence");
            check(plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")==Boolean.getBoolean("dextur.probe.expect-lp"),"Unexpected LP presence");
            for(String permission:List.of("trust.inspect","flags.inspect","vote.invalidate","vote.restore","bulk","recalculate.all"))check(!ordinary.hasPermission("dexturreputation.admin."+permission),"Ordinary player gained "+permission);
            var attachment=operator.addAttachment(plugin);for(var permission:plugin.getServer().getPluginManager().getPermissions())if(permission.getName().startsWith("dexturreputation.admin."))attachment.setPermission(permission.getName(),true);
            Path folder=owner.getDataFolder().toPath();for(String name:List.of("config.yml","storage.yml","integrations.yml","voting.yml","trust.yml","anti-abuse.yml","tiers.yml","display.yml","gui.yml","recovery.yml","features.yml","administration.yml","lang/ru_RU.yml","lang/en_US.yml"))check(Files.isRegularFile(folder.resolve(name)),"Default missing "+name);
            var settings=Settings.load(folder);check(settings.defaultLanguage()==PlayerLanguage.ru_RU,"Fresh default locale");check(settings.storage().type().name().equals(System.getProperty("dextur.probe.storage")),"Selected backend");
            Path marker=plugin.getDataFolder().toPath().resolve("release-created-at.txt");Files.createDirectories(marker.getParent());
            if(run==1){check(profile.visibleScore()==0,"Fresh profile nonzero");Files.writeString(marker,profile.createdAt().toString());operator.performCommand("repadmin set ReleaseTarget 100 Stable release QA");}
            else {check(Files.readString(marker).equals(profile.createdAt().toString()),"Creation timestamp lost on restart/restore");check(profile.visibleScore()==100,"Restart/restore score lost");}
            var board=operator.getScoreboard();var team=board.getTeam("release_foreign");if(team==null)team=board.registerNewTeam("release_foreign");team.prefix(Component.text("Foreign "));team.addEntry(operator.getName());operator.playerListName(Component.text("Foreign TAB"));
            var attribute=target.getAttribute(Attribute.MAX_HEALTH);if(attribute.getModifier(foreign)==null)attribute.addTransientModifier(new AttributeModifier(foreign,4,AttributeModifier.Operation.ADD_NUMBER));
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),"version DexturReputation");operator.performCommand("repadmin reload");return true;
        }))
        .thenCompose(v->poll(()->profiles.getProfile(target.getUniqueId()),p->p.visibleScore()==100,0))
        .thenCompose(v->main(()->{operator.performCommand("rep language ru_RU");return true;}))
        .thenCompose(v->poll(()->main(()->profiles.cachedLanguage(operator.getUniqueId())==PlayerLanguage.ru_RU),Boolean::booleanValue,0))
        .thenCompose(v->gui()).thenCompose(ru->main(()->{operator.closeInventory();operator.performCommand("rep language en_US");return ru;}))
        .thenCompose(ru->poll(()->main(()->profiles.cachedLanguage(operator.getUniqueId())==PlayerLanguage.en_US),Boolean::booleanValue,0).thenCompose(v->gui()).thenApply(en->{check(!en.equals(ru),"GUI locale did not change");return true;}))
        .thenCompose(v->run>=3?poll(()->main(()->target.getAttribute(Attribute.MAX_HEALTH).getValue()==25),Boolean::booleanValue,0):CompletableFuture.completedFuture(true))
        .thenCompose(v->run>=3&&Boolean.getBoolean("dextur.probe.expect-lp")?new LuckPermsRuntimeProbe(plugin).verifyOnline(target.getUniqueId()).thenApply(x->true):CompletableFuture.completedFuture(true))
        .thenCompose(v->poll(()->main(this::papi),Boolean::booleanValue,0))
        .thenCompose(v->waitTicks(45)).thenCompose(v->main(()->{
            var team=operator.getScoreboard().getEntryTeam(operator.getName());check(team!=null&&team.getName().equals("release_foreign")&&plain(team.prefix()).equals("Foreign "),"Foreign scoreboard state replaced");check(plain(operator.playerListName()).equals("Foreign TAB"),"Foreign TAB state replaced");
            var attribute=target.getAttribute(Attribute.MAX_HEALTH);check(attribute.getModifier(foreign)!=null,"Foreign health modifier removed");check(attribute.getModifiers().stream().filter(m->m.getKey().equals(own)).count()==(run>=3?1:0),"Owned health modifier duplication");
            String mode=DisplayConfiguration.load(plugin.getServer().getPluginManager().getPlugin("DexturReputation").getDataFolder().toPath()).tab().mode().name();
            if(mode.equals("DIRECT"))check(plain(target.playerListName()).contains("100"),"Direct TAB missing");else check(!plain(target.playerListName()).contains("100"),"Disabled/placeholder-only TAB mutated name");
            plugin.getLogger().info("PROBE_RELEASE_PASS: version1.0.1, fresh/defaults, permissions, RUEN GUI, restart/restore, optional integrations, TAB="+mode+", foreign state, run="+run);
            success.run();return true;
        })).exceptionally(error->{main(()->{failure.accept(error);return null;});return null;});
    }
    private CompletionStage<String> gui(){return main(()->{operator.performCommand("repadmin gui ReleaseTarget");return true;}).thenCompose(v->poll(()->main(()->{var view=operator.getOpenInventory();var holder=view.getTopInventory().getHolder();return holder!=null&&holder.getClass().getName().endsWith("InvestigationGui$Session")&&view.getTopInventory().getItem(0)!=null?plain(view.title()):"";}),s->!s.isEmpty(),0));}
    private boolean papi()throws Exception {
        var owner=plugin.getServer().getPluginManager().getPlugin("DexturReputation");boolean registered=(boolean)owner.getClass().getMethod("hasPlaceholders").invoke(owner);check(registered==Boolean.getBoolean("dextur.probe.expect-papi"),"PAPI registration/absence");if(!registered)return true;
        var dependency=plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");var type=Class.forName("me.clip.placeholderapi.PlaceholderAPI",true,dependency.getClass().getClassLoader());var method=type.getMethod("setPlaceholders",org.bukkit.OfflinePlayer.class,String.class);String score=(String)method.invoke(null,target,"%dexturreputation_score%");if(score.isEmpty())return false;check(score.equals("100"),"Full PAPI score");String hidden=(String)method.invoke(null,target,"%dexturreputation_trust%");check(hidden.equals("%dexturreputation_trust%")||hidden.isEmpty(),"Hidden Trust exposed");return true;
    }
    private <T> CompletableFuture<T> main(Callable<T> work){var result=new CompletableFuture<T>();Runnable task=()->{try{result.complete(work.call());}catch(Throwable error){result.completeExceptionally(error);}};if(Bukkit.isPrimaryThread())task.run();else plugin.getServer().getScheduler().runTask(plugin,task);return result;}
    private CompletionStage<Boolean> waitTicks(long ticks){var result=new CompletableFuture<Boolean>();plugin.getServer().getScheduler().runTaskLater(plugin,()->result.complete(true),ticks);return result;}
    private <T> CompletionStage<T> poll(Supplier<? extends CompletionStage<T>> read,Predicate<T> ready,int attempt){return read.get().thenCompose(value->{if(ready.test(value))return CompletableFuture.completedFuture(value);if(attempt>=150)return CompletableFuture.failedFuture(new AssertionError("Release probe timeout: "+value));return waitTicks(2).thenCompose(v->poll(read,ready,attempt+1));});}
    private static String plain(Component value){return PlainTextComponentSerializer.plainText().serialize(value);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
