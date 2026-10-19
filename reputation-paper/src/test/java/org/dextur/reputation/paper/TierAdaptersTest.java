package org.dextur.reputation.paper;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.bukkit.plugin.PluginManager;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TierAdaptersTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    DexturReputationPlugin plugin;ReputationApplication app;TierService tiers;DisplayFormatter formatter;DisplayConfiguration config;Player player;Scoreboard board;Team own,foreign;
    @BeforeEach void setup() throws Exception {
        Files.writeString(directory.resolve("display.yml"),"config-version: 1\nnameplate: {mode: TEAM_IF_FREE}\ntab: {mode: DIRECT}\n");
        config=DisplayConfiguration.load(directory);formatter=new DisplayFormatter(config);plugin=mock(DexturReputationPlugin.class);var server=mock(Server.class);when(plugin.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        app=mock(ReputationApplication.class);tiers=mock(TierService.class);player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getName()).thenReturn("Player");when(player.isOnline()).thenReturn(true);when(app.cachedLanguage(any())).thenReturn(PlayerLanguage.ru_RU);
        var tier=new ReputationTierSnapshot(player.getUniqueId(),"global","RECOGNIZED",25,1,1,Map.of("ru_RU","Признанный","en_US","Recognized"),"#55ff55","[{tier}] ","",100L,0);when(tiers.cachedTier(any())).thenReturn(Optional.of(tier));
        board=mock(Scoreboard.class);own=mock(Team.class);foreign=mock(Team.class);when(own.getName()).thenReturn("owned");when(board.getTeam("owned")).thenReturn(own);when(own.getEntries()).thenReturn(Set.of("Player"));var suffix=new AtomicReference<Component>(Component.empty());when(own.suffix()).thenAnswer(i->suffix.get());doAnswer(i->{suffix.set(i.getArgument(0));return null;}).when(own).suffix(any());when(board.registerNewTeam(any())).thenReturn(own);when(player.getScoreboard()).thenReturn(board);doReturn(List.of(player)).when(server).getOnlinePlayers();
        var tab=new AtomicReference<Component>(Component.text("Player"));when(player.playerListName()).thenAnswer(i->tab.get());doAnswer(i->{tab.set(i.getArgument(0));return null;}).when(player).playerListName(any());
    }
    @Test void foreignTeamsRemainUntouchedAndNoScoreboardIsReplaced() {
        when(board.getEntryTeam("Player")).thenReturn(foreign);var display=new ReputationDisplay(plugin,app,tiers,formatter,config);display.tick();display.close();
        verify(board,never()).registerNewTeam(any());verifyNoInteractions(foreign);verify(player,never()).setScoreboard(any());assertEquals(0,display.ownedTeams());
    }
    @Test void ownTeamUpdatesAndQuitOrDisableCleansIt() {
        var display=new ReputationDisplay(plugin,app,tiers,formatter,config);display.tick();assertEquals(1,display.ownedTeams());verify(own).suffix(argThat(c->DisplayFormatter.plain(c).equals(" (+25)")));
        when(board.getEntryTeam("Player")).thenReturn(own);when(own.getEntries()).thenReturn(Set.of());display.quit(player);verify(own).removeEntry("Player");verify(own).unregister();assertEquals(0,display.ownedTeams());assertEquals("Player",DisplayFormatter.plain(player.playerListName()));
    }
    @Test void directTabYieldsWhenAnotherPluginChangesTheName() {
        var display=new ReputationDisplay(plugin,app,tiers,formatter,config);display.tick();assertTrue(DisplayFormatter.plain(player.playerListName()).contains("Признанный"));
        player.playerListName(Component.text("Foreign TAB"));clearInvocations(player);display.tick();display.close();verify(player,never()).playerListName(any());assertEquals("Foreign TAB",DisplayFormatter.plain(player.playerListName()));
    }
    @Test void placeholderOnlyAndKnownTabOwnersCauseNoDirectWrites() throws Exception {
        when(plugin.getServer().getPluginManager().isPluginEnabled("TAB")).thenReturn(true);new ReputationDisplay(plugin,app,tiers,formatter,config).tick();verify(player,never()).playerListName(any());
        Files.writeString(directory.resolve("display.yml"),"config-version: 1\ntab: {mode: PLACEHOLDER_ONLY}\n");config=DisplayConfiguration.load(directory);new ReputationDisplay(plugin,app,tiers,formatter,config).tick();verify(player,never()).playerListName(any());
    }
    @Test void allPlaceholdersUseCacheOnlyAndHonorLanguageAndStaleMisses() throws Exception {
        var profile=new ReputationSnapshot(player.getUniqueId(),"global",25,0,25,"RECOGNIZED",1,Instant.EPOCH,Instant.EPOCH);when(app.cachedProfile(any())).thenReturn(Optional.of(profile));when(plugin.ready()).thenReturn(true);
        var expansion=new PlaceholderBridge(plugin,app,Messages.bundled(PlayerLanguage.ru_RU));expansion.tiers(tiers,formatter);
        for(var key:List.of("score","score_formatted","tier","tier_name","prefix","suffix","color","positive","negative","tier_id","reputation_formatted"))assertNotNull(expansion.onRequest(player,key));
        assertEquals("Признанный",expansion.onRequest(player,"tier"));assertEquals("Признанный",expansion.onRequest(player,"tier_name"));assertEquals("true",expansion.onRequest(player,"positive"));assertEquals("false",expansion.onRequest(player,"negative"));assertEquals("<#55ff55>",expansion.onRequest(player,"color"));
        when(app.cachedLanguage(any())).thenReturn(PlayerLanguage.en_US);assertEquals("Recognized",expansion.onRequest(player,"tier"));assertNull(expansion.onRequest(player,"unknown"));
        when(tiers.cachedTier(any())).thenReturn(Optional.empty());assertEquals("",expansion.onRequest(player,"tier"));when(app.cachedProfile(any())).thenReturn(Optional.empty());assertEquals("",expansion.onRequest(player,"score"));
        verify(app,never()).getProfile(any());verify(app,never()).language(any());verify(tiers,never()).getTier(any());
    }
    @Test void foreignTeamWithOurPotentialNameIsNotClaimed() {
        when(board.getTeam(any())).thenReturn(foreign);var display=new ReputationDisplay(plugin,app,tiers,formatter,config);display.tick();display.close();verify(board,never()).registerNewTeam(any());verifyNoInteractions(foreign);
    }
    @Test void replacingOurTeamWithSameNamedForeignTeamNeverDeletesTheReplacement() {
        var display=new ReputationDisplay(plugin,app,tiers,formatter,config);display.tick();when(board.getEntryTeam("Player")).thenReturn(foreign);when(board.getTeam("owned")).thenReturn(foreign);clearInvocations(own,foreign);display.tick();display.close();verify(own,never()).unregister();verify(own,never()).removeEntry(any());verifyNoInteractions(foreign);
    }
    @Test void externallyAdoptedTeamIsRelinquishedWithoutMutatingItsMembersOrSuffix() {
        var display=new ReputationDisplay(plugin,app,tiers,formatter,config);display.tick();when(board.getEntryTeam("Player")).thenReturn(own);when(own.prefix()).thenReturn(Component.text("Foreign rank"));clearInvocations(own);display.tick();display.close();verify(own,never()).suffix(any());verify(own,never()).removeEntry(any());verify(own,never()).unregister();assertEquals(0,display.ownedTeams());
    }
    @Test void initiallyFormattedTabNameIsNeverClaimed() {
        player.playerListName(Component.text("Foreign initial TAB"));clearInvocations(player);var display=new ReputationDisplay(plugin,app,tiers,formatter,config);display.tick();display.close();verify(player,never()).playerListName(any());
    }

    @Test void foreignAdoptionImmediatelyBeforeQuitOrDisableIsPreserved() {
        var display=new ReputationDisplay(plugin,app,tiers,formatter,config);display.tick();when(own.prefix()).thenReturn(Component.text("Foreign rank"));clearInvocations(own);display.close();verify(own,never()).removeEntry(any());verify(own,never()).unregister();
    }
    @Test void foreignVisibilityImmediatelyBeforeCleanupIsPreserved() {
        var display=new ReputationDisplay(plugin,app,tiers,formatter,config);display.tick();when(own.getOption(Team.Option.NAME_TAG_VISIBILITY)).thenReturn(Team.OptionStatus.NEVER);clearInvocations(own);display.close();verify(own,never()).removeEntry(any());verify(own,never()).unregister();
    }

    @Test void legacyGuiKeepsItsConfiguredProfileIconWithoutAnExplicitTierMaterial() throws Exception {
        String original=new String(getClass().getResourceAsStream("/gui.yml").readAllBytes());String legacy=original.split("tier-panel:")[0].replace("material: PLAYER_HEAD","material: EMERALD");Files.writeString(directory.resolve("gui.yml"),legacy);
        var gui=GuiConfiguration.load(directory,m->true);assertEquals(Material.EMERALD,gui.tierPanel().material());
    }

}
