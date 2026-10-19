package cn.mythicland.npclib.internal;

import cn.mythicland.npclib.NPCLib;
import cn.mythicland.npclib.hologram.Hologram;
import cn.mythicland.npclib.nms.v1_8_R3.NPC_v1_8_R3;
import cn.mythicland.framework.bukkit.reflect.Reflection;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.plugin.PluginManager;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class NPCBaseTest {
    @Test
    public void hiddenCooldownUpdates_shouldRefreshRespawnPacketsWithoutSendingWhileHidden() throws Exception {
        DispenserRegistry.c();
        try (var bukkit = mockStatic(Bukkit.class)) {
            CraftServer server = mock(CraftServer.class);
            PluginManager plugins = mock(PluginManager.class);
            when(server.getPluginManager()).thenReturn(plugins);
            when(plugins.getDefaultPermissions(anyBoolean())).thenReturn(Set.of());
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            CraftPlayer player = mock(CraftPlayer.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            EntityPlayer handle = mock(EntityPlayer.class);
            PlayerConnection connection = mock(PlayerConnection.class);
            handle.playerConnection = connection;
            when(player.getHandle()).thenReturn(handle);

            EntityArmorStand line = new EntityArmorStand(null);
            line.setCustomName("平面镜 1秒");
            List<Object> showPackets = new ArrayList<>(List.of(new PacketPlayOutSpawnEntityLiving(line)));
            Hologram hologram = mock(Hologram.class, CALLS_REAL_METHODS);
            this.set(hologram, "version", MinecraftVersion.V1_8_R3);
            this.set(hologram, "customNameMethod", Reflection.getMethod(Entity.class, "setCustomName", String.class));
            this.set(hologram, "text", List.of(line.getCustomName()));
            this.set(hologram, "armorStands", List.of(line));
            this.set(hologram, "showPackets", showPackets);
            this.set(hologram, "hidePackets", List.of(new PacketPlayOutEntityDestroy(line.getId())));

            NPC_v1_8_R3 npc = spy(new NPC_v1_8_R3(mock(NPCLib.class), List.of(line.getCustomName())));
            npc.playerHologram.put(player.getUniqueId(), hologram);
            npc.getShown().add(player.getUniqueId());
            doReturn(true).when(npc).inRangeOf(player);
            doReturn(true).when(npc).inViewOf(player);
            doAnswer(ignored -> {
                hologram.hide(player);
                return null;
            }).when(npc).sendHidePackets(player, false);
            doAnswer(ignored -> {
                hologram.show(player);
                return null;
            }).when(npc).sendShowPackets(player);
            doNothing().when(npc).sendMetadataPacket(player);
            doNothing().when(npc).sendEquipmentPackets(player);
            try {
                for (List<String> updates : List.of(List.of("平面镜 ✔"), List.of("平面镜 3秒", "平面镜 2秒"))) {
                    if (updates.size() > 1) npc.setText(player, List.of("平面镜 4秒"));
                    npc.hide(player);
                    clearInvocations(connection);
                    for (String text : updates) npc.setText(player, List.of(text));
                    verifyNoInteractions(connection);

                    npc.show(player);
                    String latestText = updates.getLast();
                    assertEquals(List.of(latestText), npc.getText(player));
                    assertEquals(latestText, line.getCustomName());
                    verify(connection).sendPacket((Packet<?>) showPackets.getFirst());
                }
            } finally {
                NPCManager.remove(npc);
            }
        }
    }

    private void set(Hologram hologram, String name, Object value) throws Exception {
        Field field = Hologram.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(hologram, value);
    }
}
