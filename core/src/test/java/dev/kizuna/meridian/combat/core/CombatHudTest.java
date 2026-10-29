package dev.kizuna.meridian.combat.core;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.*;

/** HUD 投影只处理显示数据，不能改变客户端已学权限或服务端施放事实。 */
public final class CombatHudTest {
    private static final String ID = "sample:step";
    private static final long RECEIVED = 10_000;
    private static final long REMAINING = 2000;
    private static final long TOTAL = 4000;
    private static final long ELAPSED = 1000;
    private static final double HALF = 0.5;
    private static final double QUARTER = 0.25;
    private static final double EPSILON = 0.000001;

    @Test
    public void tenSlotsRetainDuplicatesEmptyAndUnlearnedStates() {
        // 相同功法的两个槽共享表现冷却，未学和空槽保留各自状态。
        var hud = new CombatHud();
        hud.update(snapshot(entry()), RECEIVED);
        var bindings = new ArrayList<>(Collections.nCopies(CombatIds.SLOT_COUNT, ""));
        bindings.set(0, ID);
        bindings.set(1, "sample:removed");
        bindings.set(CombatIds.SLOT_COUNT - 1, ID);
        var slots = hud.slots(bindings, RECEIVED);
        assertEquals(CombatIds.SLOT_COUNT, slots.size());
        assertEquals(CombatHud.State.COOLDOWN, slots.get(0).state());
        assertEquals(CombatHud.State.UNAVAILABLE, slots.get(1).state());
        assertEquals(CombatHud.State.EMPTY, slots.get(2).state());
        var last = slots.get(CombatIds.SLOT_COUNT - 1);
        assertEquals(CombatIds.SLOT_COUNT, last.number());
        assertEquals(slots.get(0).cooldownFraction(), last.cooldownFraction(), EPSILON);
        assertEquals("sample:textures/gui/technique/moves/step.png", last.texture());
        assertEquals("轻步", last.name());
    }

    @Test
    public void cooldownInterpolatesAndClampsWithoutRetainingOldConnection() {
        // 本地单调计时只递减显示，负时间与到期钳位，断线后旧槽立即不可用。
        var hud = new CombatHud();
        var bindings = Collections.nCopies(CombatIds.SLOT_COUNT, ID);
        hud.update(snapshot(entry()), RECEIVED);
        assertEquals(HALF, hud.slots(bindings, RECEIVED - 1).get(0).cooldownFraction(), EPSILON);
        assertEquals(QUARTER, hud.slots(bindings, RECEIVED + ELAPSED).get(0).cooldownFraction(), EPSILON);
        assertEquals(CombatHud.State.AVAILABLE, hud.slots(bindings, RECEIVED + TOTAL).get(0).state());
        hud.clear();
        assertEquals(CombatHud.State.UNAVAILABLE, hud.slots(bindings, RECEIVED).get(0).state());
        hud.update(KnownTechniques.getDefaultInstance(), RECEIVED);
        assertEquals(CombatHud.State.UNAVAILABLE, hud.slots(bindings, RECEIVED).get(0).state());
    }

    @Test
    public void unavailableAndMissingIconKeepReadableText() {
        // 没有贴图 ID 仍保留名称；绑定失败覆盖冷却色，不丢失冷却数值。
        var entry = entry().toBuilder()
                .clearIconId()
                .setUnavailableReason("经脉未通")
                .build();
        var hud = new CombatHud();
        hud.update(snapshot(entry), RECEIVED);
        var slot = hud.slots(Collections.nCopies(CombatIds.SLOT_COUNT, ID), RECEIVED).get(0);
        assertEquals("", slot.texture());
        assertEquals("轻步", slot.name());
        assertEquals(CombatHud.State.UNAVAILABLE, slot.state());
        assertEquals(REMAINING, slot.remainingMillis());
    }

    @Test
    public void protocolRejectsTraversalDuplicatesAndInvalidCooldown() {
        // 网络整份拒绝非法资源路径或重复身份，不能静默合并恶意投影。
        var entry = entry();
        assertRejected(entry.toBuilder().setIconId("sample:../secret").build());
        assertRejected(entry.toBuilder().setIconId("sample:a//b").build());
        assertRejected(entry.toBuilder().setCooldownRemainingMillis(-1).build());
        assertRejected(entry.toBuilder().setCooldownTotalMillis(1).build());
        assertRejected(entry.toBuilder().setCooldownTotalMillis(CombatProtocol.MAX_COOLDOWN_MILLIS + 1).build());
        var duplicate = snapshot(entry).toBuilder().addTechniques(entry).build();
        assertThrows(IllegalArgumentException.class, () -> CombatProtocol.decodeKnown(duplicate.toByteArray()));
        assertEquals(1, CombatProtocol.decodeKnown(snapshot(entry).toByteArray()).getTechniquesCount());
    }

    private static KnownTechnique entry() {
        // 示例使用嵌套资源路径，验证路径与冷却数值都能保真传输。
        return KnownTechnique.newBuilder()
                .setId(ID)
                .setName("轻步")
                .setIconId("sample:moves/step")
                .setCooldownRemainingMillis(REMAINING)
                .setCooldownTotalMillis(TOTAL)
                .build();
    }

    private static KnownTechniques snapshot(KnownTechnique entry) {
        // 单条已学快照与服务端发布格式一致。
        return KnownTechniques.newBuilder().addTechniques(entry).build();
    }

    private static void assertRejected(KnownTechnique entry) {
        // 测试入口与客户端实际接收入口相同。
        var payload = snapshot(entry).toByteArray();
        assertThrows(IllegalArgumentException.class, () -> CombatProtocol.decodeKnown(payload));
    }
}
