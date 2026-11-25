package dev.kizuna.meridian.combat.fabric;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.combat.core.CombatHud;
import dev.kizuna.meridian.combat.core.CombatProtocol;
import dev.kizuna.meridian.combat.core.KnownProjectionGate;
import dev.kizuna.meridian.combat.core.PressLimiter;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.SlotCommand;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.SlotOperation;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniqueRelease;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueBreathingStatuses;
import dev.kizuna.meridian.technique.fabric.world.TechniqueInputBuffer;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.server.TechniqueHostDirectory;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueErrors;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 服务端校验逐次施放，并把 /meridian combat slot 的操作转发给执行者本人；不接收和保存客户端槽位。 */
public final class CombatFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_combat");
    private static final int REFRESH_TICKS = 5;
    private final Map<UUID, KnownTechniques> sentKnown = new HashMap<>();
    private final PressLimiter limiter = new PressLimiter();
    private final Map<ServerGamePacketListenerImpl, CombatGuardSession> guards = new HashMap<>();
    private final Map<ServerGamePacketListenerImpl, Integer> practicePresses = new HashMap<>();
    private final Set<UUID> pendingKnown = new HashSet<>();
    private final Set<UUID> pausedKnown = new HashSet<>();
    private final Set<UUID> reportedFailures = new HashSet<>();
    private final KnownProjectionGate gate = new KnownProjectionGate();
    // 已学投影专用的宿主目录：归属路由和轻量读数要跨 tick 留着，不能像施放那样每次新建。
    private TechniqueHostDirectory knownHosts;

    @Override
    public void onInitialize() {
        // JOIN 延后一 tick 推送，保证各宿主均完成玩家认领。
        ServerPlayNetworking.registerGlobalReceiver(CombatChannels.PRESS.type(),
                (payload, context) -> receive(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(CombatChannels.RELEASE.type(),
                (payload, context) -> release(context.player(), payload));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID uuid = handler.player.getUUID();
            pausedKnown.remove(uuid);
            reportedFailures.remove(uuid);
            pendingKnown.add(uuid);
            guards.put(handler, new CombatGuardSession());
            practicePresses.put(handler, 0);
        });
        ClientboundPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
            if (channels.contains(CombatChannels.KNOWN.type().id())) {
                sentKnown.remove(handler.player.getUUID());
                pendingKnown.add(handler.player.getUUID());
            }
        });
        TechniqueFabric.KNOWN_CHANGED.register((entityUuid, known) -> pendingKnown.add(entityUuid));
        TechniqueFabric.SERVICE_STARTED.register(service -> service.addCastEndListener(ended ->
                guards.values().forEach(session -> session.ended(ended.castId()))));
        ServerTickEvents.END_SERVER_TICK.register(this::flushKnown);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            guards.remove(handler);
            practicePresses.remove(handler);
            disconnect(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            clearSessions();
        });
        // 客户端不能注册 meridian 根，否则会遮住服务端整棵 /meridian；槽位指令改由服务端节点解析后下发。
        MeridianCommands.register(MeridianCommands.Branch.COMBAT, "slot", MeridianCommands.PLAYER,
                (node, context) -> CombatCommandTree.attach(node, new SlotCommands()));
    }

    private void disconnect(UUID uuid) {
        // 连接结束同时清理限流、投影与暂停状态，重登重新询问身体宿主。
        limiter.disconnect(uuid);
        pendingKnown.remove(uuid);
        gate.forget(uuid);
        if (knownHosts != null) {
            knownHosts.forget(uuid);
        }
        sentKnown.remove(uuid);
        pausedKnown.remove(uuid);
        reportedFailures.remove(uuid);
    }

    private void clearSessions() {
        // 停服不保留旧连接的同步状态。
        limiter.clear();
        guards.clear();
        practicePresses.clear();
        pendingKnown.clear();
        gate.clear();
        knownHosts = null;
        sentKnown.clear();
        pausedKnown.clear();
        reportedFailures.clear();
    }

    public void receive(ServerPlayer player, ProtobufChannel.Payload payload) {
        // 26.2 的收包回调已在服务端线程；解码前先限制包长和频率，空包与超长包直接丢弃。
        if (!CombatChannels.PRESS.accepts(payload) || !limiter.accept(player.getUUID(), System.nanoTime())) {
            return;
        }
        process(player, payload.bytes());
    }

    private void process(ServerPlayer player, byte[] payload) {
        // 解码和功法服务均在服务端线程调用，非法字节直接丢弃。
        TechniquePress press;
        try {
            press = CombatProtocol.decodePress(payload);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        if (player.hasDisconnected()) {
            return;
        }
        UUID playerUuid = player.getUUID();
        try {
            var casting = casting();
            var request = casting.request(playerUuid, press);
            var definition = TechniqueFabric.service().registry().find(request.getTechnique());
            if (definition.isPresent() && definition.get().hasMaintainedBreathing()) {
                pressPractice(player, press, request);
                return;
            }
            if (press.getPracticePressId() != 0) {
                return;
            }
            if (casting.guard(request)) {
                pressGuard(player, press.getPressId(), request);
                return;
            }
            if (press.getPressId() != 0) {
                return;
            }
            // 按早了一点的键由功法库记一小会儿、到能出手时放；记下了就先不报拒绝原因，等到头没放出来它自己会报。
            var pressed = TechniqueInputBuffer.press(player, request);
            var outcome = pressed.outcome();
            if (outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED && !pressed.buffered()) {
                player.sendSystemMessage(TechniqueInputBuffer.failureMessage(outcome), true /* overlay */);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("战斗施放失败：{}", playerUuid, failure);
            player.sendSystemMessage(Component.literal("当前无法施放功法"), true /* overlay */);
        }
    }

    private void pressPractice(ServerPlayer player, TechniquePress press, CastRequest request) {
        // 一个连接只接受递增的运功按键；收势只对客户端最近看到的那次运功生效。
        int sequence = press.getPracticePressId();
        Integer previous = practicePresses.get(player.connection);
        if (previous == null || sequence <= previous) {
            return;
        }
        practicePresses.put(player.connection, sequence);
        if (!press.getEndPracticeId().isEmpty()) {
            TechniqueBreathingStatuses.end(player, press.getTechniqueId(), press.getEndPracticeId());
        } else {
            var outcome = TechniqueFabric.service().cast(player.getUUID(), request);
            if (outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
                player.sendSystemMessage(Component.literal(outcome.getReason()), true /* overlay */);
            }
        }
        pendingKnown.add(player.getUUID());
    }

    private void pressGuard(ServerPlayer player, int pressId, CastRequest request) {
        // 守式直接尝试一次；既不排队，也不清掉已经等待的普通式。
        var session = guards.get(player.connection);
        if (session == null || !session.accept(pressId)) {
            return;
        }
        var outcome = TechniqueFabric.service().cast(player.getUUID(), request);
        if (outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
            session.started(pressId, request);
        } else {
            String reason = outcome.getReason();
            Component message = switch (reason) {
                case "meridian_human_host.guard.broken", "meridian_human_host.guard.weak" ->
                        Component.translatable(reason);
                default -> Component.literal(reason);
            };
            player.sendSystemMessage(message, true /* overlay */);
        }
    }

    private void release(ServerPlayer player, ProtobufChannel.Payload payload) {
        // 松开不占用按下额度，无匹配身份时不查询宿主，也不修改其他按键的缓冲。
        if (player.hasDisconnected() || !CombatChannels.RELEASE.accepts(payload)) {
            return;
        }
        TechniqueRelease release;
        try {
            release = CombatProtocol.decodeRelease(payload.bytes());
        } catch (IllegalArgumentException invalid) {
            return;
        }
        try {
            var session = guards.get(player.connection);
            if (session == null) {
                return;
            }
            var request = session.release(release.getPressId());
            if (request == null) {
                return;
            }
            UUID uuid = player.getUUID();
            TechniqueInputBuffer.clear(uuid, request.getCastId());
            var outcome = TechniqueFabric.service().endActive(uuid, request);
            if (outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
                player.sendSystemMessage(Component.literal(outcome.getReason()), true /* overlay */);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("战斗收势失败：{}", player.getUUID(), failure);
        }
    }

    public static CombatCasting casting() {
        // 入口和服务都来自硬依赖的公开 API，不通过 Human 获取身体。
        var entrypoints = FabricLoader.getInstance()
                .getEntrypoints(TechniqueHostEntrypoint.ENTRYPOINT_ID, TechniqueHostEntrypoint.class);
        return new CombatCasting(TechniqueFabric.service(), new TechniqueHostDirectory(entrypoints));
    }

    private void flushKnown(MinecraftServer server) {
        // 每五 tick 看一眼身体的轻量读数，身体没变就不重算；学会事件仍在下一 tick 推送。
        // 宿主答不出轻量读数时照旧每五 tick 取一次身体，内容相同则不重复发送。
        if (knownHosts == null) {
            knownHosts = new TechniqueHostDirectory(FabricLoader.getInstance()
                    .getEntrypoints(TechniqueHostEntrypoint.ENTRYPOINT_ID, TechniqueHostEntrypoint.class));
        }
        knownHosts.tick();
        if (server.getTickCount() % REFRESH_TICKS == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (gate.due(player.getUUID(), reading(player))) {
                    pendingKnown.add(player.getUUID());
                }
            }
        }
        if (pendingKnown.isEmpty()) {
            return;
        }
        var casting = new CombatCasting(TechniqueFabric.service(), knownHosts);
        for (UUID uuid : Set.copyOf(pendingKnown)) {
            pendingKnown.remove(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || pausedKnown.contains(uuid)
                    || !ServerPlayNetworking.canSend(player, CombatChannels.KNOWN.type())) {
                continue;
            }
            try {
                var snapshot = casting.known(uuid, player.level().getGameTime());
                reportedFailures.remove(uuid);
                sendKnown(player, snapshot);
                // 取身体本身可能结清时间、推进版本，读数在取完之后记。
                gate.projected(uuid, reading(player), CombatKnownProjection.recovering(snapshot));
            } catch (RuntimeException failure) {
                gate.forget(uuid);
                handleKnownFailure(player, failure);
            }
        }
    }

    private KnownProjectionGate.Reading reading(ServerPlayer player) {
        // 宿主不提供轻量读数、路由还没确认或上次取身体失败时为 null，按「可能变了」处理。
        var stamp = knownHosts.bodyStamp(player.getUUID());
        if (stamp.isEmpty()) {
            return null;
        }
        long worldMillis = player.level().getGameTime() * CombatHud.MILLIS_PER_TICK;
        // 换了手里的东西不改身体版本：两只手各拿着什么也算进读数，「兵器不对」的角标才跟得上。
        int held = Objects.hash(ItemStack.hashItemAndComponents(player.getMainHandItem()),
                ItemStack.hashItemAndComponents(player.getOffhandItem()));
        return new KnownProjectionGate.Reading(stamp.get().revision(), stamp.get().clockMillis() - worldMillis,
                player.getMainArm() == HumanoidArm.LEFT, held, TechniqueFabric.service().registry());
    }

    private void sendKnown(ServerPlayer player, KnownTechniques snapshot) {
        // 空投影同样覆盖客户端旧列表，防止身体暂停后继续显示可用功法。
        UUID uuid = player.getUUID();
        if (snapshot.equals(sentKnown.get(uuid))) {
            return;
        }
        ServerPlayNetworking.send(player, CombatChannels.KNOWN.payload(snapshot.toByteArray()));
        sentKnown.put(uuid, snapshot);
    }

    private void handleKnownFailure(ServerPlayer player, RuntimeException failure) {
        // 暂停属于宿主显式状态，停止轮询直到重新登录；其他故障仅报告一次并允许重试。
        UUID uuid = player.getUUID();
        if (failure instanceof TechniqueErrors.HostRejected rejected
                && rejected.failure() == FailureCode.BODY_PAUSED) {
            if (pausedKnown.add(uuid)) {
                LOGGER.warn("身体已暂停，停止已学功法同步：{}；请退出世界后重新加入", uuid);
            }
            sendKnown(player, KnownTechniques.getDefaultInstance());
            return;
        }
        if (reportedFailures.add(uuid)) {
            LOGGER.warn("无法推送玩家已学功法：{}", uuid, failure);
            player.sendSystemMessage(Component.literal("已学功法暂时无法同步"), true /* overlay */);
        }
    }

    private final class SlotCommands implements CombatCommandTree.Actions<CommandSourceStack> {
        @Override
        public int set(CommandSourceStack source, int slot, String technique) throws CommandSyntaxException {
            // 功法格式与已学校验留在客户端槽位语义层，失败原因仍走动作栏。
            return send(source, SlotCommand.newBuilder()
                    .setOperation(SlotOperation.SLOT_OPERATION_SET)
                    .setSlot(slot)
                    .setTechniqueId(technique));
        }

        @Override
        public int clear(CommandSourceStack source, int slot) throws CommandSyntaxException {
            // 槽号已由 Brigadier 限定在 1–10，清空只影响客户端当前分档。
            return send(source, SlotCommand.newBuilder()
                    .setOperation(SlotOperation.SLOT_OPERATION_CLEAR)
                    .setSlot(slot));
        }

        @Override
        public int list(CommandSourceStack source) throws CommandSyntaxException {
            // 十槽内容只在客户端，由客户端打印到聊天区。
            return send(source, SlotCommand.newBuilder().setOperation(SlotOperation.SLOT_OPERATION_LIST));
        }

        @Override
        public List<String> suggestions(CommandSourceStack source, String prefix) {
            // 补全取最近一次推送给该玩家的已学列表，与客户端校验用的是同一份。
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                return List.of();
            }
            KnownTechniques known = sentKnown.getOrDefault(player.getUUID(), KnownTechniques.getDefaultInstance());
            return known.getTechniquesList().stream()
                    .filter(entry -> !entry.getGuard())
                    .map(KnownTechnique::getId)
                    .filter(id -> id.startsWith(prefix))
                    .sorted()
                    .toList();
        }

        private int send(CommandSourceStack source, SlotCommand.Builder command) throws CommandSyntaxException {
            // 槽位只存在玩家客户端：控制台执行直接报错；客户端没声明接收通道时明确失败，不静默吞掉。
            ServerPlayer player = source.getPlayerOrException();
            if (!ServerPlayNetworking.canSend(player, CombatChannels.SLOT.type())) {
                source.sendFailure(Component.literal("客户端未安装战斗界面，无法修改本地战斗槽"));
                return 0;
            }
            ServerPlayNetworking.send(player, CombatChannels.SLOT.payload(command.build().toByteArray()));
            return 1;
        }
    }
}
