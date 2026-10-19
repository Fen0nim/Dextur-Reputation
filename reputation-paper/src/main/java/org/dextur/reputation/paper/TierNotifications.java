package org.dextur.reputation.paper;

import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.tier.TierRules;
import net.kyori.adventure.title.Title;

/** Best-effort local effects; offline transitions are never replayed on login. */
final class TierNotifications {
    private final DexturReputationPlugin plugin;
    private final ReputationApplication application;
    private final TierRules rules;
    private final DisplayConfiguration configuration;
    private final DisplayFormatter formatter;
    private final Messages messages;
    private final BoundedCache<UUID,Long> last;
    TierNotifications(DexturReputationPlugin plugin,ReputationApplication application,TierRules rules,DisplayConfiguration configuration,DisplayFormatter formatter,Messages messages,int cacheSize) {
        this.plugin=plugin;this.application=application;this.rules=rules;this.configuration=configuration;this.formatter=formatter;this.messages=messages;last=new BoundedCache<>(cacheSize,java.time.Duration.ofDays(1));
    }
    void accept(TierService.TierChanged change) {
        if(!plugin.ready()||!change.notifyPlayer()||!YamlFiles.flag(configuration.notifications(),"enabled"))return;
        var player=plugin.getServer().getPlayer(change.tier().playerId());if(player==null)return;
        var old=rules.byId(change.previousId());var next=rules.byId(change.tier().id());if(old==null||next==null)return;
        String kind=next.threshold()==0?"neutral":next.threshold()>0?(old.threshold()<0?"recover":next.threshold()>old.threshold()?"gain":"loss"):
            old.threshold()>=0?"negative":next.threshold()<old.threshold()?"severe":"recover";
        if(!YamlFiles.flag(configuration.notifications(),kind))return;
        long now=System.nanoTime();long delay=((Integer)configuration.notifications().get("cooldown-seconds"))*1_000_000_000L;
        if(last.get(player.getUniqueId()).filter(t->now-t<delay).isPresent()) return;last.put(player.getUniqueId(),now);
        var language=application.cachedLanguage(player.getUniqueId());var values=Map.of("tier",DisplayFormatter.plain(formatter.name(change.tier(),language)),"score",Long.toString(change.tier().score()));
        String key="tier-"+(kind.equals("neutral")?"return":kind);
        if(YamlFiles.flag(configuration.notifications(),"chat"))player.sendMessage(messages.render(language,key,values));
        if(YamlFiles.flag(configuration.notifications(),"action-bar"))player.sendActionBar(messages.content(language,key,values));
        if(YamlFiles.flag(configuration.notifications(),"title"))player.showTitle(Title.title(messages.content(language,"tier-title",values),messages.content(language,key,values)));
        String sound=(String)configuration.notifications().get("sound");if(!sound.isEmpty())player.playSound(player.getLocation(),sound,1f,1f);
    }
}
