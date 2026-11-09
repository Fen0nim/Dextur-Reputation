package org.dextur.reputation.probe;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.dextur.reputation.domain.policy.InitialEligibility;

/** One opt-in Paper session for localized voting, inventory styling and refresh routes. */
final class UxPolishRuntimeProbe {
    private final JavaPlugin plugin;
    private Player voter,target,admin;
    private VotingService voting;
    private ReputationApplication profiles;
    private final List<String> proof=new ArrayList<>();
    UxPolishRuntimeProbe(JavaPlugin plugin){this.plugin=plugin;}
    void start(Runnable success,Consumer<Throwable> failure){
        poll(()->main(()->{voter=Bukkit.getPlayerExact("UxVoter");target=Bukkit.getPlayerExact("Fen0nim");admin=Bukkit.getPlayerExact("UxAdmin");return voter!=null&&target!=null&&admin!=null;}),Boolean::booleanValue,0)
        .thenCompose(v->main(()->{
            profiles=(ReputationApplication)Bukkit.getServicesManager().load(ReputationService.class);voting=Bukkit.getServicesManager().load(VotingService.class);
            var permissions=admin.addAttachment(plugin);for(var p:Bukkit.getPluginManager().getPermissions())if(p.getName().startsWith("dexturreputation.admin."))permissions.setPermission(p.getName(),true);
            for(var p:List.of(voter,target,admin)){p.setInvulnerable(true);p.setAllowFlight(true);p.setFlying(true);p.teleport(new Location(p.getWorld(),0,200,0));p.sendMessage(Component.text("POLISH_WIRE_MARKER"));}
            return true;
        }))
        .thenCompose(v->profiles.setLanguage(voter.getUniqueId(),PlayerLanguage.ru_RU))
        .thenCompose(v->profiles.setLanguage(target.getUniqueId(),PlayerLanguage.ru_RU))
        .thenCompose(v->command(admin,"repadmin force UxVoter on"))
        .thenCompose(v->poll(()->((PolicyApplication)Bukkit.getServicesManager().load(ReputationPolicyService.class)).administration(voter.getUniqueId()),InitialEligibility::enabled,0))
        .thenCompose(v->main(()->{var like=Bukkit.getPluginCommand("like");var dislike=Bukkit.getPluginCommand("dislike");var positive=like.tabComplete(voter,"like",new String[]{"Fen0nim",""});var negative=dislike.tabComplete(voter,"dislike",new String[]{"Fen0nim",""});check(positive.size()==10&&positive.contains("Помощь игрокам")&&!positive.contains("Грубость"),"RU like suggestions");check(negative.size()==10&&negative.contains("Грубость")&&!negative.contains("Дружелюбие"),"RU dislike suggestions");return true;}))
        .thenCompose(v->command(voter,"like Fen0nim Помощь игрокам Спасибо за ресурсы"))
        .thenCompose(v->poll(()->voting.history(target.getUniqueId(),VotePage.Direction.RECEIVED,1),p->p.totalEntries()==1,0))
        .thenCompose(v->ticks(25)).thenCompose(v->command(voter,"dislike Fen0nim Грубость Проверка причины"))
        .thenCompose(v->poll(()->voting.history(target.getUniqueId(),VotePage.Direction.RECEIVED,1),p->p.totalEntries()==2,0))
        .thenApply(p->{check(p.entries().stream().anyMatch(e->e.entry().reason().equals("helpful")&&e.entry().comment().equals("Спасибо за ресурсы")),"Localized positive ID/comment");check(p.entries().stream().anyMatch(e->e.entry().reason().equals("rude")),"Localized negative ID");return true;})
        .thenCompose(v->command(voter,"rep history")).thenCompose(v->command(target,"rep history"))
        .thenCompose(v->openProfile()).thenCompose(v->main(()->{
            var inv=voter.getOpenInventory().getTopInventory();check(inv.getSize()==45,"Profile size");
            for(int slot:new int[]{2,4,6,11,13,15,20,22,24,29,31,33,38,40,42})check(inv.getItem(slot)!=null,"Missing balanced profile slot "+slot);
            inspect(voter);return true;
        }))
        .thenCompose(v->refresh(voter,"VotingGui$Session"))
        .thenCompose(v->click(voter,13)).thenCompose(v->ready(voter,"ReasonHistoryGui$Session"))
        .thenCompose(v->main(()->{inspect(voter);check(inventoryText(voter).contains("Помощь игрокам")&&inventoryText(voter).contains("Грубость"),"Localized reason groups");return true;}))
        .thenCompose(v->refresh(voter,"ReasonHistoryGui$Session"))
        .thenCompose(v->openProfile()).thenCompose(v->click(voter,11)).thenCompose(v->ready(voter,"ReasonHistoryGui$Session"))
        .thenCompose(v->main(()->{inspect(voter);check(inventoryText(voter).contains("Помощь игрокам")&&inventoryText(voter).contains("Грубость"),"Localized history entries");return true;}))
        .thenCompose(v->openProfile()).thenCompose(v->click(voter,29)).thenCompose(v->ready(voter,"RecoveryGui$Session"))
        .thenCompose(v->refresh(voter,"RecoveryGui$Session"))
        .thenCompose(v->openProfile()).thenCompose(v->click(voter,33)).thenCompose(v->ready(voter,"PolicyEffects$Panel"))
        .thenCompose(v->refresh(voter,"PolicyEffects$Panel"))
        .thenCompose(v->command(admin,"repadmin gui Fen0nim")).thenCompose(v->ready(admin,"InvestigationGui$Session"))
        .thenCompose(v->refresh(admin,"InvestigationGui$Session"))
        .thenCompose(v->click(admin,20)).thenCompose(v->ready(admin,"InvestigationGui$Session"))
        .thenCompose(v->refresh(admin,"InvestigationGui$Session"))
        .thenCompose(v->profiles.setLanguage(voter.getUniqueId(),PlayerLanguage.en_US))
        .thenCompose(v->main(()->{check(Bukkit.getPluginCommand("like").tabComplete(voter,"like",new String[]{"Fen0nim",""}).contains("Helping players"),"EN suggestions");return true;}))
        .thenCompose(v->openProfile()).thenCompose(v->click(voter,13)).thenCompose(v->ready(voter,"ReasonHistoryGui$Session"))
        .thenCompose(v->main(()->{inspect(voter);check(inventoryText(voter).contains("Helping players")&&inventoryText(voter).contains("Rudeness"),"EN reason history");return true;}))
        .thenCompose(v->ticks(20)).thenCompose(v->main(()->{
            Files.createDirectories(plugin.getDataFolder().toPath());Files.write(plugin.getDataFolder().toPath().resolve("polish-inventories.txt"),proof);
            plugin.getLogger().info("PROBE_POLISH_PASS: RU commands, canonical IDs, RU/EN reasons, symmetric profile slots, non-italic components, history/recovery/access/admin refresh");success.run();return true;
        })).exceptionally(error->{main(()->{failure.accept(error);return true;});return null;});
    }
    private CompletionStage<Boolean> openProfile(){return command(voter,"rep gui Fen0nim").thenCompose(v->ready(voter,"VotingGui$Session"));}
    private CompletionStage<Boolean> refresh(Player p,String holder){return main(()->{inspect(p);int slot=p.getOpenInventory().getTopInventory().getSize()-5;var item=p.getOpenInventory().getTopInventory().getItem(slot);check(item!=null&&plain(item.getItemMeta().displayName()).matches(".*(Обновить|Refresh).*"),"Refresh button");return slot;}).thenCompose(slot->click(p,slot)).thenCompose(v->ready(p,holder)).thenCompose(v->main(()->{inspect(p);return true;}));}
    private CompletionStage<Boolean> ready(Player p,String suffix){return poll(()->main(()->{var inv=p.getOpenInventory().getTopInventory();var holder=inv.getHolder();if(holder==null||!holder.getClass().getName().endsWith(suffix)||Arrays.stream(inv.getContents()).noneMatch(Objects::nonNull))return false;var busy=holder.getClass().getDeclaredField("busy");busy.setAccessible(true);return !busy.getBoolean(holder);}),Boolean::booleanValue,0);}
    private CompletionStage<Boolean> click(Player p,int slot){return ticks(7).thenCompose(v->main(()->{var event=new InventoryClickEvent(p.getOpenInventory(),InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL);Bukkit.getPluginManager().callEvent(event);check(event.isCancelled(),"Inventory transfer must be cancelled");return true;})).thenCompose(v->ticks(4));}
    private void inspect(Player p){normal(p.getOpenInventory().title(),false);var inv=p.getOpenInventory().getTopInventory();proof.add("SCREEN "+plain(p.getOpenInventory().title())+" size="+inv.getSize());for(int i=0;i<inv.getSize();i++){var item=inv.getItem(i);if(item==null)continue;var meta=item.getItemMeta();normal(meta.displayName(),true);check(meta.lore()!=null&&!meta.lore().isEmpty(),"Empty lore at "+i);for(var line:meta.lore())normal(line,true);proof.add(i+" "+item.getType()+" "+plain(meta.displayName())+" | "+meta.lore().stream().map(UxPolishRuntimeProbe::plain).toList());}}
    private static void normal(Component c,boolean inherited){check(c!=null,"Missing component");var state=c.decoration(TextDecoration.ITALIC);boolean italic=state==TextDecoration.State.TRUE||state==TextDecoration.State.NOT_SET&&inherited;check(!italic,"Unwanted italics: "+plain(c));for(var child:c.children())normal(child,italic);}
    private String inventoryText(Player p){StringBuilder result=new StringBuilder();for(var item:p.getOpenInventory().getTopInventory().getContents())if(item!=null){result.append(plain(item.getItemMeta().displayName()));if(item.getItemMeta().lore()!=null)item.getItemMeta().lore().forEach(c->result.append(plain(c)));}return result.toString();}
    private CompletionStage<Boolean> command(Player p,String text){return main(()->{check(p.performCommand(text),"Command refused: "+text);return true;}).thenCompose(v->ticks(5));}
    private <T> CompletableFuture<T> main(Callable<T> work){var result=new CompletableFuture<T>();Runnable run=()->{try{result.complete(work.call());}catch(Throwable error){result.completeExceptionally(error);}};if(Bukkit.isPrimaryThread())run.run();else Bukkit.getScheduler().runTask(plugin,run);return result;}
    private CompletionStage<Boolean> ticks(long count){var result=new CompletableFuture<Boolean>();Bukkit.getScheduler().runTaskLater(plugin,()->result.complete(true),count);return result;}
    private <T> CompletionStage<T> poll(Supplier<? extends CompletionStage<T>> read,Predicate<T> ready,int attempt){return read.get().thenCompose(value->{if(ready.test(value))return CompletableFuture.completedFuture(value);if(attempt>100)return CompletableFuture.failedFuture(new AssertionError("Polish timeout: "+value));return ticks(2).thenCompose(v->poll(read,ready,attempt+1));});}
    private static String plain(Component c){return PlainTextComponentSerializer.plainText().serialize(c);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
