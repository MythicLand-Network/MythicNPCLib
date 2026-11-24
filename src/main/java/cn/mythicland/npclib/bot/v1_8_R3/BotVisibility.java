package cn.mythicland.npclib.bot.v1_8_R3;

import cn.mythicland.framework.task.StaggeredDeadline;
import cn.mythicland.npclib.bot.BotHandle;
import cn.mythicland.npclib.bot.BotVisibilitySettings;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Consumer;

public class BotVisibility implements cn.mythicland.npclib.bot.BotVisibility {
    private static final int PROFILE_REMOVE_DELAY_TICKS = 2;
    private static final int MAX_RELATIVE_MOVE_TICKS = 400;
    private static final int MAX_REVEALS_PER_TICK = 16;
    private final Map<UUID, BotAudience> audiences = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<UUID, Viewer> viewers = new Object2ObjectLinkedOpenHashMap<>();
    private final List<Viewer> onlineViewers = new ArrayList<>();
    private final Map<UUID, Activity> activity = new Object2ObjectOpenHashMap<>();
    private final Map<UUID, EntityArmorStand> nameplates = new Object2ObjectOpenHashMap<>();
    private final Set<UUID> online = new ObjectOpenHashSet<>();
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
        this.configureAudience(bot, config);
    }

    private BotAudience configureAudience(UUID bot, BotVisibilitySettings config) {
        Objects.requireNonNull(config, "config");
        BotAudience audience = this.audiences.get(bot);
        if (audience == null) {
            audience = new BotAudience(config);
            this.audiences.put(bot, audience);
        } else if (!config.equals(audience.config)) {
            audience.config = config;
            for (int i = 0; i < audience.displays.size(); i++) {
                audience.displays.get(i).nextVisibilityCheck = Long.MIN_VALUE;
            }
        }
        return audience;
    }

    public void activity(Collection<? extends Player> players, long tick) {
        currentTick = tick;
        online.clear();
        this.onlineViewers.clear();
        Map<String, PacketPlayOutScoreboardTeam> removedTeams = null;
        for (Player player : players) {
            UUID id = player.getUniqueId();
            online.add(id);
            Viewer viewer = viewers.get(id);
            this.onlineViewers.add(viewer);
            if (viewer != null) {
                viewer.pending.clear();
                var retired = viewer.retiredTeams.object2LongEntrySet().fastIterator();
                while (retired.hasNext()) {
                    var entry = retired.next();
                    if (tick < entry.getLongValue()) continue;
                    if (removedTeams == null) removedTeams = new Object2ObjectOpenHashMap<>();
                    PacketPlayOutScoreboardTeam packet = removedTeams.computeIfAbsent(
                            entry.getKey(),
                            name -> new PacketPlayOutScoreboardTeam(new Scoreboard().createTeam(name), 1)
                    );
                    send(player, packet);
                    retired.remove();
                }
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
        var iterator = this.viewers.values().iterator();
        while (iterator.hasNext()) {
            Viewer viewer = iterator.next();
            if (this.online.contains(viewer.playerId)) continue;
            for (var entry : viewer.entities.entrySet()) {
                this.untrack(this.audiences.get(entry.getKey()).displays, entry.getValue());
            }
            iterator.remove();
        }
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

    @Override
    public void revealNearby(
            BotHandle bot,
            Collection<? extends Player> players,
            long tick,
            BotVisibilitySettings config,
            String name,
            boolean hasEquipment,
            double radius
    ) {
        this.update((BotEntity) bot, players, tick, null, Set.of(), config, name, hasEquipment, radius);
    }

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
        this.update(bot, players, tick, opponent, combatViewers, config, name, hasEquipment, 0);
    }

    private void update(
            EntityPlayer bot,
            Collection<? extends Player> players,
            long tick,
            UUID opponent,
            Set<UUID> combatViewers,
            BotVisibilitySettings config,
            String name,
            boolean hasEquipment,
            double immediateRadius
    ) {
        UUID botId = bot.getUniqueID();
        BotAudience audience = this.configureAudience(botId, config);
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
        boolean immediate = immediateRadius > 0;
        int playerIndex = 0;
        for (Player player : players) {
            int index = playerIndex++;
            if (immediate) {
                if (!update.world.equals(player.getWorld())) continue;
                EntityPlayer handle = ((CraftPlayer) player).getHandle();
                if (handle.h(bot) > immediateRadius * immediateRadius) continue;
            }
            UUID playerId = player.getUniqueId();
            Viewer viewer = this.viewerAt(index, playerId);
            boolean fighting = playerId.equals(opponent) || combatViewers.contains(playerId);
            Display display = viewer.entities.get(botId);
            boolean shouldCheckVisibility = fighting || display == null || !display.spawned
                    || tick >= display.nextVisibilityCheck;
            if (shouldCheckVisibility) {
                int phaseOffset = playerId.hashCode() + bot.getId();
                int interval = config.visibilityCheckTicks();
                boolean checkSight = immediate || Math.floorMod(tick + phaseOffset, interval) == 0;
                boolean clearSight = display != null && display.clearSight;
                Visibility visibility = this.visibility(player, update, fighting, checkSight, clearSight);
                if (visibility == Visibility.HIDDEN) {
                    if (display != null) this.hide(player, bot, audience, display);
                    continue;
                }
                if (display == null) {
                    display = new Display(tick, viewer, audience.displays.size());
                    audience.displays.add(display);
                    viewer.entities.put(botId, display);
                }
                display.clearSight = visibility == Visibility.CLEAR_SIGHT;
                display.nextVisibilityCheck = StaggeredDeadline.after(tick, phaseOffset, interval);
            }
            if (!immediate && !display.spawned && tick - display.revealedAt < config.spawnDelayTicks()) continue;
            if (!immediate && !display.spawned && !fighting) {
                Display pending = display;
                EntityPlayer handle = ((CraftPlayer) player).getHandle();
                double dx = handle.locX - bot.locX;
                double dy = handle.locY + player.getEyeHeight() - bot.locY;
                double dz = handle.locZ - bot.locZ;
                double distance = dx * dx + dy * dy + dz * dz;
                Reveal previous = viewer.pending.get(botId);
                long order;
                if (previous == null) order = viewer.nextRevealOrder++;
                else order = previous.order();
                viewer.pending.put(botId, new Reveal(botId, display, tick, config.maxRevealsPerTick(), distance,
                        order, () -> update.sync(player, viewer, pending)));
                continue;
            }
            if (!display.spawned) viewer.pending.remove(botId);
            update.sync(player, viewer, display);
        }
    }

    private Viewer viewerAt(int index, UUID playerId) {
        Viewer viewer = null;
        if (index < this.onlineViewers.size()) viewer = this.onlineViewers.get(index);
        if (viewer != null && viewer.playerId.equals(playerId)) return viewer;
        return this.viewers.computeIfAbsent(playerId, Viewer::new);
    }

    public void flush(long tick) {
        if (revealTick != tick) {
            revealTick = tick;
            reveals = 0;
        }
        List<Viewer> ordered = new ArrayList<>(viewers.values());
        List<Queue<Reveal>> queues = new ArrayList<>(ordered.size());
        for (Viewer viewer : ordered) {
            if (viewer.revealTick != tick) {
                viewer.revealTick = tick;
                viewer.reveals = 0;
            }
            if (viewer.pending.isEmpty()) {
                queues.add(null);
                continue;
            }
            queues.add(new PriorityQueue<>(viewer.pending.values()));
            viewer.pending.clear();
        }
        int skipped = 0;
        while (reveals < MAX_REVEALS_PER_TICK && skipped < ordered.size()) {
            int index = Math.floorMod(revealCursor++, ordered.size());
            Viewer viewer = ordered.get(index);
            Queue<Reveal> queue = queues.get(index);
            Reveal reveal = null;
            if (queue != null) reveal = queue.poll();
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

    private Visibility visibility(
            Player player,
            Update update,
            boolean fighting,
            boolean checkSight,
            boolean clearSight
    ) {
        if (!update.chunkLoaded || !Objects.equals(player.getWorld(), update.world)) return Visibility.HIDDEN;
        EntityPlayer bot = update.bot;
        BotVisibilitySettings config = update.config;
        EntityPlayer viewer = ((CraftPlayer) player).getHandle();
        double eyeY = viewer.locY + player.getEyeHeight();
        double dx = bot.locX - viewer.locX;
        double dy = bot.locY + bot.getHeadHeight() - eyeY;
        double dz = bot.locZ - viewer.locZ;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > config.viewDistance() * config.viewDistance()) return Visibility.HIDDEN;
        if (!player.canSee(update.body)) return Visibility.HIDDEN;
        if (!viewer.u().getPlayerChunkMap().a(viewer, update.chunkX, update.chunkZ)) return Visibility.HIDDEN;
        if (fighting) return Visibility.VISIBLE;
        Activity current = activity.get(player.getUniqueId());
        if (eyeY > config.maxViewerY()) return Visibility.HIDDEN;
        if (current != null && update.tick - current.tick() >= config.idleSeconds() * 20L) {
            this.onIdleHidden(player);
            return Visibility.HIDDEN;
        }
        if (distanceSquared <= config.nearbyDistance() * config.nearbyDistance()) return Visibility.VISIBLE;
        double yaw = Math.toRadians(viewer.yaw);
        double pitch = Math.toRadians(viewer.pitch);
        double horizontal = Math.cos(pitch);
        double dot = (-Math.sin(yaw) * horizontal * dx - Math.sin(pitch) * dy
                + Math.cos(yaw) * horizontal * dz) / Math.sqrt(distanceSquared);
        if (dot < Math.cos(Math.toRadians(config.viewAngle()))) return Visibility.HIDDEN;
        if (checkSight) clearSight = player.hasLineOfSight(update.body);
        if (clearSight) return Visibility.CLEAR_SIGHT;
        return Visibility.HIDDEN;
    }

    public void equipment(Player player, EntityPlayer bot) {
        for (int slot = 0; slot < 5; slot++)
            send(player, new PacketPlayOutEntityEquipment(bot.getId(), slot, bot.getEquipment(slot)));
    }

    public boolean muteCombatSounds(UUID player, UUID bot, long tick) {
        BotAudience audience = this.audiences.get(bot);
        if (audience == null) return false;
        Activity current = activity.get(player);
        if (current == null || tick - current.tick() < audience.config.idleSeconds() * 20) return false;
        Viewer viewer = viewers.get(player);
        Display display = viewer == null ? null : viewer.entities.get(bot);
        return display == null || !display.spawned;
    }

    public boolean isVisible(UUID player, UUID bot) {
        Viewer viewer = viewers.get(player);
        Display display = viewer == null ? null : viewer.entities.get(bot);
        return display != null && display.spawned;
    }

    @Override
    public long spawnedAt(UUID player, UUID bot) {
        Viewer viewer = this.viewers.get(player);
        if (viewer == null) return -1;
        Display display = viewer.entities.get(bot);
        if (display == null || !display.spawned) return -1;
        return display.spawnedAt;
    }

    @Override
    public long beginRemoval(BotHandle handle, long tick, int minimumVisibleTicks, Set<UUID> visibleViewers) {
        BotEntity bot = (BotEntity) handle;
        UUID botId = bot.getUniqueID();
        BotAudience audience = this.audiences.get(botId);
        if (audience == null || audience.displays.isEmpty()) return tick;
        ObjectArrayList<Display> displays = audience.displays;

        RemovalPackets packets = new RemovalPackets(bot, this.nameplates.get(botId));
        long lastVisibleTick = tick;
        for (int i = displays.size() - 1; i >= 0; i--) {
            Display display = displays.get(i);
            Viewer viewer = display.viewer;
            Player player = Bukkit.getPlayer(viewer.playerId);
            if (player != null && display.spawned) {
                visibleViewers.add(viewer.playerId);
                long hideAt = display.spawnedAt + minimumVisibleTicks;
                if (hideAt > tick) {
                    lastVisibleTick = Math.max(lastVisibleTick, hideAt);
                    continue;
                }
            }
            this.untrack(displays, display);
            this.removeDisplay(player, botId, display, packets);
        }
        return lastVisibleTick;
    }

    @Override
    public void hideFrom(UUID playerId, BotHandle bot) {
        Viewer viewer = this.viewers.get(playerId);
        if (viewer == null) return;
        Display display = viewer.entities.get(bot.getUniqueID());
        if (display == null) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) this.hide(player, (BotEntity) bot, this.audiences.get(bot.getUniqueID()), display);
    }

    protected void onIdleHidden(Player player) {
        this.idleHidden.accept(player);
    }

    @Override
    @SuppressWarnings("ForLoopReplaceableByForEach") // Indexed access avoids iterator allocation in tick paths.
    public boolean hasPendingReveal(UUID bot, long eligibleByTick) {
        BotAudience audience = this.audiences.get(bot);
        if (audience == null) return false;
        ObjectArrayList<Display> displays = audience.displays;
        for (int i = 0; i < displays.size(); i++) {
            Display display = displays.get(i);
            if (!display.spawned && display.revealedAt <= eligibleByTick) return true;
        }
        return false;
    }

    public void restoreIdle(Player player, long tick, long idleTicks) {
        activity.put(player.getUniqueId(), new Activity(player.getLocation(), tick - idleTicks));
    }

    @SuppressWarnings("ForLoopReplaceableByForEach") // Indexed access avoids iterator allocation in tick paths.
    public void action(EntityPlayer bot, int action) {
        UUID botId = bot.getUniqueID();
        BotAudience audience = this.audiences.get(botId);
        if (audience == null) return;
        ObjectArrayList<Display> displays = audience.displays;
        PacketPlayOutAnimation animation = null;
        for (int i = 0; i < displays.size(); i++) {
            Display display = displays.get(i);
            if (!display.spawned) continue;
            Player player = Bukkit.getPlayer(display.viewer.playerId);
            if (player == null) continue;
            if (animation == null) animation = new PacketPlayOutAnimation(bot, action);
            send(player, animation);
        }
    }

    @SuppressWarnings("ForLoopReplaceableByForEach") // Indexed access avoids iterator allocation in mass removal.
    public void destroy(EntityPlayer bot) {
        UUID botId = bot.getUniqueID();
        BotAudience audience = this.audiences.remove(botId);
        if (audience != null && !audience.displays.isEmpty()) {
            ObjectArrayList<Display> displays = audience.displays;
            RemovalPackets packets = new RemovalPackets(bot, this.nameplates.get(botId));
            for (int i = 0; i < displays.size(); i++) {
                Display display = displays.get(i);
                this.removeDisplay(Bukkit.getPlayer(display.viewer.playerId), botId, display, packets);
            }
        }
        nameplates.remove(botId);
    }

    private void hide(Player player, EntityPlayer bot, BotAudience audience, Display display) {
        UUID botId = bot.getUniqueID();
        this.untrack(audience.displays, display);
        this.removeDisplay(player, botId, display, new RemovalPackets(bot, this.nameplates.get(botId)));
    }

    private void untrack(ObjectArrayList<Display> displays, Display display) {
        // Viewer order has no effect on removal. Fill the gap without shifting other displays.
        Display last = displays.removeLast();
        if (last == display) return;
        last.botDisplayIndex = display.botDisplayIndex;
        displays.set(last.botDisplayIndex, last);
    }

    private void removeDisplay(Player player, UUID botId, Display display, RemovalPackets packets) {
        Viewer viewer = display.viewer;
        viewer.entities.remove(botId);
        if (!display.spawned) {
            viewer.pending.remove(botId);
            return;
        }
        if (player == null) return;
        this.send(player, packets.destroy());
        if (display.profileRemoveAt > 0) {
            this.send(player, packets.removeProfile());
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
        this.audiences.clear();
        viewers.clear();
        this.onlineViewers.clear();
        activity.clear();
        online.clear();
        nameplates.clear();
        revealTick = Long.MIN_VALUE;
        reveals = revealCursor = 0;
    }

    private static final class BotAudience {
        private final ObjectArrayList<Display> displays = new ObjectArrayList<>();
        private BotVisibilitySettings config;

        private BotAudience(BotVisibilitySettings config) {
            this.config = config;
        }
    }

    private static final class RemovalPackets {
        private final EntityPlayer bot;
        private final EntityArmorStand nameplate;
        private PacketPlayOutEntityDestroy destroy;
        private PacketPlayOutPlayerInfo removeProfile;

        private RemovalPackets(EntityPlayer bot, EntityArmorStand nameplate) {
            this.bot = bot;
            this.nameplate = nameplate;
        }

        private PacketPlayOutEntityDestroy destroy() {
            if (this.destroy == null) {
                int id = this.bot.getId();
                if (this.nameplate == null) {
                    this.destroy = new PacketPlayOutEntityDestroy(id);
                } else {
                    this.destroy = new PacketPlayOutEntityDestroy(id, this.nameplate.getId());
                }
            }
            return this.destroy;
        }

        private PacketPlayOutPlayerInfo removeProfile() {
            if (this.removeProfile == null) {
                this.removeProfile = new PacketPlayOutPlayerInfo(
                        PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, this.bot
                );
            }
            return this.removeProfile;
        }
    }

    private record Reveal(
            UUID bot,
            Display display,
            long tick,
            int maxPerTick,
            double distance,
            long order,
            Runnable send
    ) implements Comparable<Reveal> {
        private static final Comparator<Reveal> PRIORITY = Comparator
                .comparingLong((Reveal reveal) -> reveal.display.revealedAt)
                .thenComparingDouble(Reveal::distance)
                .thenComparingLong(Reveal::order);

        @Override
        @SuppressWarnings("NullableProblems") // Comparable already requires a non-null argument.
        public int compareTo(Reveal other) {
            return PRIORITY.compare(this, other);
        }
    }

    private static final class Viewer {
        private final UUID playerId;
        private final Map<UUID, Display> entities = new Object2ObjectOpenHashMap<>();
        private final Map<UUID, Reveal> pending = new Object2ObjectLinkedOpenHashMap<>();
        private final Object2LongOpenHashMap<String> retiredTeams = new Object2LongOpenHashMap<>();
        private long nextRevealOrder;
        private long revealTick;
        private int reveals;

        private Viewer(UUID playerId) {
            this.playerId = playerId;
        }
    }

    private enum Visibility {
        HIDDEN, VISIBLE, CLEAR_SIGHT
    }

    private static final class Display {
        private final long revealedAt;
        private final Viewer viewer;
        private int botDisplayIndex;
        private long spawnedAt;
        private long nextVisibilityCheck;
        private boolean clearSight, spawned;
        private String team;
        private Movement location, nameplate;
        private long lastTeleportTick;
        private ItemStack[] equipment;
        private int hurtTicks;
        private long nextEquipmentCheck;
        private long profileRemoveAt;

        private Display(long tick, Viewer viewer, int botDisplayIndex) {
            this.revealedAt = tick;
            this.viewer = viewer;
            this.botDisplayIndex = botDisplayIndex;
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
        private final Player body;
        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private final boolean chunkLoaded;
        private final EntityArmorStand nameplate;
        private final long tick;
        private final BotVisibilitySettings config;
        private final boolean hasEquipment;
        private final Movement movement, plateMovement;
        private final PacketPlayOutEntityMetadata metadata;
        private PacketPlayOutEntityHeadRotation rotation;
        private PacketPlayOutEntityStatus hurt;
        private PacketPlayOutPlayerInfo addProfile;
        private PacketPlayOutPlayerInfo removeProfile;
        private PacketPlayOutScoreboardTeam createTeam;
        private PacketPlayOutNamedEntitySpawn spawn;
        private PacketPlayOutSpawnEntityLiving spawnNameplate;
        private String teamName;
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
            this.body = bot.getBukkitEntity();
            this.world = this.body.getWorld();
            this.chunkX = Location.locToBlock(bot.locX) >> 4;
            this.chunkZ = Location.locToBlock(bot.locZ) >> 4;
            this.chunkLoaded = this.world.isChunkLoaded(this.chunkX, this.chunkZ);
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
                if (this.spawn == null) {
                    this.addProfile = new PacketPlayOutPlayerInfo(
                            PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, bot
                    );
                    this.teamName = "b" + bot.getUniqueID().toString().replace("-", "").substring(0, 15);
                    Scoreboard scoreboard = new Scoreboard();
                    ScoreboardTeam team = scoreboard.createTeam(this.teamName);
                    team.setNameTagVisibility(ScoreboardTeamBase.EnumNameTagVisibility.NEVER);
                    scoreboard.addPlayerToTeam(bot.getProfile().getName(), this.teamName);
                    this.createTeam = new PacketPlayOutScoreboardTeam(team, 0);
                    this.spawn = new PacketPlayOutNamedEntitySpawn(bot);
                    if (nameplate != null) this.spawnNameplate = new PacketPlayOutSpawnEntityLiving(nameplate);
                }
                display.team = this.teamName;
                viewer.retiredTeams.removeLong(display.team);
                send(player, this.addProfile);
                send(player, this.createTeam);
                send(player, this.spawn);
                if (nameplate != null) {
                    send(player, this.spawnNameplate);
                }
                display.spawned = true;
                display.spawnedAt = this.tick;
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
                if (this.removeProfile == null) {
                    this.removeProfile = new PacketPlayOutPlayerInfo(
                            PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, bot
                    );
                }
                send(player, this.removeProfile);
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
