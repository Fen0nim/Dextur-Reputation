package org.dextur.reputation.paper;

import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.api.VoteType;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class VoteReasonInputTest {
    @TempDir Path directory;
    Messages messages;VotingConfiguration config;VoteReasonInput input;
    @BeforeEach void setup()throws Exception {
        Files.copy(getClass().getResourceAsStream("/voting.yml"),directory.resolve("voting.yml"));
        messages=Messages.bundled(PlayerLanguage.ru_RU);config=VotingConfiguration.load(directory,messages);input=new VoteReasonInput(config,messages);
    }
    @ParameterizedTest @EnumSource(PlayerLanguage.class) void allTwentyLocalizedNamesRoundTripToStableIds(PlayerLanguage language){
        assertEquals(20,config.rules().reasons().size());
        for(var reason:config.rules().reasons().values()){
            var type=reason.types().iterator().next();String label=config.reasonLabel(messages,language,reason.id());assertNotEquals(label,reason.id());
            assertEquals(new VoteReasonInput.Parsed(reason.id(),""),input.parse(language,type,label));
            assertEquals(new VoteReasonInput.Parsed(reason.id(),"comment"),input.parse(language,type,label+" comment"));
            assertEquals(reason.id(),input.parse(language,type,reason.id()).id());
            assertNull(input.parse(language,type==VoteType.POSITIVE?VoteType.NEGATIVE:VoteType.POSITIVE,label));
            assertNull(input.parse(language,type==VoteType.POSITIVE?VoteType.NEGATIVE:VoteType.POSITIVE,reason.id()));
        }
    }
    @ParameterizedTest @EnumSource(PlayerLanguage.class) void suggestionsAreLocalizedAndSignSpecific(PlayerLanguage language){
        for(var type:VoteType.values()){
            var suggestions=input.suggest(language,type,new String[]{"Target",""});assertEquals(10,suggestions.size());
            for(String label:suggestions){assertNotNull(input.parse(language,type,label));if(language==PlayerLanguage.ru_RU)assertTrue(label.matches(".*[А-Яа-яЁё].*"));}
        }
    }
    @Test void multiwordAndQuotedInputRetainCommentAndCompleteTheLastArgument(){
        assertEquals(new VoteReasonInput.Parsed("helpful","спасибо за ресурсы"),input.parse(PlayerLanguage.ru_RU,VoteType.POSITIVE,"Помощь игрокам спасибо за ресурсы"));
        assertEquals(new VoteReasonInput.Parsed("helpful","comment"),input.parse(PlayerLanguage.en_US,VoteType.POSITIVE,"\"Helping players\" comment"));
        assertEquals(List.of("игрокам"),input.suggest(PlayerLanguage.ru_RU,VoteType.POSITIVE,new String[]{"Target","Помощь","иг"}));
        assertEquals(List.of("players\""),input.suggest(PlayerLanguage.en_US,VoteType.POSITIVE,new String[]{"Target","\"Helping","p"}));
        assertNull(input.parse(PlayerLanguage.ru_RU,VoteType.POSITIVE,"friendly_extra"));
        assertNull(input.parse(PlayerLanguage.ru_RU,VoteType.POSITIVE,"Грубость"));
    }
    @Test void disablingReasonRejectsNewVotesButPreservesHistoricalTranslation()throws Exception {
        var file=directory.resolve("voting.yml");Files.writeString(file,Files.readString(file).replace("friendly:\n    enabled: true","friendly:\n    enabled: false"));
        var disabled=VotingConfiguration.load(directory,messages);var parser=new VoteReasonInput(disabled,messages);
        assertNull(parser.parse(PlayerLanguage.ru_RU,VoteType.POSITIVE,"friendly"));assertNull(parser.parse(PlayerLanguage.ru_RU,VoteType.POSITIVE,"Дружелюбие"));
        assertFalse(parser.suggest(PlayerLanguage.ru_RU,VoteType.POSITIVE,new String[]{"Target",""}).contains("Дружелюбие"));
        assertEquals("Дружелюбие",disabled.reasonLabel(messages,PlayerLanguage.ru_RU,"friendly"));
    }
    @Test void legacyConfigGainsMissingDefaultsWithoutRewritingOrReplacingCustomNames()throws Exception {
        var file=directory.resolve("voting.yml");String legacy=Files.readString(file).split("\nreasons:")[0]+"\nreasons:\n  friendly:\n    message: reason-friendly\n    types: [POSITIVE]\n";Files.writeString(file,legacy);
        Files.createDirectory(directory.resolve("lang"));
        for(var language:PlayerLanguage.values()){
            String text=new String(getClass().getResourceAsStream("/lang/"+language+".yml").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("lang/"+language+".yml"),text.replace("Дружелюбие","Доброе общение").replace("Friendliness","Kindness"));
        }
        var customized=new Messages(directory.resolve("lang"),PlayerLanguage.ru_RU);var updated=VotingConfiguration.load(directory,customized);var parser=new VoteReasonInput(updated,customized);
        assertEquals(20,updated.rules().reasons().size());assertEquals(legacy,Files.readString(file));
        assertEquals("friendly",parser.parse(PlayerLanguage.ru_RU,VoteType.POSITIVE,"Доброе общение").id());assertEquals("friendly",parser.parse(PlayerLanguage.en_US,VoteType.POSITIVE,"Kindness").id());
    }
    @Test void ambiguousLabelsAreRejectedBeforeEnabling()throws Exception {
        var file=directory.resolve("voting.yml");Files.writeString(file,Files.readString(file).replace("message: reason-helpful","message: reason-friendly"));
        assertThrows(IllegalArgumentException.class,()->VotingConfiguration.load(directory,messages));
    }
}
