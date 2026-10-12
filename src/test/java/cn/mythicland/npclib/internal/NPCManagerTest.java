package cn.mythicland.npclib.internal;

import cn.mythicland.npclib.NPCLib;
import cn.mythicland.npclib.nms.v1_8_R3.NPC_v1_8_R3;
import org.bukkit.Bukkit;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class NPCManagerTest {
    @Test
    public void removingAndCreatingNpc_shouldPreserveOtherEntityIdsAndInteractionLookup() {
        List<NPCBase> npcs = new ArrayList<>();
        try (var bukkit = mockStatic(Bukkit.class)) {
            NPCLib library = mock(NPCLib.class);
            NPCBase first = new NPC_v1_8_R3(library, List.of());
            npcs.add(first);
            NPCBase second = new NPC_v1_8_R3(library, List.of());
            npcs.add(second);
            NPCManager.remove(first);
            NPCBase third = new NPC_v1_8_R3(library, List.of());
            npcs.add(third);

            assertNotEquals(first.getEntityId(), third.getEntityId());
            assertNotEquals(second.getEntityId(), third.getEntityId());
            assertNull(NPCManager.getByEntityId(first.getEntityId()));
            assertSame(second, NPCManager.getByEntityId(second.getEntityId()));
            assertSame(third, NPCManager.getByEntityId(third.getEntityId()));

            NPCManager.remove(third);
            assertSame(second, NPCManager.getByEntityId(second.getEntityId()));
        } finally {
            npcs.forEach(NPCManager::remove);
        }
    }
}
