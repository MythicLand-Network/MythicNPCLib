package cn.mythicland.npclib.bot;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotNavigatorTest {
    @Test
    void blockedDirectOnlyRecoveryDoesNotStartAPathSearch() {
        BotPathfinder pathfinder = mock(BotPathfinder.class);
        BotNavigator.Context context = mock(BotNavigator.Context.class);
        when(context.allowed(any())).thenReturn(true);
        when(context.directOnly()).thenReturn(true);
        World world = mock(World.class);
        Location start = new Location(world, 0, 64, 0), goal = new Location(world, 10, 64, 0);
        BotNavigator.Result result = new BotNavigator(pathfinder, 0).navigate(
                mock(BotNavigator.Settings.class), start, goal, 1, 1, false, false, false, context
        );
        assertTrue(result.aborted());
        verify(context).recover(goal);
        verify(pathfinder, never()).find(any(), any(), any());
    }
}
