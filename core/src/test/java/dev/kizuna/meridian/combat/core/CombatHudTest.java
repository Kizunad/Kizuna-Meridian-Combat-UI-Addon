package dev.kizuna.meridian.combat.core;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.BlockKind;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.*;

/** HUD 投影只处理显示数据，不能改变客户端已学权限或服务端施放事实。 */
public final class CombatHudTest {
    private static final String ID = "sample:step";
    private static final long NOW_TICK = 10_000;
    private static final long REMAINING = 2000;
    private static final long TOTAL = 4000;
    private static final long END_TICK = NOW_TICK + REMAINING / CombatHud.MILLIS_PER_TICK;
    private static final long ELAPSED_TICKS = 20;
    private static final double HALF = 0.5;
    private static final double QUARTER = 0.25;
    private static final double EPSILON = 0.000001;

    @Test
    public void tenSlotsRetainDuplicatesEmptyAndUnlearnedStates() {
        // 相同功法的两个槽共享表现冷却，未学和空槽保留各自状态。
        var hud = new CombatHud();
        hud.update(snapshot(entry()));
        var bindings = new ArrayList<>(Collections.nCopies(CombatIds.SLOT_COUNT, ""));
        bindings.set(0, ID);
        bindings.set(1, "sample:removed");
        bindings.set(CombatIds.SLOT_COUNT - 1, ID);
        var slots = hud.slots(bindings, NOW_TICK);
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
    public void cooldownFollowsWorldTicksToTheEndTickWithoutRetainingOldConnection() {
        // 剩余量只由世界 tick 和结束 tick 决定：同一份列表不重发也会走完，到点即可用，断线后旧槽立即不可用。
        var hud = new CombatHud();
        var bindings = Collections.nCopies(CombatIds.SLOT_COUNT, ID);
        hud.update(snapshot(entry()));
        assertEquals(HALF, hud.slots(bindings, NOW_TICK).get(0).cooldownFraction(), EPSILON);
        assertEquals(QUARTER, hud.slots(bindings, NOW_TICK + ELAPSED_TICKS).get(0).cooldownFraction(), EPSILON);
        var last = hud.slots(bindings, END_TICK - 1).get(0);
        assertEquals(CombatHud.State.COOLDOWN, last.state());
        assertEquals(CombatHud.MILLIS_PER_TICK, last.remainingMillis());
        assertEquals(CombatHud.State.AVAILABLE, hud.slots(bindings, END_TICK).get(0).state());
        assertEquals(CombatHud.State.AVAILABLE, hud.slots(bindings, END_TICK + ELAPSED_TICKS).get(0).state());
        hud.clear();
        assertEquals(CombatHud.State.UNAVAILABLE, hud.slots(bindings, NOW_TICK).get(0).state());
        hud.update(KnownTechniques.getDefaultInstance());
        assertEquals(CombatHud.State.UNAVAILABLE, hud.slots(bindings, NOW_TICK).get(0).state());
    }

    @Test
    public void cooldownBeyondDeclaredTotalDrawsFull() {
        // 招式曲线比声明的冷却长，或没有声明冷却时，占比画满而不是除出大于一或零。
        var hud = new CombatHud();
        var bindings = Collections.nCopies(CombatIds.SLOT_COUNT, ID);
        hud.update(snapshot(entry().toBuilder().setCooldownTotalMillis(REMAINING / 2).build()));
        assertEquals(1, hud.slots(bindings, NOW_TICK).get(0).cooldownFraction(), EPSILON);
        hud.update(snapshot(entry().toBuilder().setCooldownTotalMillis(0).build()));
        var slot = hud.slots(bindings, NOW_TICK).get(0);
        assertEquals(1, slot.cooldownFraction(), EPSILON);
        assertEquals(CombatHud.State.COOLDOWN, slot.state());
    }

    @Test
    public void unavailableAndMissingIconKeepReadableText() {
        // 没有贴图 ID 仍保留名称；绑定失败覆盖冷却色，不丢失冷却数值。
        var entry = entry().toBuilder()
                .clearIconId()
                .setUnavailableReason("经脉未通")
                .build();
        var hud = new CombatHud();
        hud.update(snapshot(entry));
        var slot = hud.slots(Collections.nCopies(CombatIds.SLOT_COUNT, ID), NOW_TICK).get(0);
        assertEquals("", slot.texture());
        assertEquals("轻步", slot.name());
        assertEquals(CombatHud.State.UNAVAILABLE, slot.state());
        assertEquals(REMAINING, slot.remainingMillis());
    }

    @Test
    public void shortOfQiOrStrengthGreysWithItsBadgeAndKeepsTheCooldown() {
        // 真元、力气不够：不在冷却时是 SHORT（变灰、不标红框），在冷却里仍照冷却画；两种都带原因类别供叠角标。
        var hud = new CombatHud();
        var bindings = Collections.nCopies(CombatIds.SLOT_COUNT, ID);
        var lacking = entry().toBuilder().setUnavailableReason("真元不济").setBlock(BlockKind.BLOCK_QI).build();
        hud.update(snapshot(lacking));
        var cooling = hud.slots(bindings, NOW_TICK).get(0);
        assertEquals(CombatHud.State.COOLDOWN, cooling.state());
        assertEquals(BlockKind.BLOCK_QI, cooling.block());
        var ready = hud.slots(bindings, END_TICK).get(0);
        assertEquals(CombatHud.State.SHORT, ready.state());
        assertEquals(BlockKind.BLOCK_QI, ready.block());
        hud.update(snapshot(lacking.toBuilder().setUnavailableReason("气力不继").setBlock(BlockKind.BLOCK_EXERTION).build()));
        assertEquals(BlockKind.BLOCK_EXERTION, hud.slots(bindings, END_TICK).get(0).block());
        // 身体绑不上的照旧是 UNAVAILABLE；旧服务端只发文字时也按这一类画。能放的不带类别。
        hud.update(snapshot(entry().toBuilder().setUnavailableReason("经脉未通").build()));
        var failed = hud.slots(bindings, END_TICK).get(0);
        assertEquals(CombatHud.State.UNAVAILABLE, failed.state());
        assertEquals(BlockKind.BLOCK_BODY, failed.block());
        hud.update(snapshot(entry()));
        assertEquals(BlockKind.BLOCK_NONE, hud.slots(bindings, END_TICK).get(0).block());
    }

    @Test
    public void protocolRejectsTraversalDuplicatesAndInvalidCooldown() {
        // 网络整份拒绝非法资源路径或重复身份，不能静默合并恶意投影。
        var entry = entry();
        assertRejected(entry.toBuilder().setIconId("sample:../secret").build());
        assertRejected(entry.toBuilder().setIconId("sample:a//b").build());
        assertRejected(entry.toBuilder().setCooldownEndTick(-1).build());
        assertRejected(entry.toBuilder().setCooldownTotalMillis(-1).build());
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
                .setCooldownEndTick(END_TICK)
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
