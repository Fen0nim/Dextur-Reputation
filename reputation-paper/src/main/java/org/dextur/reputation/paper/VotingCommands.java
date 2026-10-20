package org.dextur.reputation.paper;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;

/** Server-thread command parsing; every durable operation uses VotingService. */
final class VotingCommands implements CommandExecutor,TabCompleter {
    private final DexturReputationPlugin plugin;
    private final ReputationApplication application;
    private final VotingService voting;
    private final VotingConfiguration config;
    private final Messages messages;
    private final PlayerLanguage fallback;
    private VotingGui gui;
    VotingCommands(DexturReputationPlugin plugin,ReputationApplication application,VotingService voting,
            VotingConfiguration config,Messages messages,PlayerLanguage fallback) {
        this.plugin=plugin;this.application=application;this.voting=voting;this.config=config;this.messages=messages;this.fallback=fallback;
    }
    void gui(VotingGui gui) { this.gui=gui; }
    PlayerLanguage language(CommandSender sender) { return sender instanceof Player p?application.cachedLanguage(p.getUniqueId()):fallback; }
    boolean allowed(CommandSender sender,String action) { return sender.hasPermission(config.permissions().get(action)); }
    void send(CommandSender sender,String key,Map<String,String> values) { sender.sendMessage(messages.render(language(sender),key,values)); }
    OfflinePlayer known(String name) {
        var online=plugin.getServer().getPlayerExact(name);var known=online==null?plugin.getServer().getOfflinePlayerIfCached(name):online;
        return known!=null&&(online!=null||known.hasPlayedBefore())?known:null;
    }
    String name(UUID id) {
        var known=plugin.getServer().getOfflinePlayer(id);return known.getName()==null?id.toString():known.getName();
    }
    String type(PlayerLanguage language,VoteType type) { return messages.plain(language,"vote-type-"+type.name().toLowerCase(Locale.ROOT)); }
    String action(PlayerLanguage language,VoteEntry.Action action) { return messages.plain(language,"vote-action-"+action.name().toLowerCase(Locale.ROOT)); }
    String reason(PlayerLanguage language,String id) {
        var reason=config.rules().reasons().get(id);return reason==null?id:messages.plain(language,reason.messageKey());
    }
    void failed(CommandSender sender,Throwable error) {
        Throwable cause=error;while(cause.getCause()!=null&&cause.getCause()!=cause) cause=cause.getCause();
        if(cause instanceof org.dextur.reputation.api.PolicyDeniedException policy)send(sender,policy.reasonKey(),Map.of());
        else if(cause instanceof VoteRejectedException rejected) send(sender,"vote-error-"+rejected.code().name().toLowerCase(Locale.ROOT).replace('_','-'),Map.of("seconds",Long.toString(rejected.remainingSeconds())));
        else { plugin.logFailure("voting",cause);send(sender,"error",Map.of()); }
    }
    <T> void complete(CommandSender sender,CompletionStage<T> stage,java.util.function.Consumer<T> success) {
        stage.whenComplete((result,error)->plugin.onMain(()->{
            if(sender instanceof Player p&&!p.isOnline()) return;
            if(error!=null) failed(sender,error);else success.accept(result);
        }));
    }
    void cast(Player voter,UUID target,VoteType type,String reason,String comment,Runnable after) {
        submit(voter,new VoteRequest(UUID.randomUUID(),voter.getUniqueId(),target,VoteRequest.Action.SUBMIT,type,reason,comment),after);
    }
    void revoke(Player voter,UUID target,String comment,Runnable after) {
        submit(voter,new VoteRequest(UUID.randomUUID(),voter.getUniqueId(),target,VoteRequest.Action.REVOKE,null,"",comment),after);
    }
    private void submit(Player voter,VoteRequest request,Runnable after) {
        voting.submit(request).thenCompose(receipt->voting.outcome(receipt.entry().operationId()).thenApply(outcome->Map.entry(receipt,outcome))).whenComplete((result,error)->plugin.onMain(()->{
            if(!voter.isOnline()) return;
            if(error!=null) failed(voter,error);
            else {
                var receipt=result.getKey();var outcome=result.getValue();
                if(receipt.duplicate()) {send(voter,"vote-duplicate",Map.of());after.run();return;}
                var language=language(voter);var entry=receipt.entry();
                if(outcome.status()==VoteProcessingOutcome.Status.PENDING) send(voter,"protection-pending",Map.of("player",name(entry.targetId())));
                else send(voter,"vote-success",Map.of("player",name(entry.targetId()),"action",action(language,entry.action()),
                        "type",type(language,entry.type()),"reputation",messages.number(language,receipt.currentView().profile().visibleScore())));
                if(entry.calculationVersion()>=2&&entry.action()!=VoteEntry.Action.REVOKE&&outcome.status()==VoteProcessingOutcome.Status.ACCEPTED) send(voter,"protection-adjusted",Map.of());
            }
            after.run();
        }));
    }
    void notifyTarget(VotingService.VoteCommitted event) {
        if(!config.notifyTarget()) return;
        var target=plugin.getServer().getPlayer(event.entry().targetId());if(target==null) return;
        var entry=event.entry();
        complete(target,voting.outcome(entry.operationId()),outcome->{
        if(outcome.status()==VoteProcessingOutcome.Status.PENDING) {send(target,"protection-pending-received",Map.of());return;}
        var language=language(target);
        send(target,"vote-notification",Map.of("player",name(entry.voterId()),"action",action(language,entry.action()),"type",type(language,entry.type()),
                "reason",reason(language,entry.reason()),"comment",entry.comment(),"reputation",messages.number(language,event.view().profile().visibleScore())));
        });
    }
    void notifyStatus(VotingService.VoteStatusChanged event) {
        var target=plugin.getServer().getPlayer(event.view().profile().playerId());
        if(target!=null&&config.notifyTarget()) send(target,"protection-status-changed",Map.of("status",messages.plain(language(target),"protection-status-"+event.status().name().toLowerCase(Locale.ROOT))));
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!plugin.ready()) { send(sender,"unavailable",Map.of());return true; }
        if(!(sender instanceof Player voter)) { send(sender,"players-only",Map.of());return true; }
        if(!allowed(sender,"vote")) { send(sender,"no-permission",Map.of());return true; }
        if(args.length<2) { send(sender,"vote-usage",Map.of());return true; }
        var target=known(args[0]);if(target==null) { send(sender,"unknown-player",Map.of("player",args[0]));return true; }
        cast(voter,target.getUniqueId(),command.getName().equals("like")?VoteType.POSITIVE:VoteType.NEGATIVE,args[1],String.join(" ",Arrays.copyOfRange(args,2,args.length)),()->{});
        return true;
    }
    /** Returns false only for the existing /rep language command. */
    boolean rep(CommandSender sender,String[] args) {
        if(args.length>0&&args[0].equalsIgnoreCase("language")) return false;
        if(!sender.hasPermission("dexturreputation.use")) { send(sender,"no-permission",Map.of());return true; }
        if(!plugin.ready()) { send(sender,"unavailable",Map.of());return true; }
        String action=args.length==0?"":args[0].toLowerCase(Locale.ROOT);
        if(action.equals("help")) { send(sender,"vote-help",Map.of());return true; }
        if(action.equals("reasons")) {
            if(!allowed(sender,"reasons")) { send(sender,"no-permission",Map.of());return true; }
            send(sender,"vote-reasons",Map.of());var language=language(sender);
            config.rules().reasons().values().stream().sorted(Comparator.comparing(r->r.id())).forEach(r->
                    send(sender,"vote-reason-line",Map.of("reason",r.id(),"description",reason(language,r.id()),"type",r.types().stream().sorted().map(t->type(language,t)).collect(java.util.stream.Collectors.joining(", ")))));
            return true;
        }
        if(action.equals("history")||action.equals("given")) {
            if(!(sender instanceof Player p)) { send(sender,"players-only",Map.of());return true; }
            if(!allowed(sender,"history")) { send(sender,"no-permission",Map.of());return true; }
            int page=1;
            try { if(args.length>2) throw new NumberFormatException();if(args.length==2) page=Integer.parseInt(args[1]);if(page<1) throw new NumberFormatException(); }
            catch(NumberFormatException error) { failed(sender,new VoteRejectedException(VoteRejectedException.Code.INVALID_PAGE));return true; }
            var direction=action.equals("given")?VotePage.Direction.GIVEN:VotePage.Direction.RECEIVED;
            complete(sender,voting.history(p.getUniqueId(),direction,page),result->history(sender,result,direction));return true;
        }
        if(action.equals("revoke")) {
            if(!(sender instanceof Player p)) { send(sender,"players-only",Map.of());return true; }
            if(!allowed(sender,"revoke")) { send(sender,"no-permission",Map.of());return true; }
            if(args.length<2) { send(sender,"vote-usage",Map.of());return true; }
            var target=known(args[1]);if(target==null) { send(sender,"unknown-player",Map.of("player",args[1]));return true; }
            revoke(p,target.getUniqueId(),String.join(" ",Arrays.copyOfRange(args,2,args.length)),()->{});return true;
        }
        boolean inventory=action.equals("gui")||config.openGuiOnProfile();
        int index=action.equals("gui")?1:0;
        if(args.length>index+1) { send(sender,"vote-help",Map.of());return true; }
        UUID target;String display;
        if(args.length==index) {
            if(!(sender instanceof Player p)) { send(sender,"players-only",Map.of());return true; }
            target=p.getUniqueId();display=p.getName();
        } else {
            var known=known(args[index]);if(known==null) { send(sender,"unknown-player",Map.of("player",args[index]));return true; }
            target=known.getUniqueId();display=known.getName()==null?target.toString():known.getName();
        }
        if(inventory&&sender instanceof Player p) {
            if(!allowed(sender,"gui")) send(sender,"no-permission",Map.of());else gui.open(p,target,display);
        } else complete(sender,(sender instanceof Player p?application.language(p.getUniqueId()):CompletableFuture.completedFuture(fallback)).thenCombine(voting.view(target),Map::entry),result->{
            var language=result.getKey();var view=result.getValue();
            sender.sendMessage(messages.render(language,"profile",Map.of("player",display,"reputation",messages.number(language,view.profile().visibleScore()),"tier",java.util.Objects.requireNonNullElse(plugin.tierName(target,language),messages.plain(language,"tier-neutral")))));
            stats(sender,view);
        });
        return true;
    }
    private void stats(CommandSender sender,ReputationView view) {
        var s=view.statistics();send(sender,"vote-stats",Map.of("positive",Long.toString(s.positive()),"negative",Long.toString(s.negative()),"unique",Long.toString(s.uniqueVoters()),"total",Long.toString(s.historyEntries())));
    }
    private void history(CommandSender sender,VotePage page,VotePage.Direction direction) {
        var language=language(sender);send(sender,"vote-history",Map.of("direction",messages.plain(language,direction==VotePage.Direction.GIVEN?"vote-given":"vote-received"),"page",Integer.toString(page.page()),"total",Long.toString(page.totalEntries())));
        if(page.entries().isEmpty()) send(sender,"vote-empty",Map.of());
        for(var item:page.entries()) { var e=item.entry();send(sender,"vote-history-line",Map.of("time",e.createdAt().toString(),"voter",name(e.voterId()),"target",name(e.targetId()),
                "action",action(language,e.action()),"type",type(language,e.type()),"reason",reason(language,e.reason()),"comment",e.comment(),"state",messages.plain(language,item.active()?"vote-active":"vote-inactive"))); }
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(!sender.hasPermission("dexturreputation.use")) return List.of();
        List<String> candidates=List.of();boolean rep=command.getName().equals("rep");
        if(rep&&args.length==1) {
            var options=new ArrayList<String>();options.add("help");
            for(String name:List.of("history","given","reasons","gui","revoke")) if(allowed(sender,name.equals("given")?"history":name)) options.add(name);
            if(sender.hasPermission("dexturreputation.language")) options.add("language");
            candidates=options;
        }
        else if(!rep&&(!allowed(sender,"vote")||!allowed(sender,command.getName().equals("like")?"positive":"negative"))) return List.of();
        else if(!rep&&args.length==2) {
            VoteType type=command.getName().equals("like")?VoteType.POSITIVE:VoteType.NEGATIVE;
            candidates=config.rules().reasons().values().stream().filter(r->r.types().contains(type)).map(r->r.id()).sorted().toList();
        } else if((!rep&&args.length==1)||(rep&&args.length==2&&(args[0].equalsIgnoreCase("gui")||args[0].equalsIgnoreCase("revoke"))))
            candidates=plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        else if(rep&&args.length==2&&args[0].equalsIgnoreCase("language")) candidates=List.of("ru_RU","en_US");
        String prefix=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
