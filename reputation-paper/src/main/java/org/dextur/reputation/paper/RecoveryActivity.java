package org.dextur.reputation.paper;
import java.time.Instant;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.dextur.reputation.application.RecoveryApplication;
import org.dextur.reputation.domain.recovery.*;
import org.dextur.reputation.domain.recovery.RecoveryData.Facts;

/** Paper facts are captured on the server thread; persistence uses only the bounded application executor. */
final class RecoveryActivity implements Listener,AutoCloseable {
    private final org.dextur.reputation.application.Messages messages;private final org.dextur.reputation.application.ReputationApplication app;private long capacityWarningAt;private final DexturReputationPlugin plugin;private final RecoveryApplication service;private final RecoveryRules rules;private final ActivityTracker tracker;private final Set<UUID> inFlight=new HashSet<>(),logged=new HashSet<>();private final Map<UUID,Long> persisted=new HashMap<>();private int cursor;private boolean closed;
    RecoveryActivity(DexturReputationPlugin plugin,RecoveryApplication service,RecoveryRules rules,org.dextur.reputation.application.Messages messages,org.dextur.reputation.application.ReputationApplication app){this.messages=messages;this.app=app;this.plugin=plugin;this.service=service;this.rules=rules;tracker=new ActivityTracker(rules.inactivitySeconds(),rules.minimumActivity(),rules.maximumTracked());}
    static Facts facts(org.bukkit.OfflinePlayer p){return new Facts(p.isOnline()||p.hasPlayedBefore(),Math.max(0,p.getStatistic(Statistic.PLAY_ONE_MINUTE)/20L),Instant.ofEpochMilli(Math.max(0,p.getFirstPlayed())));}
    void join(Player p){if(closed)return;if(tracker.join(p.getUniqueId(),System.nanoTime()))flush(p);else{var language=app.cachedLanguage(p.getUniqueId());p.sendMessage(messages.render(language,"recovery-error",Map.of("reason",messages.plain(language,"recovery-reason-capacity"))));long now=System.nanoTime();if(now-capacityWarningAt>30000000000L){capacityWarningAt=now;plugin.getComponentLogger().warn(messages.content(language,"recovery-reason-capacity",Map.of()));}}}
    private boolean eligible(Player p){return p.getGameMode()!=GameMode.SPECTATOR&&!p.isDead()&&!p.isInsideVehicle();}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void move(PlayerMoveEvent e){if(e instanceof PlayerTeleportEvent||!eligible(e.getPlayer())||!e.getFrom().getWorld().equals(e.getTo().getWorld()))return;var a=e.getFrom();var b=e.getTo();tracker.movement(e.getPlayer().getUniqueId(),System.nanoTime(),b.getX(),b.getZ());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void broken(BlockBreakEvent e){if(eligible(e.getPlayer()))signal(e.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void placed(BlockPlaceEvent e){if(eligible(e.getPlayer()))signal(e.getPlayer());}
    private void signal(Player p){tracker.meaningful(p.getUniqueId(),System.nanoTime());}
    void tick(){if(closed||!plugin.ready())return;var players=plugin.getServer().getOnlinePlayers().stream().sorted(Comparator.comparing(Player::getUniqueId)).toList();if(players.isEmpty())return;for(int i=0;i<Math.min(players.size(),rules.batchSize());i++){var p=players.get(Math.floorMod(cursor++,players.size()));if(tracker.sample(p.getUniqueId(),System.nanoTime())==null)join(p);else if(!persisted.containsKey(p.getUniqueId())||System.nanoTime()-persisted.getOrDefault(p.getUniqueId(),0L)>=rules.persistenceSeconds()*1000000000L)flush(p);}}
    private void flush(Player p){flush(p,false);}
    private void flush(Player p,boolean force){var id=p.getUniqueId();if(!force&&inFlight.contains(id))return;var sample=tracker.sample(id,System.nanoTime());if(sample==null)return;var facts=facts(p);inFlight.add(id);service.sample(id,facts,sample.session(),sample.connectedSeconds(),sample.activeSeconds(),!logged.contains(id)).whenComplete((snapshot,error)->plugin.onMain(()->{inFlight.remove(id);if(error==null){if(p.isOnline()){logged.add(id);persisted.put(id,System.nanoTime());}else service.forget(id);}else plugin.logFailure("recovery activity",error);}));}
    void quit(Player p){flush(p,true);tracker.quit(p.getUniqueId());logged.remove(p.getUniqueId());persisted.remove(p.getUniqueId());service.forget(p.getUniqueId());}
    @Override public void close(){if(closed)return;for(var p:plugin.getServer().getOnlinePlayers())flush(p,true);closed=true;tracker.close();inFlight.clear();logged.clear();persisted.clear();}
}
