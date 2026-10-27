package cn.mythicland.npclib.bot;

import cn.mythicland.npclib.bot.v1_8_R3.BotEntity;

public interface PatrolRobot extends AutoCloseable {
    BotEntity entity();

    @Override
    void close();
}
