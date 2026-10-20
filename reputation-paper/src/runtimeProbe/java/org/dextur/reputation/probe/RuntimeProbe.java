package org.dextur.reputation.probe;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.dextur.reputation.api.ReputationService;
import org.dextur.reputation.api.ReputationSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Isolated real-server assertions. Success requires the explicit PROBE_PASS marker. */
public final class RuntimeProbe extends JavaPlugin {
    private static final UUID PLAYER = UUID.fromString("1222bc3d-d4e5-4678-9abc-1234567890ab");
    @Override public void onEnable() {
        new BukkitRunnable() {
            int attempts;
            @Override public void run() {
                ReputationService service = getServer().getServicesManager().load(ReputationService.class);
                if (service == null) {
                    if (++attempts > 30) { cancel(); fail(new IllegalStateException("API registration timed out")); }
                    return;
                }
                cancel();
                service.getProfile(PLAYER).thenCombine(service.getProfile(PLAYER), (first, second) -> {
                    if (!first.equals(second)) throw new AssertionError("Profile uniqueness failed");
                    return first;
                }).whenComplete((profile, error) -> getServer().getScheduler().runTask(RuntimeProbe.this, () -> {
                    if (error != null) { fail(error); return; }
                    try { verify(profile); } catch (Throwable failure) { fail(failure); }
                }));
            }
        }.runTaskTimer(this, 20, 20);
    }
    private void verify(ReputationSnapshot profile) throws Exception {
        if (profile.visibleScore() != 0 || !profile.tier().equals("NEUTRAL") || !profile.scopeId().equals("global")) throw new AssertionError("Wrong profile defaults");
        Path state = getDataFolder().toPath().resolve("created-at.txt");
        Files.createDirectories(state.getParent());
        if (Files.exists(state) && !Files.readString(state).equals(profile.createdAt().toString())) throw new AssertionError("Profile did not survive server restart");
        Files.writeString(state, profile.createdAt().toString());
        List<String> replies = new ArrayList<>();
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
            if (method.getName().equals("sendMessage") && args != null) {
                for (Object value : args) {
                    if (value instanceof Component component) replies.add(PlainTextComponentSerializer.plainText().serialize(component));
                    if (value instanceof String text) replies.add(text);
                }
            }
            return switch (method.getName()) {
                case "hasPermission", "isPermissionSet", "isOp" -> true;
                case "getName" -> "Phase1Probe";
                case "getServer" -> getServer();
                case "toString" -> "Phase1Probe";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> method.getReturnType() == boolean.class ? false : null;
            };
        });
        // CraftServer.dispatchCommand accepts only Craft/Bukkit native senders. Execute the
        // registered PluginCommands to capture Adventure replies without faking Craft internals.
        getServer().getPluginCommand("dexturreputation").execute(sender, "dexturreputation", new String[]{"status"});
        getServer().getPluginCommand("dexturreputation").execute(sender, "dexturreputation", new String[]{"version"});
        getServer().getPluginCommand("rep").execute(sender, "rep", new String[0]);
        getServer().getPluginCommand("rep").execute(sender, "rep", new String[]{"zzz_unknown_741"});
        getServer().getPluginCommand("dexturreputation").execute(sender, "dexturreputation", new String[]{"storage"});
        Object configured = getServer().getPluginManager().getPlugin("DexturReputation");
        String storage = (String) configured.getClass().getMethod("storageType").invoke(configured);
        if (!storage.equals(System.getProperty("dextur.probe.storage", "JSON"))) throw new AssertionError("Wrong active storage: " + storage);
        getServer().dispatchCommand(getServer().getConsoleSender(), "dexturreputation status");
        if (replies.size() != 5 || replies.stream().noneMatch(s -> s.contains("готов"))
                || replies.stream().noneMatch(s -> s.contains("только игрокам"))
                || replies.stream().noneMatch(s -> s.contains("не найден"))) throw new AssertionError("Diagnostic/unknown player commands failed: " + replies);
        Object expansionManager = null;
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            var papi = getServer().getPluginManager().getPlugin("PlaceholderAPI");
            expansionManager = papi.getClass().getMethod("getLocalExpansionManager").invoke(papi);
            Object expansion = expansionManager.getClass().getMethod("getExpansion", String.class).invoke(expansionManager, "dexturreputation");
            if (expansion == null) throw new AssertionError("PlaceholderAPI expansion not registered");
            var offline = getServer().getOfflinePlayer(PLAYER); // UUID lookup only, no name/network resolution.
            var request = expansion.getClass().getSuperclass().getMethod("onRequest", org.bukkit.OfflinePlayer.class, String.class);
            if (!"0".equals(request.invoke(expansion, offline, "score"))) throw new AssertionError("Placeholder score failed");
            if (!"0".equals(request.invoke(expansion, offline, "score_formatted"))) throw new AssertionError("Formatted placeholder failed");
            if (!"Нейтральный".equals(request.invoke(expansion, offline, "tier"))) throw new AssertionError("Tier placeholder failed");
            getLogger().info("PROBE_PAPI_PASS: registration and all three cached placeholders");
        }
        Object checkedExpansionManager=expansionManager;
        if(Boolean.getBoolean("dextur.probe.recovery"))new RecoveryRuntimeProbe(this).start(()->{try{finish(checkedExpansionManager);}catch(Throwable failure){fail(failure);}},this::fail);
        else if(Boolean.getBoolean("dextur.probe.tiers")) new TierRuntimeProbe(this).start(()->{
            try {finish(checkedExpansionManager);}catch(Throwable failure){fail(failure);}
        },this::fail);
        else if(Boolean.getBoolean("dextur.probe.protection")) new ProtectionRuntimeProbe(this).start(()->{
            try { finish(checkedExpansionManager); } catch(Throwable failure) { fail(failure); }
        },this::fail);
        else if(Boolean.getBoolean("dextur.probe.voting")) new VotingRuntimeProbe(this).start(()->{
            try { finish(checkedExpansionManager); } catch(Throwable failure) { fail(failure); }
        },this::fail);
        else finish(expansionManager);
    }
    private void finish(Object expansionManager) throws Exception {
        // Resolve the dependency class while its plugin classloader is still enabled.
        Class<org.dextur.reputation.api.VotingService> votingApi=org.dextur.reputation.api.VotingService.class;
        Class<org.dextur.reputation.api.RecoveryService> recoveryApi=org.dextur.reputation.api.RecoveryService.class;
        Class<org.dextur.reputation.api.TierService> tierApi=org.dextur.reputation.api.TierService.class;
        var target = getServer().getPluginManager().getPlugin("DexturReputation");
        getServer().getPluginManager().disablePlugin(target);
        for(var player:getServer().getOnlinePlayers()) {
            var holder=player.getOpenInventory().getTopInventory().getHolder();
            if(holder!=null&&(holder.getClass().getName().endsWith("VotingGui$Session")||holder.getClass().getName().endsWith("RecoveryGui$Session"))) throw new AssertionError("GUI remained open after listener cleanup");
        }
        if(getServer().getServicesManager().load(recoveryApi)!=null)throw new AssertionError("Recovery service leaked on shutdown");
        if(getServer().getServicesManager().load(tierApi)!=null)throw new AssertionError("Tier API not unregistered");
        for(var viewer:getServer().getOnlinePlayers())for(var team:viewer.getScoreboard().getTeams())if(team.getName().startsWith("drt_"))throw new AssertionError("Owned display team leaked on disable");
        if (getServer().getServicesManager().load(votingApi) != null) throw new AssertionError("Voting API not unregistered");
        if (getServer().getServicesManager().load(ReputationService.class) != null) throw new AssertionError("API not unregistered on shutdown");
        if (expansionManager != null && expansionManager.getClass().getMethod("getExpansion", String.class).invoke(expansionManager, "dexturreputation") != null) throw new AssertionError("Placeholder expansion not unregistered");
        getLogger().info("PROBE_PASS: startup, API, zero profile, uniqueness, restart persistence, diagnostics, unknown player, shutdown cleanup");
        getServer().shutdown();
    }
    private void fail(Throwable failure) {
        getLogger().severe("PROBE_FAIL: " + failure);
        getServer().shutdown();
    }
}
