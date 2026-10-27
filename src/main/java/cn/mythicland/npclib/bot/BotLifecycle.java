package cn.mythicland.npclib.bot;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * {@link #enqueue(Runnable)} and {@link #current(long)} may be called asynchronously.
 * Lifecycle changes and {@link #drain()} run on the server's main thread.
 */
public final class BotLifecycle {
    private final Queue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private volatile long generation;
    private boolean running;

    public void start() {
        this.running = true;
    }

    public boolean running() {
        return this.running;
    }

    public long invalidate() {
        return ++this.generation;
    }

    public boolean current(long generation) {
        return this.generation == generation;
    }

    public void enqueue(Runnable completion) {
        this.completions.add(completion);
    }

    public void drain() {
        Runnable completion;
        while ((completion = this.completions.poll()) != null) completion.run();
    }

    public void stop() {
        this.running = false;
        this.invalidate();
    }

    public void clear() {
        this.completions.clear();
    }
}
