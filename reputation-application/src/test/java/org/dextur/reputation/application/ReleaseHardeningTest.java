package org.dextur.reputation.application;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseHardeningTest {
    @TempDir Path directory;
    static final List<String> CONFIGS=List.of("config.yml","storage.yml","integrations.yml","voting.yml","trust.yml","anti-abuse.yml","tiers.yml","display.yml","gui.yml","recovery.yml","features.yml","administration.yml","lang/ru_RU.yml","lang/en_US.yml");
    void resources()throws Exception {for(String name:CONFIGS){var p=directory.resolve(name);Files.createDirectories(p.getParent());Files.copy(getClass().getResourceAsStream("/"+name),p);}}
    void validate(String name)throws Exception {
        var messages=new Messages(directory.resolve("lang"),PlayerLanguage.ru_RU);
        switch(name){
            case "config.yml","storage.yml"->Settings.load(directory);
            case "integrations.yml"->{Settings.load(directory);TierConfiguration.load(directory);DisplayConfiguration.load(directory);}
            case "voting.yml"->VotingConfiguration.load(directory,messages);
            case "trust.yml","anti-abuse.yml"->ProtectionConfiguration.load(directory);
            case "tiers.yml"->TierConfiguration.load(directory);
            case "display.yml"->DisplayConfiguration.load(directory);
            case "recovery.yml"->RecoveryConfiguration.load(directory);
            case "features.yml"->PolicyConfiguration.load(directory);
            case "administration.yml"->AdministrationConfiguration.load(directory);
            case "gui.yml"->YamlFiles.version(YamlFiles.read(directory.resolve(name)));
            default->{ }
        }
    }
    static java.util.stream.Stream<String> configs(){return CONFIGS.stream();}
    @ParameterizedTest @MethodSource("configs") void shippedDefaultsAndUnsupportedVersions(String name)throws Exception {
        resources();validate(name);var path=directory.resolve(name);String old=Files.readString(path);
        Files.writeString(path,old.replaceFirst("config-version: [12]","config-version: 999"));
        assertThrows(Exception.class,()->validate(name));assertTrue(Files.readString(path).contains("999"));
    }
    @ParameterizedTest @MethodSource("configs") void malformedYamlFailsWithoutReplacingCustomFile(String name)throws Exception {
        resources();var file=directory.resolve(name);String broken="config-version: 1\nsecret: [\n";Files.writeString(file,broken);
        var error=assertThrows(Exception.class,()->validate(name));assertEquals(broken,Files.readString(file));
        assertFalse(String.valueOf(error.getMessage()).contains("secret: ["));
    }
    @ParameterizedTest @ValueSource(strings={"key: one\nkey: two\n","key: !!java.lang.ProcessBuilder {}\n","key: &loop [*loop]\n","key: null\n","key: {1: value}\n"})
    void yamlRejectsAmbiguityObjectsAliasesAndNulls(String text)throws Exception {var p=directory.resolve("unsafe.yml");Files.writeString(p,text);assertThrows(Exception.class,()->YamlFiles.read(p));assertEquals(text,Files.readString(p));}
    @Test void newFieldsFallbackPreservesCustomizationsAndRejectsInvalidEnumsNumbersAndMarkup()throws Exception {
        resources();Files.writeString(directory.resolve("features.yml"),"config-version: 1\nhealth: {enabled: true}\n");assertTrue(PolicyConfiguration.load(directory).healthEnabled());assertEquals("config-version: 1\nhealth: {enabled: true}\n",Files.readString(directory.resolve("features.yml")));
        for(String value:List.of("scope: ../escape","default-language: xx_XX","database-workers: 999","cache-maximum-size: nope")){
            String source=new String(getClass().getResourceAsStream("/config.yml").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);String key=value.split(":")[0];Files.writeString(directory.resolve("config.yml"),source.replaceAll("(?m)^"+key+":.*$",value));assertThrows(Exception.class,()->Settings.load(directory));}
        var locale=directory.resolve("lang/ru_RU.yml");Files.writeString(locale,Files.readString(locale).replace("prefix:","prefix: '<red>broken'\nunused-prefix:"));assertThrows(Exception.class,()->new Messages(directory.resolve("lang"),PlayerLanguage.ru_RU));
    }
    @Test void everyLocaleKeyHasMatchingArgumentsAndLiteralUserInput()throws Exception {
        resources();var ru=YamlFiles.resource("/lang/ru_RU.yml");var en=YamlFiles.resource("/lang/en_US.yml");assertEquals(ru.keySet(),en.keySet());var messages=new Messages(directory.resolve("lang"),PlayerLanguage.ru_RU);
        for(String key:ru.keySet())if(!key.equals("config-version")){
            assertEquals(Messages.placeholders((String)ru.get(key)),Messages.placeholders((String)en.get(key)),key);
            var args=new HashMap<String,String>();for(String arg:Messages.placeholders((String)ru.get(key)))args.put(arg,"Юникод 😀 <click:run_command:'/op me'>literal</click>");
            for(var language:PlayerLanguage.values()){var text=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(messages.content(language,key,args));if(!args.isEmpty())assertTrue(text.contains("<click:run_command:"),key);}
        }
        var p=directory.resolve("lang/en_US.yml");Files.writeString(p,Files.readString(p).replaceAll("(?m)^unknown-player:.*\\R?",""));assertEquals(messages.template(PlayerLanguage.ru_RU,"unknown-player"),new Messages(directory.resolve("lang"),PlayerLanguage.ru_RU).template(PlayerLanguage.en_US,"unknown-player"));
    }
    @Test void failedObserversCannotTurnCommittedProfileIntoFailureOrSkipOtherListeners()throws Exception {
        var settings=new Settings("global",PlayerLanguage.ru_RU,10,60,2,8,5,false,null);var called=new AtomicInteger();
        try(var app=new ReputationApplication(new ReputationApplicationTest.Repository(),settings)){
            app.observeProfiles((p,c)->{throw new AssertionError("private-secret-must-not-be-logged");});
            app.addProfileObserver((p,c)->{throw new IllegalStateException("listener failure");});app.addProfileObserver((p,c)->called.incrementAndGet());
            var profile=app.getProfile(UUID.randomUUID()).toCompletableFuture().get(5,TimeUnit.SECONDS);assertEquals(0,profile.visibleScore());assertEquals(1,called.get());assertEquals(profile,app.cachedProfile(profile.playerId()).orElseThrow());
        }
    }
    @Test void boundedQueueShutdownAndRepeatedLifecycleNeverLeakFutures()throws Exception {
        for(int cycle=0;cycle<5;cycle++){
            var app=new ReputationApplication(new ReputationApplicationTest.Repository(),new Settings("global",PlayerLanguage.ru_RU,10,60,2,8,5,false,null));var release=new CountDownLatch(1);var started=new CountDownLatch(2);var futures=new ArrayList<CompletableFuture<Integer>>();
            for(int i=0;i<2;i++)futures.add(app.submit(()->{started.countDown();release.await();return 1;}));assertTrue(started.await(5,TimeUnit.SECONDS));
            for(int i=0;i<1000;i++)futures.add(app.submit(()->1));assertEquals(8,app.queuedOperations());assertEquals(992,futures.stream().filter(CompletableFuture::isCompletedExceptionally).count());release.countDown();app.close();assertTrue(app.terminated());assertTrue(futures.stream().allMatch(CompletableFuture::isDone));assertTrue(app.submit(()->1).isCompletedExceptionally());
        }
    }
}
