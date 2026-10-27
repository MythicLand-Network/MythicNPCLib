package cn.mythicland.npclib.bot;

import java.util.HashMap;
import java.util.Map;

public final class BotSpawnQueue {
    private final Map<String, Long> next = new HashMap<>();

    public boolean available(String center, long tick) {
        Long availableAt = next.get(center);
        return availableAt == null || tick >= availableAt || tick == availableAt - 10;
    }

    public void claim(String center, long tick) {
        if (!available(center, tick)) throw new IllegalStateException("Spawn slot is not available");
        next.put(center, tick + 10);
    }

    public void clear() {
        next.clear();
    }
}
