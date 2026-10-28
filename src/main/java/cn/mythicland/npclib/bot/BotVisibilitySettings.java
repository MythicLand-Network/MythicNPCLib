package cn.mythicland.npclib.bot;

public interface BotVisibilitySettings {
    double viewDistance();

    double idleSeconds();

    double maxViewerY();

    double nearbyDistance();

    double viewAngle();

    int visibilityCheckTicks();

    int maxRevealsPerTick();

    int spawnDelayTicks();

    int equipmentCheckTicks();
}
