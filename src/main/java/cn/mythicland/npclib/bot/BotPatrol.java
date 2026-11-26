package cn.mythicland.npclib.bot;

import cn.mythicland.framework.navigation.CyclicPathCursor;
import cn.mythicland.framework.navigation.NavigationProgress;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;

public final class BotPatrol {
    public static final double ARRIVAL_MARGIN = Math.sqrt(2);

    public interface Waypoint {
        Location location(World world);
    }

    private final CyclicPathCursor cursor;
    private final NavigationProgress progress = new NavigationProgress(100, 2.25);
    private Location goal;
    private boolean pathChanged;

    public BotPatrol(CyclicPathCursor cursor) {
        this.cursor = cursor;
    }

    public void reset(int target) {
        this.cursor.reset(target);
        this.progress.reset();
        this.goal = null;
    }

    public Location goal() {
        return this.goal;
    }

    public boolean takePathChange() {
        boolean changed = this.pathChanged;
        this.pathChanged = false;
        return changed;
    }

    public Location update(
            List<? extends Waypoint> waypoints,
            boolean balanced,
            Location location
    ) {
        int size = waypoints.size();
        int index = this.cursor.target(size);
        if (index < 0 && size == 1 && !reached(location, waypoints.getFirst().location(location.getWorld()), .6)) {
            this.cursor.reset();
            index = 0;
        }
        if (index < 0) return null;
        if (this.goal == null) {
            this.goal = waypoints.get(index).location(location.getWorld());
        }
        double margin = ARRIVAL_MARGIN;
        if (size == 1) margin = .6;
        if (reached(location, this.goal, margin)) {
            this.finish(size, true);
            index = this.cursor.target(size);
            if (index >= 0) this.goal = waypoints.get(index).location(location.getWorld());
        } else if (!balanced && this.progress.isStuck(location.getX(), location.getY(), location.getZ())) {
            this.finish(size, false);
        }
        return this.goal;
    }

    public void finish(int size, boolean reached) {
        if (reached) this.cursor.advance(size);
        this.goal = null;
        this.progress.reset();
        this.pathChanged = true;
    }

    private static boolean reached(Location location, Location target, double margin) {
        double dx = location.getX() - target.getX(), dz = location.getZ() - target.getZ();
        return dx * dx + dz * dz < margin * margin && Math.abs(location.getY() - target.getY()) <= .6;
    }
}
