package cn.mythicland.npclib.bot;

import cn.mythicland.npclib.api.skin.Skin;
import cn.mythicland.npclib.bot.v1_8_R3.BotEntity;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Predicate;

public final class FighterRobotOptions {
    private String name = "FighterRobot";
    private Skin skin;
    private double radius = 8;
    private double movementSpeed = 1;
    private Predicate<Player> targets = player -> true;
    private int respawnDelay = -1;
    private Runnable deathHandler;
    private BotEntity.Options externalControl;

    public void name(String name) { this.name = Objects.requireNonNull(name, "name"); }
    public void skin(Skin skin) { this.skin = skin; }

    public void radius(double radius) {
        if (!Double.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("Invalid combat radius");
        }
        this.radius = radius;
    }

    public void movementSpeed(double speed) {
        if (!Double.isFinite(speed) || speed < 0) {
            throw new IllegalArgumentException("Invalid movement speed");
        }
        this.movementSpeed = speed;
    }

    public void targetFilter(Predicate<Player> targets) { this.targets = Objects.requireNonNull(targets, "targets"); }

    public void onDeath(Runnable handler) { this.deathHandler = Objects.requireNonNull(handler, "handler"); }

    /**
     * Uses the supplied entity settings and transfers movement, visibility, death and cleanup to the caller.
     * The default combat settings and library-managed lifecycle do not apply.
     */
    public void externalControl(BotEntity.Options options) { this.externalControl = Objects.requireNonNull(options); }

    public BotEntity.Options externalControl() { return this.externalControl; }

    /**
     * Enables respawning after the given number of server ticks.
     */
    public void respawnDelay(int ticks) {
        if (ticks < 0) throw new IllegalArgumentException("Invalid respawn delay");
        this.respawnDelay = ticks;
    }

    public String name() { return this.name; }
    public Skin skin() { return this.skin; }
    public double radius() { return this.radius; }
    public double movementSpeed() { return this.movementSpeed; }
    public Predicate<Player> targets() { return this.targets; }
    public int respawnDelay() { return this.respawnDelay; }
    public Runnable deathHandler() { return this.deathHandler; }
}
