/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib.nms.v1_12_R1;

import cn.mythicland.npclib.NPCLib;
import cn.mythicland.npclib.api.skin.Skin;
import cn.mythicland.npclib.api.state.NPCAnimation;
import cn.mythicland.npclib.api.state.NPCSlot;
import cn.mythicland.npclib.hologram.Hologram;
import cn.mythicland.npclib.internal.MinecraftVersion;
import cn.mythicland.npclib.internal.NPCBase;
import cn.mythicland.npclib.nms.v1_12_R1.packets.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.v1_12_R1.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_12_R1.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_12_R1.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * @author Jitse Boonstra
 */
public class NPC_v1_12_R1 extends NPCBase {
    private static final long PROFILE_REMOVE_DELAY_TICKS = 40L;
    private final Set<UUID> profileViewers = new HashSet<>();
    private final Map<UUID, Long> profileGenerations = new HashMap<>();
    private long nextProfileGeneration;

    private PacketPlayOutNamedEntitySpawn packetPlayOutNamedEntitySpawn;
    private PacketPlayOutScoreboardTeam packetPlayOutScoreboardTeamRegister;
    private PacketPlayOutPlayerInfo packetPlayOutPlayerInfoAdd, packetPlayOutPlayerInfoRemove;
    private PacketPlayOutEntityHeadRotation packetPlayOutEntityHeadRotation;
    private PacketPlayOutEntityDestroy packetPlayOutEntityDestroy;

    public NPC_v1_12_R1(NPCLib instance, List<String> lines) {
        super(instance, lines);
    }

    @Override
    public Hologram getHologram(Player player) {
        Hologram holo = super.getHologram(player);
        if (holo == null) {
            holo = new Hologram(MinecraftVersion.V1_12_R1, location.clone().add(0, 0.5, 0), getText(player));
        }
        super.playerHologram.put(player.getUniqueId(), holo);
        return holo;
    }

    @Override
    public void createPackets() {
        PacketPlayOutPlayerInfoWrapper packetPlayOutPlayerInfoWrapper = new PacketPlayOutPlayerInfoWrapper();

        // Packets for spawning the NPC:
        this.packetPlayOutScoreboardTeamRegister = new PacketPlayOutScoreboardTeamWrapper()
                .createRegisterTeam(name); // First packet to send.

        this.packetPlayOutPlayerInfoAdd = packetPlayOutPlayerInfoWrapper
                .create(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, gameProfile,
                        name); // Second packet to send.

        this.packetPlayOutNamedEntitySpawn = new PacketPlayOutNamedEntitySpawnWrapper()
                .create(uuid, location, entityId); // Third packet to send.

        this.packetPlayOutEntityHeadRotation = new PacketPlayOutEntityHeadRotationWrapper()
                .create(location, entityId); // Fourth packet to send.

        this.packetPlayOutPlayerInfoRemove = packetPlayOutPlayerInfoWrapper
                .create(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, gameProfile,
                        name); // Used when hiding or destroying the NPC.

        // Packet for destroying the NPC:
        this.packetPlayOutEntityDestroy = new PacketPlayOutEntityDestroy(entityId); // First packet to send.
    }

    @Override
    public void createPackets(Player player) {
        createPackets();
    }

    @Override
    public void sendShowPackets(Player player) {
        PlayerConnection playerConnection = ((CraftPlayer) player).getHandle().playerConnection;
        UUID viewerId = player.getUniqueId();
        long generation = ++this.nextProfileGeneration;
        this.profileGenerations.put(viewerId, generation);
        this.profileViewers.add(viewerId);

        if (hasTeamRegistered.add(player.getUniqueId()))
            playerConnection.sendPacket(packetPlayOutScoreboardTeamRegister);
        playerConnection.sendPacket(packetPlayOutPlayerInfoAdd);
        playerConnection.sendPacket(packetPlayOutNamedEntitySpawn);
        playerConnection.sendPacket(packetPlayOutEntityHeadRotation);
        getHologram(player).show(player);
        Bukkit.getScheduler().runTaskLater(instance.getPlugin(), () -> {
            if (!Long.valueOf(generation).equals(this.profileGenerations.get(viewerId))) return;
            if (!player.isOnline()) {
                removeProfileState(viewerId);
                return;
            }
            removeProfile(player);
        }, PROFILE_REMOVE_DELAY_TICKS);
    }

    @Override
    public void teleport(Location destination) {
        if (!isCreated()) throw new IllegalStateException("NPC must be created before teleporting: " + getId());
        if (!getWorld().equals(destination.getWorld()))
            throw new IllegalArgumentException("NPC teleport must remain in the same world: " + getId());
        var viewers = List.copyOf(getShown());
        for (UUID id : viewers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && isShown(player)) sendHidePackets(player, false);
        }
        this.location = destination.clone();
        for (Hologram hologram : playerHologram.values()) {
            if (hologram != null) hologram.move(location.clone().add(0, 0.5, 0));
        }
        createPackets();
        for (UUID id : viewers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && isShown(player)) {
                sendShowPackets(player);
                sendMetadataPacket(player);
                sendEquipmentPackets(player);
            }
        }
    }

    @Override
    public void sendHidePackets(Player player, boolean auto) {
        PlayerConnection playerConnection = ((CraftPlayer) player).getHandle().playerConnection;

        playerConnection.sendPacket(packetPlayOutEntityDestroy);
        removeProfile(player);

        getHologram(player).hide(player);
    }

    @Override
    public void sendMetadataPacket(Player player) {
        PlayerConnection playerConnection = ((CraftPlayer) player).getHandle().playerConnection;
        PacketPlayOutEntityMetadata packet = new PacketPlayOutEntityMetadataWrapper().create(activeStates, entityId);

        playerConnection.sendPacket(packet);
    }

    @Override
    public void sendEquipmentPacket(Player player, NPCSlot slot, boolean auto) {
        PlayerConnection playerConnection = ((CraftPlayer) player).getHandle().playerConnection;

        EnumItemSlot nmsSlot = slot.getNmsEnum(EnumItemSlot.class);
        ItemStack item = getItem(slot);

        PacketPlayOutEntityEquipment packet =
                new PacketPlayOutEntityEquipment(entityId, nmsSlot, CraftItemStack.asNMSCopy(item));
        playerConnection.sendPacket(packet);
    }

    @Override
    public void sendAnimationPacket(Player player, NPCAnimation animation) {
        PlayerConnection playerConnection = ((CraftPlayer) player).getHandle().playerConnection;

        PacketPlayOutAnimation packet = new PacketPlayOutAnimationWrapper().create(animation, entityId);
        playerConnection.sendPacket(packet);
    }

    @Override
    public void updateSkin(Skin skin) {
        this.setSkin(skin);
        this.createPackets();
        GameProfile newProfile = new GameProfile(uuid, name);
        newProfile.getProperties().get("textures").clear();
        newProfile.getProperties().put("textures", new Property("textures", skin.value(), skin.signature()));
        this.packetPlayOutPlayerInfoAdd =
                new PacketPlayOutPlayerInfoWrapper().create(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER,
                        newProfile, name);

        for (UUID shownUuid : super.getShown()) {
            Player player = Bukkit.getPlayer(shownUuid);
            if (player != null && isShown(player)) {
                sendHidePackets(player, false);
                sendShowPackets(player);
                sendMetadataPacket(player);
                sendEquipmentPackets(player);
            }
        }
    }

    @Override
    public void onLogout(Player player) {
        super.onLogout(player);
        removeProfileState(player.getUniqueId());
    }

    @Override
    public void destroy() {
        Set<UUID> viewers = new HashSet<>(this.profileViewers);
        super.destroy();
        for (UUID viewerId : viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) removeProfile(viewer);
        }
        this.profileViewers.clear();
        this.profileGenerations.clear();
    }

    private void removeProfile(Player player) {
        if (!this.profileViewers.remove(player.getUniqueId())) return;
        this.profileGenerations.remove(player.getUniqueId());
        ((CraftPlayer) player).getHandle().playerConnection.sendPacket(packetPlayOutPlayerInfoRemove);
    }

    private void removeProfileState(UUID viewerId) {
        this.profileViewers.remove(viewerId);
        this.profileGenerations.remove(viewerId);
    }

    @Override
    public void sendHeadRotationPackets(Location location) {
        for (UUID shownUuid : super.getShown()) {
            Player player = Bukkit.getPlayer(shownUuid);
            if (player != null && isShown(player)) {
                PlayerConnection connection = ((CraftPlayer) player).getHandle().playerConnection;

                Location npcLocation = getLocation();
                Vector direction = location.toVector().subtract(npcLocation.toVector());
                npcLocation.setDirection(direction);

                float yaw = npcLocation.getYaw();
                float pitch = npcLocation.getPitch();
                connection.sendPacket(new PacketPlayOutEntity.PacketPlayOutEntityLook(
                        getEntityId(),
                        (byte) ((yaw % 360.0F) * 256.0F / 360.0F),
                        (byte) ((pitch % 360.0F) * 256.0F / 360.0F),
                        false
                ));
                connection.sendPacket(new PacketPlayOutEntityHeadRotationWrapper().create(npcLocation, entityId));
            }
        }
    }
}
