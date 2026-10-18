package org.dextur.reputation.paper;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.dextur.reputation.application.Messages;
import org.dextur.reputation.application.ReputationApplication;
import org.dextur.reputation.application.Settings;
import org.dextur.reputation.domain.PlayerLanguage;

final class ReputationCommands implements CommandExecutor, TabCompleter {
    private final DexturReputationPlugin plugin;
    private final ReputationApplication application;
    private final Settings settings;
    private final Messages messages;

    ReputationCommands(DexturReputationPlugin plugin, ReputationApplication application, Settings settings, Messages messages) {
        this.plugin = plugin; this.application = application; this.settings = settings; this.messages = messages;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        PlayerLanguage language = sender instanceof Player player ? application.cachedLanguage(player.getUniqueId()) : settings.defaultLanguage();
        if (command.getName().equals("dexturreputation")) {
            String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            if (args.length > 1) { send(sender, language, "help", Map.of()); return true; }
            if (action.equals("status")) {
                if (!sender.hasPermission("dexturreputation.status")) send(sender, language, "no-permission", Map.of());
                else send(sender, language, "status", Map.of("state", messages.template(language, plugin.ready() ? "state-ready" : "state-starting"),
                        "queue", messages.number(language, application.queuedOperations()), "integration", messages.template(language, plugin.hasPlaceholders() ? "integration-active" : "integration-inactive")));
            } else if (action.equals("version")) send(sender, language, "version", Map.of("version", plugin.getPluginMeta().getVersion()));
            else send(sender, language, "help", Map.of());
            return true;
        }
        if (!sender.hasPermission("dexturreputation.use")) { send(sender, language, "no-permission", Map.of()); return true; }
        if (!plugin.ready()) { send(sender, language, "unavailable", Map.of()); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("language")) {
            if (!(sender instanceof Player player)) { send(sender, language, "players-only", Map.of()); return true; }
            if (!sender.hasPermission("dexturreputation.language")) { send(sender, language, "no-permission", Map.of()); return true; }
            PlayerLanguage selected;
            try {
                if (args.length != 2) throw new IllegalArgumentException();
                selected = PlayerLanguage.parse(args[1]);
            } catch (IllegalArgumentException e) { send(sender, language, "language-usage", Map.of()); return true; }
            complete(sender, application.setLanguage(player.getUniqueId(), selected), () ->
                    send(sender, selected, "language-set", Map.of("language", selected.name())));
            return true;
        }
        if (args.length > 1) { send(sender, language, "usage", Map.of()); return true; }
        UUID target;
        String name;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) { send(sender, language, "players-only", Map.of()); return true; }
            target = player.getUniqueId(); name = player.getName();
        } else {
            // Cached lookup only: never resolve names through Mojang or create synthetic offline UUIDs.
            var player = plugin.getServer().getPlayerExact(args[0]);
            var known = player != null ? player : plugin.getServer().getOfflinePlayerIfCached(args[0]);
            if (known == null || (player == null && !known.hasPlayedBefore())) {
                send(sender, language, "unknown-player", Map.of("player", args[0])); return true;
            }
            target = known.getUniqueId(); name = known.getName() == null ? target.toString() : known.getName();
        }
        String displayName = name;
        // Resolve the sender's stored language asynchronously and deliver only on the server thread.
        CompletionStage<PlayerLanguage> selected = sender instanceof Player player ? application.language(player.getUniqueId())
                : java.util.concurrent.CompletableFuture.completedFuture(settings.defaultLanguage());
        selected.thenCombine(application.getProfile(target), (locale, profile) -> Map.entry(locale, profile))
                .whenComplete((result, error) -> plugin.onMain(() -> {
                    if (!connected(sender)) return;
                    if (error != null) { failed(sender, error); return; }
                    PlayerLanguage locale = result.getKey();
                    send(sender, locale, "profile", Map.of("player", displayName,
                            "reputation", messages.number(locale, result.getValue().visibleScore()), "tier", messages.template(locale, "tier-neutral")));
                }));
        return true;
    }

    private void complete(CommandSender sender, CompletionStage<?> stage, Runnable success) {
        stage.whenComplete((value, error) -> plugin.onMain(() -> {
            if (!connected(sender)) return;
            if (error == null) success.run(); else failed(sender, error);
        }));
    }
    private void failed(CommandSender sender, Throwable error) {
        plugin.logFailure("command", error);
        send(sender, sender instanceof Player player ? application.cachedLanguage(player.getUniqueId()) : settings.defaultLanguage(), "error", Map.of());
    }
    private boolean connected(CommandSender sender) { return !(sender instanceof Player p) || p.isOnline(); }
    private void send(CommandSender sender, PlayerLanguage language, String key, Map<String, String> placeholders) {
        sender.sendMessage(messages.render(language, key, placeholders));
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> candidates;
        if (command.getName().equals("dexturreputation")) {
            candidates = args.length == 1 ? (sender.hasPermission("dexturreputation.status") ? List.of("help", "version", "status") : List.of("help", "version")) : List.of();
        } else if (!sender.hasPermission("dexturreputation.use")) return List.of();
        else if (args.length == 2 && args[0].equalsIgnoreCase("language") && sender.hasPermission("dexturreputation.language")) candidates = Arrays.stream(PlayerLanguage.values()).map(Enum::name).toList();
        else if (args.length == 1) candidates = sender.hasPermission("dexturreputation.language") ? List.of("language") : List.of();
        else return List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
