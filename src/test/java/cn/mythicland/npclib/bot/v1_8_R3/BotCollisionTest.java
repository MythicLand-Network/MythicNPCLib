package cn.mythicland.npclib.bot.v1_8_R3;

import net.minecraft.server.v1_8_R3.AxisAlignedBB;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.DispenserRegistry;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.util.CraftMagicNumbers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotCollisionTest {
    static {
        DispenserRegistry.c();
    }

    @Test
    void nativeStandingPositionTouchingCeilingAndWallsIsNotBlocked() {
        CraftWorld world = worldWithCorner();
        List<Block> obstacles = new ArrayList<>();
        BotCollision collision = new BotCollision((ignored, block) -> obstacles.add(block));
        Location position = new Location(world, 36.30000001192093D, 91.20000004768372D, 96.69999998807907D);

        assertFalse(collision.blocked(position), "A native standing hitbox only touches the ceiling and walls");
        assertTrue(obstacles.isEmpty(), "Touching blocks must not be treated as obstacles to restore");
    }

    @Test
    void actualOverlapWithCeilingOrWallRemainsBlocked() {
        CraftWorld world = worldWithCorner();
        BotCollision collision = new BotCollision((ignored, block) -> { });
        Location position = new Location(world, 36.30000001192093D, 91.20000004768372D, 96.69999998807907D);

        assertTrue(collision.blocked(position.clone().add(0, 0.001D, 0)));
        assertTrue(collision.blocked(position.clone().add(-0.001D, 0, 0)));
        assertTrue(collision.blocked(position.clone().add(0, 0, 0.001D)));
    }

    private CraftWorld worldWithCorner() {
        CraftWorld world = mock(CraftWorld.class);
        WorldServer nativeWorld = mock(WorldServer.class);
        Block air = mock(Block.class);
        Block stone = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(stone.getType()).thenReturn(Material.STONE);
        when(world.getHandle()).thenReturn(nativeWorld);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
            return y >= 93 || x <= 35 || z >= 97 ? stone : air;
        });
        when(nativeWorld.isLoaded(any(BlockPosition.class))).thenReturn(true);
        when(nativeWorld.getType(any(BlockPosition.class))).thenAnswer(call -> {
            BlockPosition position = call.getArgument(0);
            Block block = world.getBlockAt(position.getX(), position.getY(), position.getZ());
            return CraftMagicNumbers.getBlock(block.getType()).fromLegacyData(block.getData());
        });
        when(nativeWorld.a(any(AxisAlignedBB.class))).thenCallRealMethod();
        return world;
    }
}
