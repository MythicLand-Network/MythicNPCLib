package cn.mythicland.npclib.bot;

import org.bukkit.entity.Player;

import java.util.random.RandomGenerator;

public final class BotAttack {
    private long nextAttack;
    private long lastClick;
    private int clicksRemaining;

    public BotAttack(long tick) {
        this.nextAttack = tick;
    }

    public void limitNextAttack(long tick) {
        this.nextAttack = Math.min(this.nextAttack, tick);
    }

    public boolean ready(long tick) {
        return tick >= this.nextAttack;
    }

    public boolean attack(
            BotHandle entity,
            Player opponent,
            long tick,
            double distance,
            double angle,
            int minimumTicks,
            int maximumTicks,
            RandomGenerator random
    ) {
        Player player = entity.getBukkitEntity();
        if (!this.ready(tick)) return false;
        if (player.getLocation().distanceSquared(opponent.getLocation()) > distance * distance) return false;
        if (!BotMovement.facing(
                player.getEyeLocation(),
                BotMovement.combatAim(player.getEyeLocation(), opponent.getEyeLocation()),
                angle
        )) return false;
        if (!player.hasLineOfSight(opponent)) return false;
        if (tick - this.lastClick > 10) {
            this.clicksRemaining = 0;
        }
        if (this.clicksRemaining == 0) {
            this.clicksRemaining = random.nextInt(4, 8);
        }
        this.lastClick = tick;
        this.clicksRemaining--;
        if (this.clicksRemaining == 0) {
            this.nextAttack = tick + random.nextInt(minimumTicks, maximumTicks + 1);
        } else {
            this.nextAttack = tick + random.nextInt(3, 6);
        }
        entity.hit(opponent);
        return true;
    }
}
