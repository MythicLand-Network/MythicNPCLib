package cn.mythicland.npclib.bot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BotSpawnGroupTest {
    @Test
    void cancelledRespawnRetainsItsStateAndIgnoresStaleCompletion() {
        BotLifecycle lifecycle = new BotLifecycle();
        lifecycle.start();
        BotSpawnGroup<String, String, String> group = new BotSpawnGroup<>(lifecycle);
        CompletableFuture<String> first = new CompletableFuture<>();
        AtomicInteger errors = new AtomicInteger();
        group.delay(10, "position", 7);
        group.prepare(10, 1, 0, true, () -> "profile", ignored -> first, ignored -> errors.incrementAndGet());
        assertEquals(1, group.pendingCount());
        group.cancel();
        lifecycle.drain();
        assertEquals(0, errors.get());
        assertEquals("position", group.delayed().iterator().next().state());
        group.prepare(11, 1, 0, true, () -> "profile", ignored -> CompletableFuture.completedFuture("skin"),
                ignored -> errors.incrementAndGet());
        lifecycle.drain();
        group.admitReady();
        var ticket = group.iterator().next();
        assertTrue(ticket.admitted());
        assertEquals(7, ticket.routeSlot());
        assertEquals("skin", ticket.result());
        assertEquals("position", ticket.respawn().state());
    }

    @Test
    void battleRespawnUsesNewSourceAfterDelay() {
        BotLifecycle lifecycle = new BotLifecycle();
        lifecycle.start();
        BotSpawnGroup<String, String, String> group = new BotSpawnGroup<>(lifecycle);
        group.delay(10, "old position", 3);
        group.prepare(9, 1, 0, false, () -> "new", ignored -> CompletableFuture.completedFuture("skin"),
                error -> fail(error));
        assertEquals(1, group.pendingCount());
        group.prepare(10, 1, 0, false, () -> "new", ignored -> CompletableFuture.completedFuture("skin"),
                error -> fail(error));
        lifecycle.drain();
        group.admitReady();
        assertEquals("new", group.iterator().next().source());
        assertNull(group.iterator().next().respawn());
    }
}
