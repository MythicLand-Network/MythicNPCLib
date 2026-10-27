package cn.mythicland.npclib;

import cn.mythicland.npclib.api.PacketNpc;
import cn.mythicland.npclib.api.skin.Skin;
import cn.mythicland.npclib.bot.FighterRobot;
import cn.mythicland.npclib.bot.PatrolRobot;
import cn.mythicland.npclib.bot.v1_8_R3.BotEntity;
import cn.mythicland.npclib.listeners.PacketListener;
import cn.mythicland.npclib.internal.NPCManager;
import cn.mythicland.npclib.nms.v1_8_R3.NPC_v1_8_R3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedCreationTest {
    @Test
    void externalRuntimeOwnsBothRobotTypesWithoutStartingTheDefaultManager() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(TestServer.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        Location location = new Location(mock(World.class), 1, 64, 2);
        BotEntity patrolEntity = mock(BotEntity.class);
        BotEntity fighterEntity = mock(BotEntity.class);
        BotEntity.Options patrolOptions = new BotEntity.Options(
                "patrol", "Patrol", null, true, (bot, source) -> { }, (bot, sound) -> sound.run()
        );
        BotEntity.Options fighterOptions = new BotEntity.Options(
                "fighter", "Fighter", null, false, (bot, source) -> { }, (bot, sound) -> sound.run()
        );

        try (var bukkit = mockStatic(Bukkit.class);
             var packets = mockConstruction(PacketListener.class);
             var managers = mockConstruction(BotManager.class);
             var entities = mockStatic(BotEntity.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            entities.when(() -> BotEntity.create(location, patrolOptions)).thenReturn(patrolEntity);
            entities.when(() -> BotEntity.create(location, fighterOptions)).thenReturn(fighterEntity);
            NPCLib library = new NPCLib(plugin);
            PatrolRobot patrol = library.createPatrolRobot(location, options -> options.externalControl(patrolOptions));
            FighterRobot fighter = library.createFighterRobot(location, options -> options.externalControl(fighterOptions));
            assertSame(patrolEntity, patrol.entity());
            assertSame(fighterEntity, fighter.entity());
            assertTrue(managers.constructed().isEmpty());
            library.shutdown();
            verify(patrolEntity, never()).destroy();
            verify(fighterEntity, never()).destroy();
            patrol.close();
            fighter.close();
            verify(patrolEntity).destroy();
            verify(fighterEntity).destroy();
        }
    }

    @Test
    void createsAndDestroysRealPacketNpc() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(TestServer.class);
        PluginManager plugins = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(plugins);
        try (var bukkit = mockStatic(Bukkit.class);
             var packets = mockConstruction(PacketListener.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getViewDistance).thenReturn(10);
            NPCLib library = new NPCLib(plugin);
            Location location = new Location(mock(World.class), 1, 64, 2);
            PacketNpc npc = library.createPacketNpc(location, options -> options.text(List.of("Shop")));
            assertTrue(npc.isCreated());
            assertEquals(location, npc.getLocation());
            assertTrue(NPCManager.getAllNPCs().contains(npc));
            npc.destroy();
            assertFalse(NPCManager.getAllNPCs().contains(npc));
            library.shutdown();
        }
    }

    @Test
    void createsConfiguredPacketNpcAndRegistersAutomaticVisibility() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(TestServer.class);
        PluginManager manager = mock(PluginManager.class);
        Player first = mock(Player.class), second = mock(Player.class);
        World world = mock(World.class);
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
        when(first.getUniqueId()).thenReturn(firstId);
        when(second.getUniqueId()).thenReturn(secondId);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.getDefaultPermissions(anyBoolean())).thenReturn(Set.of());
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(first));
        Location location = new Location(world, 1, 64, 2);
        Skin skin = new Skin("value", "signature");
        List<List<?>> constructorArguments = new ArrayList<>();

        try (var bukkit = mockStatic(Bukkit.class);
             var packets = mockConstruction(PacketListener.class);
             var bots = mockConstruction(BotManager.class);
             var npcs = mockConstruction(NPC_v1_8_R3.class,
                     (npc, context) -> constructorArguments.add(context.arguments()))) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getViewDistance).thenReturn(10);
            NPCLib library = new NPCLib(plugin);
            PacketNpc npc = library.createPacketNpc(location, options -> {
                options.skin(skin);
                options.text(List.of("Shop"));
                options.autoShow(true);
            });
            NPC_v1_8_R3 created = npcs.constructed().getFirst();
            assertSame(created, npc);
            assertEquals(List.of("Shop"), constructorArguments.getFirst().get(1));
            verify(created).setLocation(location);
            verify(created).setSkin(skin);
            verify(created).create();
            verify(created).show(first);
            assertTrue(library.hasAutomaticNpcs());
            Set<UUID> shown = new HashSet<>(List.of(firstId));
            when(created.getShown()).thenReturn(shown);
            library.showAutomaticNpcs(first);
            library.showAutomaticNpcs(second);
            verify(created, times(1)).show(first);
            verify(created).show(second);
            PacketNpc manual = library.createPacketNpc(location);
            verify((NPC_v1_8_R3) manual, never()).show(any());
            PatrolRobot patrol = mock(PatrolRobot.class);
            FighterRobot fighter = mock(FighterRobot.class);
            library.createPatrolRobot(location, options -> options.name("initial"));
            BotManager botManager = bots.constructed().getFirst();
            when(botManager.createPatrolRobot(any(), any())).thenReturn(patrol);
            when(botManager.createFighterRobot(any(), any())).thenReturn(fighter);
            assertSame(patrol, library.createPatrolRobot(location, options -> options.name("patrol")));
            assertSame(fighter, library.createFighterRobot(location, options -> options.name("fighter")));
            library.forgetNPC(npc);
            assertFalse(library.hasAutomaticNpcs());
            library.shutdown();
            verify(botManager).close();
        }
    }

    private interface TestServer extends Server {
        Collection<? extends Player> getOnlinePlayers();
    }
}
