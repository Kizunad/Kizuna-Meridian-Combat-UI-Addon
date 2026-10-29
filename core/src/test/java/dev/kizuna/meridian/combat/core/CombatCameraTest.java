package dev.kizuna.meridian.combat.core;

import org.junit.Test;

import static org.junit.Assert.*;

/** 镜头位置与整段偏移碰撞收缩，不要求图形环境。 */
public final class CombatCameraTest {
    private static final double EPSILON = 0.000001;
    private static final float QUARTER_TURN = 90;
    private static final double WALL_Z = -1;
    private static final double SIDE_WALL_X = -0.3;
    private static final double CEILING_Y = 0.2;
    private static final double NEGATIVE_HIT = -0.1;

    @Test
    public void shoulderIsRightAboveAndBehindForDifferentCameraAngles() {
        // 朝南时右肩为西侧；朝西时右肩为北侧，人物始终位于画面左下。
        var settings = CombatViewSettings.DEFAULT;
        double shoulderOffset = settings.shoulderOffset();
        double heightOffset = settings.heightOffset();
        double distance = settings.distance();
        var south = CombatCamera.desired(new CombatView.Angles(0, 0), settings);
        assertEquals(-shoulderOffset, south.x(), EPSILON);
        assertEquals(heightOffset, south.y(), EPSILON);
        assertEquals(-distance, south.z(), EPSILON);
        var west = CombatCamera.desired(new CombatView.Angles(QUARTER_TURN, 0), settings);
        assertEquals(distance, west.x(), EPSILON);
        assertEquals(-shoulderOffset, west.z(), EPSILON);
        var down = CombatCamera.desired(new CombatView.Angles(0, QUARTER_TURN), settings);
        assertEquals(distance, down.y(), EPSILON);
        assertEquals(heightOffset, down.z(), EPSILON);
    }

    @Test
    public void unobstructedCameraKeepsFullConfiguredOffset() {
        // 探测未命中时不扣安全距离，完整保留用户选择的距离与偏移。
        var desired = CombatCamera.desired(new CombatView.Angles(0, 0), CombatViewSettings.DEFAULT);
        assertEquals(desired, CombatCamera.clip(desired, (start, end) -> 1));
    }

    @Test
    public void backWallShrinksEntireOffsetAndReleasesImmediatelyWhenClear() {
        // 后墙使右、上、后等比拉近；离开墙壁后下一帧恢复完整位置。
        var desired = CombatCamera.desired(new CombatView.Angles(0, 0), CombatViewSettings.DEFAULT);
        var clipped = CombatCamera.clip(desired, (start, end) -> (WALL_Z - start.z()) / (end.z() - start.z()));
        assertTrue(clipped.z() > WALL_Z + CombatCamera.PROBE_RADIUS);
        assertTrue(Math.abs(clipped.x()) < Math.abs(desired.x()));
        assertTrue(clipped.y() < desired.y());
        assertEquals(desired, CombatCamera.clip(desired, (start, end) -> 1));
    }

    @Test
    public void shoulderSideWallAndCeilingAlsoPullCameraTowardEyes() {
        // 只检测后退距离会漏掉侧墙和低顶，整段探测必须同时阻止这两种穿透。
        var desired = CombatCamera.desired(new CombatView.Angles(0, 0), CombatViewSettings.DEFAULT);
        var side = CombatCamera.clip(desired,
                (start, end) -> (SIDE_WALL_X - start.x()) / (end.x() - start.x()));
        assertTrue(side.x() > SIDE_WALL_X + CombatCamera.PROBE_RADIUS);
        var ceiling = CombatCamera.clip(desired,
                (start, end) -> (CEILING_Y - start.y()) / (end.y() - start.y()));
        assertTrue(ceiling.y() < CEILING_Y - CombatCamera.PROBE_RADIUS);
    }

    @Test
    public void obstructionAtEyeClampsToZeroWithoutReversingCamera() {
        // 起点附近碰撞不能产生负距离或把镜头推出人物前方。
        var desired = CombatCamera.desired(new CombatView.Angles(0, 0), CombatViewSettings.DEFAULT);
        assertEquals(new CombatCamera.Offset(0, 0, 0),
                CombatCamera.clip(desired, (start, end) -> NEGATIVE_HIT));
    }
}
