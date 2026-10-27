package cn.mythicland.npclib.bot;

import cn.mythicland.npclib.api.skin.Skin;
import cn.mythicland.npclib.bot.v1_8_R3.BotEntity;
import org.bukkit.Location;

import java.util.List;
import java.util.Objects;

public final class PatrolRobotOptions {
    private String name = "PatrolRobot";
    private Skin skin;
    private final Location spawn;
    private List<Location> waypoints;
    private double movementSpeed = 1;
    private Runnable deathHandler;
    private BotEntity.Options externalControl;

    public PatrolRobotOptions(Location location) {
        this.spawn = Objects.requireNonNull(location, "location");
    }

    public void name(String name) { this.name = Objects.requireNonNull(name, "name"); }

    public void skin(Skin skin) { this.skin = skin; }

    public void waypoints(List<Location> points) {
        this.waypoints = Objects.requireNonNull(points, "points").stream()
                .map(Location::clone)
                .toList();
    }

    public void movementSpeed(double speed) {
        if (!Double.isFinite(speed) || speed < 0) {
            throw new IllegalArgumentException("Invalid movement speed");
        }
        this.movementSpeed = speed;
    }

    public void onDeath(Runnable handler) { this.deathHandler = Objects.requireNonNull(handler, "handler"); }

    /**
     * Uses the supplied entity settings and transfers movement, visibility, death and cleanup to the caller.
     * The default patrol settings and library-managed lifecycle do not apply.
     */
    public void externalControl(BotEntity.Options options) { this.externalControl = Objects.requireNonNull(options); }

    public BotEntity.Options externalControl() { return this.externalControl; }

    public String name() { return this.name; }
    public Skin skin() { return this.skin; }
    public List<Location> waypoints() {
        if (this.waypoints != null) return this.waypoints;
        return List.of(
                this.spawn.clone().add(-2, 0, -2), this.spawn.clone().add(2, 0, -2),
                this.spawn.clone().add(2, 0, 2), this.spawn.clone().add(-2, 0, 2)
        );
    }
    public double movementSpeed() { return this.movementSpeed; }
    public Runnable deathHandler() { return this.deathHandler; }
}
