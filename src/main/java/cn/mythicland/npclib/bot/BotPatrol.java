package cn.mythicland.npclib.bot;

import cn.mythicland.framework.navigation.CyclicPathCursor;
import cn.mythicland.framework.navigation.NavigationProgress;
import cn.mythicland.framework.navigation.PathLoadBalancer;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;

public final class BotPatrol {
    public interface Waypoint {
        Location location(World world);
    }

    private final CyclicPathCursor cursor;
    private final NavigationProgress progress = new NavigationProgress(100, 2.25);
    private Location goal;
    private boolean pathChanged;
    private boolean rejoining;
    private boolean crossing;

    public BotPatrol(CyclicPathCursor cursor) {
        this.cursor = cursor;
    }

    public void reset(int target) {
        this.cursor.reset(target);
        this.progress.reset();
        this.goal = null;
        this.rejoining = false;
        this.crossing = false;
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
            PathLoadBalancer balancing,
            long routeSlot,
            Location location
    ) {
        boolean balanced = balancing != null;
        int size = waypoints.size();
        int index = this.cursor.target(size);
        if (index < 0 && size == 1 && !reached(location, waypoints.getFirst().location(location.getWorld()), .6)) {
            this.cursor.reset();
            index = 0;
        }
        if (index < 0) return null;
        if (balanced) {
            var recovery = balancing.recoveryGoal(routeSlot);
            if (recovery.isPresent()) {
                boolean crossing = balancing.isCrossing(routeSlot);
                if (!this.rejoining || this.crossing != crossing) {
                    this.rejoining = true;
                    this.crossing = crossing;
                    this.pathChanged = true;
                    this.progress.reset();
                }
                PathLoadBalancer.Point point = recovery.get();
                this.goal = new Location(location.getWorld(), point.x(), point.y(), point.z());
                return this.goal;
            }
            if (this.rejoining) {
                this.reset(balancing.nextWaypoint(location.getX(), location.getY(), location.getZ()));
                this.pathChanged = true;
                index = this.cursor.target(size);
            }
            int target = balancing.nextWaypoint(routeSlot);
            if (target >= 0 && target != index && !reached(location, waypoints.get(target).location(location.getWorld()), .18)) {
                this.reset(target);
                this.pathChanged = true;
                index = target;
            }
        }
        if (this.goal == null) {
            this.goal = waypoints.get(index).location(location.getWorld());
        }
        double margin = .6;
        if (balanced) margin = .18;
        if (reached(location, this.goal, margin)) {
            finish(size, true);
            index = this.cursor.target(size);
            if (index >= 0) this.goal = waypoints.get(index).location(location.getWorld());
        } else if (!balanced && this.progress.isStuck(location.getX(), location.getY(), location.getZ())) {
            finish(size, false);
        }
        return this.goal;
    }

    public void finish(int size, boolean reached) {
        if (reached || size > 1) this.cursor.advance(size);
        this.goal = null;
        this.progress.reset();
        this.pathChanged = true;
    }

    private static boolean reached(Location location, Location target, double margin) {
        double dx = location.getX() - target.getX(), dz = location.getZ() - target.getZ();
        return dx * dx + dz * dz < margin * margin && Math.abs(location.getY() - target.getY()) <= .6;
    }
}
