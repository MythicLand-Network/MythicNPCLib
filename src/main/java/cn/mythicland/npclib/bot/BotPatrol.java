package cn.mythicland.npclib.bot;

import cn.mythicland.framework.path.CyclicPathCursor;
import cn.mythicland.framework.path.NavigationProgress;
import cn.mythicland.framework.path.PathLoadBalancer;
import cn.mythicland.framework.task.StaggeredDeadline;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.function.Predicate;

public final class BotPatrol {
    public interface Waypoint {
        Location location(World world);
    }

    private final CyclicPathCursor cursor;
    private final NavigationProgress progress = new NavigationProgress(100, 2.25);
    private PathLoadBalancer.Target crossing;
    private Location goal;
    private long nextBalance;
    private boolean pathChanged;

    public BotPatrol(CyclicPathCursor cursor) {
        this.cursor = cursor;
    }

    public void reset(int target, long tick, int entityId) {
        this.cursor.reset(target);
        this.crossing = null;
        this.nextBalance = StaggeredDeadline.after(tick, entityId, 10);
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
            PathLoadBalancer balancing,
            double interiorRatio,
            long routeSlot,
            long tick,
            int entityId,
            Location location,
            Predicate<Location> walkable
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
        double margin = balancing == null ? .6 : .18;
        boolean arrived;
        if (this.crossing == null) {
            arrived = reached(location, this.goal, margin);
        } else {
            arrived = balancing.hasArrived(this.crossing, location.getX(), location.getZ(), tick, margin)
                    && Math.abs(location.getY() - this.goal.getY()) <= .6;
        }
        if (arrived) {
            finish(size, balancing, routeSlot, location, tick, true);
            index = this.cursor.target(size);
            this.goal = index < 0 ? null : waypoints.get(index).location(location.getWorld());
        } else if (this.progress.isStuck(location.getX(), location.getY(), location.getZ())) {
            finish(size, balancing, routeSlot, location, tick, false);
        }
        if (balancing != null && this.goal != null && tick >= this.nextBalance) {
            this.nextBalance = StaggeredDeadline.after(tick, entityId, 10);
            if (this.crossing == null) {
                this.crossing = balancing.crossing(
                        routeSlot, interiorRatio,
                        point -> walkable.test(new Location(location.getWorld(), point.x(), point.y(), point.z()))
                );
                if (this.crossing != null) {
                    this.progress.reset();
                    this.pathChanged = true;
                }
            }
        }
        if (this.crossing != null) {
            PathLoadBalancer.Point point = balancing.destination(this.crossing, location.getX(), location.getZ(), tick);
            Location target = new Location(location.getWorld(), point.x(), point.y(), point.z());
            if (walkable.test(target)) {
                this.goal.setX(point.x());
                this.goal.setY(point.y());
                this.goal.setZ(point.z());
            }
        }
        return this.goal;
    }

    public void finish(
            int size,
            PathLoadBalancer balancing,
            long routeSlot,
            Location location,
            long tick,
            boolean reached
    ) {
        if (this.crossing != null) {
            if (reached) {
                this.cursor.reset(balancing.nextWaypoint(this.crossing));
            } else {
                this.cursor.advance(size);
            }
            balancing.finish(routeSlot, location.getX(), location.getY(), location.getZ(), tick);
            this.crossing = null;
            this.nextBalance = tick + 40;
        } else if (reached || size > 1) {
            this.cursor.advance(size);
        }
        this.goal = null;
        this.progress.reset();
        this.pathChanged = true;
    }

    private static boolean reached(Location location, Location target, double margin) {
        double dx = location.getX() - target.getX(), dz = location.getZ() - target.getZ();
        return dx * dx + dz * dz < margin * margin && Math.abs(location.getY() - target.getY()) <= .6;
    }
}
