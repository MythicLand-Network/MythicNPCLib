package cn.mythicland.npclib.bot;

import org.bukkit.Location;

import java.util.List;

public final class BotNavigator {
    private static final double PATH_SHORTCUT_RANGE_SQUARED = 24 * 24;

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
        this.advance(config, location);
        if (!this.path.isEmpty() && this.pathIndex >= this.path.size()) {
            this.nextPath = tick;
        }
        Location directGoal = goal;
        if (goal != null) {
            double dx = goal.getX() - location.getX(), dz = goal.getZ() - location.getZ();
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared > 4) {
                double distance = Math.sqrt(distanceSquared);
                directGoal = location.clone().add(dx / distance * 2, 0, dz / distance * 2);
            }
        }
        boolean direct = !descending && directGoal != null && this.pathfinder.canWalkDirectly(location, directGoal);
        if (!config.battle() && this.pathIndex < this.path.size()) {
            boolean validated = !descending && !direct && this.shortcut(location);
            Location next = this.path.get(this.pathIndex);
            boolean invalid = !validated && this.invalid(location, next);
            if (invalid) {
                if (next.getBlockY() == location.getBlockY()) {
                    context.clearPath(location, next);
                } else {
                    context.clearBlock(next);
                }
                invalid = this.invalid(location, next);
            }
            if (invalid) {
                this.reset(tick);
            }
        }
        boolean needsPath = config.battle() || this.pathIndex >= this.path.size();
        if (!descending && !direct && goal != null && needsPath && tick >= this.nextPath) {
            this.path = this.find(config, location, goal, context);
            this.pathIndex = 0;
            this.nextPath = tick + 10 + Math.floorMod(entityId, 10);
            if (this.path.isEmpty()) {
                context.clearPath(location, directGoal);
                direct = this.pathfinder.canWalkDirectly(location, directGoal);
                if (!direct) {
                    this.path = this.find(config, location, goal, context);
                }
            }
            if (this.path.isEmpty() && !direct && !config.battle()) {
                context.recover(goal);
                return new Result(null, false, true);
            }
            if (this.path.isEmpty() && !direct && !chase && !returning) {
                context.wanderFailed();
            }
            if (!config.battle() && !direct) this.shortcut(location);
        }
        this.advance(config, location);
        if (direct) return new Result(directGoal, true, false);
        Location waypoint = null;
        if (goal != null && this.pathIndex < this.path.size()) waypoint = this.path.get(this.pathIndex);
        return new Result(waypoint, false, false);
    }

    private boolean invalid(Location location, Location next) {
        return !this.pathfinder.isWalkable(next)
                || next.getBlockY() == location.getBlockY() && !this.canReach(location, next);
    }

    private boolean shortcut(Location location) {
        int index = this.pathfinder.furthestDirectPoint(location, this.path, this.pathIndex, PATH_SHORTCUT_RANGE_SQUARED);
        if (index < this.pathIndex) return false;
        this.pathIndex = index;
        return true;
    }

    private boolean canReach(Location location, Location point) {
        if (location.distanceSquared(point) > PATH_SHORTCUT_RANGE_SQUARED) return false;
        int segments = this.pathfinder.directSegments(location, point);
        return this.pathfinder.canWalkDirectly(location, point, segments);
    }

    private List<Location> find(Settings config, Location start, Location goal, Context context) {
        List<Location> found = this.pathfinder.find(config, start, goal);
        if (found.stream().anyMatch(point -> !context.allowed(point))) return List.of();
        return found;
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
