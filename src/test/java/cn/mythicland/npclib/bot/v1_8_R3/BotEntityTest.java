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
