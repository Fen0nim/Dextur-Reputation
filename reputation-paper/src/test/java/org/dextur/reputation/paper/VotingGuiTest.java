package org.dextur.reputation.paper;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.scheduler.BukkitScheduler;
import org.dextur.reputation.api.VotingService;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VotingGuiTest {
    @TempDir Path directory;
    DexturReputationPlugin plugin;
    VotingGui gui;
    VotingGui.Session session;
    Player player;
    InventoryView view;
    List<Runnable> scheduled;
    AtomicInteger actions;
    @BeforeEach void setup() throws Exception {
        for(String file:List.of("gui.yml","voting.yml")) try(var in=getClass().getResourceAsStream("/"+file)) { Files.copy(in,directory.resolve(file)); }
        plugin=mock(DexturReputationPlugin.class);var server=mock(Server.class);var scheduler=mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);when(server.getScheduler()).thenReturn(scheduler);when(plugin.ready()).thenReturn(true);
        scheduled=new ArrayList<>();doAnswer(i->{scheduled.add(i.getArgument(1));return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        var messages=Messages.bundled(PlayerLanguage.ru_RU);var config=VotingConfiguration.load(directory,messages);
        var commands=mock(VotingCommands.class);when(commands.allowed(any(),anyString())).thenReturn(true);gui=new VotingGui(plugin,mock(VotingService.class),commands,config,GuiConfiguration.load(directory,m->true),messages);
        player=mock(Player.class);var id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.isOnline()).thenReturn(true);
        session=new VotingGui.Session(id,UUID.randomUUID(),"Target");session.inventory=mock(Inventory.class);when(session.inventory.getSize()).thenReturn(54);when(session.inventory.getHolder()).thenReturn(session);
        view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(session.inventory);when(player.getOpenInventory()).thenReturn(view);
        actions=new AtomicInteger();session.actions.put(10,actions::incrementAndGet);
    }
    InventoryClickEvent click(ClickType click,int raw) {
        var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(click);when(event.getRawSlot()).thenReturn(raw);return event;
    }
    @Test void cancelsEveryTransferAndOnlyAcceptsSingleTopLeftClick() {
        for(var type:List.of(ClickType.SHIFT_LEFT,ClickType.NUMBER_KEY,ClickType.DOUBLE_CLICK,ClickType.SWAP_OFFHAND,ClickType.DROP,ClickType.CREATIVE,ClickType.RIGHT)) {
            var event=click(type,10);gui.click(event);verify(event).setCancelled(true);
        }
        for(int slot:new int[]{-999,54,60}) { var event=click(ClickType.LEFT,slot);gui.click(event);verify(event).setCancelled(true); }
        assertTrue(scheduled.isEmpty());assertEquals(0,actions.get());
        gui.click(click(ClickType.LEFT,10));gui.click(click(ClickType.LEFT,10));assertEquals(1,scheduled.size());assertEquals(0,actions.get());
        scheduled.getFirst().run();assertEquals(1,actions.get());
    }
    @Test void staleSessionsWrongOwnersDisconnectAndDisableCannotAct() {
        gui.click(click(ClickType.LEFT,10));var replacement=mock(Inventory.class);when(view.getTopInventory()).thenReturn(replacement);
        scheduled.getFirst().run();assertEquals(0,actions.get());
        when(view.getTopInventory()).thenReturn(session.inventory);session.busy=false;
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());gui.click(click(ClickType.LEFT,10));assertEquals(1,scheduled.size());
        when(player.getUniqueId()).thenReturn(session.owner);when(player.isOnline()).thenReturn(false);gui.click(click(ClickType.LEFT,10));assertEquals(1,scheduled.size());
        when(player.isOnline()).thenReturn(true);when(plugin.ready()).thenReturn(false);gui.click(click(ClickType.LEFT,10));assertEquals(1,scheduled.size());
    }
    @Test void shutdownClosesOnlyOwnedInventoriesBeforeListenersAreRemoved() {
        var other=mock(Player.class);var otherView=mock(InventoryView.class);var otherInventory=mock(Inventory.class);
        when(other.getOpenInventory()).thenReturn(otherView);when(otherView.getTopInventory()).thenReturn(otherInventory);
        var server=plugin.getServer();doReturn(java.util.List.of(player,other)).when(server).getOnlinePlayers();
        gui.closeAll();verify(player).closeInventory();verify(other,never()).closeInventory();
    }
    @Test void dragIsAlwaysCancelledAndLayoutRejectsOverlapOrInvalidMaterial() throws Exception {
        var drag=mock(InventoryDragEvent.class);when(drag.getView()).thenReturn(view);gui.drag(drag);verify(drag).setCancelled(true);
        Path file=directory.resolve("gui.yml");String original=Files.readString(file);
        for(String changed:List.of(original.replace("slot: 42","slot: 38"),original.replace("RED_DYE","NOT_A_MATERIAL"),original.replace("size: 54","size: 46"),original.replace("[0, 1, 2","[0, 0, 2"))) {
            Files.writeString(file,changed);assertThrows(IllegalArgumentException.class,()->GuiConfiguration.load(directory,m->true));
        }
    }
}
