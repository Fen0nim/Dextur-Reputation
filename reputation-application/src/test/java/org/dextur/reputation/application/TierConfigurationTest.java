package org.dextur.reputation.application;

import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TierConfigurationTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    @ParameterizedTest @ValueSource(longs={25,100,250,500,750,1000,-25,-100,-250,-500})
    void everyThresholdIsInclusiveAndAdjacentValuesAreCorrect(long threshold) throws Exception {
        var rules=TierConfiguration.load(directory);var tier=rules.definitions().stream().filter(t->t.threshold()==threshold).findFirst().orElseThrow();
        assertEquals(tier.id(),rules.select(threshold,"NEUTRAL","").id());assertEquals(tier.id(),rules.select(threshold+Long.signum(threshold),"NEUTRAL","").id());
        assertNotEquals(tier.id(),rules.select(threshold-Long.signum(threshold),"NEUTRAL","").id());
    }
    @ParameterizedTest @ValueSource(longs={-24,-1,0,1,24})
    void neutralBoundaries(long score) throws Exception {assertEquals("NEUTRAL",TierConfiguration.load(directory).select(score,"NEUTRAL","").id());}
    @Test void mirroredHysteresisAndLargeJumps() throws Exception {
        var rules=TierConfiguration.load(directory);String hash=rules.hash();
        assertEquals("RESPECTED",rules.select(99,"RESPECTED",hash).id());assertEquals("RESPECTED",rules.select(90,"RESPECTED",hash).id());assertEquals("RECOGNIZED",rules.select(89,"RESPECTED",hash).id());
        assertEquals("OFFENDER",rules.select(-90,"OFFENDER",hash).id());assertEquals("SUSPICIOUS",rules.select(-89,"OFFENDER",hash).id());
        assertEquals("SUPREME_AUTHORITY",rules.select(Long.MAX_VALUE,"RESPECTED",hash).id());assertEquals("SABOTEUR",rules.select(Long.MIN_VALUE,"SUPREME_AUTHORITY",hash).id());
        assertEquals("SUPREME_AUTHORITY",rules.select(1000,"SABOTEUR",hash).id());assertEquals("NEUTRAL",rules.select(0,"OFFENDER",hash).id());
        assertEquals("RECOGNIZED",rules.select(99,"RESPECTED","0".repeat(64)).id());
    }
    @Test void disabledTiersAndConfigurationNeverRewriteAdministratorFiles() throws Exception {
        var text=new String(getClass().getResourceAsStream("/tiers.yml").readAllBytes());
        Files.writeString(directory.resolve("tiers.yml"),text.replace("id: RESPECTED\n    enabled: true","id: RESPECTED\n    enabled: false"));
        String before=Files.readString(directory.resolve("tiers.yml"));var rules=TierConfiguration.load(directory);
        assertEquals("RECOGNIZED",rules.select(100,"RESPECTED",rules.hash()).id());assertEquals(before,Files.readString(directory.resolve("tiers.yml")));
        assertEquals(rules.hash(),TierConfiguration.load(directory).hash());
    }
    @Test void invalidDefinitionsAndCrossFileMappingsAreRejected() throws Exception {
        String text=new String(getClass().getResourceAsStream("/tiers.yml").readAllBytes());
        for(String bad:List.of(text.replace("id: RESPECTED","id: RECOGNIZED"),text.replace("threshold: 100\n","threshold: 25\n"),text.replace("priority: 1\n","priority: 0\n"),text.replace("hysteresis: 10\n","hysteresis: -1\n"),text.replace("minimum: -24","minimum: -23"),text.replace("color: '#55ff55'","color: 'javascript:foo'"),text.replace("luckperms-group: ''","luckperms-group: 'admin'"),text.replace("suffix: ''","suffix: '<red>unclosed'"))) {
            Files.writeString(directory.resolve("tiers.yml"),bad);assertThrows(Exception.class,()->TierConfiguration.load(directory),bad);
        }
        Files.writeString(directory.resolve("tiers.yml"),text);
        Files.writeString(directory.resolve("integrations.yml"),"config-version: 1\nluckperms:\n  tiers: {UNKNOWN: reputation_safe}\n");assertThrows(Exception.class,()->TierConfiguration.load(directory));
    }
    private ReputationTierSnapshot tier(long score) {return new ReputationTierSnapshot(UUID.randomUUID(),"global","RECOGNIZED",score,1,1,Map.of("ru_RU","Признанный","en_US","Recognized"),"#55ff55","<green>[{tier}] </green>","",100L,0);}
    @ParameterizedTest @ValueSource(longs={123,-123,0,Long.MIN_VALUE})
    void formatterHandlesSignsAndLongMinimumWithoutOverflow(long score) throws Exception {
        var formatter=new DisplayFormatter(DisplayConfiguration.load(directory));String text=DisplayFormatter.plain(formatter.score(tier(score),PlayerLanguage.en_US));
        assertTrue(text.startsWith(score>0?"(+":score<0?"(-":"(0"));assertTrue(text.endsWith(")"));assertFalse(text.contains("--"));
    }
    @Test void userTextIsLiteralAndFormattingIsLocalized() throws Exception {
        var formatter=new DisplayFormatter(DisplayConfiguration.load(directory));String player="<click:run_command:'/op x'><red>x</red></click>";
        var rendered=formatter.display("{player} {prefix}{reputation}",player,tier(1234),PlayerLanguage.ru_RU);
        assertTrue(DisplayFormatter.plain(rendered).contains(player));assertTrue(DisplayFormatter.plain(rendered).contains("Признанный"));assertNoEvents(rendered);
        assertEquals("(+1,234)",DisplayFormatter.plain(formatter.score(tier(1234),PlayerLanguage.en_US)));
        assertEquals(4,DisplayFormatter.plain(formatter.limit(rendered,4)).codePointCount(0,4));
    }
    @Test void invalidDisplayModesIntervalsFormatsAndSoundAreRejected() throws Exception {
        for(String custom:List.of("refresh: {interval-ticks: 1}","tab: {mode: UNKNOWN}","nameplate: {visibility: INVALID}","reputation: {positive-format: '{trust}'}","notifications: {sound: '../unsafe'}")) {
            Files.writeString(directory.resolve("display.yml"),"config-version: 1\n"+custom+"\n");assertThrows(Exception.class,()->DisplayConfiguration.load(directory));
        }
    }
    private static void assertNoEvents(net.kyori.adventure.text.Component component) {assertNull(component.clickEvent());assertNull(component.hoverEvent());component.children().forEach(TierConfigurationTest::assertNoEvents);}
    @Test void tierMessagesAreCompleteSafeAndAvailableToOldCatalogs() throws Exception {
        var messages=Messages.bundled(PlayerLanguage.ru_RU);assertEquals(YamlFiles.resource("/lang/ru_RU.yml").keySet(),YamlFiles.resource("/lang/en_US.yml").keySet());
        for(String key:YamlFiles.resource("/lang/ru_RU.yml").keySet()) if(key.startsWith("tier-")&&!key.equals("tier-neutral")||key.startsWith("display-"))for(var language:PlayerLanguage.values()) {
            var values=new HashMap<String,String>();for(String value:Messages.placeholders(messages.template(language,key)))values.put(value,"<click:run_command:'/op x'>x</click>");
            assertNull(messages.content(language,key,values).clickEvent());assertNoEvents(messages.content(language,key,values));
        }
    }
    @Test void measureCachedFormattingWithoutAnyStorageAccess() throws Exception {
        var formatter=new DisplayFormatter(DisplayConfiguration.load(directory));var value=tier(1234);long checksum=0;
        for(int i=0;i<1000;i++)formatter.score(value,PlayerLanguage.en_US);
        long start=System.nanoTime();for(int i=0;i<20000;i++)checksum+=DisplayFormatter.plain(formatter.score(value,i%2==0?PlayerLanguage.ru_RU:PlayerLanguage.en_US)).length();long elapsed=System.nanoTime()-start;
        assertTrue(checksum>0);System.out.println("PHASE4_FORMAT_BENCH: iterations=20000 elapsed_ns="+elapsed+" mean_ns="+(elapsed/20000));
    }

}
