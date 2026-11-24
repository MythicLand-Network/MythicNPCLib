package cn.mythicland.npclib.bot;

import cn.mythicland.framework.bukkit.world.GridPathfinder;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotPathfinderTest {
    @Test
    void longDirectChecksRespectTheirSegmentBudgetAndStopAtObstacles() {
        try (var construction = mockConstruction(GridPathfinder.class)) {
            BotPathfinder pathfinder = new BotPathfinder();
            GridPathfinder grid = construction.constructed().getFirst();
            World world = mock(World.class);
            Location start = new Location(world, 0, 64, 0), goal = new Location(world, 14, 64, 0);
            int[] checks = {0};
            double[] previous = {0};
            when(grid.canWalkDirectly(any(), any())).thenAnswer(
                    call -> {
                        Location from = call.getArgument(0), to = call.getArgument(1);
                        assertTrue(from.distance(to) <= 4, "Each native direct check must remain within four blocks");
                        assertEquals(
                                previous[0],
                                from.getX(),
                                .001,
                                "Direct segments must cover the line continuously"
                        );
                        previous[0] = to.getX();
                        checks[0]++;
                        return true;
                    }
            );
            assertEquals(4, pathfinder.directSegments(start, goal));
            assertFalse(pathfinder.canWalkDirectly(start, goal, 3));
            assertEquals(0, checks[0], "An insufficient budget must reject the line before any world reads");
            assertTrue(pathfinder.canWalkDirectly(start, goal, 4));
            assertEquals(4, checks[0]);
            assertEquals(14, previous[0], .001);
            checks[0] = 0;
            doAnswer(call -> ++checks[0] < 2).when(grid).canWalkDirectly(any(), any());
            assertFalse(pathfinder.canWalkDirectly(start, goal, 4));
            assertEquals(2, checks[0], "A blocked segment must stop further world scans");
            assertEquals(0, start.getX(), .001);
            assertEquals(14, goal.getX(), .001);
        }
    }
}
