package org.dextur.reputation.paper;

import java.time.Instant;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.OfflinePlayer;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlainScorePlaceholdersTest {
    private final UUID id=UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private DexturReputationPlugin plugin;
    private ReputationApplication app;
    private TierService tiers;
    private Messages messages;
    private DisplayFormatter formatter;
    private OfflinePlayer player;
    private PlaceholderBridge expansion;
    private ReputationTierSnapshot tier;

    @BeforeEach void setup() {
        plugin=mock(DexturReputationPlugin.class);app=mock(ReputationApplication.class);
        tiers=mock(TierService.class);messages=mock(Messages.class);formatter=mock(DisplayFormatter.class);
        player=mock(OfflinePlayer.class);when(player.getUniqueId()).thenReturn(id);
        when(plugin.ready()).thenReturn(true);when(app.cachedLanguage(id)).thenReturn(PlayerLanguage.ru_RU);
        expansion=new PlaceholderBridge(plugin,app,messages);expansion.tiers(tiers,formatter);cache(100);
    }

    private void cache(long score) {
        when(app.cachedProfile(id)).thenReturn(Optional.of(new ReputationSnapshot(id,"global",score,0,score,"NEUTRAL",1,Instant.EPOCH,Instant.EPOCH)));
        tier=new ReputationTierSnapshot(id,"global","NEUTRAL",score,1,1,Map.of("en_US","Neutral"),"#55ff55","","",null,0);
        when(tiers.cachedTier(id)).thenReturn(Optional.of(tier));
    }

    @ParameterizedTest
    @CsvSource({"100,100,+100,(+100)","-25,-25,-25,(-25)","0,0,0,(0)",
            "9223372036854775807,9223372036854775807,+9223372036854775807,(+9223372036854775807)",
            "-9223372036854775808,-9223372036854775808,-9223372036854775808,(-9223372036854775808)"})
    void scoresAreExactPlainTextRegardlessOfLocale(long score,String raw,String signed,String bracketed) {
        cache(score);
        for(var language:List.of(PlayerLanguage.ru_RU,PlayerLanguage.en_US)) {
            when(app.cachedLanguage(id)).thenReturn(language);
            assertEquals(raw,expansion.onRequest(player,"score"));
            assertEquals(signed,expansion.onRequest(player,"score_signed"));
            assertEquals(bracketed,expansion.onRequest(player,"score_bracketed"));
        }
        verifyNoInteractions(formatter,messages);
        verify(app,never()).getProfile(any());verify(tiers,never()).getTier(any());
    }

    @ParameterizedTest
    @CsvSource({"100,green","0,gray","-25,red"})
    void scoreColorIsOnlyThePlainColorNameForItsSign(long score,String expected) {
        cache(score);
        for(var language:List.of(PlayerLanguage.ru_RU,PlayerLanguage.en_US)) {
            when(app.cachedLanguage(id)).thenReturn(language);
            assertEquals(expected,expansion.onRequest(player,"score_color"));
        }
        verifyNoInteractions(formatter,messages);
        verify(app,never()).getProfile(any());verify(tiers,never()).getTier(any());
    }

    @Test void cacheMissesAndUnreadyPlayersRemainEmpty() {
        for(String key:List.of("score","score_signed","score_bracketed","score_color")) {
            assertEquals("",expansion.onRequest(null,key));
            when(plugin.ready()).thenReturn(false);assertEquals("",expansion.onRequest(player,key));when(plugin.ready()).thenReturn(true);
            when(app.cachedProfile(id)).thenReturn(Optional.empty());assertEquals("",expansion.onRequest(player,key));cache(100);
            when(tiers.cachedTier(id)).thenReturn(Optional.empty());assertEquals("",expansion.onRequest(player,key));cache(100);
        }
        verifyNoInteractions(formatter,messages);
        verify(app,never()).getProfile(any());verify(tiers,never()).getTier(any());
    }

    @Test void formattedMiniMessageAndUnknownReputationIdKeepTheirExistingContracts() {
        when(formatter.score(tier,PlayerLanguage.ru_RU)).thenReturn(Component.text("(+100)",NamedTextColor.GREEN));
        assertEquals("<green>(+100)</green>",expansion.onRequest(player,"reputation_formatted"));
        assertNull(expansion.onRequest(player,"reputation"));
    }
}
