package cn.mythicland.npclib.bot.v1_8_R3;

import cn.mythicland.npclib.api.skin.Skin;
import cn.mythicland.npclib.bot.BotHandle;
import cn.mythicland.npclib.bot.BotOptions;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

public class BotEntity extends EntityPlayer implements BotHandle {
    private static final IChatBaseComponent TAB_NAME = new ChatComponentText("");
    private static final Field WORLD_ACCESSES = field(World.class, "u");
    private static final Field PLAYER_STATISTICS = field(PlayerList.class, "o");
    private static final Field GAME_MODE = field(PlayerInteractManager.class, "gamemode");
    private final boolean walking;
    private final String sourceName;
    private final BiConsumer<BotEntity, DamageSource> deathHandler;
    private final BiConsumer<BotEntity, Runnable> stepHandler;

    public record Options(
            String name,
            String displayName,
            Skin skin,
            boolean walking,
            BiConsumer<BotEntity, DamageSource> deathHandler,
            BiConsumer<BotEntity, Runnable> stepHandler
    ) {
        public Options {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(deathHandler, "deathHandler");
            Objects.requireNonNull(stepHandler, "stepHandler");
        }
    }

    public static BotEntity create(Location location, BotOptions options) {
        return create(location, new Options(options.name(), options.displayName(), options.skin(), options.walking(),
                (bot, source) -> options.deathHandler().onDeath(bot,
                        source.getEntity() != null && source.getEntity().getBukkitEntity() instanceof org.bukkit.entity.Player player
                                ? player : null, source == DamageSource.OUT_OF_WORLD),
                options.stepHandler()::accept));
    }

    public static BotEntity create(Location location, Options options) {
        BotEntity entity = new BotEntity(location, profile(options.skin()), options);
        entity.spawn();
        return entity;
    }

    private static GameProfile profile(Skin skin) {
        UUID id = UUID.randomUUID();
        GameProfile profile = new GameProfile(id, "b" + id.toString().replace("-", "").substring(0, 15));
        if (skin != null) {
            profile.getProperties().put("textures", new Property("textures", skin.value(), skin.signature()));
        }
        return profile;
    }

    private BotEntity(Location location, GameProfile profile, Options options) {
        super(
                prepareServer(profile), ((CraftWorld) location.getWorld()).getHandle(),
                profile, new PlayerInteractManager(((CraftWorld) location.getWorld()).getHandle())
        );
        releaseStatistics(server.getPlayerList(), getUniqueID(), super.getStatisticManager());
        this.walking = options.walking();
        this.sourceName = options.name();
        this.deathHandler = options.deathHandler();
        this.stepHandler = options.stepHandler();
        NetworkManager network = new NetworkManager(EnumProtocolDirection.CLIENTBOUND);
        this.playerConnection = new PlayerConnection(server, network, this) {
            @Override
            public void sendPacket(Packet packet) {
            }
        };
        network.a(playerConnection);
        initializeGameMode(this.playerInteractManager);
        this.invulnerableTicks = 0;
        this.joining = false;
        this.getDataWatcher().watch(10, (byte) 127);
        this.setSprinting(!this.walking);
        this.setPositionRotation(location.getX(), location.getY(), location.getZ(), 0, 0);
        this.getBukkitEntity().setDisplayName(options.displayName());
    }

    protected final void spawn() {
        if (!addUntracked()) {
            throw new IllegalStateException("Cannot add bot entity at " + getBukkitEntity().getLocation());
        }
        world.players.remove(this);
        ((WorldServer) world).getTracker().untrackEntity(this);
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot access bot runtime field " + type.getName() + "." + name, failure);
        }
    }

    private static MinecraftServer prepareServer(GameProfile profile) {
        MinecraftServer server = MinecraftServer.getServer();
        prepareStatistics(server.getPlayerList(), profile.getId(), server);
        return server;
    }

    static void prepareStatistics(PlayerList players, UUID id, MinecraftServer server) {
        try {
            @SuppressWarnings("unchecked")
            Map<UUID, ServerStatisticManager> cached =
                    (Map<UUID, ServerStatisticManager>) PLAYER_STATISTICS.get(players);
            cached.computeIfAbsent(id, ignored -> new ServerStatisticManager(server, null) {
                @Override
                public void a() {
                }

                @Override
                public void b() {
                }
            });
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot prepare server statistics for bot " + id, failure);
        }
    }

    static void initializeGameMode(PlayerInteractManager manager) {
        try {
            GAME_MODE.set(manager, WorldSettings.EnumGamemode.SURVIVAL);
            WorldSettings.EnumGamemode.SURVIVAL.a(manager.player.abilities);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot initialize bot game mode", failure);
        }
    }

    static void releaseStatistics(PlayerList players, UUID id, ServerStatisticManager statistics) {
        try {
            @SuppressWarnings("unchecked")
            Map<UUID, ServerStatisticManager> cached =
                    (Map<UUID, ServerStatisticManager>) PLAYER_STATISTICS.get(players);
            cached.remove(id, statistics);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot release server statistics for bot " + id, failure);
        }
    }

    private boolean addUntracked() {
        try {
            @SuppressWarnings("unchecked")
            List<IWorldAccess> accesses = (List<IWorldAccess>) WORLD_ACCESSES.get(world);
            IWorldAccess[] original = accesses.toArray(IWorldAccess[]::new);
            for (int index = 0; index < original.length; index++) {
                if (original[index] instanceof WorldManager) {
                    accesses.set(index, new UntrackedWorldAccess(original[index], this));
                }
            }
            try {
                return world.addEntity(this, CreatureSpawnEvent.SpawnReason.CUSTOM);
            } finally {
                for (int index = 0; index < original.length; index++) {
                    if (original[index] instanceof WorldManager) {
                        accesses.set(index, original[index]);
                    }
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot register untracked bot entity", failure);
        }
    }

    static final class UntrackedWorldAccess implements IWorldAccess {
        private final IWorldAccess delegate;
        private final Entity bot;

        UntrackedWorldAccess(IWorldAccess delegate, Entity bot) {
            this.delegate = delegate;
            this.bot = bot;
        }

        @Override
        public void a(BlockPosition position) {
            delegate.a(position);
        }

        @Override
        public void b(BlockPosition position) {
            delegate.b(position);
        }

        @Override
        public void a(int x1, int y1, int z1, int x2, int y2, int z2) {
            delegate.a(x1, y1, z1, x2, y2, z2);
        }

        @Override
        public void a(String sound, double x, double y, double z, float volume, float pitch) {
            delegate.a(sound, x, y, z, volume, pitch);
        }

        @Override
        public void a(
                EntityHuman player,
                String sound,
                double x,
                double y,
                double z,
                float volume,
                float pitch
        ) {
            delegate.a(player, sound, x, y, z, volume, pitch);
        }

        @Override
        public void a(
                int particle,
                boolean longDistance,
                double x,
                double y,
                double z,
                double dx,
                double dy,
                double dz,
                int... data
        ) {
            delegate.a(particle, longDistance, x, y, z, dx, dy, dz, data);
        }

        @Override
        public void a(Entity entity) {
            if (entity == bot) return;
            delegate.a(entity);
        }

        @Override
        public void b(Entity entity) {
            delegate.b(entity);
        }

        @Override
        public void a(String sound, BlockPosition position) {
            delegate.a(sound, position);
        }

        @Override
        public void a(int effect, BlockPosition position, int data) {
            delegate.a(effect, position, data);
        }

        @Override
        public void a(EntityHuman player, int effect, BlockPosition position, int data) {
            delegate.a(player, effect, position, data);
        }

        @Override
        public void b(int breaker, BlockPosition position, int stage) {
            delegate.b(breaker, position, stage);
        }
    }

    @Override
    public IChatBaseComponent getPlayerListName() {
        return TAB_NAME;
    }

    @Override
    public void t_() {
    }

    @Override
    public void l() {
    }

    @Override
    public void a(Statistic statistic, int amount) {
    }

    public void tickState() {
        if (noDamageTicks > 0) {
            noDamageTicks--;
        }
        if (invulnerableTicks > 0) {
            invulnerableTicks--;
        }
        super.K();
    }

    @Override
    public boolean ad() {
        return !dead;
    }

    @Override
    public void collide(Entity entity) {
        if (collidesWithEntities) {
            super.collide(entity);
        }
    }

    public void step(float strafe, float forward, double speed, boolean jump) {
        if (dead) return;
        S = 0.6F;
        updateSprinting();
        AttributeInstance movement = getAttributeInstance(GenericAttributes.MOVEMENT_SPEED);
        movement.setValue(0.1D * speed * (getBukkitEntity().getWalkSpeed() / 0.2D));
        k((float) movement.getValue());
        if (jump && onGround) {
            bF();
        }
        g(strafe, forward);
        aI = yaw;
        aK = yaw;
        f(yaw);
        world.players.remove(this);
    }

    @Override
    public void bF() {
        updateSprinting();
        super.bF();
    }

    public void hit(org.bukkit.entity.Entity target) {
        bw();
        attack(((CraftEntity) target).getHandle());
        updateSprinting();
    }

    private void updateSprinting() {
        if (isSprinting() == walking) {
            setSprinting(!walking);
        }
    }

    @Override
    protected void a(double distance, boolean ground, Block block, BlockPosition position) {
        fallDistance = 0;
    }

    @Override
    public void e(float distance, float multiplier) {
        fallDistance = 0;
    }

    @Override
    public void die(DamageSource source) {
        this.deathHandler.accept(this, source);
    }

    @Override
    public String getName() {
        return this.sourceName == null ? super.getName() : this.sourceName;
    }

    @Override
    protected void a(BlockPosition position, Block block) {
        this.stepHandler.accept(this, () -> super.a(position, block));
    }

    @Override public double x() { return this.locX; }
    @Override public double y() { return this.locY; }
    @Override public double z() { return this.locZ; }
    @Override public float yaw() { return this.yaw; }
    @Override public float pitch() { return this.pitch; }
    @Override public void yaw(float yaw) { this.yaw = yaw; }
    @Override public void pitch(float pitch) { this.pitch = pitch; }
    @Override public boolean onGround() { return this.onGround; }
    @Override public void collidable(boolean collidable) { this.collidesWithEntities = collidable; }
    @Override public void clearArrows() { this.o(0); }

    public void transfer(Location location) {
        destroy();
        WorldServer target = ((CraftWorld) location.getWorld()).getHandle();
        spawnIn(target);
        dimension = target.worldProvider.getDimension();
        playerInteractManager.a(target);
        dead = false;
        motX = motY = motZ = 0;
        setPositionRotation(location.getX(), location.getY(), location.getZ(), yaw, pitch);
        if (!addUntracked()) throw new IllegalStateException("Cannot transfer bot entity to " + location);
        world.players.remove(this);
        target.getTracker().untrackEntity(this);
    }

    public void destroy() {
        world.removeEntity(this);
        ((WorldServer) world).getTracker().untrackEntity(this);
    }
}
