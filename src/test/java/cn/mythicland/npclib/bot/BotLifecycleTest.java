package cn.mythicland.npclib.bot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BotLifecycleTest {
    @Test
    void invalidationAndDrainRespectLifecycle() {
        BotLifecycle lifecycle = new BotLifecycle();
        AtomicInteger delivered = new AtomicInteger();
        long first = lifecycle.invalidate();
        lifecycle.start();
        lifecycle.enqueue(() -> {
            if (lifecycle.running() && lifecycle.current(first)) delivered.incrementAndGet();
        });
        lifecycle.invalidate();
        lifecycle.drain();
        assertEquals(0, delivered.get());
        long current = lifecycle.invalidate();
        lifecycle.enqueue(() -> {
            if (lifecycle.running() && lifecycle.current(current)) delivered.incrementAndGet();
        });
        lifecycle.drain();
        assertEquals(1, delivered.get());
        lifecycle.stop();
        assertFalse(lifecycle.running());
        assertFalse(lifecycle.current(current));
    }

    @Test
    void spawnBudgetResetsAfterCountOrTimeLimit() {
        BotSpawnBudget budget = new BotSpawnBudget(2, 10);
        budget.record(4);
        assertTrue(budget.available());
        budget.record(2);
        assertFalse(budget.available());
        budget.reset();
        budget.record(10);
        assertFalse(budget.available());
        budget.reset();
        assertTrue(budget.available());
    }

    @Test
    void rosterReusesSnapshotUntilMembershipChanges() {
        BotRoster<String> roster = new BotRoster<>();
        UUID id = UUID.randomUUID();
        roster.put(id, "bot");
        var snapshot = roster.snapshot();
        assertSame(snapshot, roster.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        roster.remove(id);
        assertTrue(roster.snapshot().isEmpty());
        assertEquals("bot", snapshot.getFirst());
    }
}
