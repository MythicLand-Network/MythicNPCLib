package cn.mythicland.npclib.bot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotPathfinderTest {
    interface BlockWorld extends World {
        int getBlockTypeIdAt(int x, int y, int z);
    }

    @Test
    void overlappingDirectCandidatesReuseReadsAndSeeTerrainChanges() {
        BlockWorld world = mock(BlockWorld.class);
        when(world.getMaxHeight()).thenReturn(256);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        int[] reads = {0};
        when(world.getBlockTypeIdAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            reads[0]++;
            int x = call.getArgument(0), y = call.getArgument(1);
            if (y < 64 || x == 5 && y == 64) return Material.STONE.getId();
            return Material.AIR.getId();
        });
        BotPathfinder pathfinder = new BotPathfinder();
        Location start = new Location(world, .5, 64, .5);
        List<Location> path = new ArrayList<>();
        for (int x = 2; x <= 24; x += 2) path.add(new Location(world, x + .5, 64, .5));
        for (int repeat = 0; repeat < 3; repeat++) {
            reads[0] = 0;
            int reachable = pathfinder.furthestDirectPoint(start, path, 0, 24 * 24);
            assertEquals(1, reachable);
            assertEquals(17, reads[0], "Overlapping candidates should share the same terrain reads within one check");
        }
        when(world.getBlockTypeIdAt(3, 64, 0)).thenReturn(Material.STONE.getId());
        assertEquals(0, pathfinder.furthestDirectPoint(start, path, 0, 24 * 24),
                "A new candidate check must see a newly placed obstacle");
        when(world.getBlockTypeIdAt(3, 64, 0)).thenReturn(Material.AIR.getId());
        when(world.getBlockTypeIdAt(5, 64, 0)).thenReturn(Material.AIR.getId());
        assertEquals(path.size() - 1, pathfinder.furthestDirectPoint(start, path, 0, 24 * 24),
                "Removing an obstacle must immediately restore the longer direct path");
        assertEquals(2, pathfinder.furthestDirectPoint(start, path, 0, 6 * 6));
        assertEquals(0, pathfinder.furthestDirectPoint(start, List.of(
                path.getFirst(), path.get(1).clone().add(0, 1, 0), path.get(2)
        ), 0, 24 * 24), "Direct shortcuts must stop before a required height change");
        assertEquals(-1, pathfinder.furthestDirectPoint(start, List.of(
                path.getFirst().clone().add(0, 1, 0), path.get(1)
        ), 0, 24 * 24), "The first step must not be bypassed when it changes height");
    }

    @Test
    void longDirectChecksRespectTheirSegmentBudgetAndStopAtObstacles() {
        BlockWorld world = mock(BlockWorld.class);
        when(world.getMaxHeight()).thenReturn(256);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockTypeIdAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                call.<Integer>getArgument(1) < 64 ? Material.STONE.getId() : Material.AIR.getId());
        BotPathfinder pathfinder = new BotPathfinder();
        Location start = new Location(world, 0, 64, 0), goal = new Location(world, 14, 64, 0);
        assertEquals(4, pathfinder.directSegments(start, goal));
        assertFalse(pathfinder.canWalkDirectly(start, goal, 3));
        verify(world, never()).getBlockTypeIdAt(anyInt(), anyInt(), anyInt());
        assertTrue(pathfinder.canWalkDirectly(start, goal, 4));
        clearInvocations(world);
        when(world.getBlockTypeIdAt(5, 64, 0)).thenReturn(Material.STONE.getId());
        assertFalse(pathfinder.canWalkDirectly(start, goal, 4));
        verify(world, never()).getBlockTypeIdAt(eq(6), anyInt(), anyInt());
        assertEquals(0, start.getX(), .001);
        assertEquals(14, goal.getX(), .001);
    }
}
