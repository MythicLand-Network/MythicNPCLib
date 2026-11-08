package cn.mythicland.npclib.bot.v1_8_R3;

import net.minecraft.server.v1_8_R3.AxisAlignedBB;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;

import java.util.function.BiConsumer;

public final class BotCollision {
    // Match Entity.setPosition's float dimensions before widening them to doubles.
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    private static final double HEIGHT = 1.8F;
    private final BiConsumer<World, Block> obstacleHandler;

    public BotCollision(BiConsumer<World, Block> obstacleHandler) {
        this.obstacleHandler = obstacleHandler;
    }

    public static boolean loaded(Location location) {
        return location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public boolean blocked(Location location) {
        AxisAlignedBB bounds = new AxisAlignedBB(
                location.getX() - HALF_WIDTH, location.getY(), location.getZ() - HALF_WIDTH,
                location.getX() + HALF_WIDTH, location.getY() + HEIGHT, location.getZ() + HALF_WIDTH
        );
        for (int x = (int) Math.floor(bounds.a); x <= Math.floor(Math.nextDown(bounds.d)); x++) {
            for (int z = (int) Math.floor(bounds.c); z <= Math.floor(Math.nextDown(bounds.f)); z++) {
                for (int y = (int) Math.floor(bounds.b); y <= Math.floor(Math.nextDown(bounds.e)); y++) {
                    if (!location.getWorld().isChunkLoaded(x >> 4, z >> 4)) return true;
                    Block block = location.getWorld().getBlockAt(x, y, z);
                    if (block.getType() != Material.AIR) this.obstacleHandler.accept(location.getWorld(), block);
                }
            }
        }
        return !((CraftWorld) location.getWorld()).getHandle().a(bounds).isEmpty();
    }

    public static Location standingLocation(Location location) {
        if (location.getWorld().getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ()).getType()
                != Material.CARPET) return location;
        Location standing = location.clone();
        standing.setY(Math.max(location.getY(), location.getBlockY() + 1.0 / 16));
        return standing;
    }

    public void clearPath(Location start, Location goal) {
        if (start.getBlockY() != goal.getBlockY()) return;
        double dx = goal.getX() - start.getX();
        double dz = goal.getZ() - start.getZ();
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / 0.4));
        for (int step = 0; step <= steps; step++) {
            Location location = new Location(
                    start.getWorld(), start.getX() + dx * step / steps,
                    start.getY(), start.getZ() + dz * step / steps
            );
            if (blocked(location)) return;
        }
    }
}
