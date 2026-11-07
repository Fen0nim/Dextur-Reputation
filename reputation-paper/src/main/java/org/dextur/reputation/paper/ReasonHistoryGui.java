package org.dextur.reputation.paper;

import java.util.*;
import java.util.concurrent.CompletionStage;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import net.kyori.adventure.text.Component;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.*;

/** Public, read-only history: no investigation or private anti-abuse data enters this view. */
final class ReasonHistoryGui {
    interface Reader { CompletionStage<ReasonHistory.Page> read(UUID player,ReasonHistory.Query query); }
    enum View { REASONS, ENTRIES }
    static final class Session implements InventoryHolder {
        final UUID owner,target;final String name;final ReasonHistory.Query query;final View view;final Runnable back;
        final Map<Integer,Runnable> actions=new HashMap<>();Inventory inventory;boolean busy;
        Session(UUID owner,UUID target,String name,ReasonHistory.Query query,View view,Runnable back){this.owner=owner;this.target=target;this.name=name;this.query=query;this.view=view;this.back=back;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private final DexturReputationPlugin plugin;private final VotingCommands commands;private final Messages messages;
    private final VotingConfiguration config;private final Reader reader;
    ReasonHistoryGui(DexturReputationPlugin plugin,VotingCommands commands,Messages messages,VotingConfiguration config,Reader reader){this.plugin=plugin;this.commands=commands;this.messages=messages;this.config=config;this.reader=reader;}
    private boolean allowed(Player p){return commands.allowed(p,"gui")&&commands.allowed(p,"history");}
    private boolean current(Player p,Session s){return p.isOnline()&&p.getUniqueId().equals(s.owner)&&p.getOpenInventory().getTopInventory().getHolder()==s;}
    private Component text(Player p,String key,Map<String,String> values){return messages.content(commands.language(p),key,values);}
    String reason(PlayerLanguage locale,String id){
        if(ReasonHistory.canonical(id).isEmpty())return messages.plain(locale,"gui-reason-unspecified");
        var configured=config.rules().reasons().get(id);
        if(configured!=null)return messages.plain(locale,configured.messageKey());
        try{return messages.plain(locale,"reason-"+id);}catch(IllegalArgumentException missing){return DisplayFormatter.plain(messages.content(locale,"gui-reason-archived",Map.of("id",id)));}
    }
    void open(Player p,UUID target,String name,VotePage.Direction direction,Runnable back){
        show(p,target,name,new ReasonHistory.Query(direction,null,null,false,1,config.pageSize()),View.REASONS,back);
    }
    void show(Player p,UUID target,String name,ReasonHistory.Query q,View view,Runnable back){
        if(!allowed(p)){commands.send(p,"no-permission",Map.of());return;}
        var s=new Session(p.getUniqueId(),target,name,q,view,back);s.busy=true;
        s.inventory=Bukkit.createInventory(s,27,text(p,"gui-reason-title",Map.of("player",name)));p.openInventory(s.inventory);
        reader.read(target,q).whenComplete((page,error)->plugin.onMain(()->{
            if(!current(p,s))return;if(!allowed(p)){p.closeInventory();return;}
            if(error!=null){commands.failed(p,error);p.closeInventory();return;}
            render(p,s,page);s.busy=false;
        }));
    }
    private void item(Player p,Session s,int slot,Material icon,String key,Map<String,String> values,List<Component> lore,Runnable action){
        var item=new ItemStack(icon);var meta=item.getItemMeta();meta.displayName(text(p,key,values).colorIfAbsent(icon==Material.PLAYER_HEAD?net.kyori.adventure.text.format.NamedTextColor.AQUA:net.kyori.adventure.text.format.NamedTextColor.YELLOW));meta.lore(lore.stream().map(line->line.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.GRAY)).toList());item.setItemMeta(meta);s.inventory.setItem(slot,item);if(action!=null)s.actions.put(slot,action);
    }
    private void control(Player p,Session s,int slot,Material icon,String key,Runnable action){item(p,s,slot,icon,key,Map.of(),List.of(),action);}
    private void render(Player p,Session s,ReasonHistory.Page page){
        var groups=page.groups().stream().filter(g->s.query.type()==null||g.type()==s.query.type()).toList();
        int start=(s.query.page()-1)*s.query.size();int visible=s.view==View.REASONS?Math.max(0,Math.min(s.query.size(),groups.size()-start)):page.entries().size();
        int size=MenuGeometry.size(9+Math.max(1,visible));if(s.inventory.getSize()!=size){s.inventory=Bukkit.createInventory(s,size,text(p,"gui-reason-title",Map.of("player",s.name)));p.openInventory(s.inventory);}
        s.inventory.clear();s.actions.clear();
        item(p,s,4,Material.PLAYER_HEAD,"gui-reason-player",Map.of("player",s.name),List.of(text(p,"gui-reason-summary",Map.of("active",Long.toString(page.groups().stream().mapToLong(ReasonHistory.Group::active).sum()),"events",Long.toString(page.groups().stream().mapToLong(ReasonHistory.Group::historical).sum())))),null);
        for(int i=0;i<3;i++){VoteType type=i==0?null:i==1?VoteType.POSITIVE:VoteType.NEGATIVE;String key=i==0?"all":i==1?"positive":"negative";
            item(p,s,i,s.query.type()==type?Material.GOLD_NUGGET:i==0?Material.BOOK:i==1?Material.LIME_DYE:Material.RED_DYE,"gui-filter-"+key,Map.of(),s.query.type()==type?List.of(text(p,"gui-filter-selected",Map.of())):List.of(),()->show(p,s.target,s.name,new ReasonHistory.Query(s.query.direction(),type,null,s.query.activeOnly(),1,s.query.size()),View.REASONS,s.back));
        }
        control(p,s,6,s.query.activeOnly()?Material.EMERALD:Material.CLOCK,s.query.activeOnly()?"gui-filter-active":"gui-filter-journal",()->show(p,s.target,s.name,new ReasonHistory.Query(s.query.direction(),s.query.type(),s.query.reason(),!s.query.activeOnly(),1,s.query.size()),s.view,s.back));
        control(p,s,8,Material.WRITABLE_BOOK,"gui-filter-records",()->show(p,s.target,s.name,s.query.page(1),View.ENTRIES,()->show(p,s.target,s.name,s.query,s.view,s.back)));
        if(s.view==View.REASONS){int n=9;for(var g:groups.stream().skip(start).limit(s.query.size()).toList()){
            item(p,s,n++,g.type()==VoteType.POSITIVE?Material.LIME_DYE:Material.RED_DYE,"gui-reason-category",Map.of("reason",reason(commands.language(p),g.reason())),List.of(text(p,"gui-reason-counts",Map.of("active",Long.toString(g.active()),"events",Long.toString(g.historical()))),text(p,"gui-reason-open",Map.of())),()->show(p,s.target,s.name,new ReasonHistory.Query(s.query.direction(),g.type(),g.reason(),s.query.activeOnly(),1,s.query.size()),View.ENTRIES,()->show(p,s.target,s.name,s.query,s.view,s.back)));
        }}else{int n=9;for(var row:page.entries()){
            var e=row.entry();String participant=s.query.direction()==VotePage.Direction.RECEIVED?commands.name(e.voterId(),commands.language(p)):commands.name(e.targetId(),commands.language(p));
            item(p,s,n++,e.action()==VoteEntry.Action.REVOKE?Material.GRAY_DYE:e.type()==VoteType.POSITIVE?Material.LIME_DYE:Material.RED_DYE,"gui-history-record",Map.of("type",commands.type(commands.language(p),e.type()),"reason",reason(commands.language(p),e.reason())),List.of(text(p,"gui-history-action",Map.of("action",commands.action(commands.language(p),e.action()))),text(p,s.query.direction()==VotePage.Direction.RECEIVED?"gui-history-voter":"gui-history-recipient",Map.of("player",participant)),text(p,"gui-history-time",Map.of("time",date(commands.language(p),e.createdAt()))),text(p,row.active()?"gui-history-active":"gui-history-inactive",Map.of()),text(p,"gui-entry-comment",Map.of("comment",e.comment()))),null);
        }}
        if(visible==0)item(p,s,13,Material.GLASS_PANE,"gui-history-empty",Map.of(),List.of(),null);
        if(s.query.reason()!=null)item(p,s,5,Material.NAME_TAG,"gui-reason-category",Map.of("reason",reason(commands.language(p),s.query.reason())),List.of(text(p,"gui-filter-selected",Map.of())),null);
        long total=s.view==View.REASONS?groups.size():page.total();
        if(s.query.page()>1)control(p,s,MenuGeometry.previous(size),Material.ARROW,"gui-previous",()->show(p,s.target,s.name,s.query.page(s.query.page()-1),s.view,s.back));
        if((long)s.query.page()*s.query.size()<total)control(p,s,MenuGeometry.next(size),Material.ARROW,"gui-next",()->show(p,s.target,s.name,s.query.page(s.query.page()+1),s.view,s.back));
        control(p,s,MenuGeometry.back(size),Material.ARROW,"gui-back",s.back);
        control(p,s,MenuGeometry.refresh(size),Material.CLOCK,"admin7-refresh",()->show(p,s.target,s.name,s.query,s.view,s.back));
        control(p,s,MenuGeometry.close(size),Material.OAK_DOOR,"gui-close",p::closeInventory);
    }
    static String date(PlayerLanguage language,java.time.Instant at){return java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT).withLocale(java.util.Locale.forLanguageTag(language.name().replace('_','-'))).withZone(java.time.ZoneId.systemDefault()).format(at);}
    void click(InventoryClickEvent e){if(!(e.getView().getTopInventory().getHolder() instanceof Session s))return;e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||!current(p,s)||s.busy||!plugin.ready()||e.getClick()!=ClickType.LEFT||e.getRawSlot()<0||e.getRawSlot()>=s.inventory.getSize())return;
        if(!allowed(p)){commands.send(p,"no-permission",Map.of());return;}var action=s.actions.get(e.getRawSlot());if(action==null)return;s.busy=true;
        plugin.getServer().getScheduler().runTask(plugin,()->{if(current(p,s)&&plugin.ready()&&allowed(p))action.run();});
    }
    void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Session)e.setCancelled(true);}
    void closeAll(){for(var p:plugin.getServer().getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof Session)p.closeInventory();}
}
