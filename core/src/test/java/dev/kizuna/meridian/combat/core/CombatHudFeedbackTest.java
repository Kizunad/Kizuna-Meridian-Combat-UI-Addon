package dev.kizuna.meridian.combat.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CombatHudFeedbackTest {
    @Test
    public void pressRespondsForTwoFramesAndThenRestoresState() {
        // 同帧重复读取不消耗反馈，其他槽也不受影响。
        var feedback = new CombatHudFeedback();
        feedback.press(1);
        assertEquals(CombatHudFeedback.Frame.PRESSED, feedback.frame(1));
        assertEquals(CombatHudFeedback.Frame.PRESSED, feedback.frame(1));
        assertEquals(CombatHudFeedback.Frame.NORMAL, feedback.frame(2));
        feedback.advanceFrame();
        assertEquals(CombatHudFeedback.Frame.RELEASED, feedback.frame(1));
        feedback.advanceFrame();
        assertEquals(CombatHudFeedback.Frame.NORMAL, feedback.frame(1));
    }

    @Test
    public void repeatedPressRestartsFeedbackAndDisconnectClearsIt() {
        // 连续输入重新点亮当前槽，清理不能把旧按下带到下一个连接。
        var feedback = new CombatHudFeedback();
        feedback.press(CombatIds.SLOT_COUNT);
        feedback.advanceFrame();
        feedback.press(CombatIds.SLOT_COUNT);
        assertEquals(CombatHudFeedback.Frame.PRESSED, feedback.frame(CombatIds.SLOT_COUNT));
        feedback.clear();
        assertEquals(CombatHudFeedback.Frame.NORMAL, feedback.frame(CombatIds.SLOT_COUNT));
    }
}
