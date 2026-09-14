package cn.mythicland.npclib.listeners;

import cn.mythicland.npclib.NPCLib;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

public final class PeriodicMoveListener extends HandleMoveBase implements Listener {
    private final BukkitTask task;

    public PeriodicMoveListener(NPCLib instance, long updateInterval) {
        this.task = Bukkit.getScheduler().runTaskTimer(
                instance.getPlugin(),
                () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        handleMove(player);
                    }
                },
                1L,
                updateInterval
        );
    }

    public void shutdown() {
        this.task.cancel();
    }
}
