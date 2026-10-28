package cn.mythicland.npclib.bot;

import cn.mythicland.npclib.api.skin.Skin;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.BiConsumer;

public record BotOptions(
        String name,
        String displayName,
        Skin skin,
        boolean walking,
        DeathHandler deathHandler,
        BiConsumer<BotHandle, Runnable> stepHandler
) {
    public BotOptions {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(deathHandler, "deathHandler");
        Objects.requireNonNull(stepHandler, "stepHandler");
    }

    @FunctionalInterface
    public interface DeathHandler {
        void onDeath(BotHandle bot, Player attacker, boolean outOfWorld);
    }
}
