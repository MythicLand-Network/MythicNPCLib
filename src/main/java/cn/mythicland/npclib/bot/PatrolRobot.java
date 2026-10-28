package cn.mythicland.npclib.bot;


public interface PatrolRobot extends AutoCloseable {
    BotHandle entity();

    @Override
    void close();
}
