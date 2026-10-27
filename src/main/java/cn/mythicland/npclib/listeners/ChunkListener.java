/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib.listeners;

import cn.mythicland.npclib.internal.NPCBase;
import cn.mythicland.npclib.internal.NPCManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.UUID;

/**
 * @author Jitse Boonstra
 */
public class ChunkListener implements Listener {

    public ChunkListener() {
    }

    private static int getChunkCoordinate(int coordinate) {
        return coordinate >> 4;
    }

    // Using Location#getChunk will load the chunk, which is a pretty hefty task, so
    // we'll want to avoid it.
    // Using Location#getChunk would effectively mean we'd load every chunk an NPC
    // is located in when any chunk is unloaded.
    static boolean isSameChunk(Location loc, Chunk chunk) {
        if (loc == null || loc.getWorld() == null || chunk == null) return false;
        if (!loc.getWorld().equals(chunk.getWorld())) return false;
        return getChunkCoordinate(loc.getBlockX()) == chunk.getX()
                && getChunkCoordinate(loc.getBlockZ()) == chunk.getZ();
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();

        for (NPCBase npc : NPCManager.getAllNPCs()) {
            if (npc.getLocation() == null || !isSameChunk(npc.getLocation(), chunk))
                continue; // We aren't unloading the chunk with the NPC in it.

            // We found an NPC in the chunk being unloaded. Time to hide this NPC from all
            // players.
            for (UUID uuid : npc.getShown()) {
                // Safety check so it doesn't send packets if the NPC has already
                // been automatically despawned by the system.
                if (npc.getAutoHidden().contains(uuid)) continue;

                // Bukkit.getPlayer(uuid) sometimes returns null
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    npc.hide(player, true);
                }
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();

        for (NPCBase npc : NPCManager.getAllNPCs()) {
            if (npc.getLocation() == null || !isSameChunk(npc.getLocation(), chunk))
                continue; // The NPC is not in the loaded chunk.

            // The chunk being loaded has this NPC in it. Showing it to all the players
            // again.
            for (UUID uuid : npc.getShown()) {
                // Make sure not to respawn a not-hidden NPC.
                if (!npc.getAutoHidden().contains(uuid)) continue;

                Player player = Bukkit.getPlayer(uuid);
                if (player == null)
                    continue; // Couldn't find the player, so skip.

                if (npc.inRangeOf(player) && npc.inViewOf(player)) {
                    npc.show(player, true);
                }
            }
        }
    }
}
