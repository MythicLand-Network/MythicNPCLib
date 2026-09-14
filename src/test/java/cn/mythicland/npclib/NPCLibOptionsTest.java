package cn.mythicland.npclib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NPCLibOptionsTest {
    @Test
    public void playerMoveEvent_shouldEnablePlayerMoveEventHandling() {
        NPCLibOptions.MovementHandling handling = NPCLibOptions.MovementHandling.playerMoveEvent();

        assertTrue(handling.usePme);
        assertEquals(0L, handling.updateInterval);
    }

    @Test
    public void repeatingTask_shouldDisablePlayerMoveEventHandlingAndUseInterval() {
        NPCLibOptions.MovementHandling handling = NPCLibOptions.MovementHandling.repeatingTask(5L);

        assertFalse(handling.usePme);
        assertEquals(5L, handling.updateInterval);
    }
}
