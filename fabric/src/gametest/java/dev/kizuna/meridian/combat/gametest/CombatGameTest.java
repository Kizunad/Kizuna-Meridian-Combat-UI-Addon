package dev.kizuna.meridian.combat.gametest;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import dev.kizuna.meridian.combat.core.CombatProtocol;
import dev.kizuna.meridian.combat.core.PressLimiter;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import java.util.List;

/** 专用服务端验证正式通道、公开功法服务与推送事件之间的接线。 */
public final class CombatGameTest implements FabricGameTest {
    private static final long SYNC_TICKS = 2;
    private static final long REFRESH_TICKS = 6;
    private static final long COOLDOWN_ELAPSED = 500;
    private static final long MILLIS_PER_SECOND = 1000;
    private static final float RAW_X = 3;
    private static final float RAW_Z = 4;
    private static final double NORMALIZED_X = 0.6;
    private static final double NORMALIZED_Z = 0.8;
    private static final double EPSILON = 0.000001;
    private static final int UNKNOWN_FIELD = 31;
    private static final long PAUSED_OBSERVATION_TICKS = 21;

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void pausedHostClearsHudStopsPollingAndResumesAfterReconnect(GameTestHelper context) {
        // 暂停清空旧投影且停止轮询，只有真实重连才能重新取得宿主状态。
        var fixture = new CombatTestPlayer(context);
        fixture.registerKnown();
        fixture.learn();
        later(context, fixture, () -> {
            context.assertTrue(latest(fixture).getId().equals(CombatTestContent.TECHNIQUE), "已学投影未就绪");
            fixture.body.paused = true;
            context.runAfterDelay(PAUSED_OBSERVATION_TICKS, () -> verifyPaused(context, fixture));
        });
    }

    private static void verifyPaused(GameTestHelper context, CombatTestPlayer fixture) {
        // 连续多个刷新周期只向已暂停宿主查询一次，也不重复弹动作栏错误。
        var uuid = fixture.player.getUUID();
        try (fixture) {
            context.assertTrue(fixture.body.pausedQueries == 1, "暂停后仍重复决策或查询");
            var messages = fixture.connection.known;
            context.assertTrue(messages.get(messages.size() - 1).getTechniquesCount() == 0, "暂停未清旧列表");
            context.assertTrue(fixture.connection.overlays.isEmpty(), "暂停反复弹出动作栏错误");
        }
        var reconnected = new CombatTestPlayer(context, uuid);
        reconnected.registerKnown();
        reconnected.learn();
        later(context, reconnected, () -> {
            try (reconnected) {
                context.assertTrue(latest(reconnected).getId().equals(CombatTestContent.TECHNIQUE),
                        "重连未恢复已学同步");
            }
            context.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void boundedDecodeAndCast(GameTestHelper context) {
        // 无效包不能修改身体；正常包按已学精确版本和归一化方向施放。
        try (var fixture = new CombatTestPlayer(context)) {
            fixture.learn();
            var before = fixture.body.current();
            fixture.press(new byte[0]);
            fixture.press(new byte[]{(byte) 0xff});
            fixture.press(oversize());
            fixture.press(press(CombatTestContent.TECHNIQUE, Float.NaN, 0));
            context.assertTrue(fixture.body.current().equals(before), "非法包改变了身体");
            fixture.press(press(CombatTestContent.TECHNIQUE, RAW_X, RAW_Z));
            var progress = TechniqueState.read(fixture.body.current());
            context.assertTrue(progress.hasActiveCast(), "正式通道未触发施放");
            var request = progress.getActiveCast().getRequest();
            context.assertTrue(request.getTechnique().getVersion() == 1, "旧版知识被升级为新版权限");
            context.assertTrue(request.getBodyId().equals(before.id().value()), "没有补齐可信身体身份");
            context.assertFalse(request.getCastId().isEmpty(), "缺少服务端施放身份");
            var direction = request.getDirection();
            context.assertTrue(Math.abs(direction.getX() - NORMALIZED_X) < EPSILON,
                    "水平 X 方向没有归一化");
            context.assertTrue(Math.abs(direction.getZ() - NORMALIZED_Z) < EPSILON,
                    "水平 Z 方向没有归一化");
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void chineseRejections(GameTestHelper context) {
        // 未学、目录外和冷却拒绝必须真实回传动作栏，不能改变可信知识。
        try (var fixture = new CombatTestPlayer(context)) {
            fixture.learn();
            fixture.press(press(CombatTestContent.OTHER, 0, 0));
            fixture.press(press("combat_test:missing", 0, 0));
            context.assertTrue(fixture.connection.overlays.equals(List.of("你还不会这门功法", "你还不会这门功法")),
                    "未学或目录外原因没有按公开服务中文回传");
            fixture.press(press(CombatTestContent.TECHNIQUE, 0, 0));
            var active = TechniqueState.read(fixture.body.current()).getActiveCast();
            var direction = active.getRequest().getDirection();
            context.assertTrue(direction.getX() == 0 && direction.getZ() == 0, "无输入未保留零方向");
            fixture.body.advanceTo(CombatTestContent.WARMUP_MILLIS);
            TechniqueFabric.service().tick();
            context.assertFalse(TechniqueState.read(fixture.body.current()).hasActiveCast(), "起手完成后没有收势");
            fixture.press(press(CombatTestContent.TECHNIQUE, 0, 0));
            context.assertTrue(fixture.connection.overlays.get(2).equals("气息尚未平复"), "冷却原因没有中文回传");
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void networkRateLimit(GameTestHelper context) {
        // 同一玩家第 21 包被入口丢弃，其他玩家的请求仍被服务接受。
        try (var blocked = new CombatTestPlayer(context); var other = new CombatTestPlayer(context)) {
            blocked.learn();
            other.learn();
            for (int index = 0; index < PressLimiter.MAX_PER_SECOND; index++) {
                blocked.press(press(CombatTestContent.OTHER, 0, 0));
            }
            blocked.press(press(CombatTestContent.TECHNIQUE, 0, 0));
            context.assertTrue(blocked.connection.overlays.size() == PressLimiter.MAX_PER_SECOND,
                    "正式通道没有执行每玩家限流");
            context.assertFalse(TechniqueState.read(blocked.body.current()).hasActiveCast(), "限流包仍然起手");
            other.press(press(CombatTestContent.TECHNIQUE, 0, 0));
            context.assertTrue(TechniqueState.read(other.body.current()).hasActiveCast(), "一个玩家耗尽了其他玩家额度");
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void knownChangedPush(GameTestHelper context) {
        // JOIN 后延迟注册接收通道，首次空列表与真实学会事件都必须推送。
        var fixture = new CombatTestPlayer(context);
        context.runAfterDelay(SYNC_TICKS, () -> {
            fixture.registerKnown();
            context.runAfterDelay(SYNC_TICKS, () -> {
                context.assertTrue(fixture.connection.known.size() == 1, "通道后注册未补发初始列表");
                context.assertTrue(fixture.connection.known.get(0).getTechniquesCount() == 0, "初次列表不是空表");
                fixture.learn();
                context.runAfterDelay(SYNC_TICKS, () -> verifyKnown(context, fixture));
            });
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void hudPushesIconsCooldownAndBodyUnavailability(GameTestHelper context) {
        // 捕获周期刷新的真实 S2C 包，覆盖冷却开始、递减、结束和身体失能。
        var fixture = new CombatTestPlayer(context);
        fixture.registerKnown();
        fixture.learn();
        later(context, fixture, () -> {
            var learned = latest(fixture);
            context.assertTrue(learned.getIconId().equals("combat_test:step"), "图标 ID 未推送");
            fixture.press(press(CombatTestContent.TECHNIQUE, 0, 0));
            fixture.body.advanceTo(CombatTestContent.WARMUP_MILLIS);
            TechniqueFabric.service().tick();
            later(context, fixture, () -> checkCooldown(context, fixture));
        });
    }

    private static void checkCooldown(GameTestHelper context, CombatTestPlayer fixture) {
        // 起手完成后的冷却总量和剩余量必须来自身体进度。
        context.assertTrue(latest(fixture).getCooldownRemainingMillis() == CombatTestContent.COOLDOWN_MILLIS,
                "收势后的完整冷却未推送");
        fixture.body.advanceTo(CombatTestContent.WARMUP_MILLIS + COOLDOWN_ELAPSED);
        later(context, fixture, () -> {
            long remaining = CombatTestContent.COOLDOWN_MILLIS - COOLDOWN_ELAPSED;
            context.assertTrue(latest(fixture).getCooldownRemainingMillis() == remaining, "冷却没有随身体时间递减");
            fixture.body.advanceTo(CombatTestContent.WARMUP_MILLIS + CombatTestContent.COOLDOWN_MILLIS);
            later(context, fixture, () -> checkExpiry(context, fixture));
        });
    }

    private static void checkExpiry(GameTestHelper context, CombatTestPlayer fixture) {
        // 冷却结束后身体自然寿尽，再次投影应显示明确不可用状态。
        context.assertTrue(latest(fixture).getCooldownRemainingMillis() == 0, "到期冷却没有清除");
        long lifespanMillis = (long) CombatTestContent.LIFESPAN_SECONDS * MILLIS_PER_SECOND;
        fixture.body.advanceTo(lifespanMillis);
        later(context, fixture, () -> {
            try (fixture) {
                context.assertTrue(latest(fixture).getUnavailableReason().equals("身体不可用"),
                        "身体失能没有显示不可用");
                context.succeed();
            }
        });
    }

    private static dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique latest(
            CombatTestPlayer fixture) {
        // 只读取真实网络记录的最后一份完整快照。
        var messages = fixture.connection.known;
        return messages.get(messages.size() - 1).getTechniques(0);
    }

    private static void later(GameTestHelper context, CombatTestPlayer fixture, Runnable action) {
        // 任意延迟断言失败都释放宿主，周期刷新不会继续访问失败夹具。
        context.runAfterDelay(REFRESH_TICKS, () -> {
            try {
                action.run();
            } catch (RuntimeException | Error failure) {
                fixture.close();
                throw failure;
            }
        });
    }

    private static void verifyKnown(GameTestHelper context, CombatTestPlayer fixture) {
        // 断言捕获的真实 S2C 包，不能只验证事件被调用。
        try (fixture) {
            context.assertTrue(fixture.connection.known.size() == 2, "KNOWN_CHANGED 没有推送更新");
            var known = fixture.connection.known.get(1);
            context.assertTrue(known.getTechniquesCount() == 1, "已学列表数量错误");
            var technique = known.getTechniques(0);
            context.assertTrue(technique.getId().equals(CombatTestContent.TECHNIQUE), "已学 ID 错误");
            context.assertTrue(technique.getName().equals("测试轻步"), "已学名称错误");
        }
        context.succeed();
    }

    private static byte[] press(String id, float x, float z) {
        // 消息形状与真实客户端完全一致。
        return TechniquePress.newBuilder()
                .setTechniqueId(id)
                .setMoveX(x)
                .setMoveZ(z)
                .build()
                .toByteArray();
    }

    private static byte[] oversize() {
        // 有效 Protobuf 加未知字段扩容，保证拦截依据是包长而非解码错误。
        var field = UnknownFieldSet.Field.newBuilder()
                .addLengthDelimited(ByteString.copyFrom(new byte[CombatProtocol.MAX_PRESS_BYTES]))
                .build();
        var unknown = UnknownFieldSet.newBuilder().addField(UNKNOWN_FIELD, field).build();
        return TechniquePress.newBuilder()
                .setTechniqueId(CombatTestContent.TECHNIQUE)
                .setUnknownFields(unknown)
                .build()
                .toByteArray();
    }
}
