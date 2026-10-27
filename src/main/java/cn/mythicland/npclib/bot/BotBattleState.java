package cn.mythicland.npclib.bot;

import cn.mythicland.framework.task.StaggeredDeadline;

public final class BotBattleState {
    private final int entityId;
    private long nextSearch;
    private long nextReconsider;
    private long targetSince;
    private long nextTargetChange;
    private boolean returning;

    public BotBattleState(long tick, int entityId) {
        this.entityId = entityId;
        this.nextSearch = StaggeredDeadline.after(tick, entityId, 10);
        this.nextReconsider = StaggeredDeadline.after(tick, entityId, 20);
    }

    public boolean searchDue(long tick) {
        if (tick < this.nextSearch) return false;
        this.nextSearch = StaggeredDeadline.after(tick, this.entityId, 10);
        return true;
    }

    public boolean reconsiderDue(long tick) {
        if (tick < this.nextReconsider) return false;
        this.nextReconsider = StaggeredDeadline.after(tick, this.entityId, 20);
        return true;
    }

    public boolean returning(double centerDistanceSquared, double returnRadius) {
        if (centerDistanceSquared >= Math.pow(returnRadius * 0.65, 2)) {
            this.returning = true;
        } else if (centerDistanceSquared <= Math.pow(returnRadius * 0.35, 2)) {
            this.returning = false;
        }
        return this.returning;
    }

    public boolean canChangeTarget(long tick) {
        return tick >= this.nextTargetChange;
    }

    public boolean targetHeldFor(long tick, long duration) {
        return tick - this.targetSince >= duration;
    }

    public void targetChanged(long tick, long reactionUntil) {
        this.targetSince = tick;
        this.nextTargetChange = reactionUntil + 20;
    }
}
