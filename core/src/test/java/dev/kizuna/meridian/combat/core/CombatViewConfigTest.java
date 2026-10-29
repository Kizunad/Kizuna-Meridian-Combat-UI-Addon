package dev.kizuna.meridian.combat.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 视角偏好持久化、旧配置迁移与无效视角参数保护。 */
public final class CombatViewConfigTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final double CUSTOM_RIGHT = 0.8;
    private static final double CUSTOM_UP = 0.4;
    private static final double CUSTOM_DISTANCE = 4;
    private static final float CUSTOM_TURN = 12;
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void modeAndGeometryRoundTripWithoutLeakingAcrossProfileOperations() throws Exception {
        // 切服与淘汰操作不能重置全局模式；自定义几何参数应逐值保留。
        var settings = new CombatViewSettings(CombatView.Mode.SHOULDER,
                CUSTOM_RIGHT, CUSTOM_UP, CUSTOM_DISTANCE, CUSTOM_TURN);
        var profiles = new CombatProfiles("", Map.of(), settings);
        profiles.enter("server:first", NOW);
        profiles.set(1, "sample:step", NOW);
        profiles.enter("world:second", NOW);
        var path = temporary.getRoot().toPath().resolve("combat.json");
        var file = new CombatConfigFile(path);
        file.save(profiles);
        var loaded = new CombatConfigFile(path).load();
        assertEquals(settings, loaded.view());
        assertEquals(profiles.snapshot(), loaded.snapshot());
        loaded.selectViewMode(CombatView.Mode.FIRST_PERSON);
        file.save(loaded);
        var firstPerson = new CombatConfigFile(path).load().view();
        assertEquals(settings.withMode(CombatView.Mode.FIRST_PERSON), firstPerson);
    }

    @Test
    public void legacyConfigurationKeepsSlotsAndMigratesToFirstPerson() throws Exception {
        // 首版槽位文件不带视角对象，升级后默认第一人称且不丢分档与十槽。
        String legacy = "{\"format_version\":1,\"last_profile\":\"server:old\",\"profiles\":{"
                + "\"server:old\":{\"last_used\":\"2026-10-28T00:00:00Z\","
                + "\"slots\":[\"sample:step\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"sample:step\"]}}}";
        var path = temporary.newFile().toPath();
        Files.writeString(path, legacy);
        var file = new CombatConfigFile(path);
        var profiles = file.load();
        assertEquals(CombatViewSettings.DEFAULT, profiles.view());
        assertEquals("sample:step", profiles.slots().get(CombatIds.SLOT_COUNT - 1));
        file.save(profiles);
        assertTrue(Files.readString(path).contains("\"format_version\": 2"));
        assertEquals(profiles.snapshot(), new CombatConfigFile(path).load().snapshot());
    }

    @Test
    public void invalidOrFutureViewConfigurationPreservesOriginalBytes() throws Exception {
        // 错误枚举、非有限值、越界、缺字段及未来版本都禁止覆盖原件。
        List<String> invalidViews = List.of(
                "{\"mode\":\"UNKNOWN\"}",
                "{\"mode\":\"SHOULDER\",\"shoulder_offset\":-1,\"height_offset\":0,"
                        + "\"distance\":3,\"turn_degrees_per_tick\":18}",
                "{\"mode\":\"SHOULDER\",\"shoulder_offset\":0,\"height_offset\":0,"
                        + "\"distance\":3,\"turn_degrees_per_tick\":\"NaN\"}",
                "{\"mode\":\"SHOULDER\",\"shoulder_offset\":0,\"height_offset\":0,"
                        + "\"distance\":3,\"turn_degrees_per_tick\":1e1000}",
                "{\"mode\":\"SHOULDER\",\"shoulder_offset\":\"0.65\",\"height_offset\":0,"
                        + "\"distance\":3,\"turn_degrees_per_tick\":18}",
                "{\"mode\":\"FIRST_PERSON\",\"shoulder_offset\":0,\"height_offset\":0,"
                        + "\"distance\":0,\"turn_degrees_per_tick\":18}", "{}");
        for (String view : invalidViews) {
            assertProtected("{\"format_version\":2,\"last_profile\":\"\",\"profiles\":{},\"view\":" + view + "}");
        }
        int futureVersion = CombatConfigFile.FORMAT_VERSION + 1;
        assertProtected("{\"format_version\":" + futureVersion + "}");
    }

    private void assertProtected(String original) throws Exception {
        // 检查失败后尝试写回也必须被拒绝，且原文件逐字节保留。
        var path = temporary.newFile().toPath();
        Files.writeString(path, original);
        var file = new CombatConfigFile(path);
        assertThrows(java.io.IOException.class, file::load);
        assertFalse(file.writable());
        assertThrows(java.io.IOException.class, () -> file.save(new CombatProfiles()));
        assertEquals(original, Files.readString(path));
    }
}
