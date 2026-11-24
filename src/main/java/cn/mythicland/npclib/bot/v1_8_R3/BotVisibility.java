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
        this(
                player -> {}
        );
    }

    public BotVisibility(Consumer<Player> idleHidden) {
        this.idleHidden = Objects.requireNonNull(idleHidden, "idleHidden");
    }

    private static double angle(double degrees) {
        return ((degrees + 180) % 360 + 360) % 360 - 180;
    }

    public final boolean hasNameplate(UUID bot) {
        return this.nameplates.containsKey(bot);
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
        this.currentTick = tick;
        this.online.clear();
        this.onlineViewers.clear();
        Map<String, PacketPlayOutScoreboardTeam> removedTeams = null;
        for (Player player : players) {
            UUID id = player.getUniqueId();
            this.online.add(id);
            Viewer viewer = this.viewers.get(id);
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
                    this.send(player, packet);
                    retired.remove();
                }
            }
            Location location = player.getLocation();
            Activity previous = this.activity.get(id);
            boolean moved = previous == null || !previous.location().getWorld().equals(location.getWorld())
                    || previous.location().distanceSquared(location) > 0.03 * 0.03
                    || Math.abs(angle(previous.location().getYaw() - location.getYaw())) > 0.5
                    || Math.abs(previous.location().getPitch() - location.getPitch()) > 0.5;
            if (moved) {
                this.activity.put(id, new Activity(location, tick));
            }
        }
        this.activity.keySet().retainAll(this.online);
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
        this.update((BotEntity) bot, players, tick, opponent, combatViewers, config, name, hasEquipment);
    }

    public void actionBot(BotHandle bot, int action) {
        this.action((BotEntity) bot, action);
    }

    public void destroyBot(BotHandle bot) {
        this.destroy((BotEntity) bot);
    }

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
            nameplate = this.nameplates.computeIfAbsent(
                    bot.getUniqueID(), id -> {
                        EntityArmorStand stand = new EntityArmorStand(bot.world);
                        stand.setInvisible(true);
                        stand.setGravity(false);
                        ((ArmorStand) stand.getBukkitEntity()).setMarker(true);
                        stand.setCustomName(name);
                        stand.setCustomNameVisible(true);
                        return stand;
                    }
            );
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
                Visibility visibility = this.visibility(player, viewer, update, fighting, checkSight, clearSight);
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
                viewer.pending.put(
                        botId, new Reveal(
                                botId, display, tick, config.maxRevealsPerTick(), distance,
                                order, () -> update.sync(player, viewer, pending)
                        )
                );
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
        if (this.revealTick != tick) {
            this.revealTick = tick;
            this.reveals = 0;
        }
        List<Viewer> ordered = new ArrayList<>(this.viewers.values());
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
        while (this.reveals < MAX_REVEALS_PER_TICK && skipped < ordered.size()) {
            int index = Math.floorMod(this.revealCursor++, ordered.size());
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
            this.reveals++;
            skipped = 0;
        }
    }

    private Visibility visibility(
            Player player,
            Viewer viewerState,
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
        Activity current = this.activity.get(player.getUniqueId());
        if (eyeY > config.maxViewerY()) return Visibility.HIDDEN;
        if (current != null && update.tick - current.tick() >= config.idleSeconds() * 20L) {
            this.onIdleHidden(player);
            return Visibility.HIDDEN;
        }
        if (distanceSquared <= config.nearbyDistance() * config.nearbyDistance()) return Visibility.VISIBLE;
        double dot = viewerState.viewDot(viewer.yaw, viewer.pitch, dx, dy, dz) / Math.sqrt(distanceSquared);
        if (dot < update.viewAngleCosine()) return Visibility.HIDDEN;
        if (checkSight) clearSight = player.hasLineOfSight(update.body);
        if (clearSight) return Visibility.CLEAR_SIGHT;
        return Visibility.HIDDEN;
    }

    public void equipment(Player player, EntityPlayer bot) {
        for (int slot = 0; slot < 5; slot++)
            this.send(player, new PacketPlayOutEntityEquipment(bot.getId(), slot, bot.getEquipment(slot)));
    }

    public boolean muteCombatSounds(UUID player, UUID bot, long tick) {
        BotAudience audience = this.audiences.get(bot);
        if (audience == null) return false;
        Activity current = this.activity.get(player);
        if (current == null || tick - current.tick() < audience.config.idleSeconds() * 20) return false;
        Viewer viewer = this.viewers.get(player);
        Display display = viewer == null ? null : viewer.entities.get(bot);
        return display == null || !display.spawned;
    }

    public boolean isVisible(UUID player, UUID bot) {
        Viewer viewer = this.viewers.get(player);
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
        this.activity.put(player.getUniqueId(), new Activity(player.getLocation(), tick - idleTicks));
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
            this.send(player, animation);
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
        this.nameplates.remove(botId);
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
            viewer.retiredTeams.put(display.team, this.currentTick + 2);
        }
    }

    private void send(Player player, Packet<?> packet) {
        ((CraftPlayer) player).getHandle().playerConnection.sendPacket(packet);
    }

    public void clear() {
        for (Map.Entry<UUID, Viewer> entry : this.viewers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            for (String team : entry.getValue().retiredTeams.keySet()) {
                this.send(player, new PacketPlayOutScoreboardTeam(new Scoreboard().createTeam(team), 1));
            }
        }
        this.audiences.clear();
        this.viewers.clear();
        this.onlineViewers.clear();
        this.activity.clear();
        this.online.clear();
        this.nameplates.clear();
        this.revealTick = Long.MIN_VALUE;
        this.reveals = this.revealCursor = 0;
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
        private boolean hasDirection;
        private float yaw, pitch;
        private double directionX, sinPitch, directionZ;

        private Viewer(UUID playerId) {
            this.playerId = playerId;
        }

        private double viewDot(
                float yaw,
                float pitch,
                double dx,
                double dy,
                double dz
        ) {
            if (!this.hasDirection || this.yaw != yaw || this.pitch != pitch) {
                this.yaw = yaw;
                this.pitch = pitch;
                double yawRadians = Math.toRadians(yaw);
                double pitchRadians = Math.toRadians(pitch);
                double horizontal = Math.cos(pitchRadians);
                this.directionX = -Math.sin(yawRadians) * horizontal;
                this.sinPitch = Math.sin(pitchRadians);
                this.directionZ = Math.cos(yawRadians) * horizontal;
                this.hasDirection = true;
            }
            return this.directionX * dx - this.sinPitch * dy + this.directionZ * dz;
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
            this.entityId = entity.getId();
            this.x = MathHelper.floor(entity.locX * 32.0D);
            this.y = MathHelper.floor(entity.locY * 32.0D);
            this.z = MathHelper.floor(entity.locZ * 32.0D);
            this.yaw = (byte) (int) (entity.yaw * 256.0F / 360.0F);
            this.pitch = (byte) (int) (entity.pitch * 256.0F / 360.0F);
            this.onGround = entity.onGround;
        }

        private Packet<?> packet(Movement previous, boolean absolute) {
            if (previous == null) return this.teleport();
            long dx = (long) this.x - previous.x, dy = (long) this.y - previous.y, dz = (long) this.z - previous.z;
            boolean moved = dx != 0 || dy != 0 || dz != 0;
            boolean turned = this.yaw != previous.yaw || this.pitch != previous.pitch;
            if (!moved && !turned && this.onGround == previous.onGround) return null;
            if (absolute || this.onGround != previous.onGround || dx < -128 || dx > 127
                    || dy < -128 || dy > 127 || dz < -128 || dz > 127) return this.teleport();
            if (!moved) {
                if (this.look == null) {
                    this.look = new PacketPlayOutEntity.PacketPlayOutEntityLook(
                            this.entityId,
                            this.yaw,
                            this.pitch,
                            this.onGround
                    );
                }
                return this.look;
            }
            if (this.relative == null || this.relativeX != previous.x || this.relativeY != previous.y
                    || this.relativeZ != previous.z || this.relativeLook != turned) {
                this.relativeX = previous.x;
                this.relativeY = previous.y;
                this.relativeZ = previous.z;
                this.relativeLook = turned;
                this.relative = turned
                        ? new PacketPlayOutEntity.PacketPlayOutRelEntityMoveLook(
                        this.entityId,
                        (byte) dx,
                        (byte) dy,
                        (byte) dz,
                        this.yaw,
                        this.pitch,
                        this.onGround
                )
                        : new PacketPlayOutEntity.PacketPlayOutRelEntityMove(
                        this.entityId,
                        (byte) dx,
                        (byte) dy,
                        (byte) dz,
                        this.onGround
                );
            }
            return this.relative;
        }

        private Packet<?> teleport() {
            if (this.teleport == null) {
                this.teleport = new PacketPlayOutEntityTeleport(
                        this.entityId,
                        this.x,
                        this.y,
                        this.z,
                        this.yaw,
                        this.pitch,
                        this.onGround
                );
            }
            return this.teleport;
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
        private boolean hasViewAngleCosine;
        private double viewAngleCosine;

        private double viewAngleCosine() {
            if (!this.hasViewAngleCosine) {
                this.viewAngleCosine = Math.cos(Math.toRadians(this.config.viewAngle()));
                this.hasViewAngleCosine = true;
            }
            return this.viewAngleCosine;
        }

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
            this.movement = new Movement(bot);
            this.plateMovement = nameplate == null ? null : new Movement(nameplate);
            this.metadata = bot.getDataWatcher().a()
                    ? new PacketPlayOutEntityMetadata(bot.getId(), bot.getDataWatcher(), false) : null;
        }

        private void sync(Player player, Viewer viewer, Display display) {
            boolean initial = !display.spawned;
            if (!display.spawned) {
                if (this.spawn == null) {
                    this.addProfile = new PacketPlayOutPlayerInfo(
                            PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, this.bot
                    );
                    this.teamName = "b" + this.bot.getUniqueID().toString().replace("-", "").substring(0, 15);
                    Scoreboard scoreboard = new Scoreboard();
                    ScoreboardTeam team = scoreboard.createTeam(this.teamName);
                    team.setNameTagVisibility(ScoreboardTeamBase.EnumNameTagVisibility.NEVER);
                    scoreboard.addPlayerToTeam(this.bot.getProfile().getName(), this.teamName);
                    this.createTeam = new PacketPlayOutScoreboardTeam(team, 0);
                    this.spawn = new PacketPlayOutNamedEntitySpawn(this.bot);
                    if (this.nameplate != null)
                        this.spawnNameplate = new PacketPlayOutSpawnEntityLiving(this.nameplate);
                }
                display.team = this.teamName;
                viewer.retiredTeams.removeLong(display.team);
                send(player, this.addProfile);
                send(player, this.createTeam);
                send(player, this.spawn);
                if (this.nameplate != null) {
                    send(player, this.spawnNameplate);
                }
                display.spawned = true;
                display.spawnedAt = this.tick;
                display.profileRemoveAt = this.tick + PROFILE_REMOVE_DELAY_TICKS;
                display.location = this.movement;
                display.nameplate = this.plateMovement;
                display.lastTeleportTick = this.tick;
                if (this.hasEquipment) {
                    ItemStack[] snapshot = this.equipment();
                    for (int slot = 0; slot < 5; slot++) {
                        if (snapshot[slot] != null) {
                            send(player, this.equipmentPacket(slot));
                        }
                    }
                    display.equipment = snapshot;
                    display.nextEquipmentCheck =
                            StaggeredDeadline.after(this.tick, -this.bot.getId(), this.config.equipmentCheckTicks());
                }
            }
            if (display.profileRemoveAt > 0 && this.tick >= display.profileRemoveAt) {
                if (this.removeProfile == null) {
                    this.removeProfile = new PacketPlayOutPlayerInfo(
                            PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, this.bot
                    );
                }
                send(player, this.removeProfile);
                display.profileRemoveAt = 0;
            }
            if (this.metadata != null && !initial) {
                send(player, this.metadata);
            }
            boolean absolute = this.tick - display.lastTeleportTick >= MAX_RELATIVE_MOVE_TICKS;
            Packet<?> packet = this.movement.packet(display.location, absolute);
            if (packet != null) {
                send(player, packet);
                if (packet instanceof PacketPlayOutEntityTeleport) {
                    display.lastTeleportTick = this.tick;
                }
            }
            if (initial || display.location.yaw != this.movement.yaw) {
                if (this.rotation == null) {
                    this.rotation = new PacketPlayOutEntityHeadRotation(this.bot, this.movement.yaw);
                }
                send(player, this.rotation);
            }
            if (packet != null) {
                display.location = this.movement;
            }
            if (this.plateMovement != null) {
                Packet<?> platePacket = this.plateMovement.packet(display.nameplate, absolute);
                if (platePacket != null) {
                    send(player, platePacket);
                    display.nameplate = this.plateMovement;
                }
            }
            if (this.hasEquipment && this.tick >= display.nextEquipmentCheck) {
                display.nextEquipmentCheck =
                        StaggeredDeadline.after(this.tick, -this.bot.getId(), this.config.equipmentCheckTicks());
                boolean changed = false;
                for (int slot = 0; slot < 5; slot++) {
                    ItemStack current = this.equipment == null ? this.bot.getEquipment(slot) : this.equipment[slot];
                    if (ItemStack.matches(display.equipment == null ? null : display.equipment[slot], current)) {
                        continue;
                    }
                    send(player, this.equipmentPacket(slot));
                    changed = true;
                }
                if (changed) {
                    display.equipment = this.equipment();
                }
            }
            if (this.bot.hurtTicks > display.hurtTicks) {
                if (this.hurt == null) {
                    this.hurt = new PacketPlayOutEntityStatus(this.bot, (byte) 2);
                }
                send(player, this.hurt);
            }
            display.hurtTicks = this.bot.hurtTicks;
        }

        private ItemStack[] equipment() {
            if (this.equipment != null) return this.equipment;
            this.equipment = new ItemStack[5];
            for (int slot = 0; slot < this.equipment.length; slot++) {
                ItemStack item = this.bot.getEquipment(slot);
                this.equipment[slot] = item == null ? null : item.cloneItemStack();
            }
            return this.equipment;
        }

        private PacketPlayOutEntityEquipment equipmentPacket(int slot) {
            if (this.equipmentPackets == null) {
                this.equipmentPackets = new PacketPlayOutEntityEquipment[5];
            }
            if (this.equipmentPackets[slot] == null) {
                this.equipmentPackets[slot] =
                        new PacketPlayOutEntityEquipment(this.bot.getId(), slot, this.equipment()[slot]);
            }
            return this.equipmentPackets[slot];
        }
    }
}
