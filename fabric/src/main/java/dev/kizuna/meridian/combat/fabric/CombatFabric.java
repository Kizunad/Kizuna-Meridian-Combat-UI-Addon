package dev.kizuna.meridian.combat.fabric;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.combat.core.CombatProtocol;
import dev.kizuna.meridian.combat.core.PressLimiter;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.SlotCommand;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.SlotOperation;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.server.TechniqueHostDirectory;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueErrors;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 服务端校验逐次施放，并把 /meridian combat slot 的操作转发给执行者本人；不接收和保存客户端槽位。 */
public final class CombatFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_combat");
    private static final int REFRESH_TICKS = 5;
    private final Map<UUID, KnownTechniques> sentKnown = new HashMap<>();
    private final PressLimiter limiter = new PressLimiter();
    private final Set<UUID> pendingKnown = new HashSet<>();
    private final Set<UUID> pausedKnown = new HashSet<>();
    private final Set<UUID> reportedFailures = new HashSet<>();

    @Override
    public void onInitialize() {
        // JOIN 延后一 tick 推送，保证各宿主均完成玩家认领。
        ServerPlayNetworking.registerGlobalReceiver(CombatChannels.PRESS,
                (server, player, handler, buffer, sender) -> receive(server, player, buffer));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID uuid = handler.player.getUUID();
            pausedKnown.remove(uuid);
            reportedFailures.remove(uuid);
            pendingKnown.add(uuid);
        });
        S2CPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
            if (channels.contains(CombatChannels.KNOWN)) {
                sentKnown.remove(handler.player.getUUID());
                pendingKnown.add(handler.player.getUUID());
            }
        });
        TechniqueFabric.KNOWN_CHANGED.register((entityUuid, known) -> pendingKnown.add(entityUuid));
        ServerTickEvents.END_SERVER_TICK.register(this::flushKnown);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
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
        sentKnown.remove(uuid);
        pausedKnown.remove(uuid);
        reportedFailures.remove(uuid);
    }

    private void clearSessions() {
        // 停服不保留旧连接的同步状态。
        limiter.clear();
        pendingKnown.clear();
        sentKnown.clear();
        pausedKnown.clear();
        reportedFailures.clear();
    }

    public void receive(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buffer) {
        // 网络线程不解码；入队前限制包长和频率，避免无界任务堆积。
        int size = buffer.readableBytes();
        if (size == 0 || size > CombatProtocol.MAX_PRESS_BYTES
                || !limiter.accept(player.getUUID(), System.nanoTime())) {
            return;
        }
        byte[] payload = new byte[size];
        buffer.readBytes(payload);
        server.execute(() -> process(player, payload));
    }

    public static void process(ServerPlayer player, byte[] payload) {
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
            var outcome = casting().cast(playerUuid, press);
            if (outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
                player.displayClientMessage(Component.literal(outcome.getReason()), true /* overlay */);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("战斗施放失败：{}", playerUuid, failure);
            player.displayClientMessage(Component.literal("当前无法施放功法"), true /* overlay */);
        }
    }

    public static CombatCasting casting() {
        // 入口和服务都来自硬依赖的公开 API，不通过 Human 获取身体。
        var entrypoints = FabricLoader.getInstance()
                .getEntrypoints(TechniqueHostEntrypoint.ENTRYPOINT_ID, TechniqueHostEntrypoint.class);
        return new CombatCasting(TechniqueFabric.service(), new TechniqueHostDirectory(entrypoints));
    }

    private void flushKnown(MinecraftServer server) {
        // 每五 tick 刷新冷却和绑定，内容相同则不重复发送；学会事件仍在下一 tick 推送。
        if (server.getTickCount() % REFRESH_TICKS == 0) {
            server.getPlayerList().getPlayers().forEach(player -> pendingKnown.add(player.getUUID()));
        }
        if (pendingKnown.isEmpty()) {
            return;
        }
        var casting = casting();
        for (UUID uuid : Set.copyOf(pendingKnown)) {
            pendingKnown.remove(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || pausedKnown.contains(uuid)
                    || !ServerPlayNetworking.canSend(player, CombatChannels.KNOWN)) {
                continue;
            }
            try {
                var snapshot = casting.known(uuid);
                reportedFailures.remove(uuid);
                sendKnown(player, snapshot);
            } catch (RuntimeException failure) {
                handleKnownFailure(player, failure);
            }
        }
    }

    private void sendKnown(ServerPlayer player, KnownTechniques snapshot) {
        // 空投影同样覆盖客户端旧列表，防止身体暂停后继续显示可用功法。
        UUID uuid = player.getUUID();
        if (snapshot.equals(sentKnown.get(uuid))) {
            return;
        }
        var payload = PacketByteBufs.create();
        payload.writeBytes(snapshot.toByteArray());
        ServerPlayNetworking.send(player, CombatChannels.KNOWN, payload);
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
            player.displayClientMessage(Component.literal("已学功法暂时无法同步"), true /* overlay */);
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
                    .map(KnownTechnique::getId)
                    .filter(id -> id.startsWith(prefix))
                    .sorted()
                    .toList();
        }

        private int send(CommandSourceStack source, SlotCommand.Builder command) throws CommandSyntaxException {
            // 槽位只存在玩家客户端：控制台执行直接报错；客户端没声明接收通道时明确失败，不静默吞掉。
            ServerPlayer player = source.getPlayerOrException();
            if (!ServerPlayNetworking.canSend(player, CombatChannels.SLOT)) {
                source.sendFailure(Component.literal("客户端未安装战斗界面，无法修改本地战斗槽"));
                return 0;
            }
            var payload = PacketByteBufs.create();
            payload.writeBytes(command.build().toByteArray());
            ServerPlayNetworking.send(player, CombatChannels.SLOT, payload);
            return 1;
        }
    }
}
