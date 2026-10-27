package cn.mythicland.npclib.bot;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.List;

public final class BotNavigator {
    public interface Settings extends BotMovement.Settings, BotPathfinder.Settings {
    }

    public interface Context {
        boolean allowed(Location location);

        void clearPath(Location start, Location goal);

        void clearBlock(Location location);

        void recover(Location goal);

        void wanderFailed();
    }

    public record Result(Location waypoint, boolean direct, boolean aborted) {
    }

    private final BotPathfinder pathfinder;
    private List<Location> path = List.of();
    private int pathIndex;
    private long nextPath;

    public BotNavigator(BotPathfinder pathfinder, long firstPath) {
        this.pathfinder = pathfinder;
        this.nextPath = firstPath;
    }

    public void reset(long nextPath) {
        this.path = List.of();
        this.pathIndex = 0;
        this.nextPath = nextPath;
    }

    public void refresh(long tick) {
        this.nextPath = tick;
    }

    public Result navigate(
            Settings config,
            Location location,
            Location goal,
            int entityId,
            long tick,
            boolean descending,
            boolean chase,
            boolean returning,
            Context context
    ) {
        if (goal != null && !context.allowed(goal)) {
            goal = new Location(location.getWorld(), config.x(), config.y(), config.z());
            this.path = List.of();
            this.pathIndex = 0;
        }
        advance(config, location);
        if (!this.path.isEmpty() && this.pathIndex >= this.path.size()) {
            this.nextPath = tick;
        }
        Location directGoal = goal;
        if (goal != null) {
            Vector offset = goal.toVector()
                    .subtract(location.toVector())
                    .setY(0);
            if (offset.lengthSquared() > 4) {
                directGoal = location.clone().add(offset.normalize().multiply(2));
            }
        }
        boolean direct = !descending && directGoal != null && this.pathfinder.canWalkDirectly(location, directGoal);
        if (!config.battle() && this.pathIndex < this.path.size()) {
            Location next = this.path.get(this.pathIndex);
            boolean invalid = invalid(location, next);
            if (invalid) {
                if (next.getBlockY() == location.getBlockY()) {
                    context.clearPath(location, next);
                } else {
                    context.clearBlock(next);
                }
                invalid = invalid(location, next);
            }
            if (invalid) {
                reset(tick);
            }
        }
        boolean needsPath = config.battle() || this.pathIndex >= this.path.size();
        if (!descending && !direct && goal != null && needsPath && tick >= this.nextPath) {
            this.path = find(config, location, goal, context);
            this.pathIndex = 0;
            this.nextPath = tick + 10 + Math.floorMod(entityId, 10);
            if (this.path.isEmpty()) {
                context.clearPath(location, directGoal);
                direct = this.pathfinder.canWalkDirectly(location, directGoal);
                if (!direct) {
                    this.path = find(config, location, goal, context);
                }
            }
            if (this.path.isEmpty() && !direct && !config.battle()) {
                context.recover(goal);
                return new Result(null, false, true);
            }
            if (this.path.isEmpty() && !direct && !chase && !returning) {
                context.wanderFailed();
            }
        }
        advance(config, location);
        Location waypoint = goal != null && this.pathIndex < this.path.size() ? this.path.get(this.pathIndex) : null;
        return new Result(direct ? directGoal : waypoint, direct, false);
    }

    private boolean invalid(Location location, Location next) {
        return !this.pathfinder.isWalkable(next)
                || next.getBlockY() == location.getBlockY() && !this.pathfinder.canWalkDirectly(location, next);
    }

    private List<Location> find(Settings config, Location start, Location goal, Context context) {
        List<Location> found = this.pathfinder.find(config, start, goal);
        return found.stream().anyMatch(point -> !context.allowed(point)) ? List.of() : found;
    }

    private void advance(Settings config, Location location) {
        while (this.pathIndex < this.path.size()) {
            Location point = this.path.get(this.pathIndex);
            double dx = location.getX() - point.getX(), dz = location.getZ() - point.getZ();
            if (Math.pow(dx, 2) + Math.pow(dz, 2) >= .35 * .35
                    || !config.battle() && Math.abs(location.getY() - point.getY()) > .6) break;
            this.pathIndex++;
        }
    }
}
