package cn.mythicland.npclib.listeners;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.Before;
import org.junit.Test;

public class PlayerMoveEventListenerTest {
    private Player player;
    private RecordingMoveListener listener;

    @Before
    public void setUp() {
        this.player = mock(Player.class);
        this.listener = new RecordingMoveListener();
    }

    @Test
    public void onPlayerMove_shouldCheckVisibilityWhenOnlyViewDirectionChanges() {
        Location from = new Location(
                null,
                0.0D,
                64.0D,
                0.0D,
                0.0F,
                0.0F
        );
        Location to = new Location(
                null,
                0.0D,
                64.0D,
                0.0D,
                90.0F,
                0.0F
        );

        this.listener.onPlayerMove(new PlayerMoveEvent(this.player, from, to));

        assertTrue(this.listener.handled);
    }

    private static final class RecordingMoveListener extends PlayerMoveEventListener {
        private boolean handled;

        @Override
        void handleMove(Player player) {
            this.handled = true;
        }
    }
}
