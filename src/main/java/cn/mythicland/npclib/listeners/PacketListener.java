/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib.listeners;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import cn.mythicland.npclib.NPCLib;
import cn.mythicland.npclib.api.events.NPCInteractEvent;
import cn.mythicland.npclib.internal.NPCBase;
import cn.mythicland.npclib.internal.NPCManager;
import cn.mythicland.framework.bukkit.reflect.Reflection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

/**
 * @author Jitse Boonstra
 */
public class PacketListener {

    // Classes:
    private final Class<?> packetPlayInUseEntityClazz = Reflection.getMinecraftClass("PacketPlayInUseEntity");

    // Fields:
    private final Reflection.FieldAccessor<Integer> entityIdField =
            Reflection.getField(packetPlayInUseEntityClazz, "a", int.class);
    private final Reflection.FieldAccessor<?> actionField =
            Reflection.getField(packetPlayInUseEntityClazz, "action", Object.class);

    // Prevent players from clicking at very high speeds.
    private final ConcurrentMap<UUID, Boolean> delay = new ConcurrentHashMap<>();

    private volatile NPCLib instance;
    private Plugin plugin;
    private AsyncListenerHandler asyncListenerHandler;

    public void start(NPCLib instance) {
        this.instance = instance;
        this.plugin = instance.getPlugin();
        this.asyncListenerHandler = ProtocolLibrary.getProtocolManager().getAsynchronousManager()
                .registerAsyncHandler(
                        new PacketAdapter(instance.getPlugin(), PacketType.Play.Client.USE_ENTITY) {
                            @Override
                            public void onPacketReceiving(PacketEvent event) {
                                handleInteractPacket(event.getPlayer(), event.getPacket().getHandle());
                            }
                        }
                );
        this.asyncListenerHandler.start();

    }

    public void stop() {
        this.instance = null;
        this.delay.clear();
        if (this.asyncListenerHandler == null) return;
        try {
            ProtocolLibrary.getProtocolManager().getAsynchronousManager().unregisterAsyncHandler(
                    this.asyncListenerHandler.getAsyncListener()
            );
        } catch (RuntimeException exception) {
            this.plugin.getLogger().log(Level.WARNING, "Unable to unregister NPC packet listener", exception);
        }
        try {
            this.asyncListenerHandler.stop();
        } catch (RuntimeException exception) {
            this.plugin.getLogger().log(Level.WARNING, "Unable to stop NPC packet listener", exception);
        } finally {
            this.asyncListenerHandler = null;
            this.plugin = null;
        }
    }

    private void handleInteractPacket(Player player, Object packet) {
        int packetEntityId = entityIdField.get(packet);
        NPCBase npc = NPCManager.getByEntityId(packetEntityId);
        NPCLib owner = this.instance;
        if (owner == null || npc == null || npc.getInstance() != owner) return;

        // Not using streams here is an intentional choice.
        // Packet listeners is one of the few places where it is important to write
        // optimized code.
        // Lambdas (And the stream api) create a massive amount of objects, especially
        // if it isn't a static lambda.
        // So, we're avoiding them here.
        // ~ Kneesnap, 9 / 20 / 2019.

        if (!npc.isCreated() || !npc.isShown(player)) {
            // Default player, not doing magic with the packet.
            return;
        }

        if (this.delay.putIfAbsent(player.getUniqueId(), Boolean.TRUE) != null) { // There is an active delay.
            return;
        }

        NPCInteractEvent.ClickType clickType = actionField.get(packet).toString().equals("ATTACK")
                ? NPCInteractEvent.ClickType.LEFT_CLICK
                : NPCInteractEvent.ClickType.RIGHT_CLICK;

        Bukkit.getScheduler().runTask(
                owner.getPlugin(),
                () -> this.callNpcInteractEvent(owner, new NPCInteractEvent(player, clickType, npc))
        );
    }

    private void callNpcInteractEvent(NPCLib owner, NPCInteractEvent eventToCall) {
        Player player = eventToCall.getWhoClicked();
        this.delay.remove(player.getUniqueId());
        NPCBase npc = (NPCBase) eventToCall.getNPC();
        if (this.instance != owner || !npc.isCreated() || !npc.isShown(player)) return;
        if (!player.getWorld().equals(eventToCall.getNPC().getWorld())) return;

        Location playerLocation = new Location(
                null,
                0,
                0,
                0
        );
        double distance = player.getLocation(playerLocation).distanceSquared(eventToCall.getNPC().getLocation());
        if (distance <= 64)
            Bukkit.getPluginManager().callEvent(eventToCall);
    }
}
