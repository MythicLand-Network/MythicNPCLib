package cn.mythicland.npclib;

import cn.mythicland.framework.path.CyclicPathCursor;
import cn.mythicland.npclib.api.skin.Skin;
import cn.mythicland.npclib.bot.*;
import cn.mythicland.npclib.bot.v1_8_R3.BotEntity;
import cn.mythicland.npclib.bot.v1_8_R3.BotVisibility;
import cn.mythicland.npclib.bot.BotVisibilitySettings;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Owns and ticks independently created bots. All methods must be called on the server thread.
 */
final class BotManager implements Listener, AutoCloseable {
    private static final BotVisibilitySettings VISIBILITY = new BotVisibilitySettings() {
        @Override
        public double viewDistance() { return 32; }
        @Override
        public double idleSeconds() { return 30; }
        @Override
        public double maxViewerY() { return 256; }
        @Override
        public double nearbyDistance() { return 12; }
        @Override
        public double viewAngle() { return 70; }
        @Override
        public int visibilityCheckTicks() { return 10; }
        @Override
        public int maxRevealsPerTick() { return 2; }
        @Override
        public int spawnDelayTicks() { return 2; }
        @Override
        public int equipmentCheckTicks() { return 5; }
    };

    private final Map<UUID, Bot> bots = new LinkedHashMap<>();
    private final JavaPlugin plugin;
    private final Map<Bot, Long> respawns = new LinkedHashMap<>();
    private List<Bot> snapshot = List.of();
    private final BotVisibility visibility = new BotVisibility();
    private final BotPathfinder pathfinder = new BotPathfinder();
    private final BotTargetSelector selector = new BotTargetSelector(new BotTargetSelector.Targets() {
        @Override
        public List<? extends Player> bots() {
            return BotManager.this.bots.values().stream()
                    .map(bot -> bot.entity.getBukkitEntity())
                    .toList();
        }

        @Override
        public double healthRatio(Player player) {
            return player.getHealth() / player.getMaxHealth();
        }
    }, ThreadLocalRandom.current());
    private final BukkitTask task;
    private long tick;
    private boolean closed;

    public BotManager(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private Bot createPatrolBot(
            Location location,
            String name,
            Skin skin,
            List<Location> waypoints,
            double speed
    ) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(waypoints, "waypoints");
        if (waypoints.isEmpty()) throw new IllegalArgumentException("A patrol requires at least one waypoint");
        List<Location> points = new ArrayList<>();
        for (Location waypoint : waypoints) {
            if (waypoint == null || !Objects.equals(location.getWorld(), waypoint.getWorld())) {
                throw new IllegalArgumentException("Patrol waypoints must be in the spawn world");
            }
            points.add(waypoint.clone());
        }
        return create(location, name, skin, false, points, player -> false, 8, speed, -1);
    }

    public PatrolRobot createPatrolRobot(Location location, PatrolRobotOptions options) {
        Objects.requireNonNull(options, "options");
        Bot bot = createPatrolBot(location, options.name(), options.skin(), options.waypoints(), options.movementSpeed());
        Runnable handler = options.deathHandler();
        if (handler != null) {
            bot.onDeath(ignored -> handler.run());
        }
        return (PatrolRobot) bot;
    }

    public FighterRobot createFighterRobot(Location location, FighterRobotOptions options) {
        Objects.requireNonNull(options, "options");
        Bot bot = create(location, options.name(), options.skin(), true, List.of(), options.targets(),
                options.radius(), options.movementSpeed(), options.respawnDelay());
        Runnable handler = options.deathHandler();
        if (handler != null) {
            bot.onDeath(ignored -> handler.run());
        }
        return (FighterRobot) bot;
    }

    private Bot create(
            Location location,
            String name,
            Skin skin,
            boolean battle,
            List<Location> points,
            Predicate<Player> targets,
            double radius,
            double speed,
            int respawnDelay
    ) {
        if (this.closed) throw new IllegalStateException("Bot manager is closed");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(location.getWorld(), "location world");
        Objects.requireNonNull(name, "name");
        BotEntity entity = BotEntity.create(location, new BotEntity.Options(
                name, name, skin, !battle, (bot, damage) -> died(bot.getUniqueID()), (bot, sound) -> sound.run()
        ));
        try {
            Bot bot;
            if (battle) {
                bot = new Fighter(entity, name, skin, location, points, targets, radius, speed, respawnDelay);
            } else {
                bot = new Patrol(entity, name, skin, location, points, targets, radius, speed, respawnDelay);
            }
            this.bots.put(entity.getUniqueID(), bot);
            this.snapshot = List.copyOf(this.bots.values());
            return bot;
        } catch (RuntimeException failure) {
            entity.destroy();
            throw failure;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Bot bot = this.bots.get(event.getEntity().getUniqueId());
        if (bot == null || !(event.getDamager() instanceof Player attacker)) return;
        bot.attacker = attacker.getUniqueId();
        bot.damageTick = this.tick + 1;
    }

    private void tick() {
        this.tick++;
        spawnRespawns();
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        this.visibility.activity(players, this.tick);
        if (this.tick % 10 == 1) {
            this.selector.clear();
            for (Bot bot : this.bots.values()) {
                if (bot.target != null) {
                    this.selector.claim(bot.target);
                }
            }
            this.selector.index(players);
        }
        for (Bot bot : this.snapshot) {
            BotEntity entity = bot.entity;
            if (!this.bots.containsKey(entity.getUniqueID())) continue;
            Location location = entity.getBukkitEntity().getLocation();
            if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                this.visibility.destroy(entity);
                continue;
            }
            entity.tickState();
            if (!this.bots.containsKey(entity.getUniqueID())) continue;
            boolean awake = players.stream().anyMatch(player -> player.getWorld().equals(location.getWorld())
                    && player.getLocation().distanceSquared(location) <= 32 * 32);
            if (awake) {
                bot.tick(location);
            }
            if (!this.bots.containsKey(entity.getUniqueID())) continue;
            this.visibility.update(entity, players, this.tick, bot.target, Set.of(), VISIBILITY, bot.name, true);
        }
        this.visibility.flush(this.tick);
    }

    private void remove(UUID id) {
        Bot bot = this.bots.remove(id);
        if (bot == null) return;
        this.snapshot = List.copyOf(this.bots.values());
        if (bot.target != null) {
            this.selector.release(bot.target);
        }
        this.visibility.destroy(bot.entity);
        bot.entity.destroy();
    }

    private void died(UUID id) {
        Bot bot = this.bots.get(id);
        if (bot == null) return;
        try {
            if (bot.deathHandler != null) {
                bot.deathHandler.accept(bot);
            }
        } finally {
            boolean respawn = !this.closed && this.bots.get(id) == bot && bot.respawnDelay >= 0;
            remove(id);
            if (respawn) {
                this.respawns.put(bot, this.tick + bot.respawnDelay);
            }
        }
    }

    private void spawnRespawns() {
        if (this.respawns.isEmpty()) return;
        int spawned = 0;
        for (Map.Entry<Bot, Long> entry : List.copyOf(this.respawns.entrySet())) {
            if (spawned >= 2) break;
            Bot bot = entry.getKey();
            Location location = bot.spawn;
            if (entry.getValue() > this.tick || !location.getWorld().isChunkLoaded(
                    location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                continue;
            }
            this.respawns.remove(bot);
            BotEntity entity = null;
            try {
                entity = BotEntity.create(location, new BotEntity.Options(
                        bot.name, bot.name, bot.skin, !bot.settings.battle(),
                        (next, damage) -> died(next.getUniqueID()), (next, sound) -> sound.run()
                ));
                bot.reset(entity);
                this.bots.put(entity.getUniqueID(), bot);
                this.snapshot = List.copyOf(this.bots.values());
                spawned++;
            } catch (RuntimeException failure) {
                if (entity != null) {
                    entity.destroy();
                }
                this.plugin.getLogger().log(Level.SEVERE, "Cannot respawn bot " + bot.name, failure);
            }
        }
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        this.task.cancel();
        HandlerList.unregisterAll(this);
        for (UUID id : List.copyOf(this.bots.keySet())) {
            remove(id);
        }
        this.respawns.clear();
        this.visibility.clear();
        this.selector.clear();
    }

    public class Bot implements AutoCloseable {
        private BotEntity entity;
        private final String name;
        private final Skin skin;
        private final Location spawn;
        private final int respawnDelay;
        private final Settings settings;
        private final Predicate<Player> targets;
        private final List<BotPatrol.Waypoint> waypoints;
        private final BotPatrol patrol = new BotPatrol(new CyclicPathCursor());
        private BotNavigator navigator;
        private BotMotion motion;
        private BotBattleState battleState;
        private BotBattle battle;
        private BotAttack attack;
        private final BotBattle.Context battleContext = battleContext();
        private final BotNavigator.Context navigationContext = navigationContext();
        private UUID target, attacker;
        private long damageTick, reactionUntil;
        private Consumer<Bot> deathHandler;

        private Bot(
                BotEntity entity,
                String name,
                Skin skin,
                Location location,
                boolean combat,
                List<Location> points,
                Predicate<Player> targets,
                double radius,
                double speed,
                int respawnDelay
        ) {
            this.entity = entity;
            this.name = name;
            this.skin = skin;
            this.spawn = location.clone();
            this.respawnDelay = respawnDelay;
            this.settings = new Settings(location.getX(), location.getY(), location.getZ(), combat, radius, speed);
            this.targets = targets;
            this.waypoints = points.stream().<BotPatrol.Waypoint>map(point -> world -> point.clone()).toList();
            reset(entity);
        }

        private void reset(BotEntity entity) {
            this.entity = entity;
            this.target = null;
            this.attacker = null;
            this.damageTick = 0;
            this.reactionUntil = 0;
            this.patrol.reset(0, BotManager.this.tick, entity.getId());
            this.navigator = new BotNavigator(BotManager.this.pathfinder, BotManager.this.tick);
            this.motion = new BotMotion(BotManager.this.tick, ThreadLocalRandom.current());
            this.battleState = new BotBattleState(BotManager.this.tick, entity.getId());
            this.battle = new BotBattle(this.battleState, ThreadLocalRandom.current());
            this.attack = new BotAttack(BotManager.this.tick);
        }

        public BotEntity entity() {
            return this.entity;
        }

        /**
         * Registers a callback invoked before the dead bot is removed.
         */
        public void onDeath(Consumer<Bot> handler) {
            this.deathHandler = Objects.requireNonNull(handler, "handler");
        }

        @Override
        public void close() {
            BotManager.this.respawns.remove(this);
            BotManager.this.remove(this.entity.getUniqueID());
        }

        private boolean valid(Player player) {
            if (player == null || player.isDead()) return false;
            if (player.equals(this.entity.getBukkitEntity())) return false;
            if (!player.getWorld().equals(this.entity.getBukkitEntity().getWorld())) return false;
            if (!player.isOnline() && !BotManager.this.bots.containsKey(player.getUniqueId())) {
                return false;
            }
            if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
                return false;
            }
            return this.targets.test(player);
        }

        private void tick(Location location) {
            Location goal;
            Player opponent = null;
            boolean chase = false, returning = false, near = false;
            if (this.settings.battle()) {
                BotBattle.Decision decision = this.battle.decide(this.settings, this.entity.getBukkitEntity(), location,
                        false, BotManager.this.tick, this.damageTick, this.attacker, this.battleContext);
                if (this.battle.takePathChange()) {
                    this.navigator.reset(BotManager.this.tick);
                }
                goal = decision.goal();
                opponent = decision.opponent();
                chase = decision.chase();
                returning = decision.returning();
                near = decision.near();
            } else {
                goal = this.patrol.update(this.waypoints, null, 0, 0, BotManager.this.tick, this.entity.getId(),
                        location, BotManager.this.pathfinder::isWalkable);
                if (this.patrol.takePathChange()) {
                    this.navigator.reset(BotManager.this.tick);
                }
            }
            this.motion.navigateAndMove(this.settings, this.navigator, this.navigationContext, this.entity,
                    location, goal, opponent, BotManager.this.tick, false, chase, returning, near, false);
            if (opponent != null && BotManager.this.tick >= this.reactionUntil && valid(opponent)
                    && this.attack.attack(this.entity, opponent, BotManager.this.tick, 2.8, 30, 10, 16,
                    ThreadLocalRandom.current())) {
                BotManager.this.visibility.action(this.entity, 0);
            }
        }

        private BotBattle.Context battleContext() {
            return new BotBattle.Context() {
                @Override
                public UUID target() { return Bot.this.target; }
                @Override
                public void target(UUID id) {
                    if (Objects.equals(id, Bot.this.target)) return;
                    if (Bot.this.target != null) {
                        BotManager.this.selector.release(Bot.this.target);
                    }
                    Bot.this.target = id;
                    if (id == null) return;
                    BotManager.this.selector.claim(id);
                    Bot.this.reactionUntil = BotManager.this.tick + ThreadLocalRandom.current().nextInt(6, 11);
                    Bot.this.battleState.targetChanged(BotManager.this.tick, Bot.this.reactionUntil);
                    Bot.this.navigator.refresh(BotManager.this.tick);
                }
                @Override
                public Player resolve(UUID id) {
                    Bot bot = BotManager.this.bots.get(id);
                    if (bot != null) return bot.entity.getBukkitEntity();
                    if (id == null) return null;
                    return Bukkit.getPlayer(id);
                }
                @Override
                public boolean valid(Player player) { return Bot.this.valid(player); }
                @Override
                public Player search() {
                    return BotManager.this.selector.search(Bot.this.settings::radius, Bot.this.entity.getBukkitEntity(),
                            Bot.this::valid, id -> false, player -> 0);
                }
                @Override
                public int pressure(UUID id) { return BotManager.this.selector.pressure(id); }
                @Override
                public Location separate(Location goal) {
                    return BotManager.this.selector.separate(Bot.this.settings, Bot.this.entity.getBukkitEntity(), goal);
                }
                @Override
                public void strafe(long tick) { Bot.this.motion.updateStrafe(tick); }
            };
        }

        private BotNavigator.Context navigationContext() {
            return new BotNavigator.Context() {
                @Override
                public boolean allowed(Location location) { return true; }
                @Override
                public void clearPath(Location start, Location goal) { }
                @Override
                public void clearBlock(Location location) { }
                @Override
                public void recover(Location goal) {
                    if (!BotManager.this.pathfinder.isWalkable(goal)) return;
                    BotManager.this.visibility.destroy(Bot.this.entity);
                    Bot.this.entity.transfer(goal);
                    Bot.this.patrol.finish(Bot.this.waypoints.size(), null, 0, goal, BotManager.this.tick, true);
                }
                @Override
                public void wanderFailed() { Bot.this.battle.clearGoal(); }
            };
        }
    }

    private final class Patrol extends Bot implements PatrolRobot {
        private Patrol(
                BotEntity entity,
                String name,
                Skin skin,
                Location location,
                List<Location> points,
                Predicate<Player> targets,
                double radius,
                double speed,
                int respawnDelay
        ) {
            super(entity, name, skin, location, false, points, targets, radius, speed, respawnDelay);
        }
    }

    private final class Fighter extends Bot implements FighterRobot {
        private Fighter(
                BotEntity entity,
                String name,
                Skin skin,
                Location location,
                List<Location> points,
                Predicate<Player> targets,
                double radius,
                double speed,
                int respawnDelay
        ) {
            super(entity, name, skin, location, true, points, targets, radius, speed, respawnDelay);
        }
    }

    private record Settings(
            double x,
            double y,
            double z,
            boolean battle,
            double radius,
            double movementSpeed
    ) implements BotNavigator.Settings, BotMotion.Settings {
        @Override
        public double spawnHeight() { return 0; }
        @Override
        public double spawnRadius() { return 0; }
        @Override
        public double centerAttraction() { return .8; }
        @Override
        public double attackDistance() { return 2.8; }
        @Override
        public double wanderMinDistance() { return Math.min(2, this.radius); }
        @Override
        public double wanderMaxDistance() { return Math.min(5, this.radius); }
        @Override
        public double yawSpeed() { return this.battle ? 9 : 18; }
        @Override
        public double pitchSpeed() { return 4.5; }
        @Override
        public double approachJumpChance() { return 0; }
    }
}
