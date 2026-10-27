package cn.mythicland.npclib.bot;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.random.RandomGenerator;

public final class BotBattle {
    private final BotBattleState state;
    private final RandomGenerator random;
    private long lastDamage;
    private Location wanderGoal;
    private boolean pathChanged;

    public BotBattle(BotBattleState state, RandomGenerator random) {
        this.state = state;
        this.random = random;
    }

    private static double horizontal(Location first, Location second) {
        return Math.pow(first.getX() - second.getX(), 2) + Math.pow(first.getZ() - second.getZ(), 2);
    }

    public void clearGoal() {
        this.wanderGoal = null;
    }

    public boolean takePathChange() {
        boolean changed = this.pathChanged;
        this.pathChanged = false;
        return changed;
    }

    public Decision decide(
            BotMovement.Settings config,
            Player bot,
            Location location,
            boolean descending,
            long tick,
            long damageTime,
            UUID attacker,
            Context context
    ) {
        if (damageTime > this.lastDamage && (context.target() == null || this.state.canChangeTarget(tick))
                && context.valid(context.resolve(attacker))) {
            this.lastDamage = damageTime;
            context.target(attacker);
        }
        Player opponent = context.target() == null ? null : context.resolve(context.target());
        if (!context.valid(opponent)) {
            context.target(null);
            opponent = null;
        }
        double centerDistance = BotMovement.distanceSquared(config, location);
        double returnRadius = config.radius() / Math.sqrt(Math.max(1, config.centerAttraction()));
        boolean returning = this.state.returning(centerDistance, returnRadius);
        boolean searchDue = this.state.searchDue(tick);
        boolean reconsiderDue = this.state.reconsiderDue(tick);
        double attackDistanceSquared = config.attackDistance() * config.attackDistance();
        if (!descending && opponent == null && searchDue) {
            opponent = context.search();
            if (opponent != null) {
                context.target(opponent.getUniqueId());
            }
        } else if (!descending && opponent != null && this.state.canChangeTarget(tick) && reconsiderDue
                && (location.distanceSquared(opponent.getLocation()) > attackDistanceSquared
                || context.pressure(context.target()) > 2)) {
            Player alternative = context.search();
            if (alternative != null && (this.state.targetHeldFor(tick, 60)
                    || location.distanceSquared(alternative.getLocation())
                    < location.distanceSquared(opponent.getLocation()) * .7)) {
                context.target(alternative.getUniqueId());
                opponent = alternative;
            }
        }
        boolean near = opponent != null && location.distanceSquared(opponent.getLocation()) <= attackDistanceSquared;
        boolean chase = opponent != null && (!returning || near && centerDistance <= config.radius() * config.radius());
        Location goal = null;
        if (chase) {
            context.strafe(tick);
            goal = context.separate(BotMovement.approach(config, location, opponent.getLocation(), bot.getUniqueId()));
            if (returning) {
                Vector inward = new Vector(config.x() - location.getX(), 0, config.z() - location.getZ());
                if (inward.lengthSquared() > 1e-6) {
                    goal.add(inward.normalize().multiply(.35));
                }
            }
        } else if (returning) {
            goal = context.separate(BotMovement.returnGoal(config, location, bot.getUniqueId()));
        } else if (!descending) {
            if (this.wanderGoal == null || horizontal(location, this.wanderGoal) < .6 * .6) {
                this.wanderGoal = BotMovement.wander(config, location, this.random);
                this.pathChanged = true;
            }
            goal = this.wanderGoal;
        }
        if (goal != null && BotMovement.distanceSquared(config, goal) > config.radius() * config.radius()) {
            goal = BotMovement.returnGoal(config, location, bot.getUniqueId());
        }
        return new Decision(goal, opponent, chase, returning, near);
    }

    public interface Context {
        UUID target();

        void target(UUID target);

        /**
         * Returns null when the player cannot be found.
         */
        Player resolve(UUID id);

        boolean valid(Player candidate);

        /**
         * Returns null when there is no attackable target.
         */
        Player search();

        int pressure(UUID target);

        Location separate(Location goal);

        void strafe(long tick);
    }

    public record Decision(
            Location goal,
            Player opponent,
            boolean chase,
            boolean returning,
            boolean near
    ) {
    }
}
