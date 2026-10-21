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
    private org.dextur.reputation.domain.tier.TierRules tierRules;
    private org.dextur.reputation.application.DisplayConfiguration displayConfiguration;
    private org.dextur.reputation.application.DisplayFormatter displayFormatter;
    private org.dextur.reputation.application.TierApplication tiers;
    private org.dextur.reputation.application.TierPermissionSync permissionSync;
    private ReputationDisplay display;
    private java.util.concurrent.ArrayBlockingQueue<org.dextur.reputation.api.TierService.TierChanged> notificationQueue;
    private VotingGui votingGui;
    private org.dextur.reputation.domain.policy.PolicyRules featureRules;
    private org.dextur.reputation.application.PolicyApplication policies;
    private org.dextur.reputation.domain.investigation.InvestigationData.Rules administrationRules;
    private org.dextur.reputation.application.InvestigationApplication investigation;
    private InvestigationGui investigationGui;
    private InvestigationGui.Layout administrationLayout;
    private PolicyEffects policyEffects;
    private PolicyEffects.Layout policyLayout;
    private boolean policyPlaceholders;
    private org.dextur.reputation.domain.recovery.RecoveryRules recoveryRules;
    private org.dextur.reputation.application.RecoveryApplication recovery;
    private RecoveryActivity recoveryActivity;
    private RecoveryGui recoveryGui;
    private RecoveryGui.Layout recoveryLayout;
    private boolean recoveryPlaceholders;
    private final java.util.Set<java.util.concurrent.CompletableFuture<org.dextur.reputation.domain.recovery.RecoveryData.Facts>> pendingRecoveryFacts=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private java.util.concurrent.ArrayBlockingQueue<org.dextur.reputation.api.RecoveryService.Changed> recoveryNotifications;
    private org.dextur.reputation.domain.protection.ProtectionRules protectionRules;
    // Internal capability only: never register it as a public Bukkit service.
    private org.dextur.reputation.application.ProtectionAdministration protectionAdministration;
    private final AtomicBoolean maintenance=new AtomicBoolean();
    private int onlineCursor;
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
            protectionRules=org.dextur.reputation.application.ProtectionConfiguration.load(getDataFolder().toPath());
            tierRules=org.dextur.reputation.application.TierConfiguration.load(getDataFolder().toPath());
            displayConfiguration=org.dextur.reputation.application.DisplayConfiguration.load(getDataFolder().toPath());
            displayFormatter=new org.dextur.reputation.application.DisplayFormatter(displayConfiguration);
            recoveryRules=org.dextur.reputation.application.RecoveryConfiguration.load(getDataFolder().toPath());
            featureRules=org.dextur.reputation.application.PolicyConfiguration.load(getDataFolder().toPath());
            administrationRules=org.dextur.reputation.application.AdministrationConfiguration.load(getDataFolder().toPath());
            administrationLayout=InvestigationGui.Layout.load(getDataFolder().toPath(),administrationRules.pageSize());
            policyLayout=PolicyEffects.Layout.load(getDataFolder().toPath());
            policyPlaceholders=org.dextur.reputation.application.YamlFiles.flag(org.dextur.reputation.application.TierConfiguration.configuration(getDataFolder().toPath(),"integrations.yml"),"policy-placeholders");
            recoveryLayout=RecoveryGui.Layout.load(getDataFolder().toPath());
            for(var stage:recoveryRules.program().stages()){var icon=org.bukkit.Material.matchMaterial(stage.icon());if(icon==null||!icon.isItem()||icon.isAir())throw new IllegalArgumentException("Invalid recovery stage icon");}
            recoveryPlaceholders=org.dextur.reputation.application.YamlFiles.flag(org.dextur.reputation.application.TierConfiguration.configuration(getDataFolder().toPath(),"integrations.yml"),"recovery-placeholders");
            guiConfiguration=GuiConfiguration.load(getDataFolder().toPath());
            guiConfiguration.validateTiers(tierRules);
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
        tiers=new org.dextur.reputation.application.TierApplication(application,storage,settings,tierRules,displayFormatter,error->logFailure("tier",error));
        policies=new org.dextur.reputation.application.PolicyApplication(application,storage,settings,featureRules,java.time.Clock.systemUTC(),error->logFailure("policy",error));
        var policyCommands=new PolicyCommands(this,application,policies,settings,messages);
        policyEffects=new PolicyEffects(this,policies,policyCommands,messages,policyLayout);policyCommands.effects(policyEffects);
        getServer().getPluginManager().registerEvents(policyEffects,this);
        var adminCommand=Objects.requireNonNull(getCommand("repadmin"));adminCommand.setExecutor(policyCommands);adminCommand.setTabCompleter(policyCommands);
        tiers.onTierChanged(event->onMain(()->{var player=getServer().getPlayer(event.tier().playerId());if(player!=null)policyEffects.request(player);}));
        recovery=new org.dextur.reputation.application.RecoveryApplication(application,storage,settings,recoveryRules,this::recoveryFacts,java.time.Clock.systemUTC(),error->logFailure("recovery",error));
        recoveryNotifications=new java.util.concurrent.ArrayBlockingQueue<>(settings.queueSize());
        recovery.onChanged(event->{if(event.kind()!=org.dextur.reputation.api.RecoveryService.Kind.PROGRESS_CHANGED)recoveryNotifications.offer(event);});
        var recoveryCommands=new RecoveryCommands(this,application,recovery,messages,settings.defaultLanguage());
        policyCommands.recovery(recoveryCommands);
        recoveryGui=new RecoveryGui(this,recovery,recoveryCommands,messages,recoveryLayout,recoveryRules);recoveryCommands.gui(recoveryGui);
        recoveryActivity=new RecoveryActivity(this,recovery,recoveryRules,messages,application);
        getServer().getPluginManager().registerEvents(recoveryGui,this);getServer().getPluginManager().registerEvents(recoveryActivity,this);
        var notifications=new TierNotifications(this,application,tierRules,displayConfiguration,displayFormatter,messages,settings.cacheSize());
        notificationQueue=new java.util.concurrent.ArrayBlockingQueue<>(settings.queueSize());
        tiers.onTierChanged(event->notificationQueue.offer(event));
        var voting=new org.dextur.reputation.application.VotingApplication(application,storage,settings,votingConfiguration,
                this::voterFacts,java.time.Clock.systemUTC(),error->logFailure("vote listener",error));
        protectionAdministration=new org.dextur.reputation.application.ProtectionAdministration(application,voting,storage,settings,protectionRules,java.time.Clock.systemUTC());
        investigation=new org.dextur.reputation.application.InvestigationApplication(application,storage,voting,administrationRules,settings.scope(),java.time.Clock.systemUTC(),error->logFailure("investigation event",error));
        var investigationCommands=new InvestigationCommands(this,application,investigation,settings,messages,policyCommands);
        investigationGui=new InvestigationGui(this,investigation,investigationCommands,messages,administrationLayout);investigationCommands.gui=investigationGui;
        adminCommand.setExecutor(investigationCommands);adminCommand.setTabCompleter(investigationCommands);
        getServer().getPluginManager().registerEvents(investigationGui,this);
        investigation.onCommitted(event->onMain(()->{var player=getServer().getPlayer(event.target());if(player!=null)policyEffects.request(player);}));
        var votingCommands=new VotingCommands(this,application,voting,votingConfiguration,messages,settings.defaultLanguage());
        investigationCommands.votes=votingCommands;
        votingCommands.entryLookup(id->application.submit(()->storage.operation(id)));
        var gui=new VotingGui(this,voting,votingCommands,votingConfiguration,guiConfiguration,messages);
        votingGui=gui;gui.policies(policyEffects);gui.recovery(recoveryGui);recoveryGui.voting(gui);gui.tiers(tiers,displayFormatter,tierRules);
        votingCommands.gui(gui);
        voting.onCommitted(event->onMain(()->votingCommands.notifyTarget(event)));
        voting.onStatusChanged(event->onMain(()->votingCommands.notifyStatus(event)));
        getServer().getPluginManager().registerEvents(gui,this);
        for(String name:new String[]{"like","dislike"}) {
            var command=Objects.requireNonNull(getCommand(name));command.setExecutor(votingCommands);command.setTabCompleter(votingCommands);
        }
        ReputationCommands commands = new ReputationCommands(this, application, settings, messages);
        commands.policies(policyCommands);commands.voting(votingCommands);commands.recovery(recoveryCommands);
        for (String name : new String[]{"dexturreputation", "rep"}) {
            var command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(commands); command.setTabCompleter(commands);
        }
        getServer().getPluginManager().registerEvents(this, this);
        Path dataDirectory = getDataFolder().toPath();
        application.submit(() -> { storage.protection(protectionRules);storage.tiers(tierRules);storage.recovery(recoveryRules);storage.features(featureRules);storage.investigation(administrationRules);storage.open(dataDirectory, settings, System.getenv()); return null; })
                .whenComplete((ignored, error) -> onMain(() -> {
                    if (error != null) {
                        getComponentLogger().error(messages.render(settings.defaultLanguage(), "storage-failed",
                                Map.of("type", settings.storage().type().name(), "error", root(error).getClass().getSimpleName())));
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                    }
                    getServer().getServicesManager().register(ReputationService.class, application, this, ServicePriority.Normal);
                    getServer().getServicesManager().register(org.dextur.reputation.api.VotingService.class,voting,this,ServicePriority.Normal);
                    getServer().getServicesManager().register(org.dextur.reputation.api.TierService.class,tiers,this,ServicePriority.Normal);
                    getServer().getServicesManager().register(org.dextur.reputation.api.RecoveryService.class,recovery,this,ServicePriority.Normal);
                    getServer().getServicesManager().register(org.dextur.reputation.api.ReputationPolicyService.class,policies,this,ServicePriority.Normal);
                    ready = true;
                    getServer().getScheduler().runTaskTimer(this,policyEffects::tick,20L,20L);
                    getServer().getScheduler().runTaskTimer(this,recoveryActivity::tick,20L,20L);
                    display=new ReputationDisplay(this,application,tiers,displayFormatter,displayConfiguration);
                    getServer().getScheduler().runTaskTimer(this,()->{
                        display.tick();
                        for(int i=0;i<displayConfiguration.batch();i++){var event=recoveryNotifications.poll();if(event==null)break;if(!recoveryCommands.notifyEvent(event)){recoveryNotifications.offer(event);break;}}
                        for(int i=0;i<displayConfiguration.batch();i++) {var event=notificationQueue.poll();if(event==null)break;notifications.accept(event);}
                    },1L,displayConfiguration.interval());
                    if(displayConfiguration.luckperms().enabled()) {
                        if(getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
                            try {
                                permissionSync=new org.dextur.reputation.application.TierPermissionSync(application,storage,settings.scope(),displayConfiguration.luckperms().batch(),
                                    new LuckPermsBridge(net.luckperms.api.LuckPermsProvider.get(),settings.scope(),displayConfiguration.luckperms().createGroups()),
                                    failure->getComponentLogger().warn(messages.render(settings.defaultLanguage(),"tier-sync-failed",Map.of("reason",root(failure).getClass().getSimpleName()))));
                                getServer().getScheduler().runTaskTimer(this,()->permissionSync.tick(),20L,20L*displayConfiguration.luckperms().retrySeconds());
                            }catch(LinkageError|RuntimeException failure){logFailure("LuckPerms",failure);}
                        }else getComponentLogger().warn(messages.render(settings.defaultLanguage(),"tier-luckperms-missing",Map.of()));
                    }
                    if (settings.placeholderApi() && getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                        try { placeholders = new PlaceholderBridge(this, application, messages); placeholders.tiers(tiers,displayFormatter);placeholders.recovery(recovery,recoveryPlaceholders);placeholders.policies(policies,policyPlaceholders);placeholders.healthEffects(policyEffects);placeholders.register(); }
                        catch (LinkageError | RuntimeException e) {
                            placeholders = null;
                            getLogger().warning("PlaceholderAPI integration unavailable: " + e.getClass().getSimpleName());
                        }
                    }
                    getServer().getOnlinePlayers().forEach(p -> {warm(p.getUniqueId());initializeTrust(p);recoveryActivity.join(p);});
                    getServer().getScheduler().runTaskTimer(this, () ->
                            getServer().getOnlinePlayers().forEach(p -> warm(p.getUniqueId())),
                            20L, Math.max(20L, settings.cacheSeconds() * 10L));
                    getServer().getScheduler().runTaskTimer(this,this::maintenance,1200L,1200L);
                    getServer().getScheduler().runTaskTimer(this,()->protectionAdministration.analyze().exceptionally(analysisError->{logProtectionFailure(analysisError);return false;}),
                            20L*protectionRules.analysis().intervalSeconds(),20L*protectionRules.analysis().intervalSeconds());
                    getLogger().info("READY: ReputationService registered; VotingService registered; Paper 26.2 " + getPluginMeta().getVersion() + " enabled.");
                }));
    }

    org.dextur.reputation.application.TierPermissionSync permissionSynchronizer() {return permissionSync;}
    String tierName(UUID id,org.dextur.reputation.domain.PlayerLanguage language) {
        return tiers==null?messages.plain(language,"tier-neutral"):tiers.cachedTier(id).map(t->org.dextur.reputation.application.DisplayFormatter.plain(displayFormatter.name(t,language))).orElse(messages.plain(language,"tier-neutral"));
    }
    String tierLabel(String id,org.dextur.reputation.domain.PlayerLanguage language){
        var definition=tierRules==null?null:tierRules.byId(id);
        if(definition==null)return messages.plain(language,"tier-neutral");
        return org.dextur.reputation.application.DisplayFormatter.plain(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(definition.names().getOrDefault(language.name(),definition.names().get("en_US"))));
    }
    private void defaults() throws IOException {
        Path directory = getDataFolder().toPath();
        Files.createDirectories(directory.resolve("lang"));
        for (String file : new String[]{"config.yml", "storage.yml", "integrations.yml", "voting.yml", "gui.yml", "trust.yml", "anti-abuse.yml", "tiers.yml", "display.yml", "recovery.yml", "features.yml", "administration.yml", "lang/ru_RU.yml", "lang/en_US.yml"}) {
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

    private java.util.concurrent.CompletionStage<org.dextur.reputation.domain.recovery.RecoveryData.Facts> recoveryFacts(UUID id){
        var result=new java.util.concurrent.CompletableFuture<org.dextur.reputation.domain.recovery.RecoveryData.Facts>();
        if(stopping.get())return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Recovery stopping"));
        pendingRecoveryFacts.add(result);result.whenComplete((value,error)->pendingRecoveryFacts.remove(result));
        onMain(()->{try{result.complete(RecoveryActivity.facts(getServer().getOfflinePlayer(id)));}catch(RuntimeException e){result.completeExceptionally(e);}});
        return result;
    }
    private void warm(UUID playerId) {
        if (!ready) return;
        application.getProfile(playerId).exceptionally(error -> { logFailure("profile warmup", error); return null; });
        application.language(playerId).exceptionally(error -> { logFailure("language warmup", error); return null; });
    }
    private org.dextur.reputation.domain.VoterFacts verifiedFacts(org.bukkit.entity.Player player) {
        return new org.dextur.reputation.domain.VoterFacts(true,true,player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE)/20L,
                java.time.Instant.ofEpochMilli(player.getFirstPlayed()),true,true,true);
    }
    private void initializeTrust(org.bukkit.entity.Player player) {
        if(ready) protectionAdministration.initialize(player.getUniqueId(),verifiedFacts(player)).exceptionally(error->{logProtectionFailure(error);return false;});
    }
    private void maintenance() {
        if(!ready||!maintenance.compareAndSet(false,true)) return;
        var online=getServer().getOnlinePlayers().stream().sorted(java.util.Comparator.comparing(org.bukkit.entity.Player::getUniqueId)).toList();
        var jobs=new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
        if(protectionRules.trust().recalculate()&&!online.isEmpty()) {
            int count=Math.min(online.size(),protectionRules.trust().batchSize());
            for(int i=0;i<count;i++) {
                var player=online.get(Math.floorMod(onlineCursor++,online.size()));var id=player.getUniqueId();var facts=verifiedFacts(player);
                jobs.add(protectionAdministration.trust(id).thenCompose(record->record==null?protectionAdministration.initialize(id,facts):
                        java.time.Instant.now().isBefore(record.calculatedAt().plusSeconds(protectionRules.trust().intervalMinutes()*60L))?
                                java.util.concurrent.CompletableFuture.completedFuture(false):protectionAdministration.refresh(id,record.revision(),facts)).toCompletableFuture());
            }
        }
        jobs.add(protectionAdministration.recalculateDue().toCompletableFuture());
        if(settings.storage().type()==org.dextur.reputation.application.StorageType.JSON) jobs.add(protectionAdministration.storageBytes().thenAccept(bytes->{
            if(bytes>=settings.storage().maximumJsonBytes()*Math.min(protectionRules.jsonWarningFraction(),administrationRules.jsonWarningPercent()*10000L)/1_000_000L)
                getComponentLogger().warn(messages.render(settings.defaultLanguage(),"protection-storage-growth",Map.of("bytes",Long.toString(bytes),"limit",Integer.toString(settings.storage().maximumJsonBytes()))));
        }).toCompletableFuture());
        java.util.concurrent.CompletableFuture.allOf(jobs.toArray(java.util.concurrent.CompletableFuture[]::new)).whenComplete((ignored,error)->{
            maintenance.set(false);if(error!=null) logProtectionFailure(error);
        });
    }
    private void logProtectionFailure(Throwable error) {
        if(root(error) instanceof org.dextur.reputation.storage.JsonStorage.LimitException){logFailure("protection",error);return;}
        getComponentLogger().warn(messages.render(settings.defaultLanguage(),"protection-processing-failed",Map.of("error",root(error).getClass().getSimpleName())));
    }
    @EventHandler public void join(PlayerJoinEvent event) { warm(event.getPlayer().getUniqueId());initializeTrust(event.getPlayer());if(ready()&&recoveryActivity!=null)recoveryActivity.join(event.getPlayer());if(ready()&&policyEffects!=null)policyEffects.request(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) { if(policyEffects!=null)policyEffects.quit(event.getPlayer());if(recoveryActivity!=null)recoveryActivity.quit(event.getPlayer()); if(display!=null)display.quit(event.getPlayer());if(tiers!=null)tiers.forget(event.getPlayer().getUniqueId());if(application!=null)application.forget(event.getPlayer().getUniqueId()); }
    public boolean ready() { return ready && !stopping.get(); }
    org.dextur.reputation.application.InvestigationApplication investigationAdministration(){return investigation;}
    org.dextur.reputation.application.PolicyApplication policyAdministration(){return policies;}
    org.dextur.reputation.application.ProtectionAdministration protectionAdministration() { return protectionAdministration; }
    public String storageType() { return settings.storage().type().name(); }
    public boolean hasPlaceholders() { return placeholders != null && placeholders.isRegistered(); }
    private final java.util.concurrent.atomic.AtomicLong capacityWarning = new java.util.concurrent.atomic.AtomicLong();
    public void logFailure(String operation, Throwable error) {
        // Never print SQLException messages, credentials, URLs, SQL parameters or player input.
        if(root(error) instanceof org.dextur.reputation.storage.JsonStorage.LimitException capacity){
            long now=System.nanoTime(),previous=capacityWarning.get();
            if((previous==0||now-previous>=java.util.concurrent.TimeUnit.MINUTES.toNanos(1))&&capacityWarning.compareAndSet(previous,now))
                getComponentLogger().warn(messages.render(settings.defaultLanguage(),"storage-json-limit",Map.of("limit",Integer.toString(capacity.maximumBytes))));return;
        }
        getLogger().warning(operation + " failed: " + root(error).getClass().getSimpleName());
    }
    private static Throwable root(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) error = error.getCause();
        return error;
    }
    @Override public void onDisable() {
        if(investigation!=null)investigation.close();
        if(investigationGui!=null)investigationGui.closeAll();
        if(policyEffects!=null)policyEffects.close();
        if(recoveryActivity!=null)recoveryActivity.close();
        stopping.set(true); ready = false;
        if(protectionAdministration!=null) protectionAdministration.stop();
        if(permissionSync!=null)permissionSync.close();
        if(display!=null)display.close();
        if(recoveryGui!=null)recoveryGui.closeAll();
        if(tiers!=null)tiers.close();
        if(notificationQueue!=null)notificationQueue.clear();
        if(votingGui!=null) { votingGui.closeAll();votingGui=null; }
        pendingRecoveryFacts.forEach(future->future.completeExceptionally(new IllegalStateException("Plugin stopping")));
        if(recoveryNotifications!=null)recoveryNotifications.clear();
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
        if(recovery!=null)recovery.close();
        if(policies!=null)policies.close();
        getLogger().info("STOPPED: services unregistered; database shutdown requested.");
    }
}
