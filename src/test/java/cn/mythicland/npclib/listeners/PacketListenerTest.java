package cn.mythicland.npclib.listeners;

import cn.mythicland.npclib.NPCLib;
import cn.mythicland.npclib.api.events.NPCInteractEvent;
import cn.mythicland.npclib.internal.NPCBase;
import cn.mythicland.npclib.internal.NPCManager;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PacketListenerTest {
    @Test
    void dispatchesOnlyForTheOwningInstanceAndDropsEventsAfterShutdown() throws Exception {
        CraftServer server = mock(CraftServer.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PluginManager plugins = mock(PluginManager.class);
        NPCLib owner = mock(NPCLib.class), other = mock(NPCLib.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(owner.getPlugin()).thenReturn(plugin);
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0);
            location.setWorld(world);
            return location;
        });
        NPCBase npc = mock(NPCBase.class);
        when(npc.getEntityId()).thenReturn(123);
        when(npc.getInstance()).thenReturn(owner);
        when(npc.isCreated()).thenReturn(true);
        when(npc.isShown(player)).thenReturn(true);
        when(npc.getWorld()).thenReturn(world);
        when(npc.getLocation()).thenReturn(new Location(world, 1, 0, 0));
        List<Runnable> tasks = new ArrayList<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1));
            return null;
        });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            PacketListener first = new PacketListener(), second = new PacketListener();
            set(first, "instance", owner);
            set(second, "instance", other);
            PacketPlayInUseEntity packet = new PacketPlayInUseEntity();
            set(packet, "a", 123);
            set(packet, "action", PacketPlayInUseEntity.EnumEntityUseAction.ATTACK);
            var receive = PacketListener.class.getDeclaredMethod("handleInteractPacket", Player.class, Object.class);
            receive.setAccessible(true);
            NPCManager.add(npc);
            try {
                receive.invoke(first, player, packet);
                receive.invoke(second, player, packet);
                assertEquals(1, tasks.size());
                tasks.removeFirst().run();
                verify(plugins, times(1)).callEvent(any(NPCInteractEvent.class));

                receive.invoke(first, player, packet);
                assertEquals(1, tasks.size());
                first.stop();
                tasks.removeFirst().run();
                verify(plugins, times(1)).callEvent(any(NPCInteractEvent.class));
            } finally {
                NPCManager.remove(npc);
            }
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
