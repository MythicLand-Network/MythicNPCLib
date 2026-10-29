package cn.mythicland.npclib.bot.v1_8_R3;

import net.minecraft.server.v1_8_R3.AxisAlignedBB;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;

import java.util.function.BiConsumer;

public final class BotCollision {
    private final BiConsumer<World, Block> obstacleHandler;

    public BotCollision(BiConsumer<World, Block> obstacleHandler) {
        this.obstacleHandler = obstacleHandler;
    }

    public static boolean loaded(Location location) {
        return location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public boolean blocked(Location location) {
        for (int x = (int) Math.floor(location.getX() - 0.3); x <= Math.floor(location.getX() + 0.3); x++) {
            for (int z = (int) Math.floor(location.getZ() - 0.3); z <= Math.floor(location.getZ() + 0.3); z++) {
                for (int y = location.getBlockY(); y <= Math.floor(location.getY() + 1.8); y++) {
                    if (!location.getWorld().isChunkLoaded(x >> 4, z >> 4)) return true;
                    Block block = location.getWorld().getBlockAt(x, y, z);
                    if (block.getType() != Material.AIR) this.obstacleHandler.accept(location.getWorld(), block);
                }
            }
        }
        return !((CraftWorld) location.getWorld()).getHandle().a(new AxisAlignedBB(
                location.getX() - 0.3, location.getY(), location.getZ() - 0.3,
                location.getX() + 0.3, location.getY() + 1.8, location.getZ() + 0.3
        )).isEmpty();
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
