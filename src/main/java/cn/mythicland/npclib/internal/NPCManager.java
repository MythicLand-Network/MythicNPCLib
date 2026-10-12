/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib.internal;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Jitse Boonstra
 */
public final class NPCManager {

    private static final Set<NPCBase> npcs = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Integer, NPCBase> npcsByEntityId = new ConcurrentHashMap<>();
    private static final AtomicInteger nextEntityId = new AtomicInteger(Integer.MAX_VALUE);

    private NPCManager() {
        throw new SecurityException("You cannot initialize this class.");
    }

    public static Set<NPCBase> getAllNPCs() {
        return npcs;
    }

    static int allocateEntityId() {
        return nextEntityId.getAndDecrement();
    }

    public static NPCBase getByEntityId(int entityId) {
        return npcsByEntityId.get(entityId);
    }

    public static Set<NPCBase> getShownToPlayer(Player player) {
        Set<NPCBase> set = new ObjectOpenHashSet<>();
        for (NPCBase npc : getAllNPCs()) {
            if (npc.getShown().contains(player.getUniqueId())) {
                set.add(npc);
            }
        }
        return set;
    }

    public static void add(NPCBase npc) {
        npcs.add(npc);
        npcsByEntityId.put(npc.getEntityId(), npc);
    }

    public static void remove(NPCBase npc) {
        npcs.remove(npc);
        npcsByEntityId.remove(npc.getEntityId());
    }

}
