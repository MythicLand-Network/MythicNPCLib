/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib.listeners;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
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
    private PacketAdapter packetAdapter;

    public void start(NPCLib instance) {
        this.instance = instance;
        this.packetAdapter = new PacketAdapter(instance.getPlugin(), ListenerPriority.MONITOR, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) return;
                handleInteractPacket(event.getPlayer(), event.getPacket().getHandle());
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(this.packetAdapter);
    }

    public void stop() {
        this.instance = null;
        this.delay.clear();
        PacketAdapter listener = this.packetAdapter;
        if (listener == null) return;
        this.packetAdapter = null;
        try {
            ProtocolLibrary.getProtocolManager().removePacketListener(listener);
        } catch (RuntimeException exception) {
            listener.getPlugin().getLogger().log(Level.WARNING, "Unable to unregister NPC packet listener", exception);
        }
    }

    private void handleInteractPacket(Player player, Object packet) {
        int packetEntityId = entityIdField.get(packet);
        NPCBase npc = NPCManager.getByEntityId(packetEntityId);
        NPCLib owner = this.instance;
        if (owner == null || npc == null || npc.getInstance() != owner) return;

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
