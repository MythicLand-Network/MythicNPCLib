package cn.mythicland.npclib.bot;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * A caller-owned or library-managed robot entity.
 */
public interface BotHandle {
    int getId();

    UUID getUniqueID();

    Player getBukkitEntity();

    double x();

    double y();

    double z();

    float yaw();

    float pitch();

    void yaw(float yaw);

    void pitch(float pitch);

    boolean onGround();

    void collidable(boolean collidable);

    /**
     * Members of the same nonnull group do not physically push each other. Other collisions are unchanged.
     */
    void collisionGroup(Object group);

    /**
     * Keep the entity addressable by late attack packets without intercepting projectiles or pushing actors.
     */
    void retireForLateMelee();

    void clearArrows();

    void tickState();

    void step(float strafe, float forward, double speed, boolean jump);

    void hit(org.bukkit.entity.Entity opponent);

    void transfer(Location location);

    void destroy();
}
