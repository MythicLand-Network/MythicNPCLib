package cn.mythicland.npclib.bot;

import cn.mythicland.framework.path.CyclicPathCursor;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BotPatrolTest {
    @Test
    void singleWaypointStopsOnArrivalAndResumesAfterDisplacement() {
        World world = mock(World.class);
        BotPatrol patrol = new BotPatrol(new CyclicPathCursor());
        List<BotPatrol.Waypoint> waypoints = List.of(ignored -> new Location(world, 5, 64, 0));
        Location start = new Location(world, 0, 64, 0);
        assertEquals(5, patrol.update(waypoints, null, 0, 0, 1, 1, start, ignored -> true).getX());
        assertFalse(patrol.takePathChange());
        Location arrived = new Location(world, 5, 64, 0);
        assertNull(patrol.update(waypoints, null, 0, 0, 2, 1, arrived, ignored -> true));
        assertTrue(patrol.takePathChange());
        assertNull(patrol.update(waypoints, null, 0, 0, 3, 1, arrived, ignored -> true));
        assertEquals(5, patrol.update(waypoints, null, 0, 0, 4, 1, start, ignored -> true).getX());
    }
}
