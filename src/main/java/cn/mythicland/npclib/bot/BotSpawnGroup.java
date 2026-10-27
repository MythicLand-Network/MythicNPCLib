package cn.mythicland.npclib.bot;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Manages asynchronous spawn requests and delayed respawns for a bot group.
 * Queue operations and lifecycle scheduling run on the server's main thread;
 * request completion callbacks may run asynchronously.
 */
public final class BotSpawnGroup<P, S, R> {
    public record Respawn<R>(long tick, R state, long routeSlot) {
    }

    public static final class Ticket<P, S, R> {
        private final P source;
        private final Respawn<R> respawn;
        private final long routeSlot;
        private volatile boolean valid = true;
        private CompletableFuture<S> request;
        private S result;
        private boolean ready;
        private boolean admitted;

        private Ticket(P source, Respawn<R> respawn, long routeSlot) {
            this.source = source;
            this.respawn = respawn;
            this.routeSlot = routeSlot;
        }

        public P source() {
            return this.source;
        }

        /**
         * Returns null for a newly spawned bot with no respawn state.
         */
        public Respawn<R> respawn() {
            return this.respawn;
        }

        public long routeSlot() {
            return this.routeSlot;
        }

        /**
         * The request may return null. Read this result only after the ticket is admitted for spawning.
         */
        public S result() {
            return this.result;
        }

        public boolean admitted() {
            return this.admitted;
        }
    }

    private final Deque<Ticket<P, S, R>> pending = new ArrayDeque<>();
    private final Deque<Respawn<R>> delayed = new ArrayDeque<>();
    private final BotLifecycle lifecycle;
    private long nextRouteSlot;

    public BotSpawnGroup(BotLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    public Collection<Ticket<P, S, R>> pending() {
        return Collections.unmodifiableCollection(this.pending);
    }

    public Collection<Respawn<R>> delayed() {
        return Collections.unmodifiableCollection(this.delayed);
    }

    public int pendingCount() {
        return this.pending.size() + this.delayed.size();
    }

    public void delay(long tick, R state, long routeSlot) {
        this.delayed.addLast(new Respawn<>(tick, state, routeSlot));
    }

    public void trim(int maximum) {
        while (this.delayed.size() > maximum) this.delayed.removeLast();
    }

    public void clearDelays() {
        this.delayed.clear();
    }

    public void prepare(
            long tick,
            int target,
            int alive,
            boolean preserveRespawn,
            Supplier<P> source,
            Function<P, CompletableFuture<S>> request,
            Consumer<Throwable> failure
    ) {
        while (!this.delayed.isEmpty() && this.delayed.peekFirst().tick() <= tick) {
            Respawn<R> respawn = this.delayed.removeFirst();
            if (preserveRespawn) {
                this.prepareOne(source.get(), respawn, request, failure);
            }
        }
        while (alive + pendingCount() < target) this.prepareOne(source.get(), null, request, failure);
    }

    private void prepareOne(
            P source,
            Respawn<R> respawn,
            Function<P, CompletableFuture<S>> request,
            Consumer<Throwable> failure
    ) {
        Ticket<P, S, R> ticket = new Ticket<>(source, respawn,
                respawn == null ? this.nextRouteSlot++ : respawn.routeSlot());
        this.pending.add(ticket);
        ticket.request = request.apply(source);
        ticket.request.whenComplete((result, error) -> {
            if (!ticket.valid) return;
            this.lifecycle.enqueue(() -> {
                if (!this.lifecycle.running() || !ticket.valid || !this.pending.contains(ticket)) return;
                if (error != null) {
                    failure.accept(error);
                } else {
                    ticket.result = result;
                    ticket.ready = true;
                }
            });
        });
    }

    public void admitReady() {
        for (Ticket<P, S, R> ticket : this.pending) if (ticket.ready) ticket.admitted = true;
    }

    public Iterator<Ticket<P, S, R>> iterator() {
        return this.pending.iterator();
    }

    public void cancel() {
        for (Ticket<P, S, R> ticket : this.pending) {
            ticket.valid = false;
            if (ticket.request != null) {
                ticket.request.cancel(false);
            }
            if (ticket.respawn != null) {
                this.delayed.addFirst(ticket.respawn);
            }
        }
        this.pending.clear();
    }
}
