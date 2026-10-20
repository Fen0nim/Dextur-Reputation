package org.dextur.reputation.paper;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import net.kyori.adventure.text.Component;

/** Identity-based sessions: all client inventory actions are cancelled; only mapped left clicks run. */
final class VotingGui implements Listener {
    static final class Session implements InventoryHolder {
        final UUID owner,target;
        final String name;
        final Map<Integer,Runnable> actions=new HashMap<>();
        Inventory inventory;
        boolean busy;
        Session(UUID owner,UUID target,String name) { this.owner=owner;this.target=target;this.name=name; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final DexturReputationPlugin plugin;
    private final VotingService voting;
    private final VotingCommands commands;
    private final VotingConfiguration votingConfig;
    private final GuiConfiguration config;
    private final Messages messages;
    private TierService tiers;
    private PolicyEffects policies;
    void policies(PolicyEffects policies){this.policies=policies;}
    private RecoveryGui recovery;
    void recovery(RecoveryGui recovery){this.recovery=recovery;}
    private DisplayFormatter formatter;
    void tiers(TierService tiers,DisplayFormatter formatter,org.dextur.reputation.domain.tier.TierRules rules) {
        this.tiers=tiers;this.formatter=formatter;
        config.validateTiers(rules);
    }
    VotingGui(DexturReputationPlugin plugin,VotingService voting,VotingCommands commands,VotingConfiguration votingConfig,GuiConfiguration config,Messages messages) {
        this.plugin=plugin;this.voting=voting;this.commands=commands;this.votingConfig=votingConfig;this.config=config;this.messages=messages;
    }
    private boolean current(Player player,Session session) {
        return player.isOnline()&&player.getUniqueId().equals(session.owner)&&player.getOpenInventory().getTopInventory().getHolder()==session;
    }
    private Session screen(Player player,UUID target,String name,String title,Map<String,String> values) {
        var session=new Session(player.getUniqueId(),target,name);
        session.inventory=Bukkit.createInventory(session,config.size(),messages.content(commands.language(player),title,values));
        button(session,player,"close",()->player.closeInventory());player.openInventory(session.inventory);return session;
    }
    private ItemStack item(org.bukkit.Material material,Component name,List<Component> lore) {
        var item=new ItemStack(material);var meta=item.getItemMeta();meta.displayName(name);meta.lore(lore);item.setItemMeta(meta);return item;
    }
    private void button(Session session,Player player,String key,Runnable action) {
        var button=config.buttons().get(key);
        session.inventory.setItem(button.slot(),item(button.material(),messages.content(commands.language(player),"gui-"+key,Map.of()),List.of()));
        session.actions.put(button.slot(),action);
    }
    void open(Player player,UUID target,String name) {
        if(!commands.allowed(player,"gui")) { commands.send(player,"no-permission",Map.of());return; }
        var session=screen(player,target,name,"gui-profile-title",Map.of("player",name));session.busy=true;
        voting.view(target).whenComplete((view,error)->plugin.onMain(()->{
            if(!current(player,session)) return;
            session.busy=false;if(error!=null) { commands.failed(player,error);player.closeInventory();return; }
            var language=commands.language(player);var s=view.statistics();var button=config.buttons().get("profile");
            session.inventory.setItem(button.slot(),item(button.material(),messages.content(language,"gui-profile",Map.of("player",name,"reputation",messages.number(language,view.profile().visibleScore()))),
                    List.of(messages.content(language,"gui-stats",Map.of("positive",Long.toString(s.positive()),"negative",Long.toString(s.negative()),"unique",Long.toString(s.uniqueVoters()))))));
            if(tiers!=null&&config.tierPanel().enabled()) tiers.cachedTier(target).ifPresent(tier->{
                var lore=new ArrayList<>(session.inventory.getItem(button.slot()).getItemMeta().lore());
                for(String key:config.tierPanel().lore()) {
                    if(key.equals("gui-tier"))lore.add(messages.content(language,key,Map.of("tier",DisplayFormatter.plain(formatter.name(tier,language)))));
                    else if(key.equals("gui-tier-progress"))lore.add(tier.nextPositiveThreshold()==null?messages.content(language,"gui-tier-maximum",Map.of()):messages.content(language,key,Map.of("threshold",Long.toString(tier.nextPositiveThreshold()),"remaining",java.math.BigInteger.valueOf(tier.nextPositiveThreshold()).subtract(java.math.BigInteger.valueOf(tier.score())).toString())));
                    else {var description=config.tierPanel().benefits().get(tier.id());if(description!=null&&!description.get(language.name()).isBlank())lore.add(messages.content(language,key,Map.of("benefits",description.get(language.name()))));}
                }
                session.inventory.setItem(button.slot(),item(config.tierPanel().icons().getOrDefault(tier.id(),config.tierPanel().material()),messages.content(language,"gui-profile",Map.of("player",name,"reputation",messages.number(language,tier.score()))),lore));
            });
            if(recovery!=null)recovery.attach(player,session);
            if(policies!=null)policies.attach(player,session);
            if(!player.getUniqueId().equals(target)) {
                if(commands.allowed(player,"vote")&&commands.allowed(player,"positive")) button(session,player,"like",()->reasons(player,target,name,VoteType.POSITIVE,1));
                if(commands.allowed(player,"vote")&&commands.allowed(player,"negative")) button(session,player,"dislike",()->reasons(player,target,name,VoteType.NEGATIVE,1));
                if(votingConfig.rules().allowRevoke()&&commands.allowed(player,"revoke")) button(session,player,"revoke",()->{
                    session.busy=true;commands.revoke(player,target,"",()->{ if(current(player,session)) open(player,target,name); });
                });
            }
            if(commands.allowed(player,"history")) {
                button(session,player,"history",()->history(player,target,name,VotePage.Direction.RECEIVED,1));
                button(session,player,"given",()->history(player,player.getUniqueId(),player.getName(),VotePage.Direction.GIVEN,1));
            }
        }));
    }
    private void reasons(Player player,UUID target,String name,VoteType type,int page) {
        var session=screen(player,target,name,"gui-reasons-title",Map.of("player",name));
        var reasons=votingConfig.rules().reasons().values().stream().filter(r->r.types().contains(type)).sorted(Comparator.comparing(r->r.id())).toList();
        int capacity=config.contentSlots().size();int start=(page-1)*capacity;
        for(int i=start;i<Math.min(start+capacity,reasons.size());i++) {
            var reason=reasons.get(i);int slot=config.contentSlots().get(i-start);
            session.inventory.setItem(slot,item(config.reasonMaterial(),messages.content(commands.language(player),reason.messageKey(),Map.of()),List.of(Component.text(reason.id()))));
            session.actions.put(slot,()->{
                session.busy=true;commands.cast(player,target,type,reason.id(),"",()->{ if(current(player,session)) open(player,target,name); });
            });
        }
        button(session,player,"back",()->open(player,target,name));
        if(page>1) button(session,player,"previous",()->reasons(player,target,name,type,page-1));
        if(start+capacity<reasons.size()) button(session,player,"next",()->reasons(player,target,name,type,page+1));
    }
    private void history(Player player,UUID target,String name,VotePage.Direction direction,int page) {
        if(!commands.allowed(player,"history")) { commands.send(player,"no-permission",Map.of());return; }
        var session=screen(player,target,name,"gui-history-title",Map.of("player",name,"page",Integer.toString(page)));session.busy=true;
        voting.history(target,direction,page).whenComplete((result,error)->plugin.onMain(()->{
            if(!current(player,session)) return;
            session.busy=false;if(error!=null) { commands.failed(player,error);player.closeInventory();return; }
            var language=commands.language(player);int slot=0;
            for(var row:result.entries()) {
                var e=row.entry();
                session.inventory.setItem(config.contentSlots().get(slot++),item(config.entryMaterial(),messages.content(language,"gui-entry",Map.of("action",commands.action(language,e.action()),"type",commands.type(language,e.type()),"reason",commands.reason(language,e.reason()))),
                        List.of(messages.content(language,"gui-entry-detail",Map.of("voter",commands.name(e.voterId()),"target",commands.name(e.targetId()),"time",e.createdAt().toString(),"state",messages.plain(language,row.active()?"vote-active":"vote-inactive"))),
                                messages.content(language,"gui-entry-comment",Map.of("comment",e.comment())))));
            }
            button(session,player,"back",()->open(player,target,name));
            if(page>1) button(session,player,"previous",()->history(player,target,name,direction,page-1));
            if((long)page*result.pageSize()<result.totalEntries()) button(session,player,"next",()->history(player,target,name,direction,page+1));
        }));
    }
    /** Must run on the Paper thread before listeners are unregistered. */
    void closeAll() {
        for(var player:plugin.getServer().getOnlinePlayers())
            if(player.getOpenInventory().getTopInventory().getHolder() instanceof Session) player.closeInventory();
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Session session)) return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)||!current(player,session)||session.busy||!plugin.ready()
                ||event.getClick()!=ClickType.LEFT||event.getRawSlot()<0||event.getRawSlot()>=session.inventory.getSize()) return;
        var action=session.actions.get(event.getRawSlot());
        if(action!=null) {
            session.busy=true;
            // Opening/closing inventories from inside InventoryClickEvent is unsafe; schedule next tick.
            plugin.getServer().getScheduler().runTask(plugin,()->{ if(current(player,session)&&plugin.ready()) action.run(); });
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Session) event.setCancelled(true);
    }
}
