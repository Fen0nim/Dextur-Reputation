package org.dextur.reputation.paper;

import java.util.*;
import org.dextur.reputation.application.Messages;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuditIdentityTest {
    @Test void participantRolesAreExplicitAndOperationIdsAreNotPlayers(){
        UUID admin=UUID.randomUUID(),target=UUID.randomUUID(),vote=UUID.randomUUID();
        var view=AuditIdentity.expand(Map.of("action","ADD","actor",admin.toString(),"target",target.toString(),"vote",vote.toString()),id->id.equals(admin)?"Admin":"Target","Unknown","Console","System");
        assertEquals("Admin",view.get("administrator_name"));assertEquals(admin.toString(),view.get("administrator_uuid"));assertEquals("Target",view.get("target_name"));assertEquals(target.toString(),view.get("target_uuid"));assertEquals(vote.toString(),view.get("vote"));assertFalse(view.containsKey("vote_name"));var legacy=AuditIdentity.expand(Map.of("source_actor","PLAYER"),id->{fail("PLAYER is a source marker, not a UUID");return "wrong";},"Unknown","Console","System");assertEquals("PLAYER",legacy.get("source_actor"));assertFalse(legacy.containsKey("source_actor_name"));
    }
    @Test void offlineUnknownKeepsUuidAndConsoleNeverInventsAPlayer(){
        UUID target=UUID.randomUUID();var view=AuditIdentity.expand(Map.of("action","SET","actor","CONSOLE","target",target.toString()),id->null,"Неизвестный игрок","Консоль","Система");
        assertEquals("Консоль",view.get("administrator_name"));assertFalse(view.containsKey("administrator_uuid"));assertEquals("Неизвестный игрок",view.get("target_name"));assertEquals(target.toString(),view.get("target_uuid"));
    }
    @Test void compactGeometryUsesAllRequiredSizes(){
        assertEquals(18,MenuGeometry.size(1));assertEquals(27,MenuGeometry.size(10));assertEquals(36,MenuGeometry.size(19));assertEquals(45,MenuGeometry.size(28));assertEquals(54,MenuGeometry.size(45));
        for(int size:List.of(18,27,36,45,54))assertEquals(size-7,MenuGeometry.back(size));
    }
    @Test void russianAndEnglishMenuLabelsAreCompleteAndDistinct()throws Exception {
        var m=Messages.bundled(PlayerLanguage.ru_RU);assertEquals("Администратор",m.plain(PlayerLanguage.ru_RU,"admin7-field-administrator"));assertEquals("Administrator",m.plain(PlayerLanguage.en_US,"admin7-field-administrator"));assertEquals("Консоль",m.plain(PlayerLanguage.ru_RU,"admin7-console"));assertEquals("Console",m.plain(PlayerLanguage.en_US,"admin7-console"));
        assertFalse(m.plain(PlayerLanguage.ru_RU,"admin7-section-trust").contains("Trust"));assertEquals("Причина не указана",m.plain(PlayerLanguage.ru_RU,"gui-reason-unspecified"));assertEquals("Reason not specified",m.plain(PlayerLanguage.en_US,"gui-reason-unspecified"));
    }
    @Test void absentTargetSentinelIsNotResolvedAsAPlayer(){
        var view=AuditIdentity.expand(Map.of("actor","SYSTEM","target",new UUID(0,0).toString()),id->{fail("Sentinel is not a player");return "wrong";},"Unknown","Console","System");assertEquals("—",view.get("target_name"));assertFalse(view.containsKey("target_uuid"));
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void offlineNamesUseOnlyTheProfileCacheWithoutNbtOrMojangLookups(boolean present){
        var server=org.mockito.Mockito.mock(org.bukkit.Server.class);UUID id=UUID.randomUUID();var profile=org.mockito.Mockito.mock(com.destroystokyo.paper.profile.PlayerProfile.class);org.mockito.Mockito.when(server.createProfile(id)).thenReturn(profile);org.mockito.Mockito.when(profile.getName()).thenReturn(present?"OfflineUser":null);
        assertEquals(present?"OfflineUser":null,PlayerMessages.cachedName(server,id));org.mockito.Mockito.verify(profile).completeFromCache(false,true);org.mockito.Mockito.verify(server,org.mockito.Mockito.never()).getOfflinePlayer(org.mockito.ArgumentMatchers.any(UUID.class));
    }

}
