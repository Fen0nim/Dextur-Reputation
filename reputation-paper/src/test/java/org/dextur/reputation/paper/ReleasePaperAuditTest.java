package org.dextur.reputation.paper;

import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReleasePaperAuditTest {
    @TempDir Path directory;
    @Test void allDeclaredSensitivePermissionsHaveOperatorDefaultAndNoWildcardGrants()throws Exception {
        var descriptor=YamlFiles.resource("/plugin.yml");assertEquals("1.0.0",descriptor.get("version"));assertEquals("26.2",descriptor.get("api-version"));assertFalse(descriptor.containsKey("depend"));
        var permissions=VotingConfiguration.mapping(descriptor.get("permissions"));assertEquals(Set.of("dexturreputation","rep","repadmin","like","dislike"),VotingConfiguration.mapping(descriptor.get("commands")).keySet());
        for(var entry:permissions.entrySet()){assertTrue(entry.getKey().startsWith("dexturreputation."));assertFalse(entry.getKey().contains("*"));var definition=VotingConfiguration.mapping(entry.getValue());assertFalse(definition.containsKey("children"));if(entry.getKey().startsWith("dexturreputation.admin.")||entry.getKey().equals("dexturreputation.recovery.admin")||entry.getKey().equals("dexturreputation.status"))assertEquals("op",definition.get("default"),entry.getKey());else assertEquals(true,definition.get("default"),entry.getKey());}
    }
    @ParameterizedTest @ValueSource(strings={"1-1-1-1-1","not-a-uuid","00000000-0000-0000-0000-00000000000g","00000000-0000-0000-0000-000000000000?x","../../escape"})
    void malformedUuidNeverNormalizesToAnotherIdentity(String text){assertThrows(IllegalArgumentException.class,()->CommandInput.uuid(text));assertThrows(IllegalArgumentException.class,()->InvestigationCommands.id(text));}
    @Test void boundedParserPreservesValidUnicodeAndMaximumLengthComments(){assertTrue(CommandInput.valid(new String[]{"target","helpful","Ж😀 <click:run_command:'/op bad'>"}));var words=new ArrayList<>(List.of("target","helpful"));for(int n=0;n<1000;n++)words.add("x");assertTrue(CommandInput.valid(words.toArray(String[]::new)));assertFalse(CommandInput.valid(new String[]{"x".repeat(9000)}));assertFalse(CommandInput.valid(new String[]{"reason\nforged-log"}));assertFalse(CommandInput.valid(new String[]{"nul\0value"}));assertEquals(UUID.fromString("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),CommandInput.uuid("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"));}
    @Test void excessivelyLongCommandsNeverReachStorageOrKnownPlayerLookup()throws Exception {
        var plugin=mock(DexturReputationPlugin.class);var profiles=mock(ReputationApplication.class);var settings=mock(Settings.class);when(settings.defaultLanguage()).thenReturn(PlayerLanguage.ru_RU);var sender=mock(CommandSender.class);var messages=Messages.bundled(PlayerLanguage.ru_RU);var voting=mock(org.dextur.reputation.api.VotingService.class);var policy=mock(PolicyApplication.class);var investigation=mock(InvestigationApplication.class);var previous=mock(PolicyCommands.class);var command=mock(Command.class);String[] args={"inspect","x".repeat(9000)};
        assertTrue(new ReputationCommands(plugin,profiles,settings,messages).onCommand(sender,command,"rep",args));
        assertTrue(new VotingCommands(plugin,profiles,voting,mock(VotingConfiguration.class),messages,PlayerLanguage.ru_RU).onCommand(sender,command,"like",args));
        assertTrue(new PolicyCommands(plugin,profiles,policy,settings,messages).onCommand(sender,command,"repadmin",args));
        assertTrue(new InvestigationCommands(plugin,profiles,investigation,settings,messages,previous).onCommand(sender,command,"repadmin",args));
        verifyNoInteractions(profiles,voting,policy,investigation);verify(plugin,never()).getServer();
    }
    @Test void explicitAdminHelpListsNewCommandsWithoutQueryingStorage()throws Exception {
        var plugin=mock(DexturReputationPlugin.class);var app=mock(InvestigationApplication.class);var previous=mock(PolicyCommands.class);var sender=mock(CommandSender.class);when(sender.hasPermission("dexturreputation.admin.inspect")).thenReturn(true);
        var commands=new InvestigationCommands(plugin,mock(ReputationApplication.class),app,mock(Settings.class),Messages.bundled(PlayerLanguage.ru_RU),previous);commands.onCommand(sender,mock(Command.class),"repadmin",new String[]{"help"});verify(previous).send(sender,"admin7-help",Map.of());verifyNoInteractions(app);
    }
    @Test void caseInsensitivePlayerCompletionAndDeniedAdministrativeCompletion()throws Exception {
        var plugin=mock(DexturReputationPlugin.class);var server=mock(Server.class);when(plugin.getServer()).thenReturn(server);var player=mock(Player.class);when(player.getName()).thenReturn("MixedCaseName");doReturn(List.of(player)).when(server).getOnlinePlayers();var sender=mock(CommandSender.class);var commands=new PolicyCommands(plugin,mock(ReputationApplication.class),mock(PolicyApplication.class),mock(Settings.class),Messages.bundled(PlayerLanguage.ru_RU));
        assertTrue(commands.onTabComplete(sender,mock(Command.class),"repadmin",new String[]{"set","mi"}).isEmpty());when(sender.hasPermission("dexturreputation.admin.set")).thenReturn(true);assertEquals(List.of("MixedCaseName"),commands.onTabComplete(sender,mock(Command.class),"repadmin",new String[]{"set","mi"}));
    }
    @Test void investigationCompletionPreservesOldAdaptersAndUsesActionPermissions()throws Exception {
        var previous=mock(PolicyCommands.class);var sender=mock(CommandSender.class);var command=mock(Command.class);var plugin=mock(DexturReputationPlugin.class);var commands=new InvestigationCommands(plugin,mock(ReputationApplication.class),mock(InvestigationApplication.class),mock(Settings.class),Messages.bundled(PlayerLanguage.ru_RU),previous);
        String[] legacy={"set","mi"};when(previous.onTabComplete(sender,command,"repadmin",legacy)).thenReturn(List.of("MixedCaseName"));assertEquals(List.of("MixedCaseName"),commands.onTabComplete(sender,command,"repadmin",legacy));
        when(previous.onTabComplete(any(),any(),any(),any())).thenReturn(List.of());when(sender.hasPermission("dexturreputation.admin.flags.inspect")).thenReturn(true);assertEquals(List.of("flags"),commands.onTabComplete(sender,command,"repadmin",new String[]{"flag"}));
        when(sender.hasPermission("dexturreputation.admin.flags.resolve")).thenReturn(true);assertEquals(List.of("flag","flags"),commands.onTabComplete(sender,command,"repadmin",new String[]{"flag"}));
        when(sender.hasPermission("dexturreputation.admin.recalculate.reputation")).thenReturn(true);assertEquals(List.of("recalculate"),commands.onTabComplete(sender,command,"repadmin",new String[]{"recal"}));assertEquals(List.of("reputation"),commands.onTabComplete(sender,command,"repadmin",new String[]{"recalculate",""}));
        when(sender.hasPermission("dexturreputation.admin.pending.approve")).thenReturn(true);assertEquals(List.of("approve"),commands.onTabComplete(sender,command,"repadmin",new String[]{"pending",""}));
    }
    @ParameterizedTest @EnumSource(PlayerLanguage.class)
    void jsonThresholdWarningAndCapacityRateLimitAreLocalized(PlayerLanguage language)throws Exception {
        var plugin=mock(DexturReputationPlugin.class,CALLS_REAL_METHODS);var server=mock(Server.class);doReturn(server).when(plugin).getServer();doReturn(List.of()).when(server).getOnlinePlayers();
        var logger=mock(net.kyori.adventure.text.logger.slf4j.ComponentLogger.class);doReturn(logger).when(plugin).getComponentLogger();
        var settings=new Settings("global",language,100,60,2,256,15,false,null,new Settings.Storage(StorageType.JSON,"storage/json",4096,"storage/reputation.sqlite",5000));
        var messages=Messages.bundled(language);var protection=mock(org.dextur.reputation.domain.protection.ProtectionRules.class);when(protection.trust()).thenReturn(mock(org.dextur.reputation.domain.protection.ProtectionRules.Trust.class));when(protection.jsonWarningFraction()).thenReturn(750000L);
        var rules=mock(org.dextur.reputation.domain.investigation.InvestigationData.Rules.class);when(rules.jsonWarningPercent()).thenReturn(75);
        var administration=mock(ProtectionAdministration.class);when(administration.recalculateDue()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));when(administration.storageBytes()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(3071L),java.util.concurrent.CompletableFuture.completedFuture(3072L));
        field(plugin,"ready",true);field(plugin,"maintenance",new java.util.concurrent.atomic.AtomicBoolean());field(plugin,"capacityWarning",new java.util.concurrent.atomic.AtomicLong());field(plugin,"settings",settings);field(plugin,"messages",messages);field(plugin,"protectionRules",protection);field(plugin,"administrationRules",rules);field(plugin,"protectionAdministration",administration);
        var maintenance=DexturReputationPlugin.class.getDeclaredMethod("maintenance");maintenance.setAccessible(true);maintenance.invoke(plugin);verifyNoInteractions(logger);maintenance.invoke(plugin);
        var constructor=org.dextur.reputation.storage.JsonStorage.LimitException.class.getDeclaredConstructor(int.class);constructor.setAccessible(true);var failure=constructor.newInstance(4096);plugin.logFailure("profile",failure);plugin.logFailure("profile",failure);
        var output=org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);verify(logger,times(2)).warn(output.capture());
        assertEquals(messages.render(language,"protection-storage-growth",Map.of("bytes","3072","limit","4096")),output.getAllValues().getFirst());assertEquals(messages.render(language,"storage-json-limit",Map.of("limit","4096")),output.getAllValues().getLast());
    }
    private static void field(Object object,String name,Object value)throws Exception{var field=DexturReputationPlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    @ParameterizedTest @ValueSource(strings={"gui.yml","administration.yml"})
    void guiLayoutsRejectVersionsMalformedYamlAndInvalidMaterials(String name)throws Exception {
        String source=new String(getClass().getResourceAsStream("/"+name).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);for(String bad:List.of(source.replace("config-version: 1","config-version: 999"),"config-version: 1\nkey: [\n",source.replace("material: BOOK","material: INVALID_MATERIAL"))){Files.writeString(directory.resolve(name),bad);if(name.equals("gui.yml"))assertThrows(Exception.class,()->GuiConfiguration.load(directory,m->true));else assertThrows(Exception.class,()->{AdministrationConfiguration.load(directory);InvestigationGui.Layout.load(directory,14);});assertEquals(bad,Files.readString(directory.resolve(name)));}
    }
}
