package dev.kizuna.meridian.combat.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CombatCastBarTest {
    private static final int WIDTH = 200;
    private static final long WARMUP_END = 500;
    private static final long ACTIVE_END = 1000;
    private static final long FREE = 1500;
    private static final long RECOVERY_END = 2000;
    private static final long HIT = 750;
    private static final String NEXT = "sample:next";
    private static final String OTHER = "sample:other";
    private static final long LONG_HOLD = 90_000_000;

    @Test
    public void unknownEndStaysBusyWithoutInventingATimeline() {
        // 零结束边界表示未知，不表示空闲；即使守很久也不显示假的长条。
        var guard = new CombatCast(LONG_HOLD, WARMUP_END, 0, 0, 0, List.of(), List.of(), "",
                CombatCast.EndTimes.UNKNOWN);
        assertTrue(guard.casting());
        assertTrue(guard.busy(OTHER));
        assertFalse(guard.followUpOpen(OTHER));
        assertEquals(0, guard.spanMillis());
        var recovering = new CombatCast(FREE - 1, WARMUP_END, ACTIVE_END, RECOVERY_END, RECOVERY_END,
                List.of(), List.of(), "");
        assertTrue(recovering.busy(OTHER));
        assertEquals(RECOVERY_END, recovering.spanMillis());
    }

    @Test
    public void layoutMapsEveryMomentOntoTheBarInProportion() {
        // 没有承接窗口伸到收势后面时，整条就是起手到收势结束；各时刻按比例落位，走过头的指针停在末端。
        var bar = CombatCastBar.layout(cast(HIT, List.of()), WIDTH);
        assertEquals(new CombatCastBar.Layout(50, 100, 150, 200, 75, List.of(75), List.of()), bar);
        assertEquals(WIDTH, CombatCastBar.layout(cast(RECOVERY_END + 1, List.of()), WIDTH).cursor());
    }

    @Test
    public void windowOpenAfterRecoveryStretchesTheBarAndKeepsRecoveryInsideIt() {
        // 承接窗口关得比收势结束晚：整条延长到窗口关上，收势的末端落在条中间，窗口那一段照样画得出来。
        var window = new CombatCast.Window(NEXT, 1500, 2500);
        var bar = CombatCastBar.layout(cast(0, List.of(window)), WIDTH);
        assertEquals(160, bar.recoveryEnd());
        assertEquals(List.of(new CombatCastBar.Span(120, 200)), bar.windows());
    }

    @Test
    public void castTellsWhichTechniquesAreBlockedRightNow() {
        // 收招段以前别的式都按不出来；承接窗口开着的那一式例外；进了收招段、或者收势走完，就都不挡了。
        var window = new CombatCast.Window(NEXT, 1200, 2500);
        assertTrue(cast(1199, List.of(window)).busy(NEXT));
        assertFalse(cast(1200, List.of(window)).busy(NEXT));
        assertTrue(cast(1200, List.of(window)).followUpOpen(NEXT));
        assertTrue(cast(FREE - 1, List.of(window)).busy(OTHER));
        assertFalse(cast(FREE, List.of(window)).busy(OTHER));
        assertFalse(cast(RECOVERY_END, List.of(window)).casting());
        assertTrue(cast(RECOVERY_END, List.of(window)).followUpOpen(NEXT));
        assertFalse(CombatCast.NONE.busy(OTHER));
    }

    private static CombatCast cast(long elapsed, List<CombatCast.Window> windows) {
        return new CombatCast(elapsed, WARMUP_END, ACTIVE_END, RECOVERY_END, FREE, List.of(HIT), windows, "");
    }
}
