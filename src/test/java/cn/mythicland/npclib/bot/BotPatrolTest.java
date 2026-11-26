package cn.mythicland.npclib.bot;

import cn.mythicland.framework.navigation.CyclicPathCursor;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BotPatrolTest {
    @Test
    void patrolTurnsTowardTheNextWaypointWithinTheCitizensArrivalMargin() {
        World world = mock(World.class);
        BotPatrol patrol = new BotPatrol(new CyclicPathCursor());
        List<BotPatrol.Waypoint> waypoints = List.of(
                ignored -> new Location(world, 7, 64, 0), ignored -> new Location(world, 7, 64, 7)
        );
        assertEquals(new Location(world, 7, 64, 0), patrol.update(waypoints, true, new Location(world, 5.5, 64, 0)));
        assertEquals(
                new Location(world, 7, 64, 7), patrol.update(waypoints, true, new Location(world, 5.7, 64, 0)),
                "A patrol near the waypoint should turn inward toward the next point without reaching the exact vertex"
        );
        assertTrue(patrol.takePathChange());
    }

    @Test
    void displacedPatrolKeepsItsWaypointUntilArrival() {
        World world = mock(World.class);
        CyclicPathCursor cursor = new CyclicPathCursor();
        cursor.reset(1);
        BotPatrol patrol = new BotPatrol(cursor);
        List<BotPatrol.Waypoint> waypoints = List.of(
                ignored -> new Location(world, 0, 64, 0), ignored -> new Location(world, 10, 64, 0),
                ignored -> new Location(world, 10, 64, 10), ignored -> new Location(world, 0, 64, 10)
        );
        Location originalGoal = new Location(world, 10, 64, 0);
        for (Location position : List.of(
                new Location(world, 5, 64, 0), new Location(world, 5, 64, 8),
                new Location(world, 5, 64, 9.4), new Location(world, 5, 64, 10)
        )) {
            assertEquals(originalGoal, patrol.update(waypoints, true, position));
            assertEquals(1, cursor.target(waypoints.size()));
            assertFalse(patrol.takePathChange(), "Knockback must preserve the current navigation target");
        }
        assertEquals(
                new Location(world, 10, 64, 10), patrol.update(waypoints, true, originalGoal),
                "Only arriving at the original waypoint should advance the patrol"
        );
        assertEquals(2, cursor.target(waypoints.size()));
        assertTrue(patrol.takePathChange());
    }

    @Test
    void failedNavigationRetriesTheSameWaypoint() {
        World world = mock(World.class);
        BotPatrol patrol = new BotPatrol(new CyclicPathCursor());
        List<BotPatrol.Waypoint> waypoints = List.of(
                ignored -> new Location(world, 7, 64, 0), ignored -> new Location(world, 7, 64, 7)
        );
        Location start = new Location(world, 0, 64, 0);
        Location goal = patrol.update(waypoints, false, start);
        patrol.finish(waypoints.size(), false);
        assertTrue(patrol.takePathChange());
        assertEquals(goal, patrol.update(waypoints, false, start));
        for (int tick = 0; tick < 400; tick++) {
            patrol.update(waypoints, false, start);
        }
        assertEquals(goal, patrol.update(waypoints, false, start), "Being stuck must not skip a waypoint");
    }

    @Test
    void singleWaypointStopsOnArrivalAndResumesAfterDisplacement() {
        World world = mock(World.class);
        BotPatrol patrol = new BotPatrol(new CyclicPathCursor());
        List<BotPatrol.Waypoint> waypoints = List.of(ignored -> new Location(world, 5, 64, 0));
        Location start = new Location(world, 0, 64, 0);
        assertEquals(5, patrol.update(waypoints, false, start).getX());
        assertFalse(patrol.takePathChange());
        Location arrived = new Location(world, 5, 64, 0);
        assertNull(patrol.update(waypoints, false, arrived));
        assertTrue(patrol.takePathChange());
        assertNull(patrol.update(waypoints, false, arrived));
        assertEquals(5, patrol.update(waypoints, false, start).getX());
    }

    @Test
    void balancedPatrolDoesNotSkipCornersWhileWaitingForHeadway() {
        World world = mock(World.class);
        BotPatrol patrol = new BotPatrol(new CyclicPathCursor());
        List<BotPatrol.Waypoint> waypoints = List.of(
                ignored -> new Location(world, 7, 64, 0), ignored -> new Location(world, 7, 64, 7)
        );
        Location waiting = new Location(world, 3, 64, 0);
        for (int tick = 0; tick < 400; tick++) {
            assertEquals(new Location(world, 7, 64, 0), patrol.update(waypoints, true, waiting));
            assertFalse(patrol.takePathChange(), "Deliberate slowdown must not skip a waypoint");
        }
        assertEquals(
                new Location(world, 7, 64, 7),
                patrol.update(waypoints, true, new Location(world, 7, 64, 0))
        );
        assertTrue(patrol.takePathChange());
    }
}
