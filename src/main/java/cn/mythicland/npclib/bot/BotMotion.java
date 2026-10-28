package cn.mythicland.npclib.bot;

import cn.mythicland.framework.math.Angles;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.random.RandomGenerator;

public final class BotMotion {
    public interface Settings {
        double yawSpeed();

        double pitchSpeed();

        double movementSpeed();

        double approachJumpChance();
    }

    private final RandomGenerator random;
    private long nextStrafe;
    private long nextTurnSpeed;
    private long nextJump;
    private double turnSpeed, desiredTurnSpeed;
    private int strafeDirection = 1;

    public BotMotion(long tick, RandomGenerator random) {
        this.random = random;
        this.turnSpeed = this.desiredTurnSpeed = random.nextDouble(.85, 1.15);
        this.nextTurnSpeed = tick + random(20, 40);
    }

    public void updateStrafe(long tick) {
        if (tick < this.nextStrafe) return;
        this.strafeDirection = this.random.nextBoolean() ? 1 : -1;
        this.nextStrafe = tick + random(20, 40);
    }

    public <T extends Settings & BotNavigator.Settings> void navigateAndMove(
            T config,
            BotNavigator navigator,
            BotNavigator.Context context,
            BotHandle entity,
            Location location,
            Location goal,
            Player opponent,
            long tick,
            boolean descending,
            boolean chase,
            boolean returning,
            boolean near,
            boolean balanced
    ) {
        BotNavigator.Result result = navigator.navigate(config, location, goal, entity.getId(), tick,
                descending, chase, returning, context);
        if (!result.aborted()) {
            move(config, entity, location, result.waypoint(), opponent, tick,
                    descending, chase, near, result.direct(), balanced);
        }
    }

    public void move(
            Settings config,
            BotHandle entity,
            Location location,
            Location waypoint,
            Player opponent,
            long tick,
            boolean descending,
            boolean chase,
            boolean near,
            boolean direct,
            boolean balanced
    ) {
        Player player = entity.getBukkitEntity();
        Location aim = waypoint;
        if (chase) {
            aim = BotMovement.combatAim(player.getEyeLocation(), opponent.getEyeLocation());
        }
        float desiredYaw = entity.yaw();
        float desiredPitch = 0;
        if (aim != null) {
            double dx = aim.getX() - location.getX(), dz = aim.getZ() - location.getZ();
            desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            if (chase) {
                desiredPitch = (float) -Math.toDegrees(
                        Math.atan2(aim.getY() - player.getEyeLocation().getY(), Math.hypot(dx, dz)));
            }
        }
        if (tick >= this.nextTurnSpeed) {
            this.desiredTurnSpeed = this.random.nextDouble(.85, 1.15);
            this.nextTurnSpeed = tick + random(20, 40);
        }
        this.turnSpeed += Math.clamp(this.desiredTurnSpeed - this.turnSpeed, -.02, .02);
        double yawSpeed = config.yawSpeed() * this.turnSpeed;
        double pitchSpeed = config.pitchSpeed() * this.turnSpeed;
        if (chase) {
            entity.yaw(BotMovement.turnToTarget(entity.yaw(), desiredYaw, yawSpeed));
            entity.pitch(BotMovement.turnToTarget(entity.pitch(), desiredPitch, pitchSpeed));
        } else {
            entity.yaw(Angles.turn(entity.yaw(), desiredYaw, yawSpeed));
            entity.pitch(Angles.turn(entity.pitch(), desiredPitch, pitchSpeed));
        }
        boolean moving = !descending && waypoint != null;
        Vector movement;
        if (moving) {
            movement = new Vector(waypoint.getX() - location.getX(), 0, waypoint.getZ() - location.getZ());
        } else {
            movement = new Vector();
        }
        double remaining = movement.length();
        if (remaining > 1e-6) {
            movement.multiply((balanced ? 1 : Math.min(1, remaining / .6)) / remaining);
        }
        Vector input = BotMovement.inputs(entity.yaw(), movement);
        if (chase && input.getZ() < 0) {
            input.setZ(0);
            input.setX(Math.abs(input.getX()) > 0.01 ? Math.signum(input.getX()) : this.strafeDirection);
        }
        boolean jump = moving && !direct && waypoint.getY() > location.getY() + 0.6;
        if (moving && chase && !near && entity.onGround() && tick >= this.nextJump
                && location.distanceSquared(opponent.getLocation()) <= 8 * 8
                && movement.dot(opponent.getLocation().toVector().subtract(location.toVector()).setY(0)) > 0) {
            this.nextJump = tick + random(200, 400);
            jump |= this.random.nextDouble() < config.approachJumpChance();
        }
        entity.step((float) input.getX(), (float) input.getZ(), config.movementSpeed(), jump);
    }

    private int random(int min, int max) {
        return (int) this.random.nextLong(min, (long) max + 1);
    }
}
