package cn.mythicland.npclib.bot.v1_8_R3;

import cn.mythicland.framework.task.StaggeredDeadline;
import cn.mythicland.npclib.bot.BotHandle;
import cn.mythicland.npclib.bot.BotVisibilitySettings;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Consumer;

public class BotVisibility implements cn.mythicland.npclib.bot.BotVisibility {
    private static final int PROFILE_REMOVE_DELAY_TICKS = 2;
    private static final int MAX_RELATIVE_MOVE_TICKS = 400;
    private static final int MAX_REVEALS_PER_TICK = 16;
    private final Map<UUID, BotVisibilitySettings> configurations = new HashMap<>();
    private final Map<UUID, Viewer> viewers = new LinkedHashMap<>();
    private final Map<UUID, Activity> activity = new HashMap<>();
    private final Map<UUID, EntityArmorStand> nameplates = new HashMap<>();
    private final Set<UUID> online = new HashSet<>();
    private final Consumer<Player> idleHidden;
    private long currentTick;
    private long revealTick = Long.MIN_VALUE;
    private int reveals, revealCursor;

    public BotVisibility() {
        this(player -> { });
    }

    public BotVisibility(Consumer<Player> idleHidden) {
        this.idleHidden = Objects.requireNonNull(idleHidden, "idleHidden");
    }

    private static double angle(double degrees) {
        return ((degrees + 180) % 360 + 360) % 360 - 180;
    }

    public final boolean hasNameplate(UUID bot) {
        return nameplates.containsKey(bot);
    }

    public void configure(UUID bot, BotVisibilitySettings config) {
        BotVisibilitySettings previous = configurations.put(bot, Objects.requireNonNull(config, "config"));
        if (config.equals(previous)) return;
        for (Viewer viewer : viewers.values()) {
            Display display = viewer.entities.get(bot);
            if (display != null) {
                display.checkedAt = Long.MIN_VALUE / 2;
            }
        }
    }

    public void activity(Collection<? extends Player> players, long tick) {
        currentTick = tick;
        online.clear();
        for (Player player : players) {
            UUID id = player.getUniqueId();
            online.add(id);
            Viewer viewer = viewers.get(id);
            if (viewer != null) {
                viewer.pending.clear();
                viewer.retiredTeams.entrySet().removeIf(entry -> {
                    if (tick < entry.getValue()) return false;
                    send(player, new PacketPlayOutScoreboardTeam(new Scoreboard().createTeam(entry.getKey()), 1));
                    return true;
                });
            }
            Location location = player.getLocation();
            Activity previous = activity.get(id);
            boolean moved = previous == null || !previous.location().getWorld().equals(location.getWorld())
                    || previous.location().distanceSquared(location) > 0.03 * 0.03
                    || Math.abs(angle(previous.location().getYaw() - location.getYaw())) > 0.5
                    || Math.abs(previous.location().getPitch() - location.getPitch()) > 0.5;
            if (moved) {
                activity.put(id, new Activity(location, tick));
            }
        }
        activity.keySet().retainAll(online);
        viewers.keySet().retainAll(online);
    }

    public void updateBot(
            BotHandle bot,
            Collection<? extends Player> players,
            long tick,
            UUID opponent,
            Set<UUID> combatViewers,
            BotVisibilitySettings config,
            String name,
            boolean hasEquipment
    ) {
        update((BotEntity) bot, players, tick, opponent, combatViewers, config, name, hasEquipment);
    }

    public void actionBot(BotHandle bot, int action) { action((BotEntity) bot, action); }

    public void destroyBot(BotHandle bot) { destroy((BotEntity) bot); }

    public void update(
            EntityPlayer bot,
            Collection<? extends Player> players,
            long tick,
            UUID opponent,
            Set<UUID> combatViewers,
            BotVisibilitySettings config,
            String name,
            boolean hasEquipment
    ) {
        configure(bot.getUniqueID(), config);
        EntityArmorStand nameplate = null;
        if (name != null) {
            nameplate = nameplates.computeIfAbsent(bot.getUniqueID(), id -> {
                EntityArmorStand stand = new EntityArmorStand(bot.world);
                stand.setInvisible(true);
                stand.setGravity(false);
                ((ArmorStand) stand.getBukkitEntity()).setMarker(true);
                stand.setCustomName(name);
                stand.setCustomNameVisible(true);
                return stand;
            });
        }
        if (nameplate != null) {
            nameplate.setPosition(bot.locX, bot.locY + 1.8, bot.locZ);
        }
        Update update = new Update(bot, nameplate, tick, config, hasEquipment);
        for (Player player : players) {
            Viewer viewer = viewers.computeIfAbsent(player.getUniqueId(), id -> new Viewer());
            boolean fighting = player.getUniqueId().equals(opponent) || combatViewers.contains(player.getUniqueId());
            Display display = viewer.entities.get(bot.getUniqueID());
            boolean shouldCheckVisibility = fighting || display == null || !display.spawned
                    || tick - display.checkedAt >= config.visibilityCheckTicks();
            boolean visible;
            if (shouldCheckVisibility) {
                visible = visible(player, bot, tick, fighting, config);
            } else {
                visible = display.visible;
            }
            if (display != null && tick - display.checkedAt >= config.visibilityCheckTicks()) {
                display.checkedAt = tick;
                display.visible = visible;
            }
            if (!visible) {
                if (display != null) {
                    hide(player, bot, viewer);
                }
                continue;
            }
            if (display == null) {
                display = new Display(tick);
                viewer.entities.put(bot.getUniqueID(), display);
            }
            if (!display.spawned && tick - display.revealedAt < config.spawnDelayTicks()) continue;
            if (!display.spawned && !fighting) {
                Display pending = display;
                double distance = player.getEyeLocation().distanceSquared(bot.getBukkitEntity().getLocation());
                viewer.pending.put(bot.getUniqueID(), new Reveal(bot.getUniqueID(), display, tick,
                        config.maxRevealsPerTick(), distance, () -> update.sync(player, viewer, pending)));
                continue;
            }
            viewer.pending.remove(bot.getUniqueID());
            update.sync(player, viewer, display);
        }
    }

    public void flush(long tick) {
        if (revealTick != tick) {
            revealTick = tick;
            reveals = 0;
        }
        List<Viewer> ordered = new ArrayList<>(viewers.values());
        List<Deque<Reveal>> queues = new ArrayList<>();
        for (Viewer viewer : ordered) {
            if (viewer.revealTick != tick) {
                viewer.revealTick = tick;
                viewer.reveals = 0;
            }
            List<Reveal> pending = new ArrayList<>(viewer.pending.values());
            pending.sort(Comparator.comparingLong((Reveal reveal) -> reveal.display.revealedAt)
                    .thenComparingDouble(Reveal::distance));
            queues.add(new ArrayDeque<>(pending));
            viewer.pending.clear();
        }
        int skipped = 0;
        while (reveals < MAX_REVEALS_PER_TICK && skipped < ordered.size()) {
            int index = Math.floorMod(revealCursor++, ordered.size());
            Viewer viewer = ordered.get(index);
            Reveal reveal = queues.get(index).pollFirst();
            if (reveal == null || viewer.reveals >= reveal.maxPerTick) {
                skipped++;
                continue;
            }
            if (reveal.tick != tick || viewer.entities.get(reveal.bot) != reveal.display || reveal.display.spawned) {
                continue;
            }
            reveal.send.run();
            viewer.reveals++;
            reveals++;
            skipped = 0;
        }
    }

    private boolean visible(
            Player player,
            EntityPlayer bot,
            long tick,
            boolean fighting,
            BotVisibilitySettings config
    ) {
        if (!player.getWorld().equals(bot.getBukkitEntity().getWorld())) return false;
        if (!player.getWorld().isChunkLoaded((int) Math.floor(bot.locX) >> 4, (int) Math.floor(bot.locZ) >> 4)) {
            return false;
        }
        if (!player.canSee(bot.getBukkitEntity())) return false;
        Location eye = player.getEyeLocation();
        double dx = bot.locX - eye.getX();
        double dy = bot.locY + bot.getHeadHeight() - eye.getY();
        double dz = bot.locZ - eye.getZ();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > config.viewDistance() * config.viewDistance()) return false;
        EntityPlayer viewer = ((CraftPlayer) player).getHandle();
        if (!viewer.u().getPlayerChunkMap().a(
                viewer, (int) Math.floor(bot.locX) >> 4, (int) Math.floor(bot.locZ) >> 4
        )) return false;
        if (fighting) return true;
        Activity current = activity.get(player.getUniqueId());
        if (eye.getY() > config.maxViewerY()) return false;
        if (current != null && tick - current.tick() >= config.idleSeconds() * 20L) {
            this.onIdleHidden(player);
            return false;
        }
        if (distanceSquared <= config.nearbyDistance() * config.nearbyDistance()) return true;
        double yaw = Math.toRadians(eye.getYaw());
        double pitch = Math.toRadians(eye.getPitch());
        double horizontal = Math.cos(pitch);
        double dot = (-Math.sin(yaw) * horizontal * dx - Math.sin(pitch) * dy
                + Math.cos(yaw) * horizontal * dz) / Math.sqrt(distanceSquared);
        if (dot < Math.cos(Math.toRadians(config.viewAngle()))) return false;
        return player.hasLineOfSight(bot.getBukkitEntity());
    }

    public void equipment(Player player, EntityPlayer bot) {
        for (int slot = 0; slot < 5; slot++)
            send(player, new PacketPlayOutEntityEquipment(bot.getId(), slot, bot.getEquipment(slot)));
    }

    public boolean muteCombatSounds(UUID player, UUID bot, long tick) {
        BotVisibilitySettings config = configurations.get(bot);
        if (config == null) return false;
        Activity current = activity.get(player);
        if (current == null || tick - current.tick() < config.idleSeconds() * 20) return false;
        Viewer viewer = viewers.get(player);
        Display display = viewer == null ? null : viewer.entities.get(bot);
        return display == null || !display.spawned;
    }

    public boolean isVisible(UUID player, UUID bot) {
        Viewer viewer = viewers.get(player);
        Display display = viewer == null ? null : viewer.entities.get(bot);
        return display != null && display.spawned;
    }

    protected void onIdleHidden(Player player) {
        this.idleHidden.accept(player);
    }

    public void restoreIdle(Player player, long tick, long idleTicks) {
        activity.put(player.getUniqueId(), new Activity(player.getLocation(), tick - idleTicks));
    }

    public void action(EntityPlayer bot, int action) {
        for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && isVisible(entry.getKey(), bot.getUniqueID())) {
                send(player, new PacketPlayOutAnimation(bot, action));
            }
        }
    }

    public void destroy(EntityPlayer bot) {
        for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && entry.getValue().entities.containsKey(bot.getUniqueID())) {
                hide(player, bot, entry.getValue());
            }
        }
        nameplates.remove(bot.getUniqueID());
        configurations.remove(bot.getUniqueID());
    }

    private void hide(Player player, EntityPlayer bot, Viewer viewer) {
        Display display = viewer.entities.remove(bot.getUniqueID());
        viewer.pending.remove(bot.getUniqueID());
        if (!display.spawned) return;
        EntityArmorStand nameplate = nameplates.get(bot.getUniqueID());
        send(player, new PacketPlayOutEntityDestroy(nameplate == null
                ? new int[]{bot.getId()} : new int[]{bot.getId(), nameplate.getId()}));
        if (display.profileRemoveAt > 0) {
            send(player, new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, bot));
        }
        if (display.team != null) {
            viewer.retiredTeams.put(display.team, currentTick + 2);
        }
    }

    private void send(Player player, Packet<?> packet) {
        ((CraftPlayer) player).getHandle().playerConnection.sendPacket(packet);
    }

    public void clear() {
        for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            for (String team : entry.getValue().retiredTeams.keySet()) {
                send(player, new PacketPlayOutScoreboardTeam(new Scoreboard().createTeam(team), 1));
            }
        }
        configurations.clear();
        viewers.clear();
        activity.clear();
        online.clear();
        nameplates.clear();
        revealTick = Long.MIN_VALUE;
        reveals = revealCursor = 0;
    }

    private record Reveal(
            UUID bot,
            Display display,
            long tick,
            int maxPerTick,
            double distance,
            Runnable send
    ) {
    }

    private static final class Viewer {
        private final Map<UUID, Display> entities = new HashMap<>();
        private final Map<UUID, Reveal> pending = new HashMap<>();
        private final Map<String, Long> retiredTeams = new HashMap<>();
        private long revealTick;
        private int reveals;
    }

    private static final class Display {
        private final long revealedAt;
        private long checkedAt;
        private boolean visible = true, spawned;
        private String team;
        private Movement location, nameplate;
        private long lastTeleportTick;
        private ItemStack[] equipment;
        private int hurtTicks;
        private long nextEquipmentCheck;
        private long profileRemoveAt;

        private Display(long tick) {
            revealedAt = tick;
            checkedAt = tick;
        }
    }

    private static final class Movement {
        private final int entityId, x, y, z;
        private final byte yaw, pitch;
        private final boolean onGround;
        private Packet<?> teleport, look, relative;
        private int relativeX, relativeY, relativeZ;
        private boolean relativeLook;

        private Movement(Entity entity) {
            entityId = entity.getId();
            x = MathHelper.floor(entity.locX * 32.0D);
            y = MathHelper.floor(entity.locY * 32.0D);
            z = MathHelper.floor(entity.locZ * 32.0D);
            yaw = (byte) (int) (entity.yaw * 256.0F / 360.0F);
            pitch = (byte) (int) (entity.pitch * 256.0F / 360.0F);
            onGround = entity.onGround;
        }

        private Packet<?> packet(Movement previous, boolean absolute) {
            if (previous == null) return teleport();
            long dx = (long) x - previous.x, dy = (long) y - previous.y, dz = (long) z - previous.z;
            boolean moved = dx != 0 || dy != 0 || dz != 0;
            boolean turned = yaw != previous.yaw || pitch != previous.pitch;
            if (!moved && !turned && onGround == previous.onGround) return null;
            if (absolute || onGround != previous.onGround || dx < -128 || dx > 127
                    || dy < -128 || dy > 127 || dz < -128 || dz > 127) return teleport();
            if (!moved) {
                if (look == null) {
                    look = new PacketPlayOutEntity.PacketPlayOutEntityLook(entityId, yaw, pitch, onGround);
                }
                return look;
            }
            if (relative == null || relativeX != previous.x || relativeY != previous.y
                    || relativeZ != previous.z || relativeLook != turned) {
                relativeX = previous.x;
                relativeY = previous.y;
                relativeZ = previous.z;
                relativeLook = turned;
                relative = turned
                        ? new PacketPlayOutEntity.PacketPlayOutRelEntityMoveLook(
                        entityId,
                        (byte) dx,
                        (byte) dy,
                        (byte) dz,
                        yaw,
                        pitch,
                        onGround
                )
                        : new PacketPlayOutEntity.PacketPlayOutRelEntityMove(
                        entityId,
                        (byte) dx,
                        (byte) dy,
                        (byte) dz,
                        onGround
                );
            }
            return relative;
        }

        private Packet<?> teleport() {
            if (teleport == null) {
                teleport = new PacketPlayOutEntityTeleport(entityId, x, y, z, yaw, pitch, onGround);
            }
            return teleport;
        }
    }

    private record Activity(Location location, long tick) {
    }

    private final class Update {
        private final EntityPlayer bot;
        private final EntityArmorStand nameplate;
        private final long tick;
        private final BotVisibilitySettings config;
        private final boolean hasEquipment;
        private final Movement movement, plateMovement;
        private final PacketPlayOutEntityMetadata metadata;
        private PacketPlayOutEntityHeadRotation rotation;
        private PacketPlayOutEntityStatus hurt;
        private ItemStack[] equipment;
        private PacketPlayOutEntityEquipment[] equipmentPackets;

        private Update(
                EntityPlayer bot,
                EntityArmorStand nameplate,
                long tick,
                BotVisibilitySettings config,
                boolean hasEquipment
        ) {
            this.bot = bot;
            this.nameplate = nameplate;
            this.tick = tick;
            this.config = config;
            this.hasEquipment = hasEquipment;
            movement = new Movement(bot);
            plateMovement = nameplate == null ? null : new Movement(nameplate);
            metadata = bot.getDataWatcher().a()
                    ? new PacketPlayOutEntityMetadata(bot.getId(), bot.getDataWatcher(), false) : null;
        }

        private void sync(Player player, Viewer viewer, Display display) {
            boolean initial = !display.spawned;
            if (!display.spawned) {
                send(player, new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, bot));
                display.team = "b" + bot.getUniqueID().toString().replace("-", "").substring(0, 15);
                viewer.retiredTeams.remove(display.team);
                Scoreboard scoreboard = new Scoreboard();
                ScoreboardTeam team = scoreboard.createTeam(display.team);
                team.setNameTagVisibility(ScoreboardTeamBase.EnumNameTagVisibility.NEVER);
                scoreboard.addPlayerToTeam(bot.getProfile().getName(), display.team);
                send(player, new PacketPlayOutScoreboardTeam(team, 0));
                send(player, new PacketPlayOutNamedEntitySpawn(bot));
                if (nameplate != null) {
                    send(player, new PacketPlayOutSpawnEntityLiving(nameplate));
                }
                display.spawned = true;
                display.profileRemoveAt = tick + PROFILE_REMOVE_DELAY_TICKS;
                display.location = movement;
                display.nameplate = plateMovement;
                display.lastTeleportTick = tick;
                if (hasEquipment) {
                    ItemStack[] snapshot = equipment();
                    for (int slot = 0; slot < 5; slot++) {
                        if (snapshot[slot] != null) {
                            send(player, equipmentPacket(slot));
                        }
                    }
                    display.equipment = snapshot;
                    display.nextEquipmentCheck =
                            StaggeredDeadline.after(tick, -bot.getId(), config.equipmentCheckTicks());
                }
            }
            if (display.profileRemoveAt > 0 && tick >= display.profileRemoveAt) {
                send(player,
                        new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, bot));
                display.profileRemoveAt = 0;
            }
            if (metadata != null && !initial) {
                send(player, metadata);
            }
            boolean absolute = tick - display.lastTeleportTick >= MAX_RELATIVE_MOVE_TICKS;
            Packet<?> packet = movement.packet(display.location, absolute);
            if (packet != null) {
                send(player, packet);
                if (packet instanceof PacketPlayOutEntityTeleport) {
                    display.lastTeleportTick = tick;
                }
            }
            if (initial || display.location.yaw != movement.yaw) {
                if (rotation == null) {
                    rotation = new PacketPlayOutEntityHeadRotation(bot, movement.yaw);
                }
                send(player, rotation);
            }
            if (packet != null) {
                display.location = movement;
            }
            if (plateMovement != null) {
                Packet<?> platePacket = plateMovement.packet(display.nameplate, absolute);
                if (platePacket != null) {
                    send(player, platePacket);
                    display.nameplate = plateMovement;
                }
            }
            if (hasEquipment && tick >= display.nextEquipmentCheck) {
                display.nextEquipmentCheck = StaggeredDeadline.after(tick, -bot.getId(), config.equipmentCheckTicks());
                boolean changed = false;
                for (int slot = 0; slot < 5; slot++) {
                    ItemStack current = equipment == null ? bot.getEquipment(slot) : equipment[slot];
                    if (ItemStack.matches(display.equipment == null ? null : display.equipment[slot], current)) {
                        continue;
                    }
                    send(player, equipmentPacket(slot));
                    changed = true;
                }
                if (changed) {
                    display.equipment = equipment();
                }
            }
            if (bot.hurtTicks > display.hurtTicks) {
                if (hurt == null) {
                    hurt = new PacketPlayOutEntityStatus(bot, (byte) 2);
                }
                send(player, hurt);
            }
            display.hurtTicks = bot.hurtTicks;
        }

        private ItemStack[] equipment() {
            if (equipment != null) return equipment;
            equipment = new ItemStack[5];
            for (int slot = 0; slot < equipment.length; slot++) {
                ItemStack item = bot.getEquipment(slot);
                equipment[slot] = item == null ? null : item.cloneItemStack();
            }
            return equipment;
        }

        private PacketPlayOutEntityEquipment equipmentPacket(int slot) {
            if (equipmentPackets == null) {
                equipmentPackets = new PacketPlayOutEntityEquipment[5];
            }
            if (equipmentPackets[slot] == null) {
                equipmentPackets[slot] = new PacketPlayOutEntityEquipment(bot.getId(), slot, equipment()[slot]);
            }
            return equipmentPackets[slot];
        }
    }
}
