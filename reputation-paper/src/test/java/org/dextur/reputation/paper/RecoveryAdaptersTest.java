package org.dextur.reputation.paper;
import java.time.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RecoveryAdaptersTest {
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
 RecoverySnapshot snapshot(){return new RecoverySnapshot(UUID.randomUUID(),"global",1,RecoverySnapshot.Status.ACTIVE,false,"ACTIVE",UUID.randomUUID().toString(),"test",Map.of("ru_RU","Тест","en_US","Test"),"one",Map.of("ru_RU","Один","en_US","One"),List.of(new RecoverySnapshot.Objective("first","ACTIVE_PLAYTIME",Map.of(),Map.of(),5,10,true)),List.of(),List.of(),"CLOCK",Instant.EPOCH,Instant.EPOCH,Instant.EPOCH);}
 @Test void recoveryPlaceholdersReadOnlyImmutableCacheAndMissesAreEmpty()throws Exception{var plugin=mock(DexturReputationPlugin.class);var app=mock(ReputationApplication.class);var recovery=mock(RecoveryService.class);var player=mock(OfflinePlayer.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(plugin.ready()).thenReturn(true);var bridge=new PlaceholderBridge(plugin,app,Messages.bundled(PlayerLanguage.ru_RU));bridge.recovery(recovery,true);when(recovery.cached(any())).thenReturn(Optional.empty());assertEquals("",bridge.onRequest(player,"recovery_status"));when(recovery.cached(any())).thenReturn(Optional.of(snapshot()));assertEquals("ACTIVE",bridge.onRequest(player,"recovery_status"));assertEquals("one",bridge.onRequest(player,"recovery_stage"));assertEquals("50",bridge.onRequest(player,"recovery_progress"));assertEquals("0",bridge.onRequest(player,"recovery_completed_tasks"));assertEquals("1",bridge.onRequest(player,"recovery_total_tasks"));assertEquals("false",bridge.onRequest(player,"recovery_eligible"));verify(recovery,never()).status(any());verifyNoInteractions(app);bridge.recovery(recovery,false);assertEquals("",bridge.onRequest(player,"recovery_status"));}
 @Test void commandPermissionsPreventAnyServiceMutation()throws Exception{var plugin=mock(DexturReputationPlugin.class);when(plugin.ready()).thenReturn(true);var app=mock(ReputationApplication.class);var service=mock(RecoveryApplication.class);var sender=mock(Player.class);when(app.cachedLanguage(any())).thenReturn(PlayerLanguage.en_US);var commands=new RecoveryCommands(plugin,app,service,Messages.bundled(PlayerLanguage.ru_RU),PlayerLanguage.ru_RU);assertTrue(commands.rep(sender,new String[]{"recovery","start"}));assertTrue(commands.rep(sender,new String[]{"admin","recovery","unknown","start"}));verifyNoInteractions(service);assertTrue(commands.tabs(sender,new String[]{"rec"}).isEmpty());}
 @Test void inventoryDragAndUnsafeClicksAreAlwaysCancelled()throws Exception{var plugin=mock(DexturReputationPlugin.class);var service=mock(RecoveryService.class);var commands=mock(RecoveryCommands.class);var layout=new RecoveryGui.Layout(true,47,54,List.of(0),40,38,42,45,53,Material.COMPASS,Material.PAPER,Material.LIME_DYE,Material.EMERALD,Material.BARRIER);var gui=new RecoveryGui(plugin,service,commands,Messages.bundled(PlayerLanguage.ru_RU),layout,RecoveryConfiguration.load(directory));var session=new RecoveryGui.Session(UUID.randomUUID());session.inventory=mock(Inventory.class);var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(session.inventory);when(session.inventory.getHolder()).thenReturn(session);var click=mock(InventoryClickEvent.class);when(click.getView()).thenReturn(view);gui.click(click);verify(click).setCancelled(true);var drag=mock(InventoryDragEvent.class);when(drag.getView()).thenReturn(view);gui.drag(drag);verify(drag).setCancelled(true);verifyNoInteractions(service);}
 @Test void administrativeCompletionAndPublicSnapshotsDoNotExposeTrust(){assertTrue(Arrays.stream(RecoverySnapshot.class.getRecordComponents()).noneMatch(c->c.getName().toLowerCase(Locale.ROOT).contains("trust")));var dto=snapshot();assertThrows(UnsupportedOperationException.class,()->dto.objectives().clear());assertThrows(UnsupportedOperationException.class,()->dto.programNames().put("x","y"));}
}
