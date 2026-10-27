package cn.mythicland.npclib.bot;

import cn.mythicland.framework.task.StaggeredDeadline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BotBattleStateTest {
    @Test
    void searchesOnStaggeredDeadlinesAndKeepsReturnHysteresis() {
        long tick = 100;
        int entityId = 17;
        BotBattleState battle = new BotBattleState(tick, entityId);
        long search = StaggeredDeadline.after(tick, entityId, 10);
        assertFalse(battle.searchDue(search - 1));
        assertTrue(battle.searchDue(search));
        assertFalse(battle.searchDue(search));
        assertTrue(battle.returning(36, 8));
        assertTrue(battle.returning(16, 8));
        assertFalse(battle.returning(4, 8));
        battle.targetChanged(tick, tick + 5);
        assertFalse(battle.canChangeTarget(tick + 24));
        assertTrue(battle.canChangeTarget(tick + 25));
        assertFalse(battle.targetHeldFor(tick + 59, 60));
        assertTrue(battle.targetHeldFor(tick + 60, 60));
    }
}
