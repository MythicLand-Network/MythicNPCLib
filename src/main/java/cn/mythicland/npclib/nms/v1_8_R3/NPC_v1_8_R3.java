/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib.nms.v1_8_R3;

import cn.mythicland.npclib.NPCLib;
import cn.mythicland.npclib.api.skin.Skin;
import cn.mythicland.npclib.api.state.NPCAnimation;
import cn.mythicland.npclib.api.state.NPCSlot;
import cn.mythicland.npclib.hologram.Hologram;
import cn.mythicland.npclib.internal.MinecraftVersion;
import cn.mythicland.npclib.internal.NPCBase;
import cn.mythicland.npclib.nms.v1_8_R3.packets.*;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_8_R3.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * @author Jitse Boonstra
 */
public class NPC_v1_8_R3 extends NPCBase {
    private static final long PROFILE_REMOVE_DELAY_TICKS = 20L;
    private final Set<UUID> profileViewers = new HashSet<>();
    private final Map<UUID, Long> profileGenerations = new HashMap<>();
    private long nextProfileGeneration;
    private final Villager.Profession villagerProfession;
    private PacketPlayOutSpawnEntityLiving packetPlayOutVillagerSpawn;
    private PacketPlayOutNamedEntitySpawn packetPlayOutNamedEntitySpawn;
    private PacketPlayOutScoreboardTeam packetPlayOutScoreboardTeamRegister;
    private PacketPlayOutPlayerInfo packetPlayOutPlayerInfoAdd, packetPlayOutPlayerInfoRemove;
    private PacketPlayOutEntityHeadRotation packetPlayOutEntityHeadRotation;
    private PacketPlayOutEntityDestroy packetPlayOutEntityDestroy;

    public NPC_v1_8_R3(NPCLib instance, List<String> lines) {
        this(instance, lines, null);
    }

    private NPC_v1_8_R3(NPCLib instance, List<String> lines, Villager.Profession villagerProfession) {
        super(instance, lines);
        this.villagerProfession = villagerProfession;
    }

    public static NPC_v1_8_R3 villager(NPCLib instance, List<String> lines, Villager.Profession profession) {
        return new NPC_v1_8_R3(instance, lines, Objects.requireNonNull(profession));
    }

    @Override
    public Hologram getHologram(Player player) {
        Hologram holo = super.getHologram(player);
        if (holo == null) {
            holo = new Hologram(
                    MinecraftVersion.V1_8_R3,
                    location.clone().add(0, 0.5, 0),
                    getText(player)
            );
        }
        super.playerHologram.put(player.getUniqueId(), holo);
        return holo;
    }

    @Override
    public void createPackets() {
        createPackets(null);
    }

    @Override
    public void createPackets(Player player) {

        if (this.villagerProfession != null) {
            this.packetPlayOutVillagerSpawn = this.createVillagerSpawnPacket();
            this.packetPlayOutEntityHeadRotation = new PacketPlayOutEntityHeadRotationWrapper().create(location, entityId);
            this.packetPlayOutEntityDestroy = new PacketPlayOutEntityDestroy(entityId);
            return;
        }

        PacketPlayOutPlayerInfoWrapper packetPlayOutPlayerInfoWrapper = new PacketPlayOutPlayerInfoWrapper();

        // Packets for spawning the NPC:
        this.packetPlayOutScoreboardTeamRegister =
                new PacketPlayOutScoreboardTeamWrapper().createRegisterTeam(name); // First
        // packet
        // to
        // send.

        this.packetPlayOutPlayerInfoAdd = packetPlayOutPlayerInfoWrapper.create(
                PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER,
                gameProfile,
                name
        ); // Second packet to send.

        this.packetPlayOutNamedEntitySpawn = new PacketPlayOutNamedEntitySpawnWrapper().create(
                uuid,
                location,
                entityId
        ); // Third packet to send.

        this.packetPlayOutEntityHeadRotation = new PacketPlayOutEntityHeadRotationWrapper().create(
                location,
                entityId
        ); // Fourth packet to send.

        this.packetPlayOutPlayerInfoRemove = packetPlayOutPlayerInfoWrapper.create(
                PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER,
                gameProfile,
                name
        );

        // Packet for destroying the NPC:
        this.packetPlayOutEntityDestroy = new PacketPlayOutEntityDestroy(entityId); // First packet to send.
    }

    @Override
    public void sendShowPackets(Player player) {
        PlayerConnection playerConnection = ((CraftPlayer) player).getHandle().playerConnection;
        if (this.villagerProfession != null) {
            playerConnection.sendPacket(this.packetPlayOutVillagerSpawn);
            playerConnection.sendPacket(this.packetPlayOutEntityHeadRotation);
            getHologram(player).show(player);
            return;
        }
        UUID viewerUniqueId = player.getUniqueId();
        long profileGeneration = ++this.nextProfileGeneration;
        this.profileGenerations.put(viewerUniqueId, profileGeneration);
        this.profileViewers.add(viewerUniqueId);

        if (hasTeamRegistered.add(player.getUniqueId()))
            playerConnection.sendPacket(packetPlayOutScoreboardTeamRegister);
        playerConnection.sendPacket(packetPlayOutPlayerInfoAdd);
        playerConnection.sendPacket(packetPlayOutNamedEntitySpawn);
        playerConnection.sendPacket(packetPlayOutEntityHeadRotation);

        getHologram(player).show(player);

        Bukkit.getScheduler().runTaskLater(
                instance.getPlugin(),
                () -> {
                    if (!player.isOnline()) {
                        this.removeProfileState(viewerUniqueId, profileGeneration);
                        return;
                    }
                    if (!this.isCurrentProfile(viewerUniqueId, profileGeneration)) return;
                    this.removeProfile(player);
                },
                PROFILE_REMOVE_DELAY_TICKS
        );
    }

    @Override
    public void teleport(Location destination) {
        if (!isCreated()) throw new IllegalStateException("NPC must be created before teleporting: " + getId());
        if (!getWorld().equals(destination.getWorld())) {
            throw new IllegalArgumentException("NPC teleport must remain in the same world: " + getId());
        }
        this.location = destination.clone();
        if (this.villagerProfession != null) {
            this.packetPlayOutVillagerSpawn = this.createVillagerSpawnPacket();
        } else {
            this.packetPlayOutNamedEntitySpawn = new PacketPlayOutNamedEntitySpawnWrapper().create(uuid, location, entityId);
        }
        this.packetPlayOutEntityHeadRotation = new PacketPlayOutEntityHeadRotationWrapper().create(location, entityId);
        PacketPlayOutEntityTeleport packet = new PacketPlayOutEntityTeleport(
                entityId,
                MathHelper.floor(location.getX() * 32.0D),
                MathHelper.floor(location.getY() * 32.0D),
                MathHelper.floor(location.getZ() * 32.0D),
                (byte) (location.getYaw() * 256.0F / 360.0F),
                (byte) (location.getPitch() * 256.0F / 360.0F),
                false
        );
        for (Map.Entry<UUID, Hologram> entry : playerHologram.entrySet()) {
            Hologram hologram = entry.getValue();
            if (hologram == null) continue;
            List<Object> packets = hologram.move(location.clone().add(0, 0.5, 0));
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null && isShown(viewer)) hologram.update(viewer, packets);
        }
        for (UUID viewerId : getShown()) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !isShown(viewer)) continue;
            PlayerConnection connection = ((CraftPlayer) viewer).getHandle().playerConnection;
            connection.sendPacket(packet);
            connection.sendPacket(packetPlayOutEntityHeadRotation);
        }
    }

    @Override
    public void sendHidePackets(Player player, boolean auto) {
        PlayerConnection playerConnection = ((CraftPlayer) player).getHandle().playerConnection;

        playerConnection.sendPacket(packetPlayOutEntityDestroy);

        getHologram(player).hide(player);

        this.removeProfile(player);
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

        if (slot == NPCSlot.OFFHAND) {
            if (!auto) throw new UnsupportedOperationException("Offhand is not supported on servers below 1.9");
            return;
        }

        ItemStack item = getItem(slot);

        PacketPlayOutEntityEquipment packet = new PacketPlayOutEntityEquipment(
                entityId,
                slot.getSlot(),
                CraftItemStack.asNMSCopy(item)
        );
        playerConnection.sendPacket(packet);
    }

    @Override
    public void sendAnimationPacket(Player player, NPCAnimation animation) {
        if (animation == NPCAnimation.SWING_OFFHAND) {
            throw new IllegalArgumentException("Offhand Swing Animations are only available on 1.9 and up.");
        }

        PlayerConnection playerConnection = ((CraftPlayer) player).getHandle().playerConnection;

        PacketPlayOutAnimation packet = new PacketPlayOutAnimationWrapper().create(animation, entityId);
        playerConnection.sendPacket(packet);
    }

    @Override
    public void playHurtFeedback() {
        super.playHurtFeedback();

        Location npcLocation = getLocation();
        if (npcLocation == null || npcLocation.getWorld() == null) return;
        PacketPlayOutNamedSoundEffect packet = new PacketPlayOutNamedSoundEffect(
                "game.player.hurt",
                npcLocation.getX(),
                npcLocation.getY(),
                npcLocation.getZ(),
                1.0F,
                1.0F
        );
        for (UUID shownUuid : getShown()) {
            Player player = Bukkit.getPlayer(shownUuid);
            if (player == null || !isShown(player)) continue;
            ((CraftPlayer) player).getHandle().playerConnection.sendPacket(packet);
        }
    }

    @Override
    public void updateSkin(Skin skin) {
        this.setSkin(skin);
        this.createPackets();
        for (UUID shownUuid : getShown()) {
            Player player = Bukkit.getPlayer(shownUuid);
            if (player == null || !isShown(player)) continue;
            sendHidePackets(player, false);
            sendShowPackets(player);
            sendMetadataPacket(player);
            sendEquipmentPackets(player);
        }
    }

    @Override
    public void onLogout(Player player) {
        super.onLogout(player);
        this.removeProfileState(player.getUniqueId());
    }

    @Override
    public void destroy() {
        Set<UUID> viewers = new HashSet<>(this.profileViewers);
        super.destroy();
        for (UUID viewerUniqueId : viewers) {
            Player player = Bukkit.getPlayer(viewerUniqueId);
            if (player == null || !player.isOnline()) continue;
            this.removeProfile(player);
        }
        this.profileViewers.clear();
        this.profileGenerations.clear();
    }

    private void removeProfile(Player player) {
        if (!this.profileViewers.remove(player.getUniqueId())) return;
        this.profileGenerations.remove(player.getUniqueId());
        PlayerConnection playerConnection = ((CraftPlayer) player).getHandle().playerConnection;
        playerConnection.sendPacket(packetPlayOutPlayerInfoRemove);
    }

    private PacketPlayOutSpawnEntityLiving createVillagerSpawnPacket() {
        EntityVillager villager = new EntityVillager(((CraftWorld) location.getWorld()).getHandle());
        villager.d(entityId);
        villager.setProfession(this.villagerProfession.getId());
        villager.setPositionRotation(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        villager.aK = location.getYaw();
        return new PacketPlayOutSpawnEntityLiving(villager);
    }

    private boolean isCurrentProfile(UUID viewerUniqueId, long profileGeneration) {
        return Long.valueOf(profileGeneration).equals(this.profileGenerations.get(viewerUniqueId));
    }

    private void removeProfileState(UUID viewerUniqueId, long profileGeneration) {
        if (!this.isCurrentProfile(viewerUniqueId, profileGeneration)) return;
        this.removeProfileState(viewerUniqueId);
    }

    private void removeProfileState(UUID viewerUniqueId) {
        this.profileGenerations.remove(viewerUniqueId);
        this.profileViewers.remove(viewerUniqueId);
    }

    @Override
    public void sendHeadRotationPackets(Location location) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerConnection connection = ((CraftPlayer) player).getHandle().playerConnection;

            Location npcLocation = getLocation();
            Vector dirBetweenLocations = location.toVector().subtract(npcLocation.toVector());

            npcLocation.setDirection(dirBetweenLocations);

            float yaw = npcLocation.getYaw();
            float pitch = npcLocation.getPitch();

            connection.sendPacket(
                    new PacketPlayOutEntity.PacketPlayOutEntityLook(
                            getEntityId(),
                            (byte) ((yaw % 360.) * 256 / 360),
                            (byte) ((pitch % 360.) * 256 / 360),
                            false
                    )
            );
            connection.sendPacket(
                    new PacketPlayOutEntityHeadRotationWrapper().create(npcLocation, entityId)
            );
        }
    }
}
