package cn.mythicland.npclib.listeners;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.Test;

public class ChunkListenerTest {
    @Test
    public void isSameChunk_shouldRequireSameWorldAndCoordinates() {
        World npcWorld = mock(World.class);
        World otherWorld = mock(World.class);
        Location npcLocation = new Location(npcWorld, 1.0D, 64.0D, 1.0D);
        Chunk sameChunk = this.chunk(npcWorld, 0, 0);
        Chunk sameCoordinatesInOtherWorld = this.chunk(otherWorld, 0, 0);
        Chunk differentChunk = this.chunk(npcWorld, 1, 0);

        assertTrue(ChunkListener.isSameChunk(npcLocation, sameChunk));
        assertFalse(ChunkListener.isSameChunk(npcLocation, sameCoordinatesInOtherWorld));
        assertFalse(ChunkListener.isSameChunk(npcLocation, differentChunk));
    }

    private Chunk chunk(World world, int x, int z) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(x);
        when(chunk.getZ()).thenReturn(z);
        return chunk;
    }
}
