package org.dextur.reputation.application;

import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtectionConfigurationTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    @BeforeEach void resources() throws Exception {
        for(String name:List.of("trust.yml","anti-abuse.yml","lang/ru_RU.yml","lang/en_US.yml")) {
            var file=directory.resolve(name);Files.createDirectories(file.getParent());try(var in=getClass().getResourceAsStream("/"+name)) { Files.copy(in,file); }
        }
    }
    @Test void defaultsAndMissingFieldsPreserveCustomizationsWithoutRewriting() throws Exception {
        var rules=ProtectionConfiguration.load(directory);assertEquals(500000,rules.trust().initial());assertEquals(1500000,rules.weight().max());assertTrue(rules.burst().pending());
        Files.writeString(directory.resolve("trust.yml"),"config-version: 1\ntrust:\n  score:\n    initial: 0.61\n");
        Files.writeString(directory.resolve("anti-abuse.yml"),"config-version: 1\nvoting-weight:\n  base: {like: 0.25}\n");
        String before=Files.readString(directory.resolve("trust.yml"));var merged=ProtectionConfiguration.load(directory);
        assertEquals(610000,merged.trust().initial());assertEquals(250000,merged.weight().positive());assertEquals(1000000,merged.weight().negative());assertEquals(before,Files.readString(directory.resolve("trust.yml")));
    }
    @Test void rejectsInvalidBoundsPrecisionBudgetsAndPermanentPunishment() throws Exception {
        Path file=directory.resolve("anti-abuse.yml");String original=Files.readString(file);
        for(String bad:List.of(original.replace("maximum: 1.50","maximum: -1"),original.replace("like: 1.0","like: 0.0000001"),original.replace("maximum-events: 2048","maximum-events: 16").replace("suspicious-count: 10","suspicious-count: 20"),original.replace("maximum-nodes: 128","maximum-nodes: 3"),original.replace("second: 0.70","second: 1.1"),original.replace("automatic-permanent-punishment: false","automatic-permanent-punishment: true"))) {
            Files.writeString(file,bad);assertThrows(IllegalArgumentException.class,()->ProtectionConfiguration.load(directory));
        }
        Files.writeString(file,original);Files.writeString(directory.resolve("trust.yml"),"config-version: 1\ntrust: {model-version: 2}\n");assertThrows(IllegalArgumentException.class,()->ProtectionConfiguration.load(directory));
    }
    @Test void completePrivateAndPublicMessagesAreSafeInBothLanguages() throws Exception {
        var messages=new Messages(directory.resolve("lang"),PlayerLanguage.ru_RU);var ru=YamlFiles.read(directory.resolve("lang/ru_RU.yml"));var en=YamlFiles.read(directory.resolve("lang/en_US.yml"));assertEquals(ru.keySet(),en.keySet());
        for(String key:ru.keySet()) if(key.startsWith("protection-")||key.equals("vote-error-temporarily-restricted")||key.equals("vote-error-processing-busy")) for(var lang:PlayerLanguage.values()) {
            var text=messages.template(lang,key);assertEquals(Messages.placeholders(messages.template(PlayerLanguage.ru_RU,key)),Messages.placeholders(text));
            var input="<click:run_command:'/op x'><red>payload</red></click>";var fields=new HashMap<String,String>();for(String field:Messages.placeholders(text)) fields.put(field,input);
            var component=messages.render(lang,key,fields);assertNull(component.clickEvent());assertTrue(component.children().stream().allMatch(c->c.clickEvent()==null));
        }
    }
    @Test void oldLocalesReceiveNewMessagesAndPublicApiHasNoTrustFields() throws Exception {
        for(var lang:PlayerLanguage.values()) { Path f=directory.resolve("lang/"+lang+".yml");Files.writeString(f,Files.readString(f).lines().filter(s->!s.startsWith("protection-")&&!s.startsWith("vote-error-temporarily")&&!s.startsWith("vote-error-processing")).collect(java.util.stream.Collectors.joining("\n"))); }
        var messages=new Messages(directory.resolve("lang"),PlayerLanguage.ru_RU);assertFalse(messages.plain(PlayerLanguage.en_US,"protection-status-pending").isBlank());
        for(Class<?> api:List.of(org.dextur.reputation.api.ReputationSnapshot.class,org.dextur.reputation.api.ReputationView.class,org.dextur.reputation.api.VoteProcessingOutcome.class))
            for(var field:api.getRecordComponents()) assertFalse(field.getName().toLowerCase(Locale.ROOT).contains("trust"));
    }
}
