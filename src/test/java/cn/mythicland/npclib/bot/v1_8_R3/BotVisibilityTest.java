package cn.mythicland.npclib.bot.v1_8_R3;

import cn.mythicland.npclib.bot.BotVisibilitySettings;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotVisibilityTest {
    @Test
    void visibleViewersShareMovementPacketsAndCombatVisibilityStillRespectsDistance() throws IOException {
        DispenserRegistry.c();
        Server server = mock(Server.class);
        PluginManager permissions = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(permissions);
        when(permissions.getDefaultPermissions(anyBoolean())).thenReturn(Set.of());
        try (var bukkit = mockStatic(Bukkit.class);
             var info = mockConstruction(PacketPlayOutPlayerInfo.class);
             var spawn = mockConstruction(PacketPlayOutNamedEntitySpawn.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            World world = mock(World.class);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            WorldServer handleWorld = mock(WorldServer.class);
            PlayerChunkMap chunks = mock(PlayerChunkMap.class);
            when(handleWorld.getPlayerChunkMap()).thenReturn(chunks);
            when(chunks.a(any(EntityPlayer.class), anyInt(), anyInt())).thenReturn(true);
            EntityPlayer bot = mock(EntityPlayer.class);
            when(bot.getId()).thenReturn(42);
            bot.locY = 64;
            bot.locZ = 10;
            when(bot.getHeadHeight()).thenReturn(1.62F);
            UUID botId = UUID.randomUUID();
            when(bot.getUniqueID()).thenReturn(botId);
            when(bot.getProfile()).thenReturn(new GameProfile(botId, "bot"));
            when(bot.getDataWatcher()).thenReturn(mock(DataWatcher.class));
            CraftPlayer body = mock(CraftPlayer.class);
            when(bot.getBukkitEntity()).thenReturn(body);
            when(body.getWorld()).thenReturn(world);
            when(body.getLocation()).thenAnswer(
                    call -> new Location(world, bot.locX, bot.locY, bot.locZ, bot.yaw, bot.pitch));
            List<Player> viewers = new ArrayList<>();
            List<Location> eyes = new ArrayList<>();
            List<List<Packet<?>>> sent = new ArrayList<>();
            Location eye = new Location(world, 0, 65.62, 0);
            for (int index = 0; index < 2; index++) {
                CraftPlayer viewer = mock(CraftPlayer.class);
                when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
                when(viewer.getWorld()).thenReturn(world);
                when(viewer.canSee(body)).thenReturn(true);
                when(viewer.hasLineOfSight(body)).thenReturn(true);
                Location viewerEye = eye.clone();
                eyes.add(viewerEye);
                when(viewer.getLocation()).thenAnswer(call -> viewerEye.clone());
                when(viewer.getEyeLocation()).thenAnswer(call -> viewerEye.clone());
                EntityPlayer handle = mock(EntityPlayer.class);
                when(handle.u()).thenReturn(handleWorld);
                handle.playerConnection = mock(PlayerConnection.class);
                when(viewer.getEyeHeight()).thenReturn(1.62);
                when(viewer.getHandle()).thenAnswer(call -> {
                    handle.locX = viewerEye.getX();
                    handle.locY = viewerEye.getY() - viewer.getEyeHeight();
                    handle.locZ = viewerEye.getZ();
                    handle.yaw = viewerEye.getYaw();
                    handle.pitch = viewerEye.getPitch();
                    return handle;
                });
                List<Packet<?>> packets = new ArrayList<>();
                doAnswer(call -> {
                    packets.add(call.getArgument(0));
                    return null;
                })
                        .when(handle.playerConnection).sendPacket(any());
                viewers.add(viewer);
                sent.add(packets);
                bukkit.when(() -> Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
            }
            BotVisibility visibility = spy(new BotVisibility());
            BotVisibilitySettings settings = new Settings(32, 30, 128, 0, 70, 10, 2, 0, 5);
            visibility.activity(viewers, 0);
            visibility.update(bot, viewers, 0, null, Set.of(), settings, null, false);
            visibility.flush(0);
            assertTrue(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            for (Class<?> packet : new Class<?>[]{PacketPlayOutEntityHeadRotation.class}) {
                assertSame(sent.getFirst().stream()
                                .filter(packet::isInstance)
                                .findFirst()
                                .orElseThrow(),
                        sent.getLast().stream()
                                .filter(packet::isInstance)
                                .findFirst()
                                .orElseThrow());
            }
            assertTrue(sent.stream()
                    .allMatch(packets -> packets.stream()
                            .noneMatch(PacketPlayOutEntityTeleport.class::isInstance)));
            sent.forEach(List::clear);
            bot.locX = 0.01;
            bot.yaw = 0.1F;
            bot.pitch = 0.1F;
            visibility.update(bot, viewers, 1, null, Set.of(), settings, null, false);
            visibility.flush(1);
            assertTrue(sent.stream()
                    .allMatch(List::isEmpty));

            bot.locX = 1.0 / 32;
            visibility.update(bot, viewers, 2, null, Set.of(), settings, null, false);
            visibility.flush(2);
            Packet<?> move = sent.getFirst().getLast();
            assertInstanceOf(PacketPlayOutEntity.PacketPlayOutRelEntityMove.class, move);
            assertSame(move, sent.getLast().getLast());
            assertArrayEquals(new byte[]{42, 1, 0, 0, 0}, payload(move));

            sent.forEach(List::clear);
            bot.yaw = 90;
            bot.pitch = 45;
            visibility.update(bot, viewers, 3, null, Set.of(), settings, null, false);
            visibility.flush(3);
            assertInstanceOf(PacketPlayOutEntity.PacketPlayOutEntityLook.class, sent.getFirst().getFirst());
            assertArrayEquals(new byte[]{42, 64, 32, 0}, payload(sent.getFirst().getFirst()));
            assertInstanceOf(PacketPlayOutEntityHeadRotation.class, sent.getFirst().getLast());
            assertSame(sent.getFirst().getFirst(), sent.getLast().getFirst());
            assertSame(sent.getFirst().getLast(), sent.getLast().getLast());

            sent.forEach(List::clear);
            bot.locX = 3.0 / 32;
            bot.yaw = 180;
            visibility.update(bot, viewers, 4, null, Set.of(), settings, null, false);
            visibility.flush(4);
            assertInstanceOf(PacketPlayOutEntity.PacketPlayOutRelEntityMoveLook.class, sent.getFirst().getFirst());
            assertArrayEquals(new byte[]{42, 2, 0, 0, (byte) 128, 32, 0}, payload(sent.getFirst().getFirst()));

            sent.forEach(List::clear);
            bot.locX = -0.01;
            bot.yaw = 360;
            bot.pitch = 0;
            visibility.update(bot, viewers, 5, null, Set.of(), settings, null, false);
            visibility.flush(5);
            assertArrayEquals(new byte[]{42, -4, 0, 0, 0, 0, 0}, payload(sent.getFirst().getFirst()));

            sent.forEach(List::clear);
            bot.locX = -129.0 / 32;
            visibility.update(bot, viewers, 6, null, Set.of(), settings, null, false);
            visibility.flush(6);
            assertArrayEquals(new byte[]{42, -128, 0, 0, 0}, payload(sent.getFirst().getFirst()));

            sent.forEach(List::clear);
            bot.locX = -2.0 / 32;
            visibility.update(bot, viewers, 7, null, Set.of(), settings, null, false);
            visibility.flush(7);
            assertArrayEquals(new byte[]{42, 127, 0, 0, 0}, payload(sent.getFirst().getFirst()));

            sent.forEach(List::clear);
            bot.locX = 126.0 / 32;
            visibility.update(bot, viewers, 8, null, Set.of(), settings, null, false);
            visibility.flush(8);
            Packet<?> teleport = sent.getFirst().getFirst();
            assertInstanceOf(PacketPlayOutEntityTeleport.class, teleport);
            byte[] teleportPayload = payload(teleport);
            assertEquals(16, teleportPayload.length);
            ByteBuffer absolute = ByteBuffer.wrap(teleportPayload);
            assertEquals(42, absolute.get());
            assertEquals(126, absolute.getInt());
            assertEquals(64 * 32, absolute.getInt());
            assertEquals(10 * 32, absolute.getInt());

            sent.forEach(List::clear);
            bot.onGround = true;
            visibility.update(bot, viewers, 9, null, Set.of(), settings, null, false);
            visibility.flush(9);
            assertInstanceOf(PacketPlayOutEntityTeleport.class, sent.getFirst().getFirst());
            assertEquals(1, payload(sent.getFirst().getFirst())[15]);
            for (int index = 0; index < viewers.size(); index++) {
                eyes.get(index).setYaw(180);
                when(viewers.get(index).hasLineOfSight(body)).thenReturn(false);
            }
            visibility.activity(viewers, 10);
            visibility.update(bot, viewers, 10, null, Set.of(), settings, null, false);
            visibility.flush(10);
            assertFalse(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertFalse(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            verify(visibility, never()).onIdleHidden(any());
            visibility.update(bot, viewers, 11, viewers.getFirst().getUniqueId(), Set.of(), settings, null, false);
            visibility.flush(11);
            assertTrue(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertFalse(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            for (int index = 0; index < viewers.size(); index++) {
                eyes.get(index).setYaw(0);
                when(viewers.get(index).hasLineOfSight(body)).thenReturn(true);
            }
            visibility.activity(viewers, 20);
            visibility.update(bot, viewers, 20, null, Set.of(), settings, null, false);
            visibility.flush(20);
            assertTrue(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertTrue(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            visibility.activity(viewers, 620);
            visibility.update(bot, viewers, 620, null, Set.of(), settings, null, false);
            visibility.flush(620);
            verify(visibility).onIdleHidden(viewers.getFirst());
            verify(visibility).onIdleHidden(viewers.getLast());
            assertFalse(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertFalse(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            eyes.getFirst().setYaw(1);
            visibility.activity(viewers, 621);
            visibility.update(bot, viewers, 621, null, Set.of(), settings, null, false);
            visibility.flush(621);
            assertTrue(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertFalse(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            visibility.update(bot, viewers, 622, viewers.getLast().getUniqueId(), Set.of(), settings, null, false);
            visibility.flush(622);
            assertTrue(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            bot.locZ = 40;
            clearInvocations(visibility);
            visibility.update(bot, viewers, 640, viewers.getFirst().getUniqueId(), Set.of(), settings, null, false);
            visibility.flush(640);
            assertFalse(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertFalse(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            verify(visibility, never()).onIdleHidden(any());
            bot.locZ = 10;
            visibility.restoreIdle(viewers.getFirst(), 660, 600);
            visibility.update(bot, viewers, 660, null, Set.of(), settings, null, false);
            visibility.flush(660);
            assertFalse(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            verify(visibility).onIdleHidden(viewers.getFirst());
            eyes.getFirst().setYaw(2);
            visibility.activity(viewers, 661);
            visibility.update(bot, viewers, 661, null, Set.of(), settings, null, false);
            visibility.flush(661);
            assertTrue(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            sent.forEach(List::clear);
            bot.locX += 1.0 / 32;
            visibility.update(bot, viewers, 662, viewers.getLast().getUniqueId(), Set.of(), settings, null, false);
            visibility.flush(662);
            assertInstanceOf(PacketPlayOutEntity.PacketPlayOutRelEntityMove.class, sent.getFirst().getFirst());
            assertTrue(sent.getLast().stream()
                    .anyMatch(PacketPlayOutNamedEntitySpawn.class::isInstance));
            assertFalse(sent.getLast().stream()
                    .anyMatch(PacketPlayOutEntityTeleport.class::isInstance));
            assertFalse(sent.getLast().stream()
                    .anyMatch(PacketPlayOutEntity.class::isInstance));

            sent.forEach(List::clear);
            bot.locX += 1.0 / 32;
            visibility.update(bot, viewers, 1062, null, Set.of(), settings, null, false);
            visibility.flush(1062);
            assertTrue(sent.getFirst().stream()
                    .anyMatch(PacketPlayOutEntityTeleport.class::isInstance));
            assertFalse(sent.getFirst().stream()
                    .anyMatch(PacketPlayOutEntity.class::isInstance));
            sent.forEach(List::clear);
            bot.locX -= 129.0 / 32;
            visibility.update(bot, viewers, 1063, null, Set.of(), settings, null, false);
            visibility.flush(1063);
            assertInstanceOf(PacketPlayOutEntityTeleport.class, sent.getFirst().getFirst());
            assertEquals(-1, ByteBuffer.wrap(payload(sent.getFirst().getFirst())).getInt(1));
            visibility.destroy(bot);
            visibility.clear();

            settings = new Settings(32, 30, 128, 0, 70, 10, 16, 0, 5);
            List<EntityPlayer> crowd = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                EntityPlayer candidate = bot(world, 50 + index, 20 - index);
                for (Player viewer : viewers) {
                    when(viewer.canSee(candidate.getBukkitEntity())).thenReturn(true);
                    when(viewer.hasLineOfSight(candidate.getBukkitEntity())).thenReturn(true);
                }
                crowd.add(candidate);
            }
            sent.forEach(List::clear);
            visibility.activity(viewers, 2000);
            for (EntityPlayer candidate : crowd)
                visibility.update(candidate, viewers, 2000, null, Set.of(), settings, null, false);
            assertTrue(sent.stream()
                    .allMatch(List::isEmpty));
            visibility.flush(2000);
            for (int index = 0; index < viewers.size(); index++) {
                assertEquals(8, sent.get(index).stream()
                        .filter(PacketPlayOutNamedEntitySpawn.class::isInstance)
                        .count());
                assertTrue(visibility.isVisible(viewers.get(index).getUniqueId(), crowd.getLast().getUniqueID()));
                assertFalse(visibility.isVisible(viewers.get(index).getUniqueId(), crowd.getFirst().getUniqueID()));
            }
            sent.forEach(List::clear);
            visibility.update(
                    bot,
                    viewers,
                    2000,
                    viewers.getFirst().getUniqueId(),
                    Set.of(viewers.getLast().getUniqueId()),
                    settings,
                    null,
                    false
            );
            assertTrue(sent.stream()
                    .allMatch(packets -> packets.stream()
                            .anyMatch(PacketPlayOutNamedEntitySpawn.class::isInstance)));
            sent.forEach(List::clear);
            bot.locX += 1.0 / 32;
            bot.hurtTicks = 4;
            visibility.update(bot, viewers, 2000, null, Set.of(), settings, null, false);
            for (List<Packet<?>> packets : sent) {
                assertTrue(packets.stream()
                        .anyMatch(PacketPlayOutEntity.PacketPlayOutRelEntityMove.class::isInstance));
                assertTrue(packets.stream()
                        .anyMatch(PacketPlayOutEntityStatus.class::isInstance));
            }
            visibility.destroy(crowd.removeFirst());
            visibility.destroy(bot);
            assertTrue(sent.stream()
                    .allMatch(packets -> packets.stream()
                            .anyMatch(PacketPlayOutEntityDestroy.class::isInstance)));
            sent.forEach(List::clear);
            visibility.flush(2000);
            assertTrue(sent.stream()
                    .allMatch(List::isEmpty));
            visibility.activity(viewers, 2001);
            for (EntityPlayer candidate : crowd)
                visibility.update(candidate, viewers, 2001, null, Set.of(), settings, null, false);
            visibility.flush(2001);
            for (int index = 0; index < viewers.size(); index++) {
                assertEquals(1, sent.get(index).stream()
                        .filter(PacketPlayOutNamedEntitySpawn.class::isInstance)
                        .count());
                for (EntityPlayer candidate : crowd)
                    assertTrue(visibility.isVisible(viewers.get(index).getUniqueId(), candidate.getUniqueID()));
            }
            for (EntityPlayer candidate : crowd) visibility.destroy(candidate);
            visibility.clear();
            settings = new Settings(32, 30, 128, 0, 70, 10, 1, 0, 5);
            sent.forEach(List::clear);
            visibility.activity(viewers, 2010);
            for (EntityPlayer candidate : crowd)
                visibility.update(candidate, viewers, 2010, null, Set.of(), settings, null, false);
            visibility.destroy(crowd.getLast());
            visibility.flush(2010);
            for (List<Packet<?>> packets : sent)
                assertEquals(1, packets.stream()
                        .filter(PacketPlayOutNamedEntitySpawn.class::isInstance)
                        .count());
            for (Player viewer : viewers)
                assertFalse(visibility.isVisible(viewer.getUniqueId(), crowd.getLast().getUniqueID()));
            visibility.destroy(crowd.getFirst());
            visibility.clear();
            when(chunks.a(any(EntityPlayer.class), anyInt(), anyInt())).thenReturn(false);
            sent.forEach(List::clear);
            visibility.activity(viewers, 2020);
            visibility.update(bot, viewers, 2020, viewers.getFirst().getUniqueId(), Set.of(), settings, null, false);
            visibility.flush(2020);
            assertTrue(sent.stream()
                    .allMatch(List::isEmpty));
            when(chunks.a(any(EntityPlayer.class), anyInt(), anyInt())).thenReturn(true);
            visibility.activity(viewers, 2021);
            visibility.update(bot, viewers, 2021, viewers.getFirst().getUniqueId(), Set.of(), settings, null, false);
            visibility.flush(2021);
            assertTrue(viewers.stream()
                    .allMatch(viewer -> visibility.isVisible(viewer.getUniqueId(), botId)));
            visibility.destroy(bot);
            visibility.clear();
            settings = new Settings(32, 30, 128, 0, 70, 10, 2, 0, 1);
            ItemStack hand = new ItemStack(Items.DIAMOND_SWORD);
            hand.setTag(new NBTTagCompound());
            hand.getTag().setString("state", "initial");
            when(bot.getEquipment(0)).thenReturn(hand);
            when(bot.getEquipment(2)).thenReturn(new ItemStack(Items.LEATHER_LEGGINGS));
            sent.forEach(List::clear);
            visibility.activity(viewers, 2030);
            visibility.update(bot, viewers, 2030, null, Set.of(), settings, null, true);
            visibility.flush(2030);
            List<Packet<?>> firstEquipment = sent.getFirst().stream()
                    .filter(PacketPlayOutEntityEquipment.class::isInstance)
                    .toList();
            List<Packet<?>> secondEquipment = sent.getLast().stream()
                    .filter(PacketPlayOutEntityEquipment.class::isInstance)
                    .toList();
            assertEquals(2, firstEquipment.size());
            assertSame(firstEquipment.getFirst(), secondEquipment.getFirst());
            assertSame(firstEquipment.getLast(), secondEquipment.getLast());
            byte[] initialHand = payload(firstEquipment.getFirst());
            sent.forEach(List::clear);
            visibility.update(bot, viewers, 2031, null, Set.of(), settings, null, true);
            assertTrue(sent.stream().flatMap(List::stream).noneMatch(PacketPlayOutEntityEquipment.class::isInstance));
            hand.getTag().setString("state", "changed");
            visibility.update(bot, viewers, 2032, null, Set.of(), settings, null, true);
            for (List<Packet<?>> packets : sent)
                assertEquals(1, packets.stream()
                        .filter(PacketPlayOutEntityEquipment.class::isInstance)
                        .count());
            Packet<?> changedHand = sent.getFirst().stream()
                    .filter(PacketPlayOutEntityEquipment.class::isInstance)
                    .findFirst()
                    .orElseThrow();
            assertSame(changedHand, sent.getLast().stream()
                    .filter(PacketPlayOutEntityEquipment.class::isInstance)
                    .findFirst()
                    .orElseThrow());
            assertArrayEquals(initialHand, payload(firstEquipment.getFirst()));
            assertFalse(Arrays.equals(initialHand, payload(changedHand)));
            sent.forEach(List::clear);
            when(bot.getEquipment(2)).thenReturn(null);
            visibility.update(bot, viewers, 2033, null, Set.of(), settings, null, true);
            for (List<Packet<?>> packets : sent) {
                List<Packet<?>> changes = packets.stream()
                        .filter(PacketPlayOutEntityEquipment.class::isInstance)
                        .toList();
                assertEquals(1, changes.size());
                assertArrayEquals(new byte[]{42, 0, 2, -1, -1}, payload(changes.getFirst()));
            }
            visibility.destroy(bot);
            visibility.clear();
        }
    }

    private static EntityPlayer bot(World world, int entityId, double z) {
        EntityPlayer bot = mock(EntityPlayer.class);
        UUID id = UUID.randomUUID();
        when(bot.getId()).thenReturn(entityId);
        when(bot.getUniqueID()).thenReturn(id);
        when(bot.getProfile()).thenReturn(new GameProfile(id, "bot" + entityId));
        when(bot.getDataWatcher()).thenReturn(mock(DataWatcher.class));
        when(bot.getHeadHeight()).thenReturn(1.62F);
        bot.locY = 64;
        bot.locZ = z;
        CraftPlayer body = mock(CraftPlayer.class);
        when(bot.getBukkitEntity()).thenReturn(body);
        when(body.getWorld()).thenReturn(world);
        when(body.getLocation()).thenAnswer(call -> new Location(world, bot.locX, bot.locY, bot.locZ));
        return bot;
    }

    private static byte[] payload(Packet<?> packet) throws IOException {
        PacketDataSerializer buffer = new PacketDataSerializer(Unpooled.buffer());
        try {
            packet.b(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private record Settings(
            double viewDistance,
            double idleSeconds,
            double maxViewerY,
            double nearbyDistance,
            double viewAngle,
            int visibilityCheckTicks,
            int maxRevealsPerTick,
            int spawnDelayTicks,
            int equipmentCheckTicks
    ) implements BotVisibilitySettings {
    }
}
