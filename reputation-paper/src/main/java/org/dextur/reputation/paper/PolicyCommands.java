package org.dextur.reputation.paper;
import java.time.Instant;
import java.util.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.policy.*;
import org.dextur.reputation.api.*;

final class PolicyCommands implements CommandExecutor,TabCompleter {
    private final DexturReputationPlugin plugin;private final ReputationApplication app;private final PolicyApplication policies;private final Settings settings;private final Messages messages;private PolicyEffects effects;
    private PlayerMessages feedback;private RecoveryCommands recovery;
    void recovery(RecoveryCommands commands){recovery=commands;}
    void recoveryAction(CommandSender sender,UUID target,String action,Runnable done){if(recovery!=null)recovery.administer(sender,target,org.dextur.reputation.domain.recovery.RecoveryData.Command.valueOf(action.toUpperCase(Locale.ROOT)),messages.plain(language(sender),"admin7-gui-reason"),done);}
    PolicyCommands(DexturReputationPlugin plugin,ReputationApplication app,PolicyApplication policies,Settings settings,Messages messages){this.plugin=plugin;this.app=app;this.policies=policies;this.settings=settings;this.messages=messages;this.feedback=new PlayerMessages(plugin,app,messages,settings.defaultLanguage());}
    void effects(PolicyEffects effects){this.effects=effects;}
    PolicyApplication service(){return policies;}
    PlayerMessages feedback(){return feedback;}
    void force(CommandSender sender,UUID id,String mode,Runnable done){
        if(!permitted(sender,"force"))return;
        if(!List.of("on","off","status").contains(mode)){send(sender,"admin-force-usage",Map.of());return;}
        policies.administration(id).whenComplete((state,error)->plugin.onMain(()->{
            if(error!=null){failed(sender,error);return;}
            if(!permitted(sender,"force"))return;
            boolean enabled=InitialEligibility.enabled(state),desired=mode.equals("on");
            if(mode.equals("status")||enabled==desired){send(sender,"admin-force-status",Map.of("player",feedback.name(id),"state",messages.plain(language(sender),enabled?"admin-enabled":"admin-disabled")));done.run();return;}
            var request=new AdministrationData.Request(UUID.randomUUID(),id,settings.scope(),AdministrationData.Type.OVERRIDE,0,InitialEligibility.KEY,
                    desired?AdministrationData.Override.FORCE_ALLOW:AdministrationData.Override.DEFAULT,sender instanceof Player p?p.getUniqueId().toString():"CONSOLE",
                    messages.plain(language(sender),"admin-force-audit-reason"),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            apply(sender,request,done);
        }));
    }
    void guiAdjustment(CommandSender sender,UUID id,AdministrationData.Type type,long amount,Runnable done){
        if(!permitted(sender,type==AdministrationData.Type.LOCK?"voting.lock":type==AdministrationData.Type.UNLOCK?"voting.unlock":type.name().toLowerCase(Locale.ROOT)))return;
        apply(sender,new AdministrationData.Request(UUID.randomUUID(),id,settings.scope(),type,amount,"",AdministrationData.Override.DEFAULT,
                sender instanceof Player p?p.getUniqueId().toString():"CONSOLE",messages.plain(language(sender),"admin7-gui-reason"),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)),done);
    }
    private void apply(CommandSender sender,AdministrationData.Request request,Runnable done){
        policies.administer(request).whenComplete((result,error)->plugin.onMain(()->{
            if(error!=null){failed(sender,error);done.run();return;}
            boolean force=request.policy().equals(InitialEligibility.KEY);
            String action=force?"force-"+(request.override()==AdministrationData.Override.FORCE_ALLOW?"on":"off"):request.type().name().toLowerCase(Locale.ROOT);
            var values=Map.of("player",feedback.name(request.player()),"actor",feedback.actor(sender),"value",Long.toString(result.profile().visibleScore()),"amount",Long.toString(request.requested()),"reason",request.reason());
            boolean changed=result.audit().previousAdmin()!=result.audit().resultingAdmin()||result.audit().previousLock()!=result.audit().resultingLock()||!result.audit().previousOverrides().equals(result.audit().resultingOverrides());
            if(result.duplicate()||!changed)send(sender,"admin-no-change",values);
            else{send(sender,"admin-success-"+action,values);feedback.target(sender,request.player(),"admin-target-"+action,l->values);}
            if(effects!=null&&changed)effects.refresh();done.run();
        }));
    }
    org.dextur.reputation.domain.PlayerLanguage language(CommandSender sender){return sender instanceof Player p?app.cachedLanguage(p.getUniqueId()):settings.defaultLanguage();}
    void send(CommandSender s,String key,Map<String,String> values){s.sendMessage(messages.render(language(s),key,values));}
    boolean permitted(CommandSender sender,String permission){if(sender.hasPermission("dexturreputation.admin."+permission))return true;send(sender,"no-permission",Map.of());return false;}
    private UUID resolve(CommandSender sender,String value){try{UUID id=CommandInput.uuid(value);var p=plugin.getServer().getOfflinePlayer(id);if(p.isOnline()||p.hasPlayedBefore())return id;}catch(IllegalArgumentException ignored){}var online=plugin.getServer().getPlayerExact(value);var known=online==null?plugin.getServer().getOfflinePlayerIfCached(value):online;if(known!=null&&(known.isOnline()||known.hasPlayedBefore()))return known.getUniqueId();send(sender,"unknown-player",Map.of("player",value));return null;}
    private void failed(CommandSender sender,Throwable error){while(error.getCause()!=null&&error.getCause()!=error)error=error.getCause();String code=error.getMessage();String key=switch(code==null?"":code){case "ADMIN_REASON"->"admin-error-reason";case "ADMIN_REMOVE"->"admin-error-remove";case "ADMIN_ID_CONFLICT"->"admin-error-conflict";case "ADMIN_EMERGENCY"->"admin-error-emergency";case "ADMIN_POLICY"->"admin-error-policy";default->error instanceof IllegalArgumentException||error instanceof ArithmeticException?"admin-error-value":"admin-error-storage";};send(sender,key,Map.of());if(key.equals("admin-error-storage"))plugin.logFailure("administration",error);}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(!CommandInput.valid(args)){send(sender,"admin-error-value",Map.of());return true;}if(!plugin.ready()){send(sender,"unavailable",Map.of());return true;}if(args.length==0){send(sender,"admin-help",Map.of());return true;}String action=args[0].toLowerCase(Locale.ROOT);
        if(action.equals("recovery")&&recovery!=null){String[] forwarded=new String[args.length+1];forwarded[0]="admin";System.arraycopy(args,0,forwarded,1,args.length);recovery.rep(sender,forwarded);return true;}
        if(action.equals("force")){if(!permitted(sender,"force"))return true;if(args.length<2||args.length>3){send(sender,"admin-force-usage",Map.of());return true;}UUID target=resolve(sender,args[1]);if(target!=null)force(sender,target,args.length==2?"on":args[2].toLowerCase(Locale.ROOT),()->{});return true;}
        if(action.equals("reload")){if(!permitted(sender,"reload"))return true;if(args.length!=1){send(sender,"admin-help",Map.of());return true;}policies.reload(plugin.getDataFolder().toPath()).whenComplete((v,error)->plugin.onMain(()->{if(error!=null){send(sender,"policy-reload-failed",Map.of());plugin.logFailure("policy reload",error);}else{plugin.invalidateGuiSessions();effects.refresh();send(sender,"policy-reloaded",Map.of());}}));return true;}
        try{
            AdministrationData.Type type;String player;long value=0;String policy="";var override=AdministrationData.Override.DEFAULT;int reasonAt;
            if(action.equals("voting")){if(args.length<3){send(sender,"admin-help",Map.of());return true;}String sub=args[1].toLowerCase(Locale.ROOT);if(!List.of("lock","unlock","status").contains(sub)){send(sender,"admin-help",Map.of());return true;}if(!permitted(sender,"voting."+sub))return true;player=args[2];UUID id=resolve(sender,player);if(id==null)return true;if(sub.equals("status")){policies.administration(id).whenComplete((state,error)->plugin.onMain(()->{if(error!=null)failed(sender,error);else send(sender,"admin-voting-status",Map.of("player",player,"state",messages.plain(language(sender),state!=null&&state.votingLocked()?"admin-locked":"admin-unlocked")));}));return true;}type=sub.equals("lock")?AdministrationData.Type.LOCK:AdministrationData.Type.UNLOCK;reasonAt=3;
            }else if(action.equals("policy")){if(!permitted(sender,"policy"))return true;if(args.length<4){send(sender,"admin-help",Map.of());return true;}player=args[1];policy=args[2];if(policy.equals(InitialEligibility.KEY)){send(sender,"admin-force-usage",Map.of());return true;}override=switch(args[3].toLowerCase(Locale.ROOT)){case "allow"->AdministrationData.Override.FORCE_ALLOW;case "deny"->AdministrationData.Override.FORCE_DENY;case "reset"->AdministrationData.Override.DEFAULT;default->throw new IllegalArgumentException();};type=AdministrationData.Type.OVERRIDE;reasonAt=4;
            }else{if(!List.of("set","add","remove").contains(action)){send(sender,"admin-help",Map.of());return true;}if(!permitted(sender,action))return true;if(args.length<3){send(sender,"admin-help",Map.of());return true;}type=AdministrationData.Type.valueOf(action.toUpperCase(Locale.ROOT));player=args[1];if(!args[2].matches("[+-]?[0-9]{1,19}"))throw new IllegalArgumentException();value=Long.parseLong(args[2]);reasonAt=3;}
            UUID id=resolve(sender,player);if(id==null)return true;String reason=String.join(" ",Arrays.copyOfRange(args,reasonAt,args.length));if(reason.startsWith("\"")&&reason.endsWith("\"")&&reason.length()>=2)reason=reason.substring(1,reason.length()-1);String actor=sender instanceof Player p?p.getUniqueId().toString():"CONSOLE";
            var request=new AdministrationData.Request(UUID.randomUUID(),id,settings.scope(),type,value,policy,override,actor,reason,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            apply(sender,request,()->{});
        }catch(RuntimeException error){failed(sender,error);}return true;
    }
    String policyName(String id,org.dextur.reputation.domain.PlayerLanguage language){
        if(id.equals(InitialEligibility.KEY))return messages.plain(language,"admin7-ui-bypass");
        var policy=policies.configuration().policies().get(id);
        if(policy==null)return messages.plain(language,"admin7-field-policy");
        return DisplayFormatter.plain(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(policy.names().getOrDefault(language.name(),policy.names().get("en_US"))));
    }
    private String policyReason(String key,org.dextur.reputation.domain.PlayerLanguage language){try{return messages.plain(language,key);}catch(IllegalArgumentException ignored){return messages.plain(language,"policy-reason-unavailable");}}
    Map<String,String> publicValues(ReputationPolicyResult r,CommandSender sender){return Map.of("policy",policyName(r.policyId(),language(sender)),"decision",messages.plain(language(sender),"policy-decision-"+r.decision().name().toLowerCase(Locale.ROOT)),"limit",r.limit()==null?"—":r.limit().toString(),"multiplier",r.multiplier().stripTrailingZeros().toPlainString(),"value",r.value()==null?"—":r.value().stripTrailingZeros().toPlainString(),"integration",messages.plain(language(sender),r.integrationActive()?"policy-integration-active":"policy-definition-only"),"reason",policyReason(r.reasonKey(),language(sender)));}
    boolean rep(CommandSender sender,String[] args){if(args.length==0||!args[0].equalsIgnoreCase("policies"))return false;if(!sender.hasPermission("dexturreputation.use")||!sender.hasPermission("dexturreputation.policies")){send(sender,"no-permission",Map.of());return true;}if(!plugin.ready()){send(sender,"unavailable",Map.of());return true;}if(!policies.configuration().informationEnabled()){send(sender,"policy-information-disabled",Map.of());return true;}if(!(sender instanceof Player player)){send(sender,"players-only",Map.of());return true;}if(args.length==1){effects.open(player);return true;}if(args.length!=2){send(sender,"policy-help",Map.of());return true;}try{policies.evaluate(player.getUniqueId(),args[1]).whenComplete((result,error)->plugin.onMain(()->{if(error!=null)failed(sender,error);else send(sender,"policy-summary",publicValues(result,sender));}));}catch(RuntimeException error){failed(sender,error);}return true;}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){if(args.length==0)return List.of();List<String> values=List.of();if(args.length==1)values=List.of("set","add","remove","voting","policy","reload","force","recovery").stream().filter(s->s.equals("voting")?sender.hasPermission("dexturreputation.admin.voting.status")||sender.hasPermission("dexturreputation.admin.voting.lock")||sender.hasPermission("dexturreputation.admin.voting.unlock"):(s.equals("recovery")?sender.hasPermission("dexturreputation.recovery.admin"):sender.hasPermission("dexturreputation.admin."+s))).toList();else if(args[0].equalsIgnoreCase("recovery")&&args.length==3&&sender.hasPermission("dexturreputation.recovery.admin"))values=List.of("status","start","pause","resume","cancel","reset_stage","lock","unlock");else if(args[0].equalsIgnoreCase("force")&&args.length==3&&sender.hasPermission("dexturreputation.admin.force"))values=List.of("on","off","status");else if(args[0].equalsIgnoreCase("voting")&&args.length==2)values=List.of("lock","unlock","status").stream().filter(s->sender.hasPermission("dexturreputation.admin.voting."+s)).toList();else if(args[0].equalsIgnoreCase("policy")&&args.length==3&&sender.hasPermission("dexturreputation.admin.policy"))values=policies.policies().keySet().stream().toList();else if(args[0].equalsIgnoreCase("policy")&&args.length==4&&sender.hasPermission("dexturreputation.admin.policy"))values=List.of("allow","deny","reset");else if(args.length==2&&!args[0].equalsIgnoreCase("voting")&&sender.hasPermission("dexturreputation.admin."+args[0].toLowerCase(Locale.ROOT))||args.length==3&&args[0].equalsIgnoreCase("voting")&&sender.hasPermission("dexturreputation.admin.voting."+args[1].toLowerCase(Locale.ROOT)))values=plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();String prefix=args[args.length-1].toLowerCase(Locale.ROOT);return values.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();}
}
