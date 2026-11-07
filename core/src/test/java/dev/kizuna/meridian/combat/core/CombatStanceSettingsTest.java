package dev.kizuna.meridian.combat.core;

import java.nio.file.Files;
import java.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 纯配置、锁视角、编辑时间步长和目标转身边界。 */
public final class CombatStanceSettingsTest {
    private static final double EPSILON = 0.00001;
    private static final long SECOND = 1_000_000_000L;
    private static final float TURN_STEP = 18;
    private static final float SEAM = 179;
    private static final double ABOVE = 10;
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void lockKeepsMouseLookAndOnlyLinksAnActiveShoulderSession() {
        // 联动开关不拦截鼠标；退出和第一人称禁用联动，重进从人物当前角度起步。
        var view = new CombatView(CombatView.Mode.SHOULDER);
        var entry = new CombatView.Angles(SEAM, TURN_STEP);
        view.configureLock(CombatStanceSettings.ViewLock.ON);
        assertFalse(view.locked());
        view.enter(CombatView.Perspective.THIRD_PERSON_FRONT, entry);
        assertTrue(view.locked());
        view.rotate(TURN_STEP, TURN_STEP);
        var rotated = view.camera();
        assertNotEquals(entry.yaw(), rotated.yaw(), EPSILON);
        assertEquals(entry.pitch() + TURN_STEP, rotated.pitch(), EPSILON);
        view.configureLock(CombatStanceSettings.ViewLock.OFF);
        assertEquals(rotated, view.camera());
        view.rotate(1, 0);
        assertEquals(rotated.yaw() + 1, view.camera().yaw(), EPSILON);
        view.configureLock(CombatStanceSettings.ViewLock.ON);
        view.toggle(entry);
        assertFalse(view.locked());
        var firstPerson = view.camera();
        view.rotate(TURN_STEP, TURN_STEP);
        assertEquals(firstPerson, view.camera());
        assertEquals(CombatView.Perspective.THIRD_PERSON_FRONT, view.exit());
        assertFalse(view.locked());
        var next = new CombatView.Angles(0, 0);
        view.enter(CombatView.Perspective.FIRST_PERSON, next);
        view.toggle(next);
        assertEquals(next, view.camera());
        assertTrue(view.locked());
    }

    @Test
    public void editSpeedIsFrameIndependentAndDiagonalDoesNotAccelerate() {
        // 相同一秒输入在不同帧数下等长，暂停大帧只允许有限步长。
        var slow = editForFrames(20, new CombatCameraEdit.Axes(1, 0, 0));
        var fast = editForFrames(100, new CombatCameraEdit.Axes(1, 0, 0));
        assertEquals(slow.shoulderOffset(), fast.shoulderOffset(), EPSILON);
        var diagonal = editForFrames(100, new CombatCameraEdit.Axes(1, 1, 0));
        double right = diagonal.shoulderOffset() - CombatViewSettings.DEFAULT.shoulderOffset();
        double up = diagonal.heightOffset() - CombatViewSettings.DEFAULT.heightOffset();
        assertEquals(CombatCameraEdit.BLOCKS_PER_SECOND, Math.hypot(right, up), EPSILON);
        var edit = new CombatCameraEdit(CombatViewSettings.DEFAULT, 0);
        edit.step(new CombatCameraEdit.Axes(1, 0, 0), SECOND);
        assertEquals(CombatViewSettings.DEFAULT.shoulderOffset()
                + CombatCameraEdit.BLOCKS_PER_SECOND * CombatCameraEdit.MAX_FRAME_SECONDS,
                edit.draft().shoulderOffset(), EPSILON);
        edit.step(CombatCameraEdit.Axes.ZERO, SECOND * 2);
        edit.reset();
        assertEquals(CombatViewSettings.DEFAULT, edit.draft());
    }

    private static CombatViewSettings editForFrames(int frames, CombatCameraEdit.Axes axes) {
        // 输入时间由测试显式推进，避免墙钟和调度抖动影响断言。
        var editor = new CombatCameraEdit(CombatViewSettings.DEFAULT, 0);
        for (int frame = 1; frame <= frames; frame++) {
            editor.step(axes, SECOND * frame / frames);
        }
        return editor.draft();
    }

    @Test
    public void faceUsesShortestArcAndKeepsCoincidentAnglesFinite() {
        // 接缝两侧的目标不绕远路，同点不产生 NaN，正上方不改 yaw。
        var facing = new CombatView.Angles(SEAM, 0);
        double targetYaw = Math.toRadians(-SEAM);
        var behind = new CombatCamera.Offset(-Math.sin(targetYaw), 0, Math.cos(targetYaw));
        assertEquals(SEAM + 2, CombatViewMath.face(behind, facing, TURN_STEP).yaw(), EPSILON);
        assertEquals(facing, CombatViewMath.face(new CombatCamera.Offset(0, 0, 0), facing, TURN_STEP));
        var above = CombatViewMath.face(new CombatCamera.Offset(0, ABOVE, 0), facing, TURN_STEP);
        assertEquals(SEAM, above.yaw(), EPSILON);
        assertEquals(-TURN_STEP, above.pitch(), EPSILON);
    }

    @Test
    public void signedOffsetsAndRadiusRejectNonFiniteValues() {
        // 允许范围两端，非法输入不进入正式配置或草稿。
        var defaults = CombatViewSettings.DEFAULT;
        new CombatViewSettings(defaults.mode(), -CombatViewSettings.MAX_OFFSET, CombatViewSettings.MAX_OFFSET,
                CombatViewSettings.MIN_DISTANCE, defaults.turnDegreesPerTick()); // 构造即校验，非法时抛异常。
        double[] invalidRadii = {Double.NaN, Double.POSITIVE_INFINITY, 0, CombatStanceSettings.MAX_RADIUS + 1};
        for (double invalid : invalidRadii) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CombatStanceSettings(CombatStanceSettings.ViewLock.OFF, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new CombatViewSettings(defaults.mode(), Double.NaN,
                0, defaults.distance(), defaults.turnDegreesPerTick()));
    }

    @Test
    public void versionTwoMigratesAllProfilesAndNewPreferencesRoundTrip() throws Exception {
        // 由真实旧字段构造格式二文件，迁移后十槽与分档逐项保留。
        var path = temporary.newFile().toPath();
        var profiles = new CombatProfiles();
        profiles.enter("world:settings", Instant.EPOCH);
        profiles.set(CombatIds.SLOT_COUNT, "test:stance", Instant.EPOCH);
        var file = new CombatConfigFile(path);
        file.save(profiles);
        var root = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        root.addProperty("format_version", 2);
        root.getAsJsonObject("view").remove("lock_current_view");
        root.getAsJsonObject("view").remove("auto_face_radius");
        Files.writeString(path, root.toString());
        var migrated = new CombatConfigFile(path).load();
        assertEquals(profiles.snapshot(), migrated.snapshot());
        assertEquals(CombatStanceSettings.DEFAULT, migrated.stance());
        var preference = new CombatStanceSettings(CombatStanceSettings.ViewLock.ON, CombatStanceSettings.MIN_RADIUS);
        migrated.configure(migrated.view(), preference);
        file.save(migrated);
        assertEquals(preference, new CombatConfigFile(path).load().stance());
    }

    @Test
    public void candidateSaveFailureLeavesPublishedSettingsAndCorruptOriginalUntouched() throws Exception {
        // 候选对象与正式对象独立，坏配置保护阻止覆盖并可继续保留编辑草稿。
        var path = temporary.newFile().toPath();
        String corrupt = "{broken";
        Files.writeString(path, corrupt);
        var file = new CombatConfigFile(path);
        assertThrows(java.io.IOException.class, file::load);
        var profiles = new CombatProfiles();
        var candidate = profiles.configured(profiles.view(), profiles.stance().toggleLock());
        assertThrows(java.io.IOException.class, () -> file.save(candidate));
        assertEquals(CombatStanceSettings.DEFAULT, profiles.stance());
        assertEquals(corrupt, Files.readString(path));
    }
}
