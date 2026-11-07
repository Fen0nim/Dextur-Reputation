package org.dextur.reputation.paper;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import net.kyori.adventure.text.Component;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.investigation.InvestigationData.*;

/** Private inventories with per-section/action permissions, generation checks and one-shot confirmations. */
final class InvestigationGui implements Listener {
    record Layout(int size,Material material,Material confirmation,List<Integer> content,List<Integer> sections,List<Integer> recalculate,int previous,int next,int refresh,int close,int confirm,int cancel){
        static Layout load(java.nio.file.Path directory,int pageSize)throws Exception{var config=TierConfiguration.map(TierConfiguration.configuration(directory,"administration.yml").get("gui"));int size=YamlFiles.number(config,"size",45,54);if(size%9!=0)throw new IllegalArgumentException("INV_GUI");var used=new HashSet<Integer>();var content=slots(config,"content-slots",size,used);var sections=slots(config,"section-slots",size,used);var recalculate=slots(config,"recalculate-slots",size,used);if(content.size()<pageSize||sections.size()!=10||recalculate.size()!=4)throw new IllegalArgumentException("INV_GUI");var buttons=new ArrayList<Integer>();for(String key:List.of("previous-slot","next-slot","refresh-slot","close-slot","confirm-slot","cancel-slot")){int slot=YamlFiles.number(config,key,0,size-1);if(!used.add(slot))throw new IllegalArgumentException("INV_GUI");buttons.add(slot);}return new Layout(size,material(config,"material"),material(config,"confirmation-material"),content,sections,recalculate,buttons.get(0),buttons.get(1),buttons.get(2),buttons.get(3),buttons.get(4),buttons.get(5));}
        private static Material material(Map<String,Object> values,String key){var m=Material.matchMaterial(YamlFiles.text(values,key));if(m==null||!m.isItem()||m.isAir())throw new IllegalArgumentException("INV_GUI");return m;}
        private static List<Integer> slots(Map<String,Object> values,String key,int size,Set<Integer> used){if(!(values.get(key) instanceof List<?> rows))throw new IllegalArgumentException("INV_GUI");var slots=new ArrayList<Integer>();for(Object n:rows){if(!(n instanceof Integer i)||i<0||i>=size||!used.add(i))throw new IllegalArgumentException("INV_GUI");slots.add(i);}return List.copyOf(slots);}
    }
    enum View { NORMAL,GIVEN,ACCESS,ADJUST,DEBUG }
    static final class Session implements InventoryHolder {
        final UUID owner,target;final Section section;final int page;final UUID id;
        final Map<Integer,Consumer<ClickType>> actions=new HashMap<>();Inventory inventory;boolean busy,awaiting;long lastClick;
        Report loaded;Query query;Request confirmation;UUID confirmationTarget;Instant expires;View view=View.NORMAL;
        org.dextur.reputation.domain.policy.AdministrationData.State access;
        org.dextur.reputation.api.ReputationPolicyResult voting,configuredVoting;
        List<Map.Entry<String,String>> details=List.of();Runnable back;Layout layout;long generation;
        Session(UUID owner,UUID target,Section section,int page,UUID id){this.owner=owner;this.target=target;this.section=section;this.page=page;this.id=id;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private long generation;
    private static final List<Section> SECTIONS=List.of(Section.INSPECT,Section.VOTES,Section.PENDING,Section.TRUST,Section.FLAGS,Section.RELATIONS,Section.RECOVERY,Section.ADMIN,Section.AUDIT,Section.RECALCULATE);
    final DexturReputationPlugin plugin;final InvestigationApplication app;final InvestigationCommands commands;final Messages messages;final Layout layout;
    InvestigationGui(DexturReputationPlugin plugin,InvestigationApplication app,InvestigationCommands commands,Messages messages,Layout layout){this.plugin=plugin;this.app=app;this.commands=commands;this.messages=messages;this.layout=layout;}
    private boolean allowed(Player p,Section section){return p.hasPermission("dexturreputation.admin.gui")&&p.hasPermission("dexturreputation.admin."+InvestigationCommands.permission(section));}
    private boolean current(Player p,Session s){return p.isOnline()&&s.owner.equals(p.getUniqueId())&&p.getOpenInventory().getTopInventory().getHolder()==s&&allowed(p,s.section)&&s.generation==generation;}
    private Component text(Player p,String key,Map<String,String> values){return messages.content(commands.language(p),key,values);}
    private Component text(Player p,String key){return text(p,key,Map.of());}
    private String name(Player p,UUID id){return commands.previous.feedback().name(id,commands.language(p));}
    void open(Player p,UUID target,Section section,int page){open(p,target,section,page,null,View.NORMAL,null);}
    private void open(Player p,UUID target,Section section,int page,UUID id,View view,Runnable back){open(p,target,section,page,id,view,back,null);}
    private void open(Player p,UUID target,Section section,int page,UUID id,View view,Runnable back,Filter retained){
        if(!allowed(p,section)){commands.send(p,"no-permission",Map.of());return;}
        var s=new Session(p.getUniqueId(),target,section,page,id);s.view=view;s.back=back;
        s.generation=generation;s.layout=geometry(initialSize(section,view));
        s.inventory=Bukkit.createInventory(s,s.layout.size(),title(p,s));
        Filter filter=retained!=null?retained:view==View.GIVEN?new Filter(target,null,null,"",null,null,"","",null):Filter.empty();
        s.query=new Query(section,commands.settings.scope(),section==Section.VOTE||view==View.GIVEN?null:target,id,filter,page,Math.min(app.rules().pageSize(),28));
        p.openInventory(s.inventory);load(p,s);
    }
    private static Layout geometry(int size){return new Layout(size,Material.BOOK,Material.RED_CONCRETE,MenuGeometry.content(size),List.of(),List.of(1,3,5,7),MenuGeometry.previous(size),MenuGeometry.next(size),MenuGeometry.refresh(size),MenuGeometry.close(size),4,MenuGeometry.back(size));}
    private static int initialSize(Section section,View view){return view==View.ACCESS||view==View.ADJUST||section==Section.RECALCULATE?18:27;}
    private int screenSize(Session s){
        if(s.view==View.ACCESS||s.view==View.ADJUST||s.section==Section.RECALCULATE)return 18;
        if(s.section==Section.INSPECT||s.section==Section.TRUST)return 27;
        return MenuGeometry.size(s.loaded.rows().size()+(s.section==Section.RECOVERY?9:0));
    }
    private Component title(Player p,Session s){String key=s.view==View.ACCESS?"admin7-ui-access":s.view==View.ADJUST?"admin7-ui-adjust":s.view==View.GIVEN?"admin7-ui-given":"admin7-section-"+s.section.name().toLowerCase(Locale.ROOT);return text(p,s.section==Section.INSPECT&&s.view==View.NORMAL?"admin7-ui-title":"admin7-ui-screen-title",Map.of("player",name(p,s.target),"section",messages.plain(commands.language(p),key)));}
    private void resize(Player p,Session s,int size){if(s.inventory.getSize()==size)return;s.layout=geometry(size);s.inventory=Bukkit.createInventory(s,size,title(p,s));p.openInventory(s.inventory);}
    void invalidate(){generation++;closeAll();}
    private void go(Player p,Session s){open(p,s.target,s.section,s.page,s.id,s.view,s.back,s.query.filter());}
    private void load(Player p,Session s){s.busy=true;
        app.read(s.query).thenCombine(commands.previous.service().administration(s.target),(report,access)->Map.entry(report,Optional.ofNullable(access)))
            .thenCombine(commands.previous.service().evaluate(s.target,"reputation.vote"),(pair,voting)->new Object[]{pair,voting})
            .thenCombine(commands.previous.service().configuredVoting(s.target),(data,configured)->new Object[]{data[0],data[1],configured})
            .whenComplete((result,error)->plugin.onMain(()->{
                if(!current(p,s))return;s.busy=false;
                if(error!=null){commands.failed(p,error);p.closeInventory();return;}
                @SuppressWarnings("unchecked") var pair=(Map.Entry<Report,Optional<org.dextur.reputation.domain.policy.AdministrationData.State>>)result[0];
                s.loaded=pair.getKey();s.access=pair.getValue().orElse(null);s.voting=(org.dextur.reputation.api.ReputationPolicyResult)result[1];s.configuredVoting=(org.dextur.reputation.api.ReputationPolicyResult)result[2];resize(p,s,screenSize(s));render(p,s);
            }));
    }
    private void item(Player p,Session s,int slot,Material icon,String key,Map<String,String> values,List<Component> lore,Consumer<ClickType> action){
        if(slot<0)return;var stack=new ItemStack(icon);var meta=stack.getItemMeta();if(icon==Material.PLAYER_HEAD&&meta instanceof org.bukkit.inventory.meta.SkullMeta skull)skull.setPlayerProfile(PlayerMessages.cachedProfile(plugin.getServer(),s.target));meta.displayName(text(p,key,values).colorIfAbsent(icon==Material.PLAYER_HEAD?net.kyori.adventure.text.format.NamedTextColor.AQUA:net.kyori.adventure.text.format.NamedTextColor.YELLOW));meta.lore(compact(lore).stream().map(line->line.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.GRAY)).toList());stack.setItemMeta(meta);s.inventory.setItem(slot,stack);if(action!=null)s.actions.put(slot,action);
    }
    private List<Component> compact(List<Component> lines){var result=new ArrayList<Component>();for(var line:lines){String plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line);if(plain.codePointCount(0,plain.length())<=64){result.add(line);continue;}for(int offset=0;offset<plain.length();){int end=plain.offsetByCodePoints(offset,Math.min(58,plain.codePointCount(offset,plain.length())));int space=plain.lastIndexOf(' ',end);if(space>offset+25)end=space;result.add(Component.text(plain.substring(offset,end),line.color()==null?net.kyori.adventure.text.format.NamedTextColor.GRAY:line.color()));offset=end;while(offset<plain.length()&&plain.charAt(offset)==' ')offset++;}}return List.copyOf(result);}
    private void button(Player p,Session s,int slot,Material icon,String key,String description,Runnable action){item(p,s,slot,icon,key,Map.of(),List.of(text(p,description),text(p,key.equals("gui-close")?"admin7-ui-click-close":key.equals("gui-back")||key.equals("admin7-cancel")?"admin7-ui-click-back":key.equals("admin7-refresh")?"admin7-ui-click-refresh":"admin7-ui-click-open")),type->{if(type==ClickType.LEFT)action.run();});}
    private void field(Player p,List<Component> lore,String key,String value){field(p,lore,key,value,net.kyori.adventure.text.format.NamedTextColor.WHITE);}
    private void field(Player p,List<Component> lore,String key,String value,net.kyori.adventure.text.format.NamedTextColor color){lore.add(text(p,"admin7-ui-label",Map.of("field",messages.plain(commands.language(p),key))).append(Component.text(value.codePointCount(0,value.length())<=48?value:value.substring(0,value.offsetByCodePoints(0,48))+"…",color)));}
    private void identity(Player p,List<Component> lore,String field,String raw,String role){
        if(raw.equals(new UUID(0,0).toString())){field(p,lore,"admin7-field-"+role,"—");return;}
        field(p,lore,"admin7-field-"+role,value(p,field,raw));
        try{UUID id=UUID.fromString(raw);lore.add(text(p,"admin7-identity-uuid",Map.of("uuid",id.toString())));}catch(IllegalArgumentException ignored){}
    }
    private String value(Player p,String key,String raw){if(Set.of("actor","voter","target","neighbor").contains(key))try{var id=UUID.fromString(raw);return id.equals(new UUID(0,0))?"—":name(p,id);}catch(IllegalArgumentException ignored){}return commands.human(p,key,raw);}
    private String bool(Player p,boolean enabled){return messages.plain(commands.language(p),enabled?"admin-enabled":"admin-disabled");}
    private String score(Player p,String raw){try{long n=Long.parseLong(raw);return (n>0?"+":"")+messages.number(commands.language(p),n);}catch(NumberFormatException e){return "—";}}
    private static Material icon(Section section){return switch(section){case INSPECT->Material.PLAYER_HEAD;case VOTES,VOTE->Material.WRITABLE_BOOK;case PENDING->Material.CLOCK;case TRUST,TRUST_HISTORY->Material.SHIELD;case FLAGS->Material.YELLOW_BANNER;case RELATIONS->Material.LEAD;case RECOVERY->Material.COMPASS;case ADMIN->Material.ANVIL;case AUDIT,OPERATION,BULKS->Material.CLOCK;case RECALCULATE->Material.REDSTONE;};}
    private void render(Player p,Session s){s.inventory.clear();s.actions.clear();
        if(s.view==View.DEBUG){debugPage(p,s);return;}
        if(s.section==Section.INSPECT&&s.view==View.NORMAL){overview(p,s);navigation(p,s);return;}
        if(s.view==View.ACCESS)access(p,s);else if(s.view==View.ADJUST)adjust(p,s);else if(s.section==Section.TRUST)trust(p,s);else if(s.section!=Section.RECALCULATE)rows(p,s);
        if(s.section==Section.RECALCULATE)recalculate(p,s);
        if(s.section==Section.RECOVERY&&p.hasPermission("dexturreputation.recovery.admin"))recoveryActions(p,s);
        navigation(p,s);
    }
    private int contentSlot(Session s,int logical){if(s.view==View.ACCESS)return new int[]{1,4,7}[Math.min(logical,2)];if(s.view==View.ADJUST)return new int[]{1,3,5,7}[Math.min(logical,3)];int index=logical-(s.page-1)*s.layout.content().size();return index<0||index>=s.layout.content().size()?-1:s.layout.content().get(index);}
    private void overview(Player p,Session s){var summary=s.loaded.summary();var lore=new ArrayList<Component>();
        field(p,lore,"admin7-ui-player",name(p,s.target),net.kyori.adventure.text.format.NamedTextColor.AQUA);field(p,lore,"admin7-field-visible",score(p,summary.getOrDefault("visible","0")),summary.getOrDefault("visible","0").startsWith("-")?net.kyori.adventure.text.format.NamedTextColor.RED:summary.getOrDefault("visible","0").equals("0")?net.kyori.adventure.text.format.NamedTextColor.GRAY:net.kyori.adventure.text.format.NamedTextColor.GREEN);
        field(p,lore,"admin7-field-tier",plugin.tierLabel(summary.getOrDefault("tier","NEUTRAL"),commands.language(p)));
        item(p,s,4,Material.PLAYER_HEAD,"admin7-ui-overview",Map.of(),lore,null);
        int[] positions={9,10,11,12,13,14,15,16,17};int index=0;
        for(var section:SECTIONS)if(section!=Section.INSPECT){int slot=positions[index++];if(allowed(p,section))button(p,s,slot,icon(section),"admin7-section-"+section.name().toLowerCase(Locale.ROOT),"admin7-desc-"+section.name().toLowerCase(Locale.ROOT),()->open(p,s.target,section,1));}
        if(allowed(p,Section.VOTES))button(p,s,19,Material.FEATHER,"admin7-ui-given","admin7-ui-given-desc",()->open(p,s.target,Section.VOTES,1,null,View.GIVEN,null));
        button(p,s,20,Material.TRIPWIRE_HOOK,"admin7-ui-access","admin7-ui-policy-desc",()->open(p,s.target,Section.INSPECT,1,null,View.ACCESS,null));
        button(p,s,21,Material.EMERALD,"admin7-ui-adjust","admin7-ui-adjust-desc",()->open(p,s.target,Section.INSPECT,1,null,View.ADJUST,null));
        button(p,s,23,Material.COMPARATOR,"admin7-ui-debug","admin7-ui-debug-desc",()->debugReport(p,s));
    }
    private void trust(Player p,Session s){
        int i=0;for(String key:List.of("trust","reliability","maturity","account_maturity","playtime_maturity","rehabilitation","playtime","submitted","invalidated","confirmed","first_joined","restricted_until","calculated","stale")){
            if(!s.loaded.summary().containsKey(key))continue;
            item(p,s,s.layout.content().get(i++),Material.SHIELD,"admin7-ui-detail-field",Map.of("field",commands.label(p,key)),List.of(text(p,"admin7-ui-label-value",Map.of("value",value(p,key,s.loaded.summary().get(key))))),null);
        }
        if(i==0)item(p,s,4,Material.SHIELD,"admin7-empty",Map.of(),List.of(),null);
        if(allowed(p,Section.TRUST_HISTORY))button(p,s,17,Material.WRITABLE_BOOK,"admin7-trust-history","admin7-desc-trust",()->open(p,s.target,Section.TRUST_HISTORY,1,null,View.NORMAL,()->go(p,s)));
    }
    private void access(Player p,Session s){var lore=new ArrayList<Component>();
        field(p,lore,"admin7-ui-player-lock",messages.plain(commands.language(p),s.access!=null&&s.access.votingLocked()?"admin7-voting-blocked":"admin7-voting-unblocked"),s.access!=null&&s.access.votingLocked()?net.kyori.adventure.text.format.NamedTextColor.RED:net.kyori.adventure.text.format.NamedTextColor.GREEN);
        field(p,lore,"admin7-ui-policy",value(p,"status",s.configuredVoting.decision().name()),s.configuredVoting.permitted()?net.kyori.adventure.text.format.NamedTextColor.GREEN:net.kyori.adventure.text.format.NamedTextColor.RED);
        field(p,lore,"admin7-ui-effective",value(p,"status",s.voting.decision().name()),s.voting.permitted()?net.kyori.adventure.text.format.NamedTextColor.GREEN:net.kyori.adventure.text.format.NamedTextColor.RED);
        field(p,lore,"admin7-ui-bypass",bool(p,org.dextur.reputation.domain.policy.InitialEligibility.enabled(s.access)));
        lore.add(text(p,"admin7-ui-policy-desc"));item(p,s,contentSlot(s,0),Material.TRIPWIRE_HOOK,"admin7-ui-access",Map.of(),lore,null);
        boolean locked=s.access!=null&&s.access.votingLocked();String lock=locked?"unlock":"lock";
        if(p.hasPermission("dexturreputation.admin.voting."+lock))action(p,s,1,locked?Material.LIME_DYE:Material.RED_DYE,"admin7-ui-"+lock,"admin7-ui-lock-desc","voting."+lock,()->commands.previous.guiAdjustment(p,s.target,locked?org.dextur.reputation.domain.policy.AdministrationData.Type.UNLOCK:org.dextur.reputation.domain.policy.AdministrationData.Type.LOCK,0,()->{if(current(p,s))go(p,s);}));
        boolean force=org.dextur.reputation.domain.policy.InitialEligibility.enabled(s.access);
        if(p.hasPermission("dexturreputation.admin.force"))action(p,s,2,force?Material.REDSTONE_TORCH:Material.SOUL_TORCH,"admin7-ui-force-"+(force?"off":"on"),"admin7-ui-force-desc","force",()->commands.previous.force(p,s.target,force?"off":"on",()->{if(current(p,s))go(p,s);}));
    }
    private void adjust(Player p,Session s){
        item(p,s,4,Material.PLAYER_HEAD,"gui-profile",Map.of("player",name(p,s.target),"reputation",score(p,s.loaded.summary().getOrDefault("visible","0"))),List.of(text(p,"admin7-ui-adjust-desc")),null);
        var types=List.of(org.dextur.reputation.domain.policy.AdministrationData.Type.ADD,org.dextur.reputation.domain.policy.AdministrationData.Type.REMOVE,org.dextur.reputation.domain.policy.AdministrationData.Type.SET);
        var icons=List.of(Material.LIME_DYE,Material.RED_DYE,Material.BARRIER);var keys=List.of("admin7-ui-add","admin7-ui-remove","admin7-ui-reset");
        for(int i=0;i<types.size();i++){var type=types.get(i);if(p.hasPermission("dexturreputation.admin."+type.name().toLowerCase(Locale.ROOT)))action(p,s,i,icons.get(i),keys.get(i),"admin7-ui-adjust-desc",type.name().toLowerCase(Locale.ROOT),()->commands.previous.guiAdjustment(p,s.target,type,type==org.dextur.reputation.domain.policy.AdministrationData.Type.SET?0:1,()->{if(current(p,s))go(p,s);}));}
        var lore=List.of(text(p,"admin7-ui-custom-adjust"));item(p,s,contentSlot(s,3),Material.PAPER,"admin7-ui-custom-title",Map.of(),lore,null);
    }
    private void action(Player p,Session s,int index,Material material,String key,String description,String permission,Runnable action){item(p,s,contentSlot(s,index),material,key,Map.of(),List.of(text(p,description),text(p,"admin7-ui-click-confirm")),t->{if(t==ClickType.LEFT)confirm(p,s,key,permission,action);});}
    private void rows(Player p,Session s){
        var rows=s.loaded.rows();if(rows.isEmpty()&&(s.section==Section.TRUST||s.section==Section.RECOVERY||s.section==Section.RECALCULATE)&&!s.loaded.summary().isEmpty())rows=List.of(new Row(s.target,s.loaded.summary()));
        int index=0;for(var row:rows){if(index>=s.layout.content().size())break;var values=row.values();var lore=new ArrayList<Component>();
            var fields=switch(s.section){case VOTES,VOTE,PENDING->List.of("voter","target","type","reason","status");case TRUST->List.of("trust","playtime","confirmed","restricted_until");case RELATIONS->List.of("neighbor","incoming","outgoing","evidence");case FLAGS->List.of("severity","status","voter");case ADMIN,AUDIT,OPERATION->List.of("action","actor","target","before","after","before_visible","after_visible","before_community","after_community","reason","timestamp");case RECOVERY->List.of("recovery","stage","recovery_reward");default->List.of("action","status","timestamp");};
            for(String key:fields)if(values.containsKey(key)){
                if(Set.of("actor","target","voter","neighbor").contains(key))identity(p,lore,key,values.get(key),key.equals("actor")&&AuditIdentity.administrative(values)?"administrator":key);
                else field(p,lore,"admin7-field-"+key,key.equals("reason")&&commands.votes!=null&&Set.of(Section.VOTES,Section.VOTE,Section.PENDING).contains(s.section)?commands.votes.reason(commands.language(p),values.get(key)):value(p,key,values.get(key)));
            }
            if(s.section==Section.ADMIN||s.section==Section.AUDIT){
                if(values.containsKey("action"))field(p,lore,"admin7-field-action_id",values.get("action"));
                String delta=AuditIdentity.delta(values);if(delta!=null)field(p,lore,"admin7-field-delta",delta);
            }

            String hint=s.section==Section.PENDING&&p.hasPermission("dexturreputation.admin.pending.approve")||s.section==Section.PENDING&&p.hasPermission("dexturreputation.admin.pending.reject")?"admin7-gui-pending-hint":s.section==Section.FLAGS&&p.hasPermission("dexturreputation.admin.flags.resolve")?"admin7-gui-flags-hint":s.section==Section.VOTE&&values.containsKey("voter")?"admin7-gui-votes-hint":s.section==Section.VOTES?"admin7-ui-click-open":"admin7-ui-information";
            if(!hint.equals("admin7-ui-information"))lore.add(text(p,hint));if(s.section!=Section.PENDING&&(s.section!=Section.FLAGS||!p.hasPermission("dexturreputation.admin.flags.resolve")))lore.add(text(p,"admin7-ui-right-details"));
            int number=(s.page-1)*s.query.size()+index+1;int slot=s.layout.content().get(index++);
            String title="admin7-ui-row";Map<String,String> titleValues=Map.of("section",messages.plain(commands.language(p),s.view==View.GIVEN?"admin7-ui-given":"admin7-section-"+s.section.name().toLowerCase(Locale.ROOT)),"number",Integer.toString(number));
            if(values.containsKey("voter")&&values.containsKey("type")){title="admin7-ui-vote-row";titleValues=Map.of("type",value(p,"type",values.get("type")),"player",value(p,s.view==View.GIVEN?"target":"voter",values.getOrDefault(s.view==View.GIVEN?"target":"voter","")));}
            else if(values.containsKey("action")&&values.containsKey("actor")){title="admin7-ui-operation-row";String action=org.dextur.reputation.domain.policy.InitialEligibility.KEY.equals(values.get("policy"))?messages.plain(commands.language(p),"admin7-result-force-"+(values.getOrDefault("after_override",values.getOrDefault("override","DEFAULT")).equals("FORCE_ALLOW")?"on":"off")):value(p,"action",values.get("action"));titleValues=Map.of("action",action,"player",value(p,"actor",values.get("actor")));}
            item(p,s,slot,s.section==Section.VOTES||s.section==Section.PENDING||s.section==Section.VOTE?("PENDING".equals(values.get("status"))?Material.CLOCK:"INVALIDATED".equals(values.get("status"))?Material.BARRIER:"NEGATIVE".equals(values.get("type"))?Material.RED_DYE:Material.LIME_DYE):icon(s.section),title,titleValues,lore,type->{
                if(s.section==Section.PENDING&&(type==ClickType.LEFT||type==ClickType.RIGHT)){var a=type==ClickType.LEFT?Action.APPROVE:Action.REJECT;moderate(p,s,row,a,"");}
                else if(s.section==Section.FLAGS&&p.hasPermission("dexturreputation.admin.flags.resolve")&&(type==ClickType.LEFT||type==ClickType.RIGHT))moderate(p,s,row,Action.RESOLVE_FLAG,type==ClickType.LEFT?"FALSE_POSITIVE":"CONFIRMED_ABUSE");
                else if(type==ClickType.RIGHT)debug(p,s,row);
                else if(type==ClickType.LEFT&&s.section==Section.VOTES&&p.hasPermission("dexturreputation.admin.vote.inspect"))open(p,s.target,Section.VOTE,1,row.id(),View.NORMAL,()->go(p,s));
                else if(type==ClickType.LEFT&&s.section==Section.VOTE&&values.containsKey("voter"))moderate(p,s,row,"INVALIDATED".equals(values.get("status"))?Action.RESTORE:Action.INVALIDATE,"");
            });
        }
        if(rows.isEmpty())item(p,s,contentSlot(s,0),Material.GLASS_PANE,"admin7-empty",Map.of(),List.of(),null);
    }
    private void recoveryActions(Player p,Session s){
        String state=s.loaded.summary().getOrDefault("recovery","AVAILABLE");
        var actions=state.equals("ACTIVE")?List.of("pause","cancel","reset_stage"):state.equals("PAUSED")?List.of("resume","cancel","reset_stage"):List.<String>of();
        for(int i=0;i<actions.size();i++){String action=actions.get(i);button(p,s,s.layout.size()-18+List.of(1,3,5).get(i),action.equals("cancel")?Material.BARRIER:Material.COMPASS,"admin7-recovery-"+action,"admin7-ui-recovery-desc",()->confirm(p,s,"admin7-recovery-"+action,"recovery-operation",()->commands.previous.recoveryAction(p,s.target,action,()->{if(current(p,s))go(p,s);})));}
    }
    private void moderate(Player p,Session s,Row row,Action action,String resolution){if(!commands.permitted(p,InvestigationCommands.permission(action)))return;
        var request=commands.request(p,action,row.id(),messages.plain(commands.language(p),"admin7-gui-reason"),Long.parseLong(row.values().getOrDefault("revision","-1")),resolution);
        s.confirmationTarget=row.values().containsKey("target")?UUID.fromString(row.values().get("target")):s.target;
        s.confirmation=request;confirm(p,s,"admin7-action-"+action.name().toLowerCase(Locale.ROOT),InvestigationCommands.permission(action),()->commands.mutate(p,request,()->{if(current(p,s))go(p,s);}));
    }
    private void recalculate(Player p,Session s){var actions=List.of(Action.RECALCULATE_REPUTATION,Action.RECALCULATE_TRUST,Action.RECALCULATE_TIER,Action.RECALCULATE_ALL);for(int i=0;i<actions.size();i++){var action=actions.get(i);if(p.hasPermission("dexturreputation.admin."+InvestigationCommands.permission(action)))button(p,s,s.layout.recalculate().get(i),Material.REDSTONE,"admin7-action-"+action.name().toLowerCase(Locale.ROOT),"admin7-ui-recalculate-desc",()->{
            var request=commands.request(p,action,s.target,messages.plain(commands.language(p),"admin7-gui-reason"),Long.parseLong(s.loaded.summary().get(action==Action.RECALCULATE_TRUST?"trust_revision":"revision")),"");
            s.confirmation=request;confirm(p,s,"admin7-action-"+action.name().toLowerCase(Locale.ROOT),InvestigationCommands.permission(action),()->commands.mutate(p,request,()->{if(current(p,s))go(p,s);}));});}}
    private boolean permitted(Player p,String permission){if(!permission.equals("recovery-operation"))return commands.permitted(p,permission);if(p.hasPermission("dexturreputation.recovery.admin"))return true;commands.send(p,"no-permission",Map.of());return false;}
    void confirm(Player p,Session s,String key,String permission,Runnable action){if(!current(p,s)||!permitted(p,permission))return;
        resize(p,s,18);s.awaiting=true;s.expires=app.now().plusSeconds(app.rules().tokenSeconds());s.inventory.clear();s.actions.clear();
        item(p,s,s.layout.confirm(),s.layout.confirmation(),"admin7-confirm-action",Map.of(),List.of(text(p,key),text(p,"admin7-ui-confirm-player",Map.of("player",name(p,s.confirmationTarget==null?s.target:s.confirmationTarget))),s.confirmation!=null&&!s.confirmation.resolution().isBlank()?text(p,"admin7-ui-resolution",Map.of("value",value(p,"status",s.confirmation.resolution()))):text(p,"admin7-ui-confirm-warning"),text(p,"admin7-ui-click-execute")),type->{
            if(type!=ClickType.LEFT||s.busy||!s.awaiting||!current(p,s))return;
            if(!permitted(p,permission))return;s.awaiting=false;
            if(!app.now().isBefore(s.expires)){commands.failed(p,new IllegalStateException("INV_TOKEN"));go(p,s);return;}
            s.busy=true;app.read(s.query).whenComplete((fresh,error)->plugin.onMain(()->{
                if(!current(p,s))return;
                if(error!=null){s.busy=false;commands.failed(p,error);return;}
                if(!fresh.fingerprint().equals(s.loaded.fingerprint())){commands.failed(p,new IllegalStateException("INV_STALE"));go(p,s);return;}
                if(!permitted(p,permission)){s.busy=false;return;}action.run();
            }));
        });button(p,s,s.layout.cancel(),Material.ARROW,"admin7-cancel","admin7-ui-cancel-desc",()->go(p,s));
    }
    private void navigation(Player p,Session s){
        if(s.section==Section.VOTES){var f=s.query.filter();var type=f.type();button(p,s,s.layout.size()-8,type==null?Material.BOOK:type==org.dextur.reputation.api.VoteType.POSITIVE?Material.LIME_DYE:Material.RED_DYE,type==null?"gui-filter-all":type==org.dextur.reputation.api.VoteType.POSITIVE?"gui-filter-positive":"gui-filter-negative","gui-filter-selected",()->{var next=type==null?org.dextur.reputation.api.VoteType.POSITIVE:type==org.dextur.reputation.api.VoteType.POSITIVE?org.dextur.reputation.api.VoteType.NEGATIVE:null;open(p,s.target,s.section,1,s.id,s.view,s.back,new Filter(f.voter(),f.target(),next,f.status(),f.before(),f.after(),f.severity(),f.source(),f.flag(),f.search()));});}

        if(s.page>1)button(p,s,s.layout.previous(),Material.ARROW,"admin7-previous","admin7-ui-page-desc",()->open(p,s.target,s.section,s.page-1,s.id,s.view,s.back,s.query.filter()));
        long total=s.section==Section.INSPECT||s.section==Section.TRUST||s.section==Section.RECALCULATE?0:s.loaded.total();int pageSize=s.view==View.ACCESS||s.view==View.ADJUST||s.section==Section.INSPECT?s.layout.content().size():s.query.size();
        if((long)s.page*pageSize<total)button(p,s,s.layout.next(),Material.ARROW,"admin7-next","admin7-ui-page-desc",()->open(p,s.target,s.section,s.page+1,s.id,s.view,s.back,s.query.filter()));
        button(p,s,s.layout.refresh(),Material.CLOCK,"admin7-refresh","admin7-ui-refresh-desc",()->load(p,s));
        button(p,s,s.layout.close(),Material.OAK_DOOR,"gui-close","admin7-ui-close-desc",p::closeInventory);
        if(s.section!=Section.INSPECT||s.view!=View.NORMAL)button(p,s,s.layout.cancel(),Material.ARROW,"gui-back","admin7-ui-back-desc",s.back!=null?s.back:()->open(p,s.target,Section.INSPECT,1));
    }
    private void debugReport(Player p,Session s){var fields=new TreeMap<>(s.loaded.summary());fields.remove("uuid");fields.put("target",s.target.toString());int index=1;for(var row:s.loaded.rows()){fields.put("record-"+index+"-record_id",row.id().toString());for(var field:AuditIdentity.expand(row.values(),id->name(p,id),messages.plain(commands.language(p),"admin7-player-unknown"),messages.plain(commands.language(p),"admin7-console"),messages.plain(commands.language(p),"admin7-system")).entrySet())if(!Set.of("plan","request_hash").contains(field.getKey()))fields.put("record-"+index+"-"+field.getKey(),field.getValue());index++;}debug(p,s,new Row(s.target,fields),false);}
    private void debug(Player p,Session origin,Row row){debug(p,origin,row,row.values().containsKey("action")||row.values().containsKey("operation")||row.values().containsKey("vote"));}
    private void debug(Player p,Session origin,Row row,boolean record){var s=new Session(p.getUniqueId(),origin.target,origin.section,1,origin.id);s.view=View.DEBUG;s.generation=generation;s.query=origin.query;s.loaded=origin.loaded;s.back=()->go(p,origin);
        var fields=new TreeMap<>(AuditIdentity.expand(row.values(),id->name(p,id),messages.plain(commands.language(p),"admin7-player-unknown"),messages.plain(commands.language(p),"admin7-console"),messages.plain(commands.language(p),"admin7-system")));if(record)fields.put("record_id",row.id().toString());fields.remove("plan");fields.remove("request_hash");var parts=new ArrayList<Map.Entry<String,String>>();for(var field:fields.entrySet()){String raw=technicalValue(p,field.getKey(),field.getValue());if(raw.isEmpty())parts.add(Map.entry(field.getKey(),"—"));for(int offset=0;offset<raw.length();){int end=raw.offsetByCodePoints(offset,Math.min(180,raw.codePointCount(offset,raw.length())));parts.add(Map.entry(field.getKey(),raw.substring(offset,end)));offset=end;}}s.details=List.copyOf(parts);
        s.layout=geometry(MenuGeometry.size(Math.min(s.details.size(),36)));s.inventory=Bukkit.createInventory(s,s.layout.size(),text(p,"admin7-ui-debug"));p.openInventory(s.inventory);debugPage(p,s);
    }
    private void debugPage(Player p,Session s){s.inventory.clear();s.actions.clear();int start=(s.page-1)*36;int i=0;
        for(var field:s.details.stream().skip(start).limit(36).toList()){
            String raw=field.getValue();var lore=new ArrayList<Component>();
            for(int offset=0;offset<raw.length()&&lore.size()<4;){int end=raw.offsetByCodePoints(offset,Math.min(45,raw.codePointCount(offset,raw.length())));lore.add(Component.text(raw.substring(offset,end),field.getKey().endsWith("_uuid")?net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY:net.kyori.adventure.text.format.NamedTextColor.WHITE));offset=end;}
            item(p,s,s.layout.content().get(i++),Material.PAPER,"admin7-ui-detail-field",Map.of("field",debugLabel(p,field.getKey())),lore,null);
        }
        button(p,s,s.layout.cancel(),Material.ARROW,"gui-back","admin7-ui-back-desc",s.back);button(p,s,s.layout.close(),Material.OAK_DOOR,"gui-close","admin7-ui-close-desc",p::closeInventory);
        if(s.page>1)button(p,s,s.layout.previous(),Material.ARROW,"admin7-previous","admin7-ui-page-desc",()->debugPageNumber(p,s,s.page-1));
        if(start+36<s.details.size())button(p,s,s.layout.next(),Material.ARROW,"admin7-next","admin7-ui-page-desc",()->debugPageNumber(p,s,s.page+1));
    }
    private String technicalValue(Player p,String field,String raw){String key=field.replaceFirst("^record-[0-9]+-","");
        if(Set.of("reason","comment").contains(key)||key.endsWith("_uuid")||key.endsWith("_name")||key.equals("record_id"))return raw;
        if(raw.equals("true")||raw.equals("false"))return bool(p,Boolean.parseBoolean(raw));
        if(raw.matches("[A-Z][A-Z0-9_]+"))try{return messages.plain(commands.language(p),"admin7-value-"+raw.toLowerCase(Locale.ROOT))+" ("+raw+")";}catch(IllegalArgumentException ignored){}
        return raw;
    }
    private String debugLabel(Player p,String field){String key=field.replaceFirst("^record-[0-9]+-","");try{return messages.plain(commands.language(p),"admin7-field-"+key);}catch(IllegalArgumentException missing){return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text(p,"admin7-technical-field",Map.of("id",field)));}}
    private void debugPageNumber(Player p,Session old,int page){var s=new Session(old.owner,old.target,old.section,page,old.id);s.view=View.DEBUG;s.generation=generation;s.query=old.query;s.loaded=old.loaded;s.details=old.details;s.back=old.back;s.layout=geometry(MenuGeometry.size(Math.min(Math.max(1,s.details.size()-(page-1)*36),36)));s.inventory=Bukkit.createInventory(s,s.layout.size(),text(p,"admin7-ui-debug"));p.openInventory(s.inventory);debugPage(p,s);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)public void click(InventoryClickEvent event){if(!(event.getView().getTopInventory().getHolder() instanceof Session s))return;event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player p)||!current(p,s)||s.busy||!plugin.ready()||event.getRawSlot()<0||event.getRawSlot()>=s.inventory.getSize()||event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT)return;long now=System.nanoTime();if(now-s.lastClick<250000000L)return;s.lastClick=now;var action=s.actions.get(event.getRawSlot());if(action!=null){s.busy=true;var type=event.getClick();plugin.getServer().getScheduler().runTask(plugin,()->{if(!current(p,s)||!plugin.ready())return;s.busy=false;action.accept(type);});}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof Session)event.setCancelled(true);}
    @EventHandler public void closed(InventoryCloseEvent event){if(event.getInventory().getHolder() instanceof Session s&&event.getInventory()==s.inventory){s.actions.clear();s.confirmation=null;s.awaiting=false;}}
    @EventHandler public void quit(PlayerQuitEvent event){if(event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Session s){s.actions.clear();s.confirmation=null;s.awaiting=false;}}
    void closeAll(){for(var p:plugin.getServer().getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof Session s){s.actions.clear();s.confirmation=null;s.awaiting=false;p.closeInventory();}}
}
