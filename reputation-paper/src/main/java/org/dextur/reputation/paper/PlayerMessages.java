package org.dextur.reputation.paper;

import java.util.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;

/** Main-thread, online delivery. No mailbox or durable delivery claim; absence never rejects an action. */
final class PlayerMessages {
    private final DexturReputationPlugin plugin;
    private final ReputationApplication profiles;
    private final Messages messages;
    private final PlayerLanguage fallback;
    PlayerMessages(DexturReputationPlugin plugin,ReputationApplication profiles,Messages messages,PlayerLanguage fallback) {
        this.plugin=plugin;this.profiles=profiles;this.messages=messages;this.fallback=fallback;
    }
    String name(UUID id) {
        var player=plugin.getServer().getOfflinePlayer(id);
        return player==null||player.getName()==null?messages.plain(fallback,"admin7-player-unknown"):player.getName();
    }
    String actor(CommandSender sender) {return sender instanceof Player p?p.getName():messages.plain(fallback,"admin7-console");}
    void target(CommandSender actor,UUID target,String key,java.util.function.Function<PlayerLanguage,Map<String,String>> values) {
        if(actor instanceof Player p&&p.getUniqueId().equals(target))return;
        var player=plugin.getServer().getPlayer(target);
        if(player!=null&&player.isOnline()) {
            var language=profiles.cachedLanguage(target);
            var localized=new HashMap<>(values.apply(language));
            if(localized.containsKey("actor")&&!(actor instanceof Player))localized.put("actor",messages.plain(language,"admin7-console"));
            player.sendMessage(messages.render(language,key,localized));
        }
    }
}
