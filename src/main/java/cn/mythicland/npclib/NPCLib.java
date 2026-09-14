/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib;

import cn.mythicland.npclib.NPCLibOptions.MovementHandling;
import cn.mythicland.npclib.api.NPC;
import cn.mythicland.npclib.api.utilities.Logger;
import cn.mythicland.npclib.listeners.*;
import cn.mythicland.npclib.nms.v1_8_R3.NPC_v1_8_R3;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class NPCLib {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Class<?> npcClass;
    private final List<Listener> listeners;
    private final List<NPC> ownedNpcs;
    private final PacketListener packetListener;

    private double autoHideDistance = 50.0;

    private NPCLib(JavaPlugin plugin, MovementHandling moveHandling) {
        this.plugin = plugin;
        this.logger = new Logger("NPCLib");
        this.listeners = new ArrayList<>();
        this.ownedNpcs = new ArrayList<>();

        this.npcClass = NPC_v1_8_R3.class;

        PluginManager pluginManager = plugin.getServer().getPluginManager();

        this.registerListener(pluginManager, new PlayerListener(this));
        this.registerListener(pluginManager, new ChunkListener());

        if (moveHandling.usePme) {
            this.registerListener(pluginManager, new PlayerMoveEventListener());
        } else {
            this.registerListener(pluginManager, new PeriodicMoveListener(this, moveHandling.updateInterval));
        }

        // Boot the according packet listener.
        this.packetListener = new PacketListener();
        this.packetListener.start(this);

        // Start the bStats metrics system and disable the silly relocate check.
    }

    public NPCLib(JavaPlugin plugin) {
        this(plugin, MovementHandling.playerMoveEvent());
    }

    public NPCLib(JavaPlugin plugin, NPCLibOptions options) {
        this(plugin, options.moveHandling);
    }

    /**
     * @return The JavaPlugin instance.
     */
    public JavaPlugin getPlugin() {
        return plugin;
    }

    /**
     * @return The auto-hide distance.
     */
    public double getAutoHideDistance() {
        return autoHideDistance;
    }

    /**
     * Set a new value for the auto-hide distance. A recommended value (and default)
     * is 50 blocks.
     *
     * @param autoHideDistance The new value.
     */
    public void setAutoHideDistance(double autoHideDistance) {
        this.autoHideDistance = autoHideDistance;
    }

    /**
     * @return The logger NPCLib uses.
     */
    public Logger getLogger() {
        return logger;
    }

    /**
     * Create a new non-player character (NPC).
     *
     * @param text The text you want to sendShowPackets above the NPC (null = no
     *             text).
     * @return The NPC object you may use to sendShowPackets it to players.
     */
    public NPC createNPC(List<String> text) {
        try {
            NPC npc = (NPC) npcClass.getConstructors()[0].newInstance(this, text);
            this.ownedNpcs.add(npc);
            return npc;
        } catch (Exception exception) {
            logger.warning("Failed to create NPC. Please report the following stacktrace message", exception);
        }

        return null;
    }

    /**
     * Create a new non-player character (NPC).
     *
     * @return The NPC object you may use to sendShowPackets it to players.
     */
    public NPC createNPC() {
        return createNPC(null);
    }

    public void shutdown() {
        for (NPC npc : new ArrayList<>(this.ownedNpcs)) {
            npc.destroy();
        }
        this.ownedNpcs.clear();
        for (Listener listener : listeners) {
            if (listener instanceof PeriodicMoveListener periodicMoveListener) {
                periodicMoveListener.shutdown();
            }
            HandlerList.unregisterAll(listener);
        }
        listeners.clear();
        packetListener.stop();
    }

    private void registerListener(PluginManager pluginManager, Listener listener) {
        pluginManager.registerEvents(listener, plugin);
        listeners.add(listener);
    }
}
