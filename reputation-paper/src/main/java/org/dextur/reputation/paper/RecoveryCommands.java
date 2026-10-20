package org.dextur.reputation.paper;
import java.util.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.dextur.reputation.domain.recovery.RecoveryData.Command;

final class RecoveryCommands {
    private final DexturReputationPlugin plugin;private final ReputationApplication app;private final RecoveryApplication service;private final Messages messages;private final PlayerLanguage fallback;private RecoveryGui gui;
    RecoveryCommands(DexturReputationPlugin plugin,ReputationApplication app,RecoveryApplication service,Messages messages,PlayerLanguage fallback){this.plugin=plugin;this.app=app;this.service=service;this.messages=messages;this.fallback=fallback;}
    void gui(RecoveryGui gui){this.gui=gui;}
    PlayerLanguage language(CommandSender s){return s instanceof Player p?app.cachedLanguage(p.getUniqueId()):fallback;}
    void send(CommandSender s,String key,Map<String,String> values){s.sendMessage(messages.render(language(s),key,values));}
    boolean allowed(CommandSender s,String action){return s.hasPermission("dexturreputation.use")&&s.hasPermission("dexturreputation.recovery")&&s.hasPermission("dexturreputation.recovery."+action);}
    String translated(Map<String,String> values,PlayerLanguage language){return DisplayFormatter.plain(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(values.getOrDefault(language.name(),values.getOrDefault(fallback.name(),""))));}
    Map<String,String> summary(RecoverySnapshot s,PlayerLanguage l){return Map.of("state",messages.plain(l,"recovery-state-"+s.status().name().toLowerCase(Locale.ROOT)),"program",translated(s.programNames(),l),"stage",translated(s.stageNames(),l),"progress",Integer.toString(s.progressPercent()),"completed",Integer.toString(s.completedTasks()),"total",Integer.toString(s.objectives().size()),"reason",reason(s.reason(),l),"cooldown",s.cooldownUntil().equals(java.time.Instant.EPOCH)?"0":s.cooldownUntil().toString());}
    String reason(String code,PlayerLanguage language){try{return messages.plain(language,"recovery-reason-"+code.toLowerCase(Locale.ROOT).replace('_','-'));}catch(IllegalArgumentException e){return messages.plain(language,"recovery-reason-error");}}
    void failed(CommandSender sender,Throwable error){while(error.getCause()!=null&&error.getCause()!=error)error=error.getCause();plugin.logFailure("recovery",error);send(sender,"recovery-error",Map.of("reason",reason(String.valueOf(error.getMessage()),language(sender))));}
    boolean rep(CommandSender sender,String[] args){
        boolean admin=args.length>=2&&args[0].equalsIgnoreCase("admin")&&args[1].equalsIgnoreCase("recovery");if(!admin&&(args.length==0||!args[0].equalsIgnoreCase("recovery")))return false;
        if(!plugin.ready()){send(sender,"unavailable",Map.of());return true;}
        if(admin){
            if(!sender.hasPermission("dexturreputation.recovery.admin")){send(sender,"no-permission",Map.of());return true;}
            if(args.length<4){send(sender,"recovery-admin-help",Map.of());return true;}
            UUID id;try{id=UUID.fromString(args[2]);var known=plugin.getServer().getOfflinePlayer(id);if(!known.hasPlayedBefore()&&!known.isOnline())throw new IllegalArgumentException();}catch(IllegalArgumentException ex){var known=plugin.getServer().getPlayerExact(args[2]);var cached=known==null?plugin.getServer().getOfflinePlayerIfCached(args[2]):known;if(cached==null||!cached.hasPlayedBefore()&&!cached.isOnline()){send(sender,"unknown-player",Map.of("player",args[2]));return true;}id=cached.getUniqueId();}
            Command command;try{command=args[3].equalsIgnoreCase("status")?Command.READ:Command.valueOf(args[3].toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){send(sender,"recovery-admin-help",Map.of());return true;}
            var action=service.administer(id,command,"ADMIN:"+sender.getName(),String.join(" ",Arrays.copyOfRange(args,4,args.length)));complete(sender,action,"status");return true;
        }
        String action=args.length==1?"gui":args[1].toLowerCase(Locale.ROOT);if(args.length>2||!List.of("gui","status","start","tasks","progress","rewards","help","cancel").contains(action)){send(sender,"recovery-help",Map.of());return true;}
        if(!allowed(sender,action.equals("start")?"start":action.equals("cancel")?"cancel":"status")){send(sender,"no-permission",Map.of());return true;}
        if(action.equals("help")){send(sender,"recovery-help",Map.of());return true;}
        if(!(sender instanceof Player p)){send(sender,"players-only",Map.of());return true;}
        if(action.equals("gui")){gui.open(p);return true;}
        complete(sender,action.equals("start")?service.start(p.getUniqueId()):action.equals("cancel")?service.cancel(p.getUniqueId()):service.status(p.getUniqueId()),action);return true;
    }
    void complete(CommandSender sender,java.util.concurrent.CompletionStage<RecoverySnapshot> stage,String action){stage.whenComplete((s,error)->plugin.onMain(()->{if(sender instanceof Player p&&!p.isOnline())return;if(error!=null){failed(sender,error);return;}var l=language(sender);send(sender,"recovery-summary",summary(s,l));if(List.of("tasks","progress").contains(action))for(var o:s.objectives())send(sender,"recovery-objective",Map.of("objective",translated(o.names(),l),"description",translated(o.descriptions(),l),"progress",Long.toString(o.progress()),"required",Long.toString(o.required()),"available",messages.plain(l,o.available()?"recovery-available":"recovery-unavailable")));if(action.equals("rewards")){if(s.rewards().isEmpty())send(sender,"recovery-no-rewards",Map.of());for(var reward:s.rewards())send(sender,"recovery-reward",Map.of("stage",reward.stageId(),"amount",Long.toString(reward.reputation()),"time",reward.grantedAt().toString()));}}));}
    List<String> tabs(CommandSender sender,String[] args){if(args.length==0)return List.of();List<String> options=List.of();if(args.length==1){var list=new ArrayList<String>();if(allowed(sender,"status"))list.add("recovery");if(sender.hasPermission("dexturreputation.recovery.admin"))list.add("admin");options=list;}
        else if(args[0].equalsIgnoreCase("recovery")&&args.length==2){var list=new ArrayList<>(List.of("status","tasks","progress","rewards","help"));if(allowed(sender,"start"))list.add("start");if(allowed(sender,"cancel"))list.add("cancel");options=allowed(sender,"status")?list:List.of();}
        else if(args[0].equalsIgnoreCase("admin")&&sender.hasPermission("dexturreputation.recovery.admin")){if(args.length==2)options=List.of("recovery");else if(args.length==3)options=plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();else if(args.length==4)options=List.of("status","start","pause","resume","reset_stage","cancel","lock","unlock");}
        String prefix=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);return options.stream().filter(s->s.startsWith(prefix)).toList();}
}
