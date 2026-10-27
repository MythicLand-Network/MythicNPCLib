package cn.mythicland.npclib.bot;

public final class BotSpawnBudget {
    private final int maxSpawns;
    private final long maxNanos;
    private int spawned;
    private long elapsedNanos;

    public BotSpawnBudget(int maxSpawns, long maxNanos) {
        if (maxSpawns <= 0 || maxNanos <= 0) throw new IllegalArgumentException("Invalid spawn budget");
        this.maxSpawns = maxSpawns;
        this.maxNanos = maxNanos;
    }

    public void reset() {
        this.spawned = 0;
        this.elapsedNanos = 0;
    }

    public boolean available() {
        return this.spawned < this.maxSpawns && this.elapsedNanos < this.maxNanos;
    }

    public void record(long elapsedNanos) {
        this.spawned++;
        this.elapsedNanos += elapsedNanos;
    }
}
