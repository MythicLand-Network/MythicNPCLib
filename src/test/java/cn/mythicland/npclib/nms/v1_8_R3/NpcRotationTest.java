package cn.mythicland.npclib.nms.v1_8_R3;

import cn.mythicland.npclib.NPCLib;
import cn.mythicland.npclib.internal.NPCManager;
import io.netty.buffer.Unpooled;
import net.minecraft.server.v1_8_R3.DispenserRegistry;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketDataSerializer;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntity;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityHeadRotation;
import net.minecraft.server.v1_8_R3.PlayerConnection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.plugin.PluginManager;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class NpcRotationTest {
    @Test
    public void rotationPacketsAreSharedOnlyWithPlayersWhoCanSeeTheNpc() throws Exception {
        DispenserRegistry.c();
        try (var bukkit = mockStatic(Bukkit.class)) {
            CraftServer server = mock(CraftServer.class);
            PluginManager permissions = mock(PluginManager.class);
            when(server.getPluginManager()).thenReturn(permissions);
            when(permissions.getDefaultPermissions(anyBoolean())).thenReturn(Set.of());
            bukkit.when(Bukkit::getServer).thenReturn(server);
            World world = mock(World.class);
            CraftPlayer first = this.player(world);
            CraftPlayer second = this.player(world);
            CraftPlayer hidden = this.player(world);
            CraftPlayer unshown = this.player(world);
            CraftPlayer otherWorld = this.player(mock(World.class));
            CraftPlayer offline = this.player(world);
            when(offline.isOnline()).thenReturn(false);
            List<CraftPlayer> online = List.of(first, second, hidden, unshown, otherWorld, offline);
            for (CraftPlayer player : online) {
                bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            }
            NPC_v1_8_R3 npc = new NPC_v1_8_R3(mock(NPCLib.class), List.of());
            try {
                npc.setLocation(new Location(world, 0, 64, 0));
                for (CraftPlayer player : List.of(first, second, hidden, otherWorld, offline)) {
                    npc.getShown().add(player.getUniqueId());
                }
                npc.getShown().add(UUID.randomUUID());
                npc.getAutoHidden().add(hidden.getUniqueId());

                npc.lookAt(new Location(world, 10, 64, 0));

                ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
                verify(first.getHandle().playerConnection, times(2)).sendPacket(packets.capture());
                Packet<?> look = packets.getAllValues().getFirst();
                Packet<?> head = packets.getAllValues().getLast();
                assertTrue(look instanceof PacketPlayOutEntity.PacketPlayOutEntityLook);
                assertTrue(head instanceof PacketPlayOutEntityHeadRotation);
                verify(second.getHandle().playerConnection).sendPacket(same(look));
                verify(second.getHandle().playerConnection).sendPacket(same(head));
                verifyNoMoreInteractions(first.getHandle().playerConnection, second.getHandle().playerConnection);
                for (CraftPlayer player : List.of(hidden, unshown, otherWorld, offline)) {
                    verifyNoInteractions(player.getHandle().playerConnection);
                }
                PacketDataSerializer wire = new PacketDataSerializer(Unpooled.buffer());
                try {
                    look.b(wire);
                    assertEquals(npc.getEntityId(), wire.e());
                    assertEquals((byte) -64, wire.readByte());
                    assertEquals(0, wire.readByte());
                    assertFalse(wire.readBoolean());
                    assertFalse(wire.isReadable());
                } finally {
                    wire.release();
                }
            } finally {
                NPCManager.remove(npc);
            }
        }
    }

    private CraftPlayer player(World world) {
        CraftPlayer player = mock(CraftPlayer.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        EntityPlayer handle = mock(EntityPlayer.class);
        handle.playerConnection = mock(PlayerConnection.class);
        when(player.getHandle()).thenReturn(handle);
        return player;
    }
}
