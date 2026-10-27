/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib;

import cn.mythicland.npclib.NPCLibOptions.MovementHandling;
import cn.mythicland.npclib.api.NPC;
import cn.mythicland.npclib.api.PacketNpc;
import cn.mythicland.npclib.api.PacketNpcOptions;
import cn.mythicland.npclib.api.utilities.Logger;
import cn.mythicland.npclib.bot.FighterRobot;
import cn.mythicland.npclib.bot.FighterRobotOptions;
import cn.mythicland.npclib.bot.PatrolRobot;
import cn.mythicland.npclib.bot.PatrolRobotOptions;
import cn.mythicland.npclib.bot.v1_8_R3.BotEntity;
import cn.mythicland.npclib.internal.NPCBase;
import cn.mythicland.npclib.listeners.*;
import cn.mythicland.npclib.nms.v1_8_R3.NPC_v1_8_R3;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class NPCLib {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final List<Listener> listeners;
    private final Set<NPC> ownedNpcs;
    private final Set<PacketNpc> automaticNpcs;
    private final PacketListener packetListener;
    private BotManager botManager;

    private double autoHideDistance = 50.0;

    private NPCLib(JavaPlugin plugin, MovementHandling moveHandling) {
        this.plugin = plugin;
        this.logger = new Logger("NPCLib");
        this.listeners = new ArrayList<>();
        this.ownedNpcs = new LinkedHashSet<>();
        this.automaticNpcs = new LinkedHashSet<>();

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
     * Creates a packet NPC. Automatic visibility is disabled unless requested.
     */
    public PacketNpc createPacketNpc(Location location) {
        return createPacketNpc(location, options -> { });
    }

    public PacketNpc createPacketNpc(Location location, Consumer<PacketNpcOptions> configure) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(location.getWorld(), "location world");
        PacketNpcOptions options = new PacketNpcOptions();
        Objects.requireNonNull(configure, "configure").accept(options);
        NPC npc;
        if (options.profession() == null) {
            npc = new NPC_v1_8_R3(this, options.text());
        } else {
            npc = NPC_v1_8_R3.villager(this, options.text(), options.profession());
        }
        this.ownedNpcs.add(npc);
        try {
            npc.setLocation(location.clone());
            npc.setSkin(options.skin());
            npc.create();
            PacketNpc packetNpc = (PacketNpc) npc;
            if (options.autoShow()) {
                this.automaticNpcs.add(packetNpc);
                for (Player player : this.plugin.getServer().getOnlinePlayers()) {
                    packetNpc.show(player);
                }
            }
            return packetNpc;
        } catch (RuntimeException failure) {
            npc.destroy();
            throw failure;
        }
    }

    public PatrolRobot createPatrolRobot(Location location) {
        return createPatrolRobot(location, options -> { });
    }

    public PatrolRobot createPatrolRobot(Location location, Consumer<PatrolRobotOptions> configure) {
        PatrolRobotOptions options = new PatrolRobotOptions(location);
        Objects.requireNonNull(configure, "configure").accept(options);
        if (options.externalControl() != null) {
            return new ExternalPatrolRobot(BotEntity.create(location, options.externalControl()));
        }
        return bots().createPatrolRobot(location, options);
    }

    public FighterRobot createFighterRobot(Location location) {
        return createFighterRobot(location, options -> { });
    }

    public FighterRobot createFighterRobot(Location location, Consumer<FighterRobotOptions> configure) {
        FighterRobotOptions options = new FighterRobotOptions();
        Objects.requireNonNull(configure, "configure").accept(options);
        if (options.externalControl() != null) {
            return new ExternalFighterRobot(BotEntity.create(location, options.externalControl()));
        }
        return bots().createFighterRobot(location, options);
    }

    /** Registers automatically shown NPCs for a player who has joined the server. */
    public void showAutomaticNpcs(Player player) {
        for (PacketNpc npc : List.copyOf(this.automaticNpcs)) {
            if (((NPCBase) npc).getShown().contains(player.getUniqueId())) continue;
            npc.show(player);
        }
    }

    public boolean hasAutomaticNpcs() {
        return !this.automaticNpcs.isEmpty();
    }

    /** Removes a destroyed NPC from this instance's ownership lists. */
    public void forgetNPC(NPC npc) {
        this.ownedNpcs.remove(npc);
        this.automaticNpcs.remove(npc);
    }

    /**
     * Returns the shared, automatically scheduled bot manager owned by this library instance.
     */
    private BotManager bots() {
        if (this.botManager == null) {
            this.botManager = new BotManager(this.plugin);
        }
        return this.botManager;
    }

    public void shutdown() {
        if (this.botManager != null) {
            this.botManager.close();
            this.botManager = null;
        }
        for (NPC npc : new ArrayList<>(this.ownedNpcs)) {
            npc.destroy();
        }
        this.ownedNpcs.clear();
        this.automaticNpcs.clear();
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

    private abstract static class ExternalRobot implements AutoCloseable {
        private final BotEntity entity;

        private ExternalRobot(BotEntity entity) { this.entity = entity; }

        public BotEntity entity() { return this.entity; }

        @Override
        public void close() { this.entity.destroy(); }
    }

    private static final class ExternalPatrolRobot extends ExternalRobot implements PatrolRobot {
        private ExternalPatrolRobot(BotEntity entity) { super(entity); }
    }

    private static final class ExternalFighterRobot extends ExternalRobot implements FighterRobot {
        private ExternalFighterRobot(BotEntity entity) { super(entity); }
    }
}
