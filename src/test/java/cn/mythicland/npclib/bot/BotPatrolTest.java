package cn.mythicland.npclib.bot;

import cn.mythicland.framework.navigation.CyclicPathCursor;
import cn.mythicland.framework.navigation.PathLoadBalancer;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotPatrolTest {
    @Test
    void promotionFromNearestEdgeRecoveryToCrossingRequiresNewPathValidation() {
        World world = mock(World.class);
        BotPatrol patrol = new BotPatrol(new CyclicPathCursor());
        PathLoadBalancer balancing = mock(PathLoadBalancer.class);
        when(balancing.recoveryGoal(0)).thenReturn(Optional.of(new PathLoadBalancer.Point(3, 64, 0)));
        Location position = new Location(world, 3, 64, -2);
        List<BotPatrol.Waypoint> waypoints = List.of(ignored -> new Location(world, 7, 64, 0));
        patrol.update(waypoints, balancing, 0, position);
        assertTrue(patrol.takePathChange());
        patrol.update(waypoints, balancing, 0, position);
        assertFalse(patrol.takePathChange());
        when(balancing.isCrossing(0)).thenReturn(true);
        patrol.update(waypoints, balancing, 0, position);
        assertTrue(patrol.takePathChange(), "A new shortcut must pass admission even when already rejoining an edge");
        patrol.update(waypoints, balancing, 0, position);
        assertFalse(patrol.takePathChange(), "An admitted crossing should not revalidate its full line every tick");
    }

    @Test
    void displacedPatrolRejoinsTheNearestEdgeBeforeContinuing() {
        World world = mock(World.class);
        BotPatrol patrol = new BotPatrol(new CyclicPathCursor());
        List<BotPatrol.Waypoint> waypoints = List.of(
                ignored -> new Location(world, 7, 64, 0), ignored -> new Location(world, 7, 64, 7),
                ignored -> new Location(world, 0, 64, 7), ignored -> new Location(world, 0, 64, 0));
        PathLoadBalancer balancing = new PathLoadBalancer(List.of(
                new PathLoadBalancer.Point(7, 64, 0), new PathLoadBalancer.Point(7, 64, 7),
                new PathLoadBalancer.Point(0, 64, 7), new PathLoadBalancer.Point(0, 64, 0)));
        balancing.begin(1);
        balancing.observe(0, 3, 64, -2);
        balancing.end(.16);
        Location rejoin = patrol.update(waypoints, balancing, 0, new Location(world, 3, 64, -2));
        assertEquals(3, rejoin.getX(), .001);
        assertEquals(0, rejoin.getZ(), .001);
        assertTrue(patrol.takePathChange());
        balancing.begin(2);
        balancing.observe(0, 3, 64, 0);
        balancing.end(.16);
        assertEquals(new Location(world, 7, 64, 0),
                patrol.update(waypoints, balancing, 0, new Location(world, 3, 64, 0)));
        assertTrue(patrol.takePathChange());
    }

    @Test
    void singleWaypointStopsOnArrivalAndResumesAfterDisplacement() {
        World world = mock(World.class);
        BotPatrol patrol = new BotPatrol(new CyclicPathCursor());
        List<BotPatrol.Waypoint> waypoints = List.of(ignored -> new Location(world, 5, 64, 0));
        Location start = new Location(world, 0, 64, 0);
        assertEquals(5, patrol.update(waypoints, null, 0, start).getX());
        assertFalse(patrol.takePathChange());
        Location arrived = new Location(world, 5, 64, 0);
        assertNull(patrol.update(waypoints, null, 0, arrived));
        assertTrue(patrol.takePathChange());
        assertNull(patrol.update(waypoints, null, 0, arrived));
        assertEquals(5, patrol.update(waypoints, null, 0, start).getX());
    }

    @Test
    void balancedPatrolDoesNotSkipCornersWhileWaitingForHeadway() {
        World world = mock(World.class);
        BotPatrol patrol = new BotPatrol(new CyclicPathCursor());
        List<BotPatrol.Waypoint> waypoints = List.of(
                ignored -> new Location(world, 7, 64, 0),
                ignored -> new Location(world, 7, 64, 7),
                ignored -> new Location(world, 0, 64, 7),
                ignored -> new Location(world, 0, 64, 0));
        PathLoadBalancer balancing = new PathLoadBalancer(List.of(
                new PathLoadBalancer.Point(7, 64, 0), new PathLoadBalancer.Point(7, 64, 7),
                new PathLoadBalancer.Point(0, 64, 7), new PathLoadBalancer.Point(0, 64, 0)));
        Location waiting = new Location(world, 3, 64, 0);
        for (int tick = 0; tick < 400; tick++) {
            balancing.begin(tick);
            balancing.observe(0, waiting.getX(), waiting.getY(), waiting.getZ());
            assertEquals(new Location(world, 7, 64, 0), patrol.update(waypoints, balancing, 0, waiting));
            assertFalse(patrol.takePathChange(), "Deliberate slowdown must not skip a waypoint");
        }
        assertEquals(new Location(world, 7, 64, 7),
                patrol.update(waypoints, balancing, 0, new Location(world, 7, 64, 0)));
        assertTrue(patrol.takePathChange());
        Location displaced = new Location(world, 3, 64, 7);
        balancing.begin(401);
        balancing.observe(0, displaced.getX(), displaced.getY(), displaced.getZ());
        assertEquals(new Location(world, 0, 64, 7), patrol.update(waypoints, balancing, 0, displaced),
                "A displaced patrol should continue along its current edge rather than return to its old waypoint");
    }
}
