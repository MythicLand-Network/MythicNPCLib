package cn.mythicland.npclib.bot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BotRoster<T> {
    private final Map<UUID, T> members = new LinkedHashMap<>();
    private final Map<UUID, T> view = Collections.unmodifiableMap(this.members);
    private List<T> snapshot = List.of();
    private boolean changed;

    public Map<UUID, T> members() {
        return this.view;
    }

    public void put(UUID id, T member) {
        this.members.put(id, member);
        this.changed = true;
    }

    public T remove(UUID id) {
        T removed = this.members.remove(id);
        if (removed != null) {
            this.changed = true;
        }
        return removed;
    }

    public List<T> snapshot() {
        if (this.changed) {
            this.snapshot = List.copyOf(this.members.values());
            this.changed = false;
        }
        return this.snapshot;
    }
}
