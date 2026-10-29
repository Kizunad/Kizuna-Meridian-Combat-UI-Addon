package dev.kizuna.meridian.combat.core;

import org.junit.Test;

import static org.junit.Assert.*;

/** 视角会话、镜头输入、移动换算和人物转身的独立回归。 */
public final class CombatViewTest {
    private static final double EPSILON = 0.000001;
    private static final float QUARTER_TURN = 90;
    private static final float HALF_TURN = 180;
    private static final float STEP = 18;
    private static final float ORIGINAL_PITCH = 25;
    private static final float LARGE_DELTA = 1000;
    private static final float SMALL_INPUT = 0.3f;
    private static final float DIAGONAL_YAW = -45;
    private static final float NEAR_SEAM = 179;
    private static final float ACROSS_SEAM = -179;
    private static final float CONTINUOUS_SEAM = 181;
    private static final double DIAGONAL_MAGNITUDE = Math.sqrt(2);
    private static final CombatView.Angles FACING = new CombatView.Angles(0, ORIGINAL_PITCH);

    @Test
    public void defaultsToFirstPersonAndRetainsSelectionAcrossSessions() {
        // 新用户进入第一人称，选择越肩后退出、重进与重建会话都不丢偏好。
        var view = new CombatView(CombatViewSettings.DEFAULT.mode());
        assertFalse(view.active());
        view.enter(CombatView.Perspective.THIRD_PERSON_FRONT, FACING);
        assertEquals(CombatView.Perspective.FIRST_PERSON, view.perspective());
        view.toggle(FACING);
        assertTrue(view.shoulder());
        assertEquals(CombatView.Perspective.THIRD_PERSON_FRONT, view.exit());
        assertFalse(view.shoulder());
        view.enter(CombatView.Perspective.FIRST_PERSON, FACING);
        assertTrue(view.shoulder());
        var reloaded = new CombatView(view.mode());
        reloaded.enter(CombatView.Perspective.FIRST_PERSON, FACING);
        assertTrue(reloaded.shoulder());
    }

    @Test
    public void restoresEveryOriginalPerspectiveDespiteRepeatedReconciliation() {
        // 第一人称、背面和正面第三人称都应精确恢复，重复协调不能覆盖原视角。
        for (CombatView.Perspective original : CombatView.Perspective.values()) {
            var view = new CombatView(CombatView.Mode.FIRST_PERSON);
            view.enter(original, FACING);
            view.enter(CombatView.Perspective.FIRST_PERSON, FACING);
            view.toggle(FACING);
            view.toggle(FACING);
            view.toggle(FACING);
            assertEquals(original, view.exit());
            view.toggle(FACING);
            assertEquals(CombatView.Mode.SHOULDER, view.mode());
            assertFalse(view.active());
        }
    }

    @Test
    public void cameraLookIsIndependentAndSwitchingNeverTurnsTheCharacter() {
        // 绕看只改变镜头快照；切回第一人称后再切越肩以人物角度重新起步。
        var view = shoulder();
        view.rotate(QUARTER_TURN, LARGE_DELTA);
        var camera = view.camera();
        assertEquals(QUARTER_TURN, camera.yaw(), EPSILON);
        assertEquals(QUARTER_TURN, camera.pitch(), EPSILON);
        assertEquals(0, FACING.yaw(), EPSILON);
        assertEquals(ORIGINAL_PITCH, FACING.pitch(), EPSILON);
        view.rotate(0, -LARGE_DELTA);
        assertEquals(-QUARTER_TURN, view.camera().pitch(), EPSILON);
        view.toggle(FACING);
        view.rotate(QUARTER_TURN, 0);
        assertEquals(CombatView.Mode.FIRST_PERSON, view.mode());
        view.toggle(FACING);
        assertEquals(FACING, view.camera());
    }

    @Test
    public void cameraRelativeAxesPreserveMovementAndInputMagnitudeDuringTurning() {
        // 人物尚未转到镜头方向时，W 仍立即朝镜头前方移动且不损失潜行幅度。
        float[] entityYaws = {0, STEP, QUARTER_TURN, HALF_TURN, -QUARTER_TURN};
        for (float entityYaw : entityYaws) {
            var axes = CombatViewMath.relativeInput(SMALL_INPUT, 0, QUARTER_TURN, entityYaw);
            var world = CombatDirection.fromInput(axes.forward(), axes.sideways(), entityYaw);
            assertEquals(-1, world.x(), EPSILON);
            assertEquals(0, world.z(), EPSILON);
            assertEquals(SMALL_INPUT, Math.hypot(axes.forward(), axes.sideways()), EPSILON);
        }
        var diagonal = CombatViewMath.relativeInput(1, 1, QUARTER_TURN, STEP);
        assertEquals(DIAGONAL_MAGNITUDE, Math.hypot(diagonal.forward(), diagonal.sideways()), EPSILON);
        assertEquals(new CombatViewMath.Axes(0, 0), CombatViewMath.relativeInput(0, 0, QUARTER_TURN, STEP));
    }

    @Test
    public void wasdAndDiagonalsFaceTheirCameraRelativeDirections() {
        // 镜头朝南时，W/S/A/D 的人物方向分别为南北东西，斜向为中间方向。
        assertEquals(0, CombatViewMath.movementYaw(1, 0, 0, STEP), EPSILON);
        assertEquals(HALF_TURN, Math.abs(CombatViewMath.movementYaw(-1, 0, 0, STEP)), EPSILON);
        assertEquals(-QUARTER_TURN, CombatViewMath.movementYaw(0, 1, 0, STEP), EPSILON);
        assertEquals(QUARTER_TURN, CombatViewMath.movementYaw(0, -1, 0, STEP), EPSILON);
        assertEquals(DIAGONAL_YAW, CombatViewMath.movementYaw(1, 1, 0, STEP), EPSILON);
        assertEquals(STEP, CombatViewMath.movementYaw(0, 0, QUARTER_TURN, STEP), EPSILON);
    }

    @Test
    public void turnsAtConfiguredRateAlongShortestArcWithoutOvershooting() {
        // 正反转、越过角度接缝和不足一步时均连续且不越过目标。
        assertEquals(STEP, CombatViewMath.turnYaw(0, QUARTER_TURN, STEP), EPSILON);
        assertEquals(-STEP, CombatViewMath.turnYaw(0, -QUARTER_TURN, STEP), EPSILON);
        assertEquals(CONTINUOUS_SEAM, CombatViewMath.turnYaw(NEAR_SEAM, ACROSS_SEAM, STEP), EPSILON);
        assertEquals(1, CombatViewMath.turnYaw(0, 1, STEP), EPSILON);
        float yaw = 0;
        for (int tick = 0; tick < 10; tick++) {
            yaw = CombatViewMath.turnYaw(yaw, QUARTER_TURN, STEP);
        }
        assertEquals(QUARTER_TURN, yaw, EPSILON);
        assertEquals(QUARTER_TURN,
                CombatViewMath.turnYaw(QUARTER_TURN, QUARTER_TURN, STEP), EPSILON);
    }

    @Test
    public void shoulderCastFollowsCurrentCharacterNotCameraOrMovementTarget() {
        // 人物还在转身时出招必须沿当前 yaw，停步则传零，不自动转向镜头。
        var view = shoulder();
        view.rotate(-QUARTER_TURN, 0);
        var direction = view.castDirection(-1, 1, QUARTER_TURN);
        assertEquals(-1, direction.x(), EPSILON);
        assertEquals(0, direction.z(), EPSILON);
        assertEquals(new CombatDirection(0, 0), view.castDirection(0, 0, QUARTER_TURN));
        view.toggle(FACING);
        assertEquals(CombatDirection.fromInput(-1, 1, QUARTER_TURN),
                view.castDirection(-1, 1, QUARTER_TURN));
    }

    private static CombatView shoulder() {
        // 每个用例从独立的活动越肩会话开始。
        var view = new CombatView(CombatView.Mode.SHOULDER);
        view.enter(CombatView.Perspective.FIRST_PERSON, FACING);
        return view;
    }
}
