package org.dextur.reputation.paper;

import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.dextur.reputation.api.TierService;
import org.dextur.reputation.application.*;
import net.kyori.adventure.text.Component;

/** Main-thread adapter. Owns only teams it created; yields foreign teams/list names. */
final class ReputationDisplay implements AutoCloseable {
    private record Owned(Team team,String entry,Component suffix) { }
    private record Tab(Component before,Component written,boolean yielded) { }
    private final DexturReputationPlugin plugin;
    private final ReputationApplication application;
    private final TierService tiers;
    private final DisplayFormatter formatter;
    private final DisplayConfiguration config;
    private final Map<Scoreboard,Map<UUID,Owned>> owned=new IdentityHashMap<>();
    private final Map<UUID,Tab> tabs=new HashMap<>();
    private int cursor,teamCount;
    private final boolean tabConflict;
    ReputationDisplay(DexturReputationPlugin plugin,ReputationApplication application,TierService tiers,DisplayFormatter formatter,DisplayConfiguration config) {
        this.plugin=plugin;this.application=application;this.tiers=tiers;this.formatter=formatter;this.config=config;
        tabConflict=java.util.stream.Stream.of("TAB","AnimatedTabList","CMI").anyMatch(name->plugin.getServer().getPluginManager().isPluginEnabled(name));
    }
    void tick() {
        var players=List.copyOf(plugin.getServer().getOnlinePlayers());if(players.isEmpty()) return;
        Set<Scoreboard> visible=Collections.newSetFromMap(new IdentityHashMap<>());for(var p:players) visible.add(p.getScoreboard());
        for(var board:new ArrayList<>(owned.keySet())) if(!visible.contains(board)) removeBoard(board);
        for(int i=0;i<Math.min(config.batch(),players.size());i++) update(players.get(Math.floorMod(cursor++,players.size())),visible);
    }
    void update(Player player,Set<Scoreboard> boards) {
        var tier=tiers.cachedTier(player.getUniqueId());if(tier.isEmpty()) return;var language=application.cachedLanguage(player.getUniqueId());
        if(config.tab().mode()==DisplayConfiguration.TabMode.DIRECT&&!tabConflict) {
            var state=tabs.get(player.getUniqueId());Component current=player.playerListName();
            if(state==null) state=new Tab(current,current,current!=null&&!current.equals(Component.text(player.getName())));
            if(!state.yielded()&&!Objects.equals(current,state.written())) state=new Tab(state.before(),state.written(),true);
            if(!state.yielded()) {var next=formatter.limit(formatter.display(config.tab().format(),player.getName(),tier.get(),language),config.tab().length());if(!next.equals(current))player.playerListName(next);state=new Tab(state.before(),next,false);}
            tabs.put(player.getUniqueId(),state);
        }
        if(config.nameplate().mode()!=DisplayConfiguration.NameMode.TEAM_IF_FREE) return;
        var text=formatter.limit(formatter.display(config.nameplate().format(),player.getName(),tier.get(),language),config.nameplate().length());
        for(var board:boards) {
            var records=owned.computeIfAbsent(board,b->new HashMap<>());var record=records.get(player.getUniqueId());var actual=board.getEntryTeam(player.getName());
            if(record!=null&&!record.team().equals(actual)) {remove(board,player.getUniqueId());record=null;}
            if(record!=null&&adopted(record,true)) {
                // A third party adopted this team: relinquish it without removing its entries.
                records.remove(player.getUniqueId());teamCount--;record=null;
            }
            if(actual!=null&&(record==null||!record.team().equals(actual))) continue;
            if(record==null) {
                if(teamCount>=config.maximumTeams()) continue;
                String name="drt_"+player.getUniqueId().toString().replace("-", "").substring(0,12);
                if(board.getTeam(name)!=null) continue;
                var team=board.registerNewTeam(name);team.addEntry(player.getName());record=new Owned(team,player.getName(),null);records.put(player.getUniqueId(),record);teamCount++;
            }
            record.team().suffix(text);records.put(player.getUniqueId(),new Owned(record.team(),record.entry(),text));record.team().setOption(Team.Option.NAME_TAG_VISIBILITY,visibility());
        }
    }
    private Team.OptionStatus visibility() {
        return switch(config.nameplate().visibility()) {
            case "HIDE_FOR_OTHER_TEAMS" -> Team.OptionStatus.FOR_OWN_TEAM;
            case "HIDE_FOR_OWN_TEAM" -> Team.OptionStatus.FOR_OTHER_TEAMS;
            default -> Team.OptionStatus.valueOf(config.nameplate().visibility());
        };
    }
    private boolean adopted(Owned row,boolean requireMember) {
        var entries=row.team().getEntries();
        if((requireMember||!entries.isEmpty())&&!entries.equals(Set.of(row.entry())))return true;
        var prefix=row.team().prefix();if(prefix!=null&&!prefix.equals(Component.empty()))return true;
        if(row.suffix()!=null&&!row.suffix().equals(row.team().suffix()))return true;
        var option=row.team().getOption(Team.Option.NAME_TAG_VISIBILITY);
        return row.suffix()!=null&&option!=null&&option!=visibility();
    }
    private void remove(Scoreboard board,UUID player) {
        var rows=owned.get(board);if(rows==null) return;var row=rows.remove(player);if(row==null)return;teamCount--;
        try {if(!row.team().equals(board.getTeam(row.team().getName()))||adopted(row,false))return;row.team().removeEntry(row.entry());if(row.team().getEntries().isEmpty())row.team().unregister();}catch(IllegalStateException ignored){ }
    }
    private void removeBoard(Scoreboard board) {var rows=owned.get(board);if(rows!=null)for(UUID id:List.copyOf(rows.keySet()))remove(board,id);owned.remove(board);}
    void quit(Player player) {
        for(var board:List.copyOf(owned.keySet())) remove(board,player.getUniqueId());
        var tab=tabs.remove(player.getUniqueId());if(tab!=null&&!tab.yielded()&&Objects.equals(player.playerListName(),tab.written()))player.playerListName(tab.before());
    }
    int ownedTeams() {return teamCount;}
    @Override public void close() {for(var player:plugin.getServer().getOnlinePlayers()) quit(player);for(var board:List.copyOf(owned.keySet()))removeBoard(board);tabs.clear();}
}
