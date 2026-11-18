package dev.kizuna.meridian.combat.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 输入边沿与取消不依赖游戏 tick，也不在恢复可输入时补发。 */
public final class GuardInputTest {
    @Test
    public void shortTapRetainsBothEdgesAndRepeatedPressOrReleaseIsEmpty() {
        // 同 tick 短按必须产生同一个非零标识的两个边沿。
        var input = new GuardInput();
        int first = input.press(GuardInput.Gate.OPEN);
        assertTrue(first > 0);
        assertEquals(0, input.press(GuardInput.Gate.OPEN));
        assertEquals(first, input.release());
        assertEquals(0, input.release());
        assertTrue(input.press(GuardInput.Gate.OPEN) > first);
    }

    @Test
    public void outsideAndCancelledHoldNeedPhysicalReleaseBeforeStartingAgain() {
        // 开屏、失焦、退出架势共用取消，仍按着时不能因恢复可输入而起式。
        var input = new GuardInput();
        assertEquals(0, input.press(GuardInput.Gate.CLOSED));
        assertEquals(0, input.press(GuardInput.Gate.OPEN));
        assertEquals(0, input.release());
        int first = input.press(GuardInput.Gate.OPEN);
        assertEquals(first, input.cancel());
        assertEquals(0, input.cancel());
        assertEquals(0, input.press(GuardInput.Gate.OPEN));
        assertEquals(0, input.release());
        assertTrue(input.press(GuardInput.Gate.OPEN) > first);
    }

    @Test
    public void disconnectDiscardsOldReleaseAndResetsConnectionIdentity() {
        // 后来连接不能收到旧连接的松开。
        var input = new GuardInput();
        int first = input.press(GuardInput.Gate.OPEN);
        input.disconnect();
        assertEquals(0, input.release());
        assertEquals(first, input.press(GuardInput.Gate.OPEN));
    }
}
