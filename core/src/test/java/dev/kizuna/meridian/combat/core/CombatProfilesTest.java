package dev.kizuna.meridian.combat.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 本地文件的兼容保护与跨服务器分档回归。 */
public final class CombatProfilesTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final String TECHNIQUE = "sample:step";
    private static final String FIRST = "server:first:25565";
    private static final String SECOND = "server:second:25565";
    private static final int SLOT_TEN = CombatIds.SLOT_COUNT;
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void fileRoundTripRetainsTenSlotsAndIndependentProfiles() throws Exception {
        // 重复功法、末槽和分档时刻都必须完整保留。
        var profiles = new CombatProfiles();
        profiles.enter(FIRST, NOW);
        profiles.set(1, TECHNIQUE, NOW);
        profiles.set(SLOT_TEN, TECHNIQUE, NOW);
        Instant secondVisit = NOW.plusSeconds(1);
        profiles.enter(SECOND, secondVisit);
        profiles.clear(1, secondVisit);
        var path = temporary.getRoot().toPath().resolve("combat.json");
        var file = new CombatConfigFile(path);
        file.save(profiles);
        CombatProfiles loaded = new CombatConfigFile(path).load();
        var loadedProfiles = loaded.snapshot();
        assertEquals(profiles.snapshot(), loadedProfiles);
        assertEquals(SECOND, loaded.current());
        assertEquals(TECHNIQUE, loaded.slots().get(SLOT_TEN - 1));
        assertEquals(TECHNIQUE, loadedProfiles.get(FIRST).slots().get(0));
        try (var files = Files.list(path.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    public void corruptAndFutureFilesRemainByteForByteUnchanged() throws Exception {
        // 解析错误、高版本和错误槽长均禁止后续保存覆盖原件。
        int futureVersion = CombatConfigFile.FORMAT_VERSION + 1;
        String future = "{\"format_version\":" + futureVersion + "}";
        List<String> invalid = List.of("{", future,
                "{\"format_version\":1,\"last_profile\":\"server:x\",\"profiles\":{"
                        + "\"server:x\":{\"last_used\":\"2026-10-28T00:00:00Z\",\"slots\":[]}}}");
        for (String original : invalid) {
            var path = temporary.newFile().toPath();
            Files.writeString(path, original);
            var file = new CombatConfigFile(path);
            assertThrows(java.io.IOException.class, file::load);
            assertFalse(file.writable());
            assertThrows(java.io.IOException.class, () -> file.save(new CombatProfiles()));
            assertEquals(original, Files.readString(path));
        }
    }

    @Test
    public void oversizedFileIsProtected() throws Exception {
        // 超大文件同样不允许自动覆盖，读取边界不依赖先查文件大小。
        int oversizedBytes = 1_048_577;
        var path = temporary.newFile().toPath();
        Files.write(path, new byte[oversizedBytes]);
        var file = new CombatConfigFile(path);
        assertThrows(java.io.IOException.class, file::load);
        assertEquals(oversizedBytes, Files.size(path));
    }

    @Test
    public void newProfileCopiesPreviousWithoutSharingMutableSlots() {
        // 切服复制只发生在新档，回到旧档必须恢复原配置。
        var profiles = new CombatProfiles();
        profiles.enter(FIRST, NOW);
        assertEquals(CombatIds.SLOT_COUNT, profiles.slots().size());
        profiles.set(1, TECHNIQUE, NOW);
        Instant secondVisit = NOW.plusSeconds(1);
        profiles.enter(SECOND, secondVisit);
        assertEquals(TECHNIQUE, profiles.slots().get(0));
        profiles.clear(1, secondVisit);
        profiles.enter(FIRST, NOW.plusSeconds(2));
        assertEquals(TECHNIQUE, profiles.slots().get(0));
        assertEquals("server:play.example.com:25565", CombatIds.serverProfile("PLAY.Example.COM:25565"));
        assertEquals("world:New World", CombatIds.worldProfile("New World"));
    }

    @Test
    public void capacityEvictsLeastRecentlyUsedAndRetainsCurrentAtTies() {
        // 访问过的旧档刷新时刻，淘汰按时间而非插入顺序。
        var profiles = new CombatProfiles();
        for (int index = 0; index < CombatProfiles.MAX_PROFILES; index++) {
            profiles.enter("server:" + index, NOW.plusSeconds(index));
        }
        profiles.enter("server:0", NOW.plusSeconds(CombatProfiles.MAX_PROFILES));
        profiles.enter("world:new", NOW.plusSeconds(CombatProfiles.MAX_PROFILES + 1));
        var afterEviction = profiles.snapshot();
        assertEquals(CombatProfiles.MAX_PROFILES, afterEviction.size());
        assertFalse(afterEviction.containsKey("server:1"));
        assertTrue(afterEviction.containsKey("server:0"));
        for (int index = 0; index <= CombatProfiles.MAX_PROFILES; index++) {
            profiles.enter("world:tie" + index, NOW);
        }
        assertTrue(profiles.snapshot().containsKey(profiles.current()));
    }

    @Test
    public void invalidSlotAndUnknownTechniqueCannotMutateConfiguration() {
        // 越界和未学会命令必须保持整个配置快照不变。
        var profiles = new CombatProfiles();
        profiles.enter(FIRST, NOW);
        var before = profiles.snapshot();
        var slots = new CombatSlots(profiles);
        assertThrows(IllegalArgumentException.class, () -> slots.set(1, TECHNIQUE, NOW));
        assertThrows(IllegalArgumentException.class, () -> profiles.clear(0, NOW));
        assertThrows(IllegalArgumentException.class, () -> profiles.clear(CombatIds.SLOT_COUNT + 1, NOW));
        assertEquals(before, profiles.snapshot());
        slots.updateKnown(Map.of(TECHNIQUE, "轻步"));
        slots.set(1, TECHNIQUE, NOW);
        assertEquals(List.of(TECHNIQUE), slots.suggestions("sample:"));
        assertEquals(TECHNIQUE, slots.select(1).techniqueId());
        slots.updateKnown(Map.of());
        var invalidSelection = slots.select(1);
        assertEquals("", invalidSelection.techniqueId());
        assertEquals("第 1 槽的功法未学会", invalidSelection.reason());
        assertEquals(TECHNIQUE, profiles.slots().get(0));
        assertTrue(slots.lines().get(0).contains("失效"));
    }
}
