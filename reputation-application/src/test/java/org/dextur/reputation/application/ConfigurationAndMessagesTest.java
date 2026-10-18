package org.dextur.reputation.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationAndMessagesTest {
    @TempDir Path directory;
    @BeforeEach void resources() throws Exception {
        for (String file : new String[]{"config.yml", "storage.yml", "integrations.yml", "lang/ru_RU.yml", "lang/en_US.yml"}) {
            Path destination = directory.resolve(file);
            Files.createDirectories(destination.getParent());
            try (var in = getClass().getResourceAsStream("/" + file)) { Files.copy(in, destination); }
        }
    }
    @Test void validDefaultsAndNoOverwrite() throws Exception {
        Settings settings = Settings.load(directory);
        assertEquals("global", settings.scope());
        assertEquals(PlayerLanguage.ru_RU, settings.defaultLanguage());
        assertEquals("verify-full", settings.database().sslMode());
        String before = Files.readString(directory.resolve("config.yml"));
        Settings.load(directory);
        assertEquals(before, Files.readString(directory.resolve("config.yml")));
    }
    @Test void rejectsInvalidBoundsAndVersion() throws Exception {
        Path file = directory.resolve("config.yml");
        String original = Files.readString(file);
        for (String changed : new String[]{original.replace("database-workers: 2", "database-workers: 0"),
                original.replace("cache-maximum-size: 10000", "cache-maximum-size: -1"),
                original.replace("config-version: 1", "config-version: 2"),
                original.replace("scope: global", "scope: bad/scope"),
                original.replace("database-workers: 2", "database-workers: 3")}) {
            Files.writeString(file, changed);
            assertThrows(IllegalArgumentException.class, () -> Settings.load(directory));
        }
    }
    @Test void rejectsJdbcOptionInjectionAndDuplicateYaml() throws Exception {
        Path file = directory.resolve("storage.yml");
        Files.writeString(file, Files.readString(file).replace("host: localhost", "host: localhost?sslMode=disable"));
        assertThrows(IllegalArgumentException.class, () -> Settings.load(directory));
        Files.writeString(directory.resolve("config.yml"), "config-version: 1\nconfig-version: 1\n");
        assertThrows(RuntimeException.class, () -> Settings.load(directory));
    }
    @Test void shippedLocalesAreCompleteConsistentAndStrictlyParseable() throws Exception {
        Messages messages = new Messages(directory.resolve("lang"), PlayerLanguage.ru_RU);
        for (String key : Messages.REQUIRED) {
            for (PlayerLanguage language : PlayerLanguage.values()) {
                assertTrue(YamlFiles.read(directory.resolve("lang/" + language + ".yml")).containsKey(key), key);
                assertFalse(messages.template(language, key).isBlank());
                Set<String> fields = Messages.placeholders(messages.template(language, key));
                assertEquals(Messages.placeholders(messages.template(PlayerLanguage.ru_RU, key)), fields);
                Map<String, String> values = fields.stream().collect(java.util.stream.Collectors.toMap(s -> s, s -> "test"));
                assertNotNull(messages.render(language, key, values));
            }
        }
    }
    @Test void missingTranslationFallsBackAndMissingDefaultFails() throws Exception {
        Path en = directory.resolve("lang/en_US.yml");
        Files.writeString(en, Files.readString(en).replace("tier-neutral: 'Neutral'", ""));
        Messages messages = new Messages(directory.resolve("lang"), PlayerLanguage.ru_RU);
        assertEquals("Нейтральный", messages.template(PlayerLanguage.en_US, "tier-neutral"));
        Files.writeString(directory.resolve("lang/ru_RU.yml"), "config-version: 1\n");
        assertThrows(IllegalArgumentException.class, () -> new Messages(directory.resolve("lang"), PlayerLanguage.ru_RU));
    }
    @Test void malformedMarkupAndMismatchedFieldsAreRejected() throws Exception {
        Path en = directory.resolve("lang/en_US.yml");
        String original = Files.readString(en);
        Files.writeString(en, original.replace("{player}", "{other}"));
        assertThrows(IllegalArgumentException.class, () -> new Messages(directory.resolve("lang"), PlayerLanguage.ru_RU));
        Files.writeString(en, original.replace("'Neutral'", "'<red>Neutral'"));
        assertThrows(RuntimeException.class, () -> new Messages(directory.resolve("lang"), PlayerLanguage.ru_RU));
    }
    @Test void untrustedTextCannotInjectFormattingClickOrHover() throws Exception {
        Messages messages = new Messages(directory.resolve("lang"), PlayerLanguage.ru_RU);
        String attack = "<red><click:run_command:'/op attacker'><hover:show_text:'bad'>X</hover></click></red>{reputation}";
        Component result = messages.render(PlayerLanguage.en_US, "profile", Map.of("player", attack, "reputation", "0", "tier", "Neutral"));
        assertTrue(PlainTextComponentSerializer.plainText().serialize(result).contains(attack));
        assertNoEvents(result);
    }
    private void assertNoEvents(Component component) {
        assertNull(component.clickEvent()); assertNull(component.hoverEvent());
        component.children().forEach(this::assertNoEvents);
    }
    @Test void cacheIsBoundedExpiresAndDoesNotExtendLifetimeOnRead() {
        AtomicLong time = new AtomicLong();
        BoundedCache<Integer, String> cache = new BoundedCache<>(2, Duration.ofNanos(10), time::get);
        cache.put(1, "one"); cache.put(2, "two"); cache.get(1); cache.put(3, "three");
        assertTrue(cache.get(2).isEmpty());
        time.set(9); assertEquals("one", cache.get(1).orElseThrow());
        time.set(10); assertTrue(cache.get(1).isEmpty());
        assertTrue(cache.get(3).isEmpty());
    }
}
