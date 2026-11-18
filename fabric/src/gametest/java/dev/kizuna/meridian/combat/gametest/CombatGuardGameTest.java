package dev.kizuna.meridian.combat.gametest;

import dev.kizuna.meridian.combat.core.PressLimiter;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniqueRelease;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueInputBuffer;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import static dev.kizuna.meridian.combat.gametest.CombatGameTestTicks.DEFAULT_TIMEOUT;

/** 真实 C2S 通道覆盖守式按压会话、独立松开和输入缓冲边界。 */
public final class CombatGuardGameTest {
    private static final long GUARD_END = CombatTestContent.GUARD_WARMUP + CombatTestContent.GUARD_RECOVERY;
    private static final long SHORT_COOLDOWN = 100;
    private static final long SYNC_TICKS = 2;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void guardNeedsPressIdentityAndFiniteCastCannotBorrowOne(GameTestHelper context) {
        // 槽位形状的守式包、越界序号和冒充守式的普通式均在施放之前拒绝。
        try (var fixture = new CombatTestPlayer(context)) {
            fixture.learn();
            var before = fixture.body.current();
            fixture.press(press(CombatTestContent.GUARD, 0));
            fixture.press(press(CombatTestContent.GUARD, -1));
            fixture.press(press(CombatTestContent.TECHNIQUE, 1));
            fixture.release(release(-1));
            context.assertTrue(before.equals(fixture.body.current()), "非法按压改变了身体");
            fixture.press(press(CombatTestContent.GUARD, 1));
            context.assertTrue(active(fixture).hasSustained(), "非法包污染了有效按压序号");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void shortTapPreservesBothEdgesAndDuplicateReleaseDoesNotExtendRecovery(GameTestHelper context) {
        // 两个包在同一服务端 tick 到达，收势请求必须在起手时即保存。
        try (var fixture = new CombatTestPlayer(context)) {
            fixture.press(press(CombatTestContent.GUARD, 1));
            fixture.release(release(1));
            var first = active(fixture);
            context.assertTrue(first.getSustained().hasEndRequestedMillis(), "短按漏掉松开边沿");
            context.assertTrue(first.getActiveEndMillis() == CombatTestContent.GUARD_WARMUP,
                    "短按没有等起手完成才收");
            context.assertTrue(first.getRecoveryEndMillis() == GUARD_END, "短按收势边界错误");
            fixture.release(release(1));
            context.assertTrue(first.equals(active(fixture)), "重复松开延长了收势");
            advance(fixture, CombatTestContent.GUARD_WARMUP);
            context.assertTrue(TechniqueFabric.service().activeGuard(fixture.player.getUUID()).isEmpty(),
                    "短按出现了守窗");
            advance(fixture, GUARD_END);
            context.assertFalse(TechniqueState.read(fixture.body.current()).hasActiveCast(), "短按收势未结束");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void oldReleaseCannotEndNewGuardOrFiniteCast(GameTestHelper context) {
        // 旧序号在后来守式和普通式中都无权结束当前施放。
        try (var fixture = new CombatTestPlayer(context)) {
            fixture.learn();
            fixture.press(press(CombatTestContent.GUARD, 1));
            fixture.release(release(1));
            advance(fixture, GUARD_END);
            fixture.press(press(CombatTestContent.GUARD, 2));
            var second = active(fixture);
            fixture.release(release(1));
            fixture.press(press(CombatTestContent.GUARD, 1));
            context.assertTrue(second.equals(active(fixture)), "旧松开影响了新守式");
            fixture.release(release(2));
            advance(fixture, GUARD_END + GUARD_END);
            fixture.press(press(CombatTestContent.TECHNIQUE, 0));
            var ordinary = active(fixture);
            fixture.release(release(2));
            context.assertTrue(ordinary.equals(active(fixture)), "旧松开影响了普通式");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void guardBusyPressNeverQueuesOrDiscardsOrdinaryPending(GameTestHelper context) {
        // 守式忙时只试一次，不能清掉普通式缓冲，更不能松开后补放。
        try (var fixture = new CombatTestPlayer(context)) {
            fixture.learn();
            fixture.press(press(CombatTestContent.TECHNIQUE, 0));
            fixture.press(press(CombatTestContent.GUARD, 1));
            fixture.release(release(1));
            context.assertTrue(TechniqueInputBuffer.buffered(fixture.player.getUUID()).isEmpty(), "守式进入输入缓冲");
            advance(fixture, CombatTestContent.WARMUP_MILLIS);
            TechniqueInputBuffer.tick(fixture.player.level().getServer());
            context.assertFalse(TechniqueState.read(fixture.body.current()).hasActiveCast(), "松开后补放了守式");
            long nearReady = CombatTestContent.WARMUP_MILLIS + CombatTestContent.COOLDOWN_MILLIS - SHORT_COOLDOWN;
            advance(fixture, nearReady);
            fixture.press(press(CombatTestContent.TECHNIQUE, 0));
            var uuid = fixture.player.getUUID();
            context.assertTrue(TechniqueInputBuffer.buffered(uuid).equals(CombatTestContent.TECHNIQUE),
                    "普通式缓冲夹具未就绪");
            fixture.press(press(CombatTestContent.GUARD, 2));
            context.assertTrue(TechniqueInputBuffer.buffered(uuid).equals(CombatTestContent.TECHNIQUE),
                    "守式错误地走了输入缓冲并清掉普通式");
            fixture.release(release(1));
            fixture.release(release(2));
            context.assertTrue(TechniqueInputBuffer.buffered(uuid).equals(CombatTestContent.TECHNIQUE),
                    "松开误清了普通式缓冲");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void releaseSurvivesExhaustedPressLimitAndDuplicatePress(GameTestHelper context) {
        // 按下额度耗尽仍接收松开，重复按压不会替换已有映射。
        try (var fixture = new CombatTestPlayer(context)) {
            fixture.press(press(CombatTestContent.GUARD, 1));
            var original = active(fixture);
            for (int index = 1; index < PressLimiter.MAX_PER_SECOND; index++) {
                fixture.press(press(CombatTestContent.GUARD, 1));
            }
            fixture.press(press(CombatTestContent.GUARD, 2));
            context.assertTrue(original.equals(active(fixture)), "重复按压重新施放了守式");
            fixture.release(release(1));
            context.assertTrue(active(fixture).getSustained().hasEndRequestedMillis(), "按下限流吞了松开");
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void guardProjectionIncludesInnateWithoutLearningOrVolume(GameTestHelper context) {
        // 正式 S2C 投影认识天生式，进度中仍没有伪造学习记录。
        var fixture = new CombatTestPlayer(context);
        fixture.registerKnown();
        context.runAfterDelay(SYNC_TICKS, () -> {
            try (fixture) {
                var messages = fixture.known();
                var entries = messages.get(messages.size() - 1).getTechniquesList();
                context.assertTrue(entries.size() == 1, "天生式没有进入已学投影");
                var guard = entries.getFirst();
                context.assertTrue(guard.getGuard() && guard.getId().equals(CombatTestContent.GUARD), "守式标志缺失");
                context.assertTrue(guard.getVolumeId().isEmpty(), "天生式被伪造为已学卷册");
                context.assertTrue(TechniqueState.read(fixture.body.current()).getKnownCount() == 0,
                        "知识投影写入了学习进度");
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void disconnectAndFailedPressCannotLeakIntoReconnectedSession(GameTestHelper context) {
        // 重登复用玩家 UUID，但连接与可信身体重新认领；旧映射必须丢弃。
        var first = new CombatTestPlayer(context);
        var uuid = first.player.getUUID();
        try (first) {
            first.press(press(CombatTestContent.GUARD, 1));
        }
        try (var second = new CombatTestPlayer(context, uuid)) {
            second.release(release(1));
            context.assertFalse(TechniqueState.read(second.body.current()).hasActiveCast(), "重登残留旧守式");
            second.press(press(CombatTestContent.GUARD, 1));
            context.assertTrue(active(second).hasSustained(), "重登未清按压序号");
            first.release(release(1));
            context.assertFalse(active(second).getSustained().hasEndRequestedMillis(), "旧连接结束了新连接的守式");
            second.press(press(CombatTestContent.GUARD, 2));
            second.release(release(2));
            context.assertFalse(active(second).getSustained().hasEndRequestedMillis(), "失败按压收掉了旧守式");
            second.release(release(1));
            context.assertTrue(active(second).getSustained().hasEndRequestedMillis(), "失败按压丢掉了旧映射");
        }
        context.succeed();
    }

    private static ActiveCast active(CombatTestPlayer fixture) {
        // 断言权威进度，不从网络回显推测服务端状态。
        return TechniqueState.read(fixture.body.current()).getActiveCast();
    }

    private static void advance(CombatTestPlayer fixture, long millis) {
        // 身体时钟由可信测试宿主推进，按键包没有时间字段。
        fixture.body.advanceTo(millis);
        TechniqueFabric.service().tick();
    }

    private static byte[] press(String technique, int pressId) {
        // 按下不含身体或施放身份。
        return TechniquePress.newBuilder()
                .setTechniqueId(technique)
                .setPressId(pressId)
                .build().toByteArray();
    }

    private static byte[] release(int pressId) {
        // 松开只有有界的连接内按压序号。
        return TechniqueRelease.newBuilder().setPressId(pressId).build().toByteArray();
    }
}
