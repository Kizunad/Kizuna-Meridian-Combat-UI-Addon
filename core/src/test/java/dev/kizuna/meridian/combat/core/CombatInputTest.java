package dev.kizuna.meridian.combat.core;

import org.junit.Test;

import java.time.Instant;

import static dev.kizuna.meridian.combat.core.CombatInput.*;
import static org.junit.Assert.*;

/** 输入模式只决定是否消费快捷栏按键，不改变原版其他输入。 */
public final class CombatInputTest {
    private static final double TOLERANCE = 0.000001;
    private static final float TURN_RIGHT = 90;
    private static final float TURN_BACK = 180;

    @Test
    public void entryRequiresSupportedServerButNoUsableSlots() {
        // 服务器通道握手仍是前提，槽位状态不参与架势入口。
        var input = new CombatInput();
        assertEquals("服务器不支持战斗架势",
                input.toggle(Availability.READY, Screen.CLOSED, Support.UNSUPPORTED));
        assertEquals(State.OUTSIDE, input.state());
        assertFalse(input.consumesHotbar(Availability.READY, Screen.CLOSED));
        assertEquals("已进入战斗架势",
                input.toggle(Availability.READY, Screen.CLOSED, Support.SUPPORTED));
        assertTrue(input.consumesHotbar(Availability.READY, Screen.CLOSED));
        assertEquals("已退出战斗架势",
                input.toggle(Availability.READY, Screen.CLOSED, Support.SUPPORTED));
        assertEquals(State.OUTSIDE, input.state());
    }

    @Test
    public void emptySlotsOnlyReturnPromptAndRetainStance() {
        // 未学任何功法时十槽都只返回提示，仍保持架势并消费快捷栏按键。
        var profiles = new CombatProfiles();
        profiles.enter("world:empty", Instant.EPOCH);
        var slots = new CombatSlots(profiles);
        var input = new CombatInput();
        input.toggle(Availability.READY, Screen.CLOSED, Support.SUPPORTED);
        for (int number = 1; number <= CombatIds.SLOT_COUNT; number++) {
            var selection = slots.select(number);
            assertEquals("", selection.techniqueId());
            assertEquals("第 " + number + " 槽为空", selection.reason());
            assertEquals(State.ACTIVE, input.state());
            assertTrue(input.consumesHotbar(Availability.READY, Screen.CLOSED));
        }
    }

    @Test
    public void openScreenSuspendsConsumptionAndRetainsStance() {
        // 开界面不能误施放或退出已有架势，关界面后恢复消费。
        var input = new CombatInput();
        input.toggle(Availability.READY, Screen.OPEN, Support.SUPPORTED);
        assertEquals(State.OUTSIDE, input.state());
        input.toggle(Availability.READY, Screen.CLOSED, Support.SUPPORTED);
        input.toggle(Availability.READY, Screen.OPEN, Support.SUPPORTED);
        assertEquals(State.ACTIVE, input.state());
        assertFalse(input.consumesHotbar(Availability.READY, Screen.OPEN));
        assertTrue(input.consumesHotbar(Availability.READY, Screen.CLOSED));
    }

    @Test
    public void deathSpectatorAndDisconnectAlwaysExit() {
        // 自动退出后的复活或重连不能自行恢复架势。
        for (Availability unavailable : new Availability[]{Availability.DEAD, Availability.SPECTATOR,
                Availability.DISCONNECTED}) {
            var input = new CombatInput();
            input.toggle(Availability.READY, Screen.CLOSED, Support.SUPPORTED);
            input.reconcile(unavailable);
            input.reconcile(Availability.READY);
            assertEquals(State.OUTSIDE, input.state());
            input.toggle(unavailable, Screen.CLOSED, Support.SUPPORTED);
            assertEquals(State.OUTSIDE, input.state());
        }
    }

    @Test
    public void movementUsesWorldYawAndNormalizesDiagonals() {
        // 正前、侧移、后撤和斜向与 Minecraft 世界轴保持一致。
        assertEquals(new CombatDirection(0, 0), CombatDirection.fromInput(0, 0, TURN_RIGHT));
        CombatDirection forward = CombatDirection.fromInput(1, 0, TURN_RIGHT);
        assertEquals(-1, forward.x(), TOLERANCE);
        assertEquals(0, forward.z(), TOLERANCE);
        CombatDirection left = CombatDirection.fromInput(0, 1, 0);
        assertEquals(1, left.x(), TOLERANCE);
        CombatDirection backward = CombatDirection.fromInput(-1, 0, TURN_BACK);
        assertEquals(1, backward.z(), TOLERANCE);
        CombatDirection diagonal = CombatDirection.fromInput(1, 1, 0);
        assertEquals(1, Math.hypot(diagonal.x(), diagonal.z()), TOLERANCE);
    }
}
