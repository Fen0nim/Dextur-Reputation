package org.dextur.reputation.application;

import java.io.IOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.dextur.reputation.domain.PlayerLanguage;

public final class Messages {
    public static final Set<String> STORAGE_KEYS = Set.of("storage-status", "storage-failed", "storage-config-invalid",
            "storage-admin-usage", "storage-migrated", "storage-recovered", "storage-admin-failed");
    public static final Set<String> REQUIRED = Set.of("prefix", "help", "version", "status", "profile",
            "language-set", "language-usage", "unknown-player", "players-only", "no-permission",
            "unavailable", "error", "usage", "tier-neutral", "state-ready", "state-starting", "integration-active", "integration-inactive");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-z0-9_]*)}");
    private final Map<PlayerLanguage, Map<String, String>> catalogs;
    private final PlayerLanguage fallback;
    private final MiniMessage mini = MiniMessage.builder().strict(true).build();

    public Messages(Path directory, PlayerLanguage fallback) throws IOException {
        this(directory, fallback, false);
    }
    public static Messages bundled(PlayerLanguage language) throws IOException { return new Messages(null, language, true); }
    private Messages(Path directory, PlayerLanguage fallback, boolean bundled) throws IOException {
        this.fallback = fallback;
        Map<PlayerLanguage, Map<String, String>> loaded = new EnumMap<>(PlayerLanguage.class);
        for (PlayerLanguage language : PlayerLanguage.values()) {
            Map<String, Object> shipped = YamlFiles.resource("/lang/" + language.name() + ".yml");
            Map<String, Object> yaml = new HashMap<>(bundled ? shipped : YamlFiles.read(directory.resolve(language.name() + ".yml")));
            // Additive fallback for new storage keys; never overwrite administrator customizations.
            for (String key : STORAGE_KEYS) yaml.putIfAbsent(key, shipped.get(key));
            YamlFiles.version(yaml);
            Map<String, String> values = new HashMap<>();
            for (String key : yaml.keySet()) {
                if (key.equals("config-version")) continue;
                String text = YamlFiles.text(yaml, key);
                mini.deserialize(translate(text), resolvers(text, Map.of()));
                values.put(key, text);
            }
            loaded.put(language, Map.copyOf(values));
        }
        for (String key : REQUIRED) {
            if (!loaded.get(fallback).containsKey(key)) throw new IllegalArgumentException("lang/" + fallback + ": missing " + key);
        }
        for (String key : loaded.get(fallback).keySet()) {
            Set<String> expected = placeholders(loaded.get(fallback).get(key));
            for (Map<String, String> catalog : loaded.values()) {
                if (catalog.containsKey(key) && !expected.equals(placeholders(catalog.get(key)))) {
                    throw new IllegalArgumentException("Translation placeholders differ: " + key);
                }
            }
        }
        if (!placeholders(loaded.get(fallback).get("prefix")).isEmpty()) throw new IllegalArgumentException("prefix must not contain placeholders");
        catalogs = Map.copyOf(loaded);
    }

    public Component render(PlayerLanguage language, String key, Map<String, String> values) {
        String text = template(language, key);
        if (!values.keySet().containsAll(placeholders(text))) throw new IllegalArgumentException("Missing placeholders for " + key);
        Component content = mini.deserialize(translate(text), resolvers(text, values));
        return key.equals("prefix") ? content : mini.deserialize(template(language, "prefix")).append(content);
    }

    public Component tier(PlayerLanguage language) { return mini.deserialize(template(language, "tier-neutral")); }
    public String template(PlayerLanguage language, String key) {
        String value = catalogs.getOrDefault(language, catalogs.get(fallback)).get(key);
        if (value == null) value = catalogs.get(fallback).get(key);
        if (value == null) throw new IllegalArgumentException("Unknown translation: " + key);
        return value;
    }
    public String number(PlayerLanguage language, long value) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag(language.name().replace('_', '-'))).format(value);
    }
    public static Set<String> placeholders(String template) {
        Set<String> result = new HashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) result.add(matcher.group(1));
        return Set.copyOf(result);
    }
    private static String translate(String text) { return PLACEHOLDER.matcher(text).replaceAll("<arg_$1>"); }
    private static TagResolver resolvers(String text, Map<String, String> values) {
        TagResolver.Builder builder = TagResolver.builder();
        for (String key : placeholders(text)) {
            builder.resolver(Placeholder.component("arg_" + key, Component.text(values.getOrDefault(key, ""))));
        }
        return builder.build();
    }
}
