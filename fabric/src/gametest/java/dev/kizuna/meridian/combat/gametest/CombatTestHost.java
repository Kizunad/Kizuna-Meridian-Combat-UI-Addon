package dev.kizuna.meridian.combat.gametest;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.AdvanceTime;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ResponseEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSide;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueActivityCommit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostFacts;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueRegistryEntrypoint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 测试宿主只认领已绑定玩家，活动仍走 Framework 原子处理器。 */
public final class CombatTestHost implements TechniqueHostEntrypoint, TechniqueRegistryEntrypoint {
    private static final Map<UUID, Body> BODIES = new HashMap<>();

    @Override
    public TechniqueRegistry registry() {
        // 正式加载路径只看到测试源码集注册的目录。
        return CombatTestContent.registry();
    }

    static Body bind(UUID uuid) {
        // 每项测试独立持有身体时钟和存储。
        Body body = new Body(CombatTestContent.body(uuid));
        BODIES.put(uuid, body);
        return body;
    }

    static void unbind(UUID uuid) {
        // 测试完成后不保留玩家身份引用。
        BODIES.remove(uuid);
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 操作必须先经过宿主 Protobuf 边界，不直接向服务注入身体对象。
        TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
        Body body = BODIES.get(UUID.fromString(request.getEntityUuid()));
        var response = TechniqueHostResponse.newBuilder().setRequestId(request.getRequestId());
        if (body == null) {
            response.setUnbound(true /* unbound */);
        } else if (body.paused) {
            body.pausedQueries++;
            RequestFailure failure = RequestFailure.newBuilder().setCode(FailureCode.BODY_PAUSED).build();
            response.setFailure(failure);
        } else if (request.hasInspect()) {
            var hands = HeldItems.newBuilder()
                    .setMainHand(HeldItem.getDefaultInstance())
                    .setOffHand(HeldItem.getDefaultInstance())
                    .setMainHandSide(HandSide.RIGHT)
                    .build();
            response.setBody(BodyProtobuf.toMessage(body.inspect()));
            response.setHeldItems(hands);
            response.setInspectFacts(inspectFacts());
        } else if (request.hasActivity()) {
            response.setActivity(body.submit(request));
        } else if (request.hasEffect()) {
            response.setEffectAccepted(true /* effectAccepted */);
        } else {
            throw new IllegalArgumentException("测试宿主收到未知操作");
        }
        return TechniqueProtocol.encodeHostResponse(response.build());
    }

    private static TechniqueHostFacts inspectFacts() {
        // 测试玩家按站地静止状态接入现有宿主协议，五项事实必须显式提供。
        return TechniqueHostFacts.newBuilder()
                .setGrounded(true /* grounded */)
                .setInWater(false /* inWater */)
                .setClimbing(false /* climbing */)
                .setRiding(false /* riding */)
                .setGliding(false /* gliding */)
                .build();
    }

    static final class Body {
        boolean paused;
        int pausedQueries;
        private final BodySnapshot initial;
        private final InMemoryBodyStore store;
        private final BodyCommandHandler<String> commands;
        private long nowMillis;

        private Body(BodySnapshot initial) {
            // 只将测试身份认证固定，活动、时钟、权限和回执使用真实框架。
            this.initial = initial;
            this.store = new InMemoryBodyStore(List.of(initial), List.of());
            this.commands = new BodyCommandHandler<>(
                    (context, command) -> Optional.of(TechniqueHost.CALLER_ID),
                    (context, command, body) -> true,
                    (context, body) -> timePlan(body), store, (context, receipt, before, after) -> { });
        }

        BodySnapshot current() {
            // 断言读取不隐式推进时钟。
            return store.find(initial.id()).orElseThrow();
        }

        void advanceTo(long millis) {
            // 测试只控制可信宿主时钟，客户端请求不能携带时间。
            nowMillis = millis;
        }

        private BodySnapshot inspect() {
            // 查询先结算到宿主时刻，同一时刻返回原快照。
            BodySnapshot body = current();
            BodyCommand command = BodyCommand.newBuilder()
                    .setBodyId(body.id().value())
                    .setExpectedRevision(body.revision())
                    .setAdvanceTime(AdvanceTime.getDefaultInstance())
                    .build();
            RequestEnvelope request = envelope("settle_" + UUID.randomUUID(), command);
            ResponseEnvelope response = MeridianProtocol.decodeResponse(
                    commands.handle("trusted", MeridianProtocol.encodeRequest(request)));
            var failure = response.getFailure();
            if (response.hasFailure() && failure.getCode() != FailureCode.TIME_NOT_ADVANCED) {
                throw new IllegalStateException("测试身体结时失败：" + failure);
            }
            return current();
        }

        private TechniqueActivityCommit submit(TechniqueHostRequest request) {
            // 学会和施放计划都提交到真实的可信活动入口。
            var activity = request.getActivity();
            BodyCommand command = BodyCommand.newBuilder()
                    .setBodyId(initial.id().value())
                    .setExpectedRevision(request.getExpectedRevision())
                    .setExecuteActivity(activity)
                    .build();
            RequestEnvelope envelope = envelope(request.getRequestId(), command);
            ResponseEnvelope response = MeridianProtocol.decodeResponse(
                    commands.handleActivity("trusted", MeridianProtocol.encodeRequest(envelope)));
            if (response.hasFailure()) {
                throw new IllegalStateException("测试活动提交失败：" + response.getFailure());
            }
            return TechniqueActivityCommit.newBuilder()
                    .setRevision(response.getCommandCommitted().getRevision())
                    .setActivity(activity)
                    .build();
        }

        private TimeAdvancePlan timePlan(BodySnapshot body) {
            // 身体时钟域和纪元保留不变，只推进可信毫秒。
            var target = BodyProtobuf.toMessage(body).getSettledAt().toBuilder()
                    .setElapsedMillis(nowMillis)
                    .build();
            return TimeAdvancePlan.newBuilder()
                    .setTarget(target)
                    .setBiologicalSecondsPerSimulationSecond(1)
                    .setMode(TimeAdvanceMode.ACTIVE)
                    .build();
        }

        private static RequestEnvelope envelope(String requestId, BodyCommand command) {
            // 保留服务生成的请求 ID，幂等性与真实玩家路径一致。
            return RequestEnvelope.newBuilder()
                    .setProtocolVersion(MeridianProtocol.VERSION)
                    .setRequestId(requestId)
                    .setBodyCommand(command)
                    .build();
        }
    }
}
