package dev.kizuna.meridian.combat.fabric;

import dev.kizuna.meridian.combat.core.CombatProtocol;
import dev.kizuna.meridian.combat.core.PressLimiter;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
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
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 服务端只校验逐次施放；不接收和保存客户端槽位。 */
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
            UUID uuid = handler.player.getUuid();
            pausedKnown.remove(uuid);
            reportedFailures.remove(uuid);
            pendingKnown.add(uuid);
        });
        S2CPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
            if (channels.contains(CombatChannels.KNOWN)) {
                sentKnown.remove(handler.player.getUuid());
                pendingKnown.add(handler.player.getUuid());
            }
        });
        TechniqueFabric.KNOWN_CHANGED.register((entityUuid, known) -> pendingKnown.add(entityUuid));
        ServerTickEvents.END_SERVER_TICK.register(this::flushKnown);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            disconnect(handler.player.getUuid());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            clearSessions();
        });
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

    public void receive(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buffer) {
        // 网络线程不解码；入队前限制包长和频率，避免无界任务堆积。
        int size = buffer.readableBytes();
        if (size == 0 || size > CombatProtocol.MAX_PRESS_BYTES
                || !limiter.accept(player.getUuid(), System.nanoTime())) {
            return;
        }
        byte[] payload = new byte[size];
        buffer.readBytes(payload);
        server.execute(() -> process(player, payload));
    }

    public static void process(ServerPlayerEntity player, byte[] payload) {
        // 解码和功法服务均在服务端线程调用，非法字节直接丢弃。
        TechniquePress press;
        try {
            press = CombatProtocol.decodePress(payload);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        if (player.isDisconnected()) {
            return;
        }
        UUID playerUuid = player.getUuid();
        try {
            var outcome = casting().cast(playerUuid, press);
            if (outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
                player.sendMessage(Text.literal(outcome.getReason()), true /* overlay */);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("战斗施放失败：{}", playerUuid, failure);
            player.sendMessage(Text.literal("当前无法施放功法"), true /* overlay */);
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
        if (server.getTicks() % REFRESH_TICKS == 0) {
            server.getPlayerManager().getPlayerList().forEach(player -> pendingKnown.add(player.getUuid()));
        }
        if (pendingKnown.isEmpty()) {
            return;
        }
        var casting = casting();
        for (UUID uuid : Set.copyOf(pendingKnown)) {
            pendingKnown.remove(uuid);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
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

    private void sendKnown(ServerPlayerEntity player, KnownTechniques snapshot) {
        // 空投影同样覆盖客户端旧列表，防止身体暂停后继续显示可用功法。
        UUID uuid = player.getUuid();
        if (snapshot.equals(sentKnown.get(uuid))) {
            return;
        }
        var payload = PacketByteBufs.create();
        payload.writeBytes(snapshot.toByteArray());
        ServerPlayNetworking.send(player, CombatChannels.KNOWN, payload);
        sentKnown.put(uuid, snapshot);
    }

    private void handleKnownFailure(ServerPlayerEntity player, RuntimeException failure) {
        // 暂停属于宿主显式状态，停止轮询直到重新登录；其他故障仅报告一次并允许重试。
        UUID uuid = player.getUuid();
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
            player.sendMessage(Text.literal("已学功法暂时无法同步"), true /* overlay */);
        }
    }
}
