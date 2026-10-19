package cn.mythicland.npclib.hologram;

import cn.mythicland.npclib.internal.MinecraftVersion;
import cn.mythicland.framework.bukkit.reflect.Reflection;
import net.minecraft.server.v1_8_R3.DispenserRegistry;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityArmorStand;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityDestroy;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityMetadata;
import net.minecraft.server.v1_8_R3.PacketPlayOutSpawnEntityLiving;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.plugin.PluginManager;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class HologramTest {
    @Test
    public void updates_shouldPreserveUnchangedLinesAndShowHideTransitions() throws Exception {
        DispenserRegistry.c();
        try (var bukkit = mockStatic(Bukkit.class)) {
            CraftServer server = mock(CraftServer.class);
            PluginManager plugins = mock(PluginManager.class);
            when(server.getPluginManager()).thenReturn(plugins);
            when(plugins.getDefaultPermissions(anyBoolean())).thenReturn(Set.of());
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            Hologram hologram = mock(Hologram.class, CALLS_REAL_METHODS);
            EntityArmorStand first = new EntityArmorStand(null);
            EntityArmorStand second = new EntityArmorStand(null);
            first.setCustomName("first");
            second.setCustomName("second");
            List<Object> show = new ArrayList<>(List.of(new PacketPlayOutSpawnEntityLiving(first),
                    new PacketPlayOutSpawnEntityLiving(second)));
            List<Object> hide = List.of(new PacketPlayOutEntityDestroy(first.getId()),
                    new PacketPlayOutEntityDestroy(second.getId()));
            this.set(hologram, "version", MinecraftVersion.V1_8_R3);
            this.set(hologram, "customNameMethod", Reflection.getMethod(Entity.class, "setCustomName", String.class));
            this.set(hologram, "text", List.of("first", "second"));
            this.set(hologram, "armorStands", List.of(first, second));
            this.set(hologram, "showPackets", show);
            this.set(hologram, "hidePackets", hide);
            Object unchangedShow = show.get(1);

            assertTrue(hologram.getUpdatePackets(List.of("first", "second")).isEmpty());
            List<Object> changed = hologram.getUpdatePackets(List.of("changed", "second"));
            assertEquals(1, changed.size());
            assertTrue(changed.get(0) instanceof PacketPlayOutEntityMetadata);
            assertEquals("changed", first.getCustomName());
            assertSame(unchangedShow, show.get(1));
            assertEquals(List.of(hide.get(0)), hologram.getUpdatePackets(List.of("", "second")));
            List<Object> shown = hologram.getUpdatePackets(List.of("restored", "second"));
            assertEquals(List.of(show.get(0)), shown);
            assertEquals("restored", first.getCustomName());
            assertTrue(hologram.getUpdatePackets(List.of("restored", "second")).isEmpty());
        }
    }

    private void set(Hologram hologram, String name, Object value) throws Exception {
        Field field = Hologram.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(hologram, value);
    }
}
