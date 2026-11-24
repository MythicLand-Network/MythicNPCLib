package cn.mythicland.npclib.bot;

import cn.mythicland.framework.bukkit.world.GridPathfinder;
import org.bukkit.Location;

import java.util.List;

public final class BotPathfinder {
    public interface Settings {
        boolean battle();

        double x();

        double z();

        double radius();
    }

    private static final int SEARCH_BUDGET = 128;
    private final GridPathfinder pathfinder = new GridPathfinder();

    public boolean canWalkDirectly(Location start, Location goal) {
        return this.pathfinder.canWalkDirectly(start, goal);
    }

    public int directSegments(Location start, Location goal) {
        return Math.max(1, (int) Math.ceil(Math.hypot(goal.getX() - start.getX(), goal.getZ() - start.getZ()) / 3.5));
    }

    /**
     * Validates a longer flat line using bounded checks below GridPathfinder's four-block limit.
     */
    public boolean canWalkDirectly(Location start, Location goal, int maxSegments) {
        if (maxSegments <= 0) throw new IllegalArgumentException("maximum direct segments must be positive");
        int segments = this.directSegments(start, goal);
        if (segments > maxSegments) return false;
        if (!start.getWorld().equals(goal.getWorld()) || start.getBlockY() != goal.getBlockY()) return false;
        Location from = start.clone(), to = start.clone();
        for (int index = 1; index <= segments; index++) {
            double fraction = (double) index / segments;
            to.setX(start.getX() + (goal.getX() - start.getX()) * fraction);
            to.setY(start.getY() + (goal.getY() - start.getY()) * fraction);
            to.setZ(start.getZ() + (goal.getZ() - start.getZ()) * fraction);
            if (!this.pathfinder.canWalkDirectly(from, to)) return false;
            from.setX(to.getX());
            from.setY(to.getY());
            from.setZ(to.getZ());
        }
        return true;
    }

    public boolean isWalkable(Location location) {
        return this.pathfinder.isWalkable(location);
    }

    public List<Location> find(Settings config, Location start, Location goal) {
        double arrivalHeight = .6;
        if (config.battle()) arrivalHeight = 3;
        return this.pathfinder.find(
                start, goal, SEARCH_BUDGET, arrivalHeight,
                (fx, fy, fz, tx, ty, tz) -> {
                    if (!config.battle()) return true;
                    double dx = tx + .5 - config.x(), dz = tz + .5 - config.z();
                    double previousX = fx + .5 - config.x(), previousZ = fz + .5 - config.z();
                    double distance = dx * dx + dz * dz;
                    return distance <= config.radius() * config.radius() ||
                            distance < previousX * previousX + previousZ * previousZ;
                }
        );
    }
}
