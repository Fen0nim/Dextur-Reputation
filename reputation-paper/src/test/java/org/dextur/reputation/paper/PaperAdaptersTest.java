package org.dextur.reputation.paper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.application.Messages;
import org.dextur.reputation.application.ReputationApplication;
import org.dextur.reputation.application.Settings;
import org.dextur.reputation.domain.PlayerLanguage;
import org.dextur.reputation.domain.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaperAdaptersTest {
    @TempDir Path directory;
    Messages messages;
    Settings settings;
    DexturReputationPlugin plugin;
    Server server;
    Command rep;
    final LinkedBlockingQueue<Runnable> mainTasks = new LinkedBlockingQueue<>();
    @BeforeEach void setup() throws Exception {
        for (String file : List.of("config.yml", "storage.yml", "integrations.yml", "lang/ru_RU.yml", "lang/en_US.yml")) {
            Files.createDirectories(directory.resolve(file).getParent());
            try (var in = getClass().getResourceAsStream("/" + file)) { Files.copy(in, directory.resolve(file)); }
        }
        messages = new Messages(directory.resolve("lang"), PlayerLanguage.ru_RU);
        settings = Settings.load(directory);
        plugin = mock(DexturReputationPlugin.class);
        server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.ready()).thenReturn(true);
        doAnswer(invocation -> { mainTasks.add(invocation.getArgument(0)); return null; }).when(plugin).onMain(any());
        rep = mock(Command.class); when(rep.getName()).thenReturn("rep");
    }
    ProfileRepository repository() {
        return new ProfileRepository() {
            volatile PlayerLanguage language = PlayerLanguage.ru_RU;
            public ReputationSnapshot getOrCreate(UUID id, String scope) {
                assertTrue(Thread.currentThread().getName().startsWith("DexturReputation-db-"));
                return new ReputationSnapshot(id, scope, 0, 0, 0, "NEUTRAL", 0, Instant.EPOCH, Instant.EPOCH);
            }
            public PlayerLanguage language(UUID id, PlayerLanguage fallback) { return language; }
            public void setLanguage(UUID id, PlayerLanguage selected) { language = selected; }
        };
    }
    @Test void permissionDenialAndUnknownPlayersNeverCreateProfiles() throws Exception {
        ProfileRepository repository = mock(ProfileRepository.class);
        try (var app = new ReputationApplication(repository, settings)) {
            ReputationCommands commands = new ReputationCommands(plugin, app, settings, messages);
            CommandSender sender = mock(CommandSender.class);
            commands.onCommand(sender, rep, "rep", new String[0]);
            assertTrue(lastMessage(sender).contains("Недостаточно прав"));
            when(sender.hasPermission("dexturreputation.use")).thenReturn(true);
            commands.onCommand(sender, rep, "rep", new String[]{"unknown"});
            assertTrue(lastMessage(sender).contains("не найден"));
            verifyNoInteractions(repository);
        }
    }
    @Test void languageCommandPersistsThenConfirmsInSelectedLanguageOnMain() throws Exception {
        try (var app = new ReputationApplication(repository(), settings)) {
            ReputationCommands commands = new ReputationCommands(plugin, app, settings, messages);
            Player sender = mock(Player.class);
            UUID id = UUID.randomUUID();
            when(sender.getUniqueId()).thenReturn(id);
            when(sender.isOnline()).thenReturn(true);
            when(sender.hasPermission(anyString())).thenReturn(true);
            commands.onCommand(sender, rep, "rep", new String[]{"language", "en_US"});
            Runnable reply = mainTasks.poll(3, TimeUnit.SECONDS);
            assertNotNull(reply);
            verify(sender, never()).sendMessage(any(Component.class));
            reply.run();
            assertTrue(lastMessage(sender).contains("Language saved: en_US"));
            assertEquals(PlayerLanguage.en_US, app.language(id).toCompletableFuture().get(3, TimeUnit.SECONDS));
        }
    }
    @Test void knownOfflineProfileUsesCachedIdentityAndAsyncRepository() throws Exception {
        try (var app = new ReputationApplication(repository(), settings)) {
            ReputationCommands commands = new ReputationCommands(plugin, app, settings, messages);
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission(anyString())).thenReturn(true);
            var offline = mock(org.bukkit.OfflinePlayer.class);
            when(offline.getUniqueId()).thenReturn(UUID.randomUUID());
            when(offline.getName()).thenReturn("Known"); when(offline.hasPlayedBefore()).thenReturn(true);
            when(server.getOfflinePlayerIfCached("Known")).thenReturn(offline);
            commands.onCommand(sender, rep, "rep", new String[]{"Known"});
            Runnable reply = mainTasks.poll(3, TimeUnit.SECONDS); assertNotNull(reply); reply.run();
            assertTrue(lastMessage(sender).contains("Known")); assertTrue(lastMessage(sender).contains("репутация 0"));
            verify(server, never()).getOfflinePlayer(anyString());
        }
    }
    @Test void placeholderCacheMissAndHitNeverTriggerDatabaseRead() throws Exception {
        ProfileRepository repository = spy(repository());
        try (var app = new ReputationApplication(repository, settings)) {
            var placeholders = new PlaceholderBridge(plugin, app, messages);
            var player = mock(org.bukkit.OfflinePlayer.class);
            UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
            assertEquals("", placeholders.onRequest(player, "score"));
            verifyNoInteractions(repository);
            app.getProfile(id).toCompletableFuture().get(3, TimeUnit.SECONDS);
            clearInvocations(repository);
            assertEquals("0", placeholders.onRequest(player, "score"));
            assertEquals("0", placeholders.onRequest(player, "score_formatted"));
            assertEquals("Нейтральный", placeholders.onRequest(player, "tier"));
            assertNull(placeholders.onRequest(player, "unknown"));
            verifyNoInteractions(repository);
        }
    }
    private org.dextur.reputation.application.VotingConfiguration votingConfig() throws Exception {
        try(var in=getClass().getResourceAsStream("/voting.yml")) { Files.copy(in,directory.resolve("voting.yml")); }
        return org.dextur.reputation.application.VotingConfiguration.load(directory,messages);
    }
    @Test void phaseTwoProfileRoutingPreservesOfflineTextAndAddsStatistics() throws Exception {
        try(var app=new ReputationApplication(repository(),settings)) {
            var voting=mock(org.dextur.reputation.api.VotingService.class);var commands=new VotingCommands(plugin,app,voting,votingConfig(),messages,settings.defaultLanguage());
            var sender=mock(CommandSender.class);when(sender.hasPermission(anyString())).thenReturn(true);
            var offline=mock(org.bukkit.OfflinePlayer.class);UUID id=UUID.randomUUID();when(offline.getUniqueId()).thenReturn(id);when(offline.getName()).thenReturn("Known");when(offline.hasPlayedBefore()).thenReturn(true);when(server.getOfflinePlayerIfCached("Known")).thenReturn(offline);
            var profile=new ReputationSnapshot(id,"global",7,0,7,"NEUTRAL",5,Instant.EPOCH,Instant.EPOCH);
            when(voting.view(id)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new org.dextur.reputation.api.ReputationView(profile,new org.dextur.reputation.api.VoteStatistics(2,1,3,5))));
            assertTrue(commands.rep(sender,new String[]{"Known"}));verify(sender,never()).sendMessage(any(Component.class));
            var reply=mainTasks.poll(3,TimeUnit.SECONDS);assertNotNull(reply);reply.run();
            var captured=ArgumentCaptor.forClass(Component.class);verify(sender,times(2)).sendMessage(captured.capture());
            var plain=PlainTextComponentSerializer.plainText();assertTrue(plain.serialize(captured.getAllValues().getFirst()).contains("Known"));
            assertTrue(plain.serialize(captured.getAllValues().getFirst()).contains("репутация 7"));assertTrue(lastMessage(sender).contains("+2 / −1"));
            assertFalse(commands.rep(sender,new String[]{"language","en_US"}));verify(server,never()).getOfflinePlayer(anyString());
        }
    }
    @Test void voteCommandParsesCommentAndDoesNotConfirmFailedStorage() throws Exception {
        try(var app=new ReputationApplication(repository(),settings)) {
            var voting=mock(org.dextur.reputation.api.VotingService.class);var commands=new VotingCommands(plugin,app,voting,votingConfig(),messages,settings.defaultLanguage());
            var sender=mock(Player.class);UUID voter=UUID.randomUUID(),target=UUID.randomUUID();when(sender.getUniqueId()).thenReturn(voter);when(sender.isOnline()).thenReturn(true);when(sender.hasPermission(anyString())).thenReturn(true);
            var offline=mock(org.bukkit.OfflinePlayer.class);when(offline.getUniqueId()).thenReturn(target);when(offline.hasPlayedBefore()).thenReturn(true);when(server.getOfflinePlayerIfCached("Known")).thenReturn(offline);
            var like=mock(Command.class);when(like.getName()).thenReturn("like");
            when(voting.submit(any())).thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new java.io.IOException("simulated failure")));
            commands.onCommand(sender,like,"like",new String[]{"Known","helpful","literal","<red>comment</red>"});
            var request=ArgumentCaptor.forClass(org.dextur.reputation.api.VoteRequest.class);verify(voting).submit(request.capture());
            assertEquals(voter,request.getValue().voterId());assertEquals(target,request.getValue().targetId());assertEquals("helpful",request.getValue().reason());assertEquals("literal <red>comment</red>",request.getValue().comment());
            var reply=mainTasks.poll(3,TimeUnit.SECONDS);assertNotNull(reply);reply.run();
            assertEquals(PlainTextComponentSerializer.plainText().serialize(messages.render(PlayerLanguage.ru_RU,"error",Map.of())),lastMessage(sender));
            assertTrue(commands.onTabComplete(sender,like,"like",new String[]{"Known","Пом"}).contains("Помощь игрокам"));
        }
    }
    private String lastMessage(CommandSender sender) {
        ArgumentCaptor<Component> captured = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(captured.capture());
        return PlainTextComponentSerializer.plainText().serialize(captured.getAllValues().getLast());
    }
}
