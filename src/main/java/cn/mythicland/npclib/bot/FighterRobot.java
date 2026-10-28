package cn.mythicland.npclib.bot;


public interface FighterRobot extends AutoCloseable {
    BotHandle entity();

    @Override
    void close();
}
