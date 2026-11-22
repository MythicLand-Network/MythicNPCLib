package cn.mythicland.npclib.bot;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface BotVisibility {
    boolean hasNameplate(UUID bot);
    void configure(UUID bot, BotVisibilitySettings config);
    void activity(Collection<? extends Player> players, long tick);
    void updateBot(
            BotHandle bot,
            Collection<? extends Player> players,
            long tick,
            UUID opponent,
            Set<UUID> combatViewers,
            BotVisibilitySettings config,
            String name,
            boolean hasEquipment
    );
    void flush(long tick);

    void revealNearby(BotHandle bot, Collection<? extends Player> players, long tick, BotVisibilitySettings config,
            String name, boolean hasEquipment, double radius);

    long spawnedAt(UUID viewer, UUID bot);

    void hideFrom(UUID viewer, BotHandle bot);

    boolean muteCombatSounds(UUID player, UUID bot, long tick);
    boolean isVisible(UUID player, UUID bot);

    boolean hasPendingReveal(UUID bot, long eligibleByTick);

    void restoreIdle(Player player, long tick, long idleTicks);
    void actionBot(BotHandle bot, int action);
    void destroyBot(BotHandle bot);
    void clear();
}
