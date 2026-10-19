package cn.mythicland.npclib.nms.v1_8_R3;

import cn.mythicland.npclib.NPCLib;
import cn.mythicland.npclib.hologram.Hologram;
import cn.mythicland.npclib.internal.NPCManager;
import io.netty.buffer.Unpooled;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.PluginManager;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.spigotmc.SpigotWorldConfig;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class VillagerNpcTest {
    @Test
    public void priestSpawnAndReshowUseVillagerPacketsWithoutPlayerProfiles() throws Exception {
        DispenserRegistry.c();
        try (var bukkit = mockStatic(Bukkit.class)) {
            CraftServer server = mock(CraftServer.class);
            PluginManager plugins = mock(PluginManager.class);
            when(server.getPluginManager()).thenReturn(plugins);
            when(plugins.getDefaultPermissions(anyBoolean())).thenReturn(Set.of());
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            CraftWorld world = mock(CraftWorld.class);
            WorldServer worldHandle = mock(WorldServer.class);
            worldHandle.worldProvider = mock(WorldProvider.class);
            var spigotConfig = World.class.getDeclaredField("spigotConfig");
            spigotConfig.setAccessible(true);
            spigotConfig.set(worldHandle, mock(SpigotWorldConfig.class));
            when(world.getHandle()).thenReturn(worldHandle);
            when(worldHandle.getServer()).thenReturn(server);
            CraftPlayer player = mock(CraftPlayer.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            EntityPlayer handle = mock(EntityPlayer.class);
            PlayerConnection connection = mock(PlayerConnection.class);
            handle.playerConnection = connection;
            when(player.getHandle()).thenReturn(handle);
            Hologram hologram = mock(Hologram.class);
            NPC_v1_8_R3 npc = spy(NPC_v1_8_R3.villager(mock(NPCLib.class), List.of("room"), Villager.Profession.PRIEST));
            doReturn(hologram).when(npc).getHologram(player);
            Location location = new Location(world, 12.5, 80, -4.5, 90, 0);
            try {
                npc.setLocation(location).create();
                for (int show = 0; show < 2; show++) {
                    npc.sendShowPackets(player);
                    npc.sendMetadataPacket(player);
                    ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
                    verify(connection, times(3)).sendPacket(packets.capture());
                    assertTrue(packets.getAllValues().getFirst() instanceof PacketPlayOutSpawnEntityLiving);
                    PacketDataSerializer wire = new PacketDataSerializer(Unpooled.buffer());
                    try {
                        packets.getAllValues().getFirst().b(wire);
                        assertEquals(npc.getEntityId(), wire.e());
                        assertEquals(120, wire.readUnsignedByte());
                        assertEquals((int) Math.floor(location.getX() * 32), wire.readInt());
                        assertEquals((int) Math.floor(location.getY() * 32), wire.readInt());
                        assertEquals((int) Math.floor(location.getZ() * 32), wire.readInt());
                        wire.skipBytes(9);
                        List<DataWatcher.WatchableObject> metadata = DataWatcher.b(wire);
                        assertEquals(Villager.Profession.PRIEST.getId(), metadata.stream()
                                .filter(value -> value.a() == 16).findFirst().orElseThrow().b());
                    } finally {
                        wire.release();
                    }
                    verify(hologram, times(show + 1)).show(player);
                    clearInvocations(connection);
                    npc.sendHidePackets(player, false);
                    verify(connection).sendPacket(any(PacketPlayOutEntityDestroy.class));
                    verifyNoMoreInteractions(connection);
                    clearInvocations(connection);
                    location = new Location(world, 20.5, 80, -4.5, 180, 0);
                    npc.teleport(location);
                }
            } finally {
                NPCManager.remove(npc);
            }
        }
    }
}
