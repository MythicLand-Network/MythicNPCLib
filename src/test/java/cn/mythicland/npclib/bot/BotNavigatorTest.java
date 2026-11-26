package cn.mythicland.npclib.bot;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotNavigatorTest {
    interface BlockWorld extends World {
        int getBlockTypeIdAt(int x, int y, int z);
    }

    @Test
    void displacedPatrolContinuesFromAReachableForwardNodeInsteadOfReturningToTheOldPath() {
        BlockWorld world = mock(BlockWorld.class);
        when(world.getMaxHeight()).thenReturn(256);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockTypeIdAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
            if (y < 64 || x == 2 && z >= 0 && z <= 2 && y <= 65) return Material.STONE.getId();
            return Material.AIR.getId();
        });
        BotPathfinder pathfinder = spy(new BotPathfinder());
        BotNavigator.Context context = mock(BotNavigator.Context.class);
        when(context.allowed(any())).thenReturn(true);
        BotNavigator.Settings settings = mock(BotNavigator.Settings.class);
        Location start = new Location(world, .5, 64, .5), goal = new Location(world, 6.5, 64, .5);
        Location oldNode = new Location(world, -1.5, 64, .5);
        Location forwardNode = new Location(world, 6.5, 64, 3.5);
        doReturn(List.of(
                oldNode, new Location(world, -1.5, 64, 3.5), new Location(world, .5, 64, 4.5),
                new Location(world, 2.5, 64, 4.5), forwardNode,
                new Location(world, 6.5, 64, 2.5), new Location(world, 6.5, 64, 1.5), goal
        )).when(pathfinder).find(settings, start, goal);
        BotNavigator navigator = new BotNavigator(pathfinder, 0);
        navigator.navigate(settings, start, goal, 1, 1, false, false, false, context);

        Location displaced = new Location(world, .5, 64, 3.5);
        BotNavigator.Result result = navigator.navigate(
                settings, displaced, goal, 1, 2, false, false, false, context
        );

        assertFalse(result.aborted());
        assertFalse(result.direct(), "The wall still blocks the direct approach to the patrol destination");
        assertEquals(forwardNode, result.waypoint(), "Displacement must not pull the bot back to an earlier path node");
        assertEquals(forwardNode, navigator.navigate(
                settings, displaced, goal, 1, 3, false, false, false, context
        ).waypoint(), "A longer reachable segment must remain valid on subsequent updates");
        verify(context, never()).clearPath(any(), any());
        verify(context, never()).recover(any());
        verify(pathfinder).find(settings, start, goal);
    }

    @Test
    void blockedDirectMovementKeepsRequiredHeightChanges() {
        BotPathfinder pathfinder = mock(BotPathfinder.class);
        BotNavigator.Context context = mock(BotNavigator.Context.class);
        when(context.allowed(any())).thenReturn(true);
        World world = mock(World.class);
        Location start = new Location(world, 0, 64, 0), goal = new Location(world, 10, 64, 0);
        BotNavigator.Settings settings = mock(BotNavigator.Settings.class);
        Location detour = new Location(world, 0, 64, 1);
        when(pathfinder.find(settings, start, goal)).thenReturn(List.of(detour, new Location(world, 1, 65, 1), goal));
        when(pathfinder.canWalkDirectly(any(), any(), anyInt())).thenReturn(true);
        BotNavigator.Result result = new BotNavigator(pathfinder, 0).navigate(
                settings, start, goal, 1, 1, false, false, false, context
        );
        assertFalse(result.aborted());
        assertFalse(result.direct());
        assertEquals(detour, result.waypoint());
        verify(pathfinder).find(settings, start, goal);
        verify(context, never()).recover(any());
    }
}
