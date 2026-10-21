package org.dextur.reputation.paper;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.dextur.reputation.domain.policy.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminUxTest {
    @TempDir Path directory;
    DexturReputationPlugin plugin;Server server;ReputationApplication profiles;Messages messages;Settings settings;
    Player actor,target;UUID aid=UUID.randomUUID(),tid=UUID.randomUUID();PolicyApplication policies;PolicyCommands commands;
    AtomicReference<AdministrationData.State> state=new AtomicReference<>();List<String> actorMessages,targetMessages;
    @BeforeEach void setup()throws Exception {
        plugin=mock(DexturReputationPlugin.class);server=mock(Server.class);when(plugin.getServer()).thenReturn(server);when(plugin.ready()).thenReturn(true);
        doAnswer(i->{i.<Runnable>getArgument(0).run();return null;}).when(plugin).onMain(any());
        profiles=mock(ReputationApplication.class);when(profiles.cachedLanguage(any())).thenReturn(PlayerLanguage.ru_RU);
        settings=mock(Settings.class);when(settings.scope()).thenReturn("global");when(settings.defaultLanguage()).thenReturn(PlayerLanguage.ru_RU);
        messages=Messages.bundled(PlayerLanguage.ru_RU);actor=mock(Player.class);target=mock(Player.class);
        when(actor.getUniqueId()).thenReturn(aid);when(target.getUniqueId()).thenReturn(tid);when(actor.getName()).thenReturn("Moderator");when(target.getName()).thenReturn("Target");
        when(actor.isOnline()).thenReturn(true);when(target.isOnline()).thenReturn(true);when(actor.hasPermission(anyString())).thenReturn(true);
        when(server.getPlayer(tid)).thenReturn(target);when(server.getPlayer(aid)).thenReturn(actor);when(server.getPlayerExact("Target")).thenReturn(target);
        when(server.getOfflinePlayer(tid)).thenReturn(target);when(server.getOfflinePlayer(aid)).thenReturn(actor);doReturn(List.of(target,actor)).when(server).getOnlinePlayers();
        actorMessages=collect(actor);targetMessages=collect(target);
        policies=mock(PolicyApplication.class);when(policies.configuration()).thenReturn(PolicyConfiguration.load(directory));
        when(policies.administration(tid)).thenAnswer(i->CompletableFuture.completedFuture(state.get()));
        when(policies.administer(any())).thenAnswer(i->{var request=i.<AdministrationData.Request>getArgument(0);var p=new ReputationSnapshot(request.player(),"global",0,0,0,"NEUTRAL",0,Instant.EPOCH,Instant.EPOCH);var change=AdministrationData.apply(state.get(),p,request,new PolicyRules.Limits(-100000,100000,10000,true));state.set(change.state());return CompletableFuture.completedFuture(change);});
        commands=new PolicyCommands(plugin,profiles,policies,settings,messages);
    }
    private List<String> collect(Player p){var result=new ArrayList<String>();doAnswer(i->{result.add(PlainTextComponentSerializer.plainText().serialize(i.getArgument(0)));return null;}).when(p).sendMessage(any(Component.class));return result;}
    @ParameterizedTest @EnumSource(VoteType.class)
    void voteActorAndTargetReceiveLocalizedReasonWithoutInternalKeys(VoteType type)throws Exception {
        var config=VotingConfiguration.load(directoryWithVoting(),messages);var voting=mock(VotingService.class);
        String reason=config.rules().reasons().values().stream().filter(r->r.types().contains(type)).findFirst().orElseThrow().id();
        var entry=new VoteEntry(UUID.randomUUID(),1,"global",aid,tid,VoteEntry.Action.CAST,type,reason,"",Instant.EPOCH,null,1,1,1,"0".repeat(64),0,type.sign());
        var view=new ReputationView(new ReputationSnapshot(tid,"global",type.sign(),0,type.sign(),"NEUTRAL",1,Instant.EPOCH,Instant.EPOCH),new VoteStatistics(0,0,0,1));
        when(voting.submit(any())).thenReturn(CompletableFuture.completedFuture(new VoteReceipt(entry,false,view)));
        when(voting.outcome(entry.operationId())).thenReturn(CompletableFuture.completedFuture(new VoteProcessingOutcome(entry.operationId(),VoteProcessingOutcome.Status.ACCEPTED)));
        var voteCommands=new VotingCommands(plugin,profiles,voting,config,messages,PlayerLanguage.ru_RU);
        voteCommands.cast(actor,tid,type,reason,"",()->{});voteCommands.notifyTarget(new VotingService.VoteCommitted(entry,view));
        assertEquals(1,actorMessages.size());assertEquals(1,targetMessages.size());String translated=messages.plain(PlayerLanguage.ru_RU,config.rules().reasons().get(reason).messageKey());
        assertTrue(actorMessages.getFirst().contains(translated));assertTrue(targetMessages.getFirst().contains(translated));
        assertTrue(actorMessages.getFirst().contains(type==VoteType.POSITIVE?"повысили":"понизили"));assertTrue(targetMessages.getFirst().contains("Moderator"));
        assertFalse(actorMessages.getFirst().contains("CAST"));assertFalse(targetMessages.getFirst().contains("reason-"));
    }
    Path directoryWithVoting()throws Exception{try(var in=getClass().getResourceAsStream("/voting.yml")){Files.write(directory.resolve("voting.yml"),in.readAllBytes());}return directory;}
    @Test void restrictionAndUnlockNotifyBothSidesAndDoNotExposeOperationIds(){
        commands.onCommand(actor,mock(Command.class),"repadmin",new String[]{"voting","lock","Target","review"});
        assertEquals(1,actorMessages.size());assertEquals(1,targetMessages.size());assertTrue(targetMessages.getFirst().contains("Moderator"));assertTrue(state.get().votingLocked());
        commands.onCommand(actor,mock(Command.class),"repadmin",new String[]{"voting","unlock","Target","review"});
        assertFalse(state.get().votingLocked());assertEquals(2,actorMessages.size());assertEquals(2,targetMessages.size());assertTrue(targetMessages.getLast().contains("восстановлен"));
        assertTrue(actorMessages.stream().noneMatch(s->s.contains(aid.toString())||s.contains(tid.toString())||s.contains("LOCK")));
    }
    @Test void forceOnOffAndStatusAreAuditedIdempotentAndPermissionBound(){
        commands.onCommand(actor,mock(Command.class),"repadmin",new String[]{"force","Target"});assertTrue(InitialEligibility.enabled(state.get()));assertEquals(1,targetMessages.size());
        commands.onCommand(actor,mock(Command.class),"repadmin",new String[]{"force","Target","on"});assertEquals(1,state.get().audit().size());assertEquals(1,targetMessages.size());
        commands.onCommand(actor,mock(Command.class),"repadmin",new String[]{"force","Target","status"});assertEquals(1,state.get().audit().size());
        commands.onCommand(actor,mock(Command.class),"repadmin",new String[]{"force","Target","off"});assertFalse(InitialEligibility.enabled(state.get()));assertEquals(2,state.get().audit().size());assertEquals(2,targetMessages.size());
        assertEquals(aid.toString(),state.get().audit().getFirst().request().actor());
        when(actor.hasPermission("dexturreputation.admin.force")).thenReturn(false);clearInvocations(policies);
        commands.onCommand(actor,mock(Command.class),"repadmin",new String[]{"force","Target","on"});verifyNoInteractions(policies);
    }
    @Test void selfAdministrationAndOfflineTargetDoNotDuplicateOrRejectActions(){
        commands.guiAdjustment(actor,aid,AdministrationData.Type.ADD,1,()->{});assertEquals(1,actorMessages.size());assertTrue(targetMessages.isEmpty());
        state.set(null);when(target.isOnline()).thenReturn(false);commands.force(actor,tid,"on",()->{});assertTrue(InitialEligibility.enabled(state.get()));assertEquals(2,actorMessages.size());assertTrue(targetMessages.isEmpty());
    }
    @Test void forceTabCompletionAndPolicyRouteCannotGrantReservedBypass(){
        assertEquals(List.of("on","off","status"),commands.onTabComplete(actor,mock(Command.class),"repadmin",new String[]{"force","Target",""}));
        commands.onCommand(actor,mock(Command.class),"repadmin",new String[]{"policy","Target",InitialEligibility.KEY,"allow","reason"});verify(policies,never()).administer(any());
    }
    @Test void missingPolicyDiagnosticKeysBecomeFriendlyMessages()throws Exception {
        var config=VotingConfiguration.load(directoryWithVoting(),messages);var votes=new VotingCommands(plugin,profiles,mock(VotingService.class),config,messages,PlayerLanguage.ru_RU);
        votes.failed(actor,new PolicyDeniedException("reputation.restore.pending"));assertEquals(1,actorMessages.size());assertFalse(actorMessages.getFirst().contains("reputation.restore.pending"));
    }
    @Test void recoveryAdministrationNotifiesBothSidesAndMergesLowLevelEvents(){
        var service=mock(RecoveryApplication.class);var snapshot=recovery(tid);when(service.administer(any(),any(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(snapshot));
        var recovery=new RecoveryCommands(plugin,profiles,service,messages,PlayerLanguage.ru_RU);recovery.administer(actor,tid,org.dextur.reputation.domain.recovery.RecoveryData.Command.PAUSE,"reason",()->{});
        var event=new RecoveryService.Changed(UUID.randomUUID(),RecoveryService.Kind.PROGRAM_PAUSED,snapshot,"stage");assertTrue(recovery.notifyEvent(event));assertTrue(recovery.notifyEvent(event));
        assertEquals(1,actorMessages.size());assertEquals(1,targetMessages.size());assertFalse(actorMessages.getFirst().contains("PAUSE"));
    }
    @Test void selfRecoveryAdministrationDoesNotSendASecondRoleNotification(){
        var service=mock(RecoveryApplication.class);var snapshot=recovery(aid);when(service.administer(any(),any(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(snapshot));
        var recovery=new RecoveryCommands(plugin,profiles,service,messages,PlayerLanguage.ru_RU);recovery.administer(actor,aid,org.dextur.reputation.domain.recovery.RecoveryData.Command.PAUSE,"reason",()->{});
        assertTrue(recovery.notifyEvent(new RecoveryService.Changed(UUID.randomUUID(),RecoveryService.Kind.PROGRAM_PAUSED,snapshot,"stage")));assertEquals(1,actorMessages.size());
    }
    private RecoverySnapshot recovery(UUID id){return new RecoverySnapshot(id,"global",1,RecoverySnapshot.Status.PAUSED,false,"ACTIVE","","program",Map.of("ru_RU","Программа","en_US","Program"),"stage",Map.of("ru_RU","Этап","en_US","Stage"),List.of(),List.of(),List.of(),"COMPASS",Instant.EPOCH,Instant.EPOCH,Instant.EPOCH);}
    @Test void newLocalizationFallbackPreservesCustomizedCatalogFiles()throws Exception {
        for(var locale:PlayerLanguage.values())try(var in=getClass().getResourceAsStream("/lang/"+locale+".yml")){
            String legacy=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^admin7-ui-[^\\n]*\\n", "").replaceAll("(?m)^admin-force-[^\\n]*\\n", "").replaceAll("(?m)^admin-help:.*$","admin-help: Custom help");Files.writeString(directory.resolve(locale+".yml"),legacy);
        }
        Path ru=directory.resolve("ru_RU.yml");String before=Files.readString(ru);var loaded=new Messages(directory,PlayerLanguage.ru_RU);
        assertEquals("Custom help",loaded.plain(PlayerLanguage.ru_RU,"admin-help"));assertFalse(loaded.template(PlayerLanguage.ru_RU,"admin-force-status").isEmpty());assertFalse(loaded.template(PlayerLanguage.en_US,"admin7-ui-access").isEmpty());assertEquals(before,Files.readString(ru));
        assertEquals(YamlFiles.resource("/lang/ru_RU.yml").keySet(),YamlFiles.resource("/lang/en_US.yml").keySet());
    }
}
