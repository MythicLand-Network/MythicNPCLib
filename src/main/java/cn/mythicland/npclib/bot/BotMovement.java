package cn.mythicland.npclib.bot;

import cn.mythicland.framework.math.Angles;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.random.RandomGenerator;

public final class BotMovement {
    public interface Settings {
        double x();

        double y();

        double z();

        double spawnHeight();

        double spawnRadius();

        double radius();

        double centerAttraction();

        double attackDistance();

        double wanderMinDistance();

        double wanderMaxDistance();
    }

    private BotMovement() {
    }

    public static float turnToTarget(float current, float desired, double maximum) {
        return Angles.turn(
                current, desired,
                maximum * (1 + Math.min(2, Math.abs(Angles.angle(desired - current)) / 45))
        );
    }

    public static Location combatAim(Location eye, Location targetEye) {
        Location aim = targetEye.clone();
        if (Math.abs(targetEye.getY() - eye.getY()) <= 1.5) {
            aim.setY(eye.getY());
        }
        return aim;
    }

    public static Location spawn(Settings config, World world, RandomGenerator random) {
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = Math.sqrt(random.nextDouble()) * config.spawnRadius();
        return new Location(
                world, config.x() + Math.cos(angle) * distance,
                config.y() + config.spawnHeight(), config.z() + Math.sin(angle) * distance
        );
    }

    public static boolean facing(Location eye, Location targetEye, double maximumDegrees) {
        var direction = targetEye.toVector().subtract(eye.toVector());
        if (direction.lengthSquared() < 1e-12) return true;
        return eye.getDirection().dot(direction.normalize()) >= Math.cos(Math.toRadians(maximumDegrees));
    }

    public static double inwardProbability(double distance, double radius, double attraction) {
        double ratio = Math.clamp(distance / radius, 0, 1);
        return 1 - Math.pow(1 - (0.35 + 0.65 * ratio), attraction);
    }

    public static double distanceSquared(Settings config, Location location) {
        double dx = location.getX() - config.x();
        double dz = location.getZ() - config.z();
        return dx * dx + dz * dz;
    }

    public static Vector inputs(float yaw, Vector movement) {
        double length = movement.length();
        if (length > 1) {
            movement = movement.clone().multiply(1 / length);
        }
        double radians = Math.toRadians(yaw);
        return new Vector(
                movement.getX() * Math.cos(radians) + movement.getZ() * Math.sin(radians), 0,
                -movement.getX() * Math.sin(radians) + movement.getZ() * Math.cos(radians)
        );
    }

    public static Location approach(Settings config, Location location, Location opponent, UUID id) {
        double dx = opponent.getX() - location.getX(), dz = opponent.getZ() - location.getZ();
        double distance = Math.hypot(dx, dz);
        double preference = Math.floorMod(id.hashCode(), 2001) / 1000.0 - 1;
        double spacing = Math.min(1.5, config.attackDistance() * .55);
        if (distance < 1e-6) {
            double angle = Math.floorMod(id.hashCode(), 360) * Math.PI / 180;
            dx = Math.cos(angle);
            dz = Math.sin(angle);
            distance = 1;
        }
        double offset = Math.min(.45, Math.max(0, distance - spacing) * .12) * preference;
        Location goal = opponent.clone().add(
                -dx / distance * spacing - dz / distance * offset, 0,
                -dz / distance * spacing + dx / distance * offset
        );
        return distanceSquared(config, goal) <= config.radius() * config.radius() ? goal :
                returnGoal(config, location, id);
    }

    public static Location returnGoal(Settings config, Location location, UUID id) {
        double angle = Math.floorMod(id.hashCode(), 360) * Math.PI / 180;
        double distance = config.radius() / Math.sqrt(Math.max(1, config.centerAttraction())) * .2;
        return new Location(
                location.getWorld(), config.x() + Math.cos(angle) * distance,
                location.getY(), config.z() + Math.sin(angle) * distance
        );
    }

    public static Location wander(Settings config, Location location, RandomGenerator random) {
        double distance = Math.sqrt(distanceSquared(config, location));
        boolean inward = distance > 0.1 &&
                random.nextDouble() < inwardProbability(distance, config.radius(), config.centerAttraction());
        for (int attempt = 0; attempt < 16; attempt++) {
            double direction = random.nextDouble() * Math.PI * 2;
            if (inward) {
                direction = Math.atan2(config.z() - location.getZ(), config.x() - location.getX())
                        + (random.nextDouble() - 0.5) * Math.PI * 0.75;
            }
            double length = config.wanderMinDistance()
                    + random.nextDouble() * (config.wanderMaxDistance() - config.wanderMinDistance());
            Location candidate = location.clone().add(Math.cos(direction) * length, 0, Math.sin(direction) * length);
            double candidateDistance = distanceSquared(config, candidate);
            if (candidateDistance > config.radius() * config.radius()) continue;
            if (inward && candidateDistance >= distance * distance) continue;
            return candidate;
        }
        return new Location(location.getWorld(), config.x(), location.getY(), config.z());
    }
}
