package cn.mythicland.npclib;

import cn.mythicland.npclib.bot.*;

import cn.mythicland.npclib.bot.v1_8_R3.BotEntity;
import cn.mythicland.npclib.bot.v1_8_R3.BotVisibility;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotManagerTest {
    @Test
    void createsBothBehaviorsTicksAndCleansUpOwnedEntities() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PluginManager plugins = mock(PluginManager.class);
        BukkitTask task = mock(BukkitTask.class);
        World world = mock(World.class);
        Location start = new Location(world, 0, 64, 0);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(plugins);
        when(plugins.getDefaultPermissions(anyBoolean())).thenReturn(Set.of());
        when(server.getScheduler()).thenReturn(scheduler);
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.runTaskTimer(eq(plugin), tick.capture(), eq(1L), eq(1L))).thenReturn(task);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);

        Player viewer = mock(TestPlayer.class, invocation -> {
            if (invocation.getMethod().getName().equals("getHealth")
                    || invocation.getMethod().getName().equals("getMaxHealth")) return 20.0;
            return RETURNS_DEFAULTS.answer(invocation);
        });
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(viewer.getWorld()).thenReturn(world);
        when(viewer.getLocation()).thenReturn(new Location(world, 2, 64, 0));
        when(viewer.getEyeLocation()).thenReturn(new Location(world, 2, 65.62, 0));
        when(viewer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(viewer.isOnline()).thenReturn(true);
        List<BotEntity.Options> options = new ArrayList<>();

        try (var bukkit = mockStatic(Bukkit.class);
             var factory = mockStatic(BotEntity.class);
             var visibility = mockConstruction(BotVisibility.class);
             var paths = mockConstruction(BotPathfinder.class, (path, context) -> {
                 when(path.canWalkDirectly(any(), any())).thenReturn(true);
                 when(path.isWalkable(any())).thenReturn(true);
             })) {
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.of(viewer));
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(() -> Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
            factory.when(() -> BotEntity.create(any(Location.class), any(BotEntity.Options.class)))
                    .thenAnswer(call -> {
                        options.add(call.getArgument(1));
                        return entity(world, start);
                    });

            BotManager manager = new BotManager(plugin);
            PatrolRobotOptions patrolOptions = new PatrolRobotOptions(start);
            patrolOptions.name("patrol");
            FighterRobotOptions battleOptions = new FighterRobotOptions();
            battleOptions.name("battle");
            BotManager.Bot patrol = (BotManager.Bot) manager.createPatrolRobot(start, patrolOptions);
            BotManager.Bot battle = (BotManager.Bot) manager.createFighterRobot(start, battleOptions);
            FighterRobotOptions configured = new FighterRobotOptions();
            configured.name("custom");
            configured.radius(12);
            configured.movementSpeed(1.5);
            configured.targetFilter(candidate -> false);
            configured.respawnDelay(2);
            AtomicInteger configuredDeaths = new AtomicInteger();
            configured.onDeath(configuredDeaths::incrementAndGet);
            FighterRobot respawning = manager.createFighterRobot(start, configured);
            assertNotSame(patrol.entity(), battle.entity());
            assertTrue(options.get(0).walking());
            assertFalse(options.get(1).walking());
            for (int index = 0; index < 80; index++) tick.getValue().run();
            verify(patrol.entity(), atLeastOnce()).step(anyFloat(), anyFloat(), anyDouble(), anyBoolean());
            verify(battle.entity(), atLeastOnce()).step(anyFloat(), anyFloat(), anyDouble(), anyBoolean());
            verify(battle.entity(), atLeastOnce()).hit(viewer);
            verify(respawning.entity(), atLeastOnce()).step(anyFloat(), anyFloat(), eq(1.5), anyBoolean());
            verify(respawning.entity(), never()).hit(any());
            verify(visibility.constructed().getFirst(), atLeastOnce())
                    .update(eq(patrol.entity()), anyCollection(), anyLong(), isNull(), anySet(), any(), eq("patrol"), eq(true));
            AtomicInteger deaths = new AtomicInteger();
            battle.onDeath(bot -> deaths.incrementAndGet());
            options.get(1).deathHandler().accept(battle.entity(), null);
            assertEquals(1, deaths.get());
            verify(battle.entity()).destroy();
            BotEntity old = respawning.entity();
            options.get(2).deathHandler().accept(old, null);
            assertEquals(1, configuredDeaths.get());
            tick.getValue().run();
            assertSame(old, respawning.entity());
            tick.getValue().run();
            assertNotSame(old, respawning.entity());
            verify(old).destroy();
            BotEntity replacement = respawning.entity();
            options.get(3).deathHandler().accept(replacement, null);
            respawning.close();
            verify(replacement).destroy();
            for (int index = 0; index < 4; index++) tick.getValue().run();
            assertEquals(4, options.size());
            patrol.close();
            verify(patrol.entity()).destroy();
            manager.close();
            verify(battle.entity(), times(1)).destroy();
            verify(task).cancel();
            assertThrows(IllegalStateException.class, () -> manager.createFighterRobot(start, battleOptions));
        }
    }

    private static BotEntity entity(World world, Location location) {
        BotEntity entity = mock(BotEntity.class);
        CraftPlayer player = mock(CraftPlayer.class);
        UUID id = UUID.randomUUID();
        when(entity.getUniqueID()).thenReturn(id);
        when(entity.getBukkitEntity()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(location.clone());
        when(player.getEyeLocation()).thenAnswer(call -> {
            Location eye = location.clone().add(0, 1.62, 0);
            eye.setYaw(entity.yaw);
            eye.setPitch(entity.pitch);
            return eye;
        });
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(player.hasLineOfSight(any())).thenReturn(true);
        return entity;
    }

    private interface TestPlayer extends Player {
        double getHealth();
        double getMaxHealth();
    }
}
