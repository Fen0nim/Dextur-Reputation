package org.dextur.reputation.paper;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.dextur.reputation.application.Messages;
import org.dextur.reputation.application.ReputationApplication;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Loaded only when PlaceholderAPI is enabled. Cache misses return an empty value, never fake zero. */
final class PlaceholderBridge extends PlaceholderExpansion {
    private final DexturReputationPlugin plugin;
    private final ReputationApplication application;
    private final Messages messages;
    PlaceholderBridge(DexturReputationPlugin plugin, ReputationApplication application, Messages messages) {
        this.plugin = plugin; this.application = application; this.messages = messages;
    }
    @Override public String getIdentifier() { return "dexturreputation"; }
    @Override public String getAuthor() { return "Dextur"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player, String parameter) {
        if (player == null || !plugin.ready()) return "";
        var id = player.getUniqueId();
        var cached = application.cachedProfile(id);
        if (cached.isEmpty()) return "";
        var language = application.cachedLanguage(id);
        return switch (parameter) {
            case "score" -> Long.toString(cached.get().visibleScore());
            case "score_formatted" -> messages.number(language, cached.get().visibleScore());
            case "tier" -> PlainTextComponentSerializer.plainText().serialize(messages.tier(language));
            default -> null;
        };
    }
}
