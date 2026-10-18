package org.dextur.reputation.paper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.dextur.reputation.api.ReputationService;
import org.dextur.reputation.application.Messages;
import org.dextur.reputation.application.ReputationApplication;
import org.dextur.reputation.application.Settings;
import org.dextur.reputation.storage.StorageManager;

public final class DexturReputationPlugin extends JavaPlugin implements Listener {
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile boolean ready;
    private ReputationApplication application;
    private Settings settings;
    private Messages messages;
    private PlaceholderBridge placeholders;
    private org.dextur.reputation.application.VotingConfiguration votingConfiguration;
    private GuiConfiguration guiConfiguration;
    private VotingGui votingGui;
    private final java.util.Set<java.util.concurrent.CompletableFuture<org.dextur.reputation.domain.VoterFacts>> pendingFacts=java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override public void onEnable() {
        stopping.set(false);
        org.dextur.reputation.domain.PlayerLanguage startupLanguage=org.dextur.reputation.domain.PlayerLanguage.ru_RU;
        try {
            defaults();
            startupLanguage=org.dextur.reputation.domain.PlayerLanguage.parse(org.dextur.reputation.application.YamlFiles.text(
                    org.dextur.reputation.application.YamlFiles.read(getDataFolder().toPath().resolve("config.yml")),"default-language"));
            // Build the complete immutable configuration before exposing any service.
            settings = Settings.load(getDataFolder().toPath());
            messages = new Messages(getDataFolder().toPath().resolve("lang"), settings.defaultLanguage());
            votingConfiguration=org.dextur.reputation.application.VotingConfiguration.load(getDataFolder().toPath(),messages);
            guiConfiguration=GuiConfiguration.load(getDataFolder().toPath());
            if(votingConfiguration.pageSize()>guiConfiguration.contentSlots().size()) throw new IllegalArgumentException("History page exceeds GUI content slots");
        } catch (Exception e) {
            try {
                getComponentLogger().error(Messages.bundled(startupLanguage).render(startupLanguage,"storage-config-invalid",
                        Map.of("reason",e instanceof IllegalArgumentException ? String.valueOf(e.getMessage()) : e.getClass().getSimpleName())));
            } catch (Exception localization) { getLogger().severe("Configuration rejected / Конфигурация отклонена"); }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        StorageManager storage = new StorageManager();
        application = new ReputationApplication(storage, settings, storage::close);
        var voting=new org.dextur.reputation.application.VotingApplication(application,storage,settings,votingConfiguration,
                this::voterFacts,java.time.Clock.systemUTC(),error->logFailure("vote listener",error));
        var votingCommands=new VotingCommands(this,application,voting,votingConfiguration,messages,settings.defaultLanguage());
        var gui=new VotingGui(this,voting,votingCommands,votingConfiguration,guiConfiguration,messages);
        votingGui=gui;
        votingCommands.gui(gui);
        voting.onCommitted(event->onMain(()->votingCommands.notifyTarget(event)));
        getServer().getPluginManager().registerEvents(gui,this);
        for(String name:new String[]{"like","dislike"}) {
            var command=Objects.requireNonNull(getCommand(name));command.setExecutor(votingCommands);command.setTabCompleter(votingCommands);
        }
        ReputationCommands commands = new ReputationCommands(this, application, settings, messages);
        commands.voting(votingCommands);
        for (String name : new String[]{"dexturreputation", "rep"}) {
            var command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(commands); command.setTabCompleter(commands);
        }
        getServer().getPluginManager().registerEvents(this, this);
        Path dataDirectory = getDataFolder().toPath();
        application.submit(() -> { storage.open(dataDirectory, settings, System.getenv()); return null; })
                .whenComplete((ignored, error) -> onMain(() -> {
                    if (error != null) {
                        getComponentLogger().error(messages.render(settings.defaultLanguage(), "storage-failed",
                                Map.of("type", settings.storage().type().name(), "error", root(error).getClass().getSimpleName())));
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                    }
                    getServer().getServicesManager().register(ReputationService.class, application, this, ServicePriority.Normal);
                    getServer().getServicesManager().register(org.dextur.reputation.api.VotingService.class,voting,this,ServicePriority.Normal);
                    ready = true;
                    if (settings.placeholderApi() && getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                        try { placeholders = new PlaceholderBridge(this, application, messages); placeholders.register(); }
                        catch (LinkageError | RuntimeException e) {
                            placeholders = null;
                            getLogger().warning("PlaceholderAPI integration unavailable: " + e.getClass().getSimpleName());
                        }
                    }
                    getServer().getOnlinePlayers().forEach(p -> warm(p.getUniqueId()));
                    getServer().getScheduler().runTaskTimer(this, () ->
                            getServer().getOnlinePlayers().forEach(p -> warm(p.getUniqueId())),
                            20L, Math.max(20L, settings.cacheSeconds() * 10L));
                    getLogger().info("READY: ReputationService registered; VotingService registered; Paper 26.2 Phase 2 enabled.");
                }));
    }

    private void defaults() throws IOException {
        Path directory = getDataFolder().toPath();
        Files.createDirectories(directory.resolve("lang"));
        for (String file : new String[]{"config.yml", "storage.yml", "integrations.yml", "voting.yml", "gui.yml", "lang/ru_RU.yml", "lang/en_US.yml"}) {
            if (!Files.exists(directory.resolve(file))) saveResource(file, false);
        }
    }

    public void onMain(Runnable task) {
        if (stopping.get() || !isEnabled()) return;
        if (Bukkit.isPrimaryThread()) { task.run(); return; }
        try { getServer().getScheduler().runTask(this, () -> { if (!stopping.get()) task.run(); }); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Disable won the race. */ }
    }

    private java.util.concurrent.CompletionStage<org.dextur.reputation.domain.VoterFacts> voterFacts(org.dextur.reputation.api.VoteRequest request) {
        var result=new java.util.concurrent.CompletableFuture<org.dextur.reputation.domain.VoterFacts>();
        if(stopping.get()||!isEnabled()) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Plugin stopping"));
        pendingFacts.add(result);result.whenComplete((value,error)->pendingFacts.remove(result));
        onMain(()->{
            try {
                var voter=getServer().getPlayer(request.voterId());var target=getServer().getOfflinePlayer(request.targetId());
                var nodes=votingConfiguration.permissions();
                result.complete(new org.dextur.reputation.domain.VoterFacts(voter!=null,target.isOnline()||target.hasPlayedBefore(),
                        voter==null?0:voter.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE)/20L,
                        voter==null?null:java.time.Instant.ofEpochMilli(voter.getFirstPlayed()),
                        voter!=null&&voter.hasPermission(nodes.get("vote"))&&voter.hasPermission(nodes.get("positive")),
                        voter!=null&&voter.hasPermission(nodes.get("vote"))&&voter.hasPermission(nodes.get("negative")),
                        voter!=null&&voter.hasPermission(nodes.get("vote"))&&voter.hasPermission(nodes.get("revoke"))));
            } catch(RuntimeException error) { result.completeExceptionally(error); }
        });
        if(stopping.get()) result.completeExceptionally(new IllegalStateException("Plugin stopping"));
        return result;
    }

    private void warm(UUID playerId) {
        if (!ready) return;
        application.getProfile(playerId).exceptionally(error -> { logFailure("profile warmup", error); return null; });
        application.language(playerId).exceptionally(error -> { logFailure("language warmup", error); return null; });
    }
    @EventHandler public void join(PlayerJoinEvent event) { warm(event.getPlayer().getUniqueId()); }
    @EventHandler public void quit(PlayerQuitEvent event) { if (application != null) application.forget(event.getPlayer().getUniqueId()); }
    public boolean ready() { return ready && !stopping.get(); }
    public String storageType() { return settings.storage().type().name(); }
    public boolean hasPlaceholders() { return placeholders != null && placeholders.isRegistered(); }
    public void logFailure(String operation, Throwable error) {
        // Never print SQLException messages, credentials, URLs, SQL parameters or player input.
        getLogger().warning(operation + " failed: " + root(error).getClass().getSimpleName());
    }
    private static Throwable root(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) error = error.getCause();
        return error;
    }
    @Override public void onDisable() {
        stopping.set(true); ready = false;
        if(votingGui!=null) { votingGui.closeAll();votingGui=null; }
        pendingFacts.forEach(future->future.completeExceptionally(new IllegalStateException("Plugin stopping")));
        getServer().getScheduler().cancelTasks(this);
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        getServer().getServicesManager().unregisterAll(this);
        if (placeholders != null) { placeholders.unregister(); placeholders = null; }
        if (application != null) {
            try { application.close(); }
            catch (IllegalStateException e) {
                getLogger().severe("Shutdown deadline exceeded; accepted operations continue draining before pool cleanup.");
            }
        }
        getLogger().info("STOPPED: services unregistered; database shutdown requested.");
    }
}
