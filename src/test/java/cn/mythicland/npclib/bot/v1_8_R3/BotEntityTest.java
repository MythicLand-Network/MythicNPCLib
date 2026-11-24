package cn.mythicland.npclib.bot.v1_8_R3;

import net.minecraft.server.v1_8_R3.*;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotEntityTest {
    @Test
    void potionMetadataAndInvisibilityRefreshWithoutTreatingTheBotAsAWorldViewer() throws Exception {
        DispenserRegistry.c();
        BotEntity bot = mock(BotEntity.class, CALLS_REAL_METHODS);
        WorldServer world = mock(WorldServer.class);
        EntityTracker tracker = mock(EntityTracker.class);
        DataWatcher watcher = mock(DataWatcher.class);
        when(world.getTracker()).thenReturn(tracker);
        doReturn(false).when(bot).isSpectator();
        bot.world = world;
        var dataWatcherField = Entity.class.getDeclaredField("datawatcher");
        dataWatcherField.setAccessible(true);
        dataWatcherField.set(bot, watcher);
        Map<Integer, MobEffect> effects = new HashMap<>();
        var effectsField = EntityLiving.class.getDeclaredField("effects");
        effectsField.setAccessible(true);
        effectsField.set(bot, effects);

        bot.B();
        verify(watcher).watch(7, 0);
        verify(watcher).watch(8, (byte) 0);
        verify(bot).setInvisible(false);

        effects.put(MobEffectList.INVISIBILITY.id, new MobEffect(MobEffectList.INVISIBILITY.id, 100, 0, true, true));
        clearInvocations(watcher, bot);
        bot.B();
        verify(watcher).watch(7, PotionBrewer.a(effects.values()));
        verify(watcher).watch(8, (byte) 1);
        verify(bot).setInvisible(true);

        effects.remove(MobEffectList.INVISIBILITY.id);
        effects.put(MobEffectList.FASTER_MOVEMENT.id, new MobEffect(MobEffectList.FASTER_MOVEMENT.id, 100, 0));
        clearInvocations(watcher, bot);
        bot.B();
        verify(watcher).watch(7, PotionBrewer.a(effects.values()));
        verify(watcher).watch(8, (byte) 0);
        verify(bot).setInvisible(false);

        effects.clear();
        clearInvocations(watcher, bot);
        bot.B();
        verify(watcher).watch(7, 0);
        verify(watcher).watch(8, (byte) 0);
        verify(bot).setInvisible(false);

        when(bot.isSpectator()).thenReturn(true);
        clearInvocations(watcher, bot);
        bot.B();
        verify(watcher).watch(7, 0);
        verify(watcher).watch(8, (byte) 0);
        verify(bot).setInvisible(true);
        verifyNoInteractions(tracker);
    }

    @Test
    void patrolPeersKeepTheirHitboxesWithoutPushingEachOtherOffTheRoute() {
        BotEntity first = mock(BotEntity.class, CALLS_REAL_METHODS);
        BotEntity second = mock(BotEntity.class, CALLS_REAL_METHODS);
        first.collidable(true);
        second.collidable(true);
        second.locX = .4;
        second.locZ = .2;
        first.collide(second);
        assertTrue(Math.hypot(first.motX, first.motZ) > 0, "Native collisions push both participants");
        assertTrue(Math.hypot(second.motX, second.motZ) > 0);

        Object patrol = new Object();
        first.collisionGroup(patrol);
        second.collisionGroup(patrol);
        first.motX = second.motX = first.motZ = second.motZ = 0;
        first.collide(second);
        second.collide(first);
        assertEquals(0, Math.hypot(first.motX, first.motZ));
        assertEquals(0, Math.hypot(second.motX, second.motZ));
        assertTrue(first.ad(), "Patrol separation must preserve projectile hitboxes");
        assertTrue(second.ad());
        assertTrue(first.collidesWithEntities);
        second.collisionGroup(null);
        second.collide(first);
        assertTrue(Math.hypot(first.motX, first.motZ) > 0, "Other collision groups keep native pushing");
    }

    @Test
    void retiringABotClearsHurtFramesAndArrowCollisionButAllowsAddressedMelee() {
        BotEntity entity = mock(BotEntity.class, CALLS_REAL_METHODS);
        entity.noDamageTicks = 20;
        entity.invulnerableTicks = 10;
        entity.collidable(true);
        assertTrue(entity.ad());
        entity.retireForLateMelee();
        assertTrue(entity.aD());
        assertFalse(entity.ad());
        assertFalse(entity.collidesWithEntities);
        assertEquals(0, entity.noDamageTicks);
        assertEquals(0, entity.invulnerableTicks);
    }

    @Test
    void repeatedBotSpawnsDoNotRetainStatisticsOrRemovePlayerStatistics() throws Exception {
        PlayerList players = mock(PlayerList.class);
        Map<UUID, ServerStatisticManager> cached = new HashMap<>();
        var field = PlayerList.class.getDeclaredField("o");
        field.setAccessible(true);
        field.set(players, cached);
        UUID playerId = UUID.randomUUID();
        ServerStatisticManager player = mock(ServerStatisticManager.class);
        ServerStatisticManager bot = mock(ServerStatisticManager.class);
        cached.put(playerId, player);
        for (int spawn = 0; spawn < 1000; spawn++) {
            UUID botId = UUID.randomUUID();
            BotEntity.prepareStatistics(players, botId, null);
            ServerStatisticManager statistics = cached.get(botId);
            assertNotNull(statistics);
            statistics.a();
            statistics.b();
            BotEntity.releaseStatistics(players, botId, statistics);
        }
        assertEquals(Map.of(playerId, player), cached);
        BotEntity.releaseStatistics(players, playerId, bot);
        assertSame(player, cached.get(playerId));
    }

    @Test
    void botStatisticsRemainIndependentAndLeaveExistingPlayerStatisticsUntouched() throws Exception {
        PlayerList players = mock(PlayerList.class);
        Map<UUID, ServerStatisticManager> cached = new HashMap<>();
        var field = PlayerList.class.getDeclaredField("o");
        field.setAccessible(true);
        field.set(players, cached);
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
        BotEntity.prepareStatistics(players, firstId, null);
        BotEntity.prepareStatistics(players, secondId, null);
        assertNotSame(cached.get(firstId), cached.get(secondId));
        ServerStatisticManager existing = cached.get(firstId);
        BotEntity.prepareStatistics(players, firstId, null);
        assertSame(existing, cached.get(firstId));
    }

    @Test
    void survivalAbilitiesAreInitializedWithoutBroadcastingGameMode() {
        PlayerInteractManager manager = mock(PlayerInteractManager.class, CALLS_REAL_METHODS);
        EntityPlayer player = mock(EntityPlayer.class);
        player.abilities = new PlayerAbilities();
        player.abilities.canFly = true;
        player.abilities.isFlying = true;
        player.abilities.canInstantlyBuild = true;
        player.abilities.isInvulnerable = true;
        manager.player = player;
        BotEntity.initializeGameMode(manager);
        assertEquals(WorldSettings.EnumGamemode.SURVIVAL, manager.getGameMode());
        assertFalse(player.abilities.canFly);
        assertFalse(player.abilities.isFlying);
        assertFalse(player.abilities.canInstantlyBuild);
        assertFalse(player.abilities.isInvulnerable);
        assertTrue(player.abilities.mayBuild);
        verifyNoInteractions(player);
    }

    @Test
    void untrackedRegistrationSuppressesOnlyTheNewBotAndForwardsOtherCallbacks() {
        IWorldAccess delegate = mock(IWorldAccess.class);
        Entity bot = mock(Entity.class), other = mock(Entity.class);
        EntityHuman human = mock(EntityHuman.class);
        var access = new BotEntity.UntrackedWorldAccess(delegate, bot);
        BlockPosition position = new BlockPosition(1, 2, 3);
        access.a(bot);
        verifyNoInteractions(delegate);
        access.a(other);
        access.b(bot);
        access.a(position);
        access.b(position);
        access.a(1, 2, 3, 4, 5, 6);
        access.a("sound", 1, 2, 3, 4, 5);
        access.a(human, "sound", 1, 2, 3, 4, 5);
        access.a(1, true, 2, 3, 4, 5, 6, 7, 8, 9);
        access.a("sound", position);
        access.a(1, position, 2);
        access.a(human, 1, position, 2);
        access.b(1, position, 2);
        verify(delegate).a(other);
        verify(delegate).b(bot);
        verify(delegate).a(position);
        verify(delegate).b(position);
        verify(delegate).a(1, 2, 3, 4, 5, 6);
        verify(delegate).a("sound", 1, 2, 3, 4, 5);
        verify(delegate).a(human, "sound", 1, 2, 3, 4, 5);
        verify(delegate).a(1, true, 2, 3, 4, 5, 6, 7, 8, 9);
        verify(delegate).a("sound", position);
        verify(delegate).a(1, position, 2);
        verify(delegate).a(human, 1, position, 2);
        verify(delegate).b(1, position, 2);
        verifyNoMoreInteractions(delegate);
    }

}
