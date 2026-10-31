package dev.kizuna.meridian.combat.gametest;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.combat.core.CombatProtocol;
import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.SlotCommand;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 接入真实玩家管理器和 Fabric 接收器，仅把套接字换成记录连接。 */
final class CombatTestPlayer implements AutoCloseable {
    private static final int UUID_NAME_LENGTH = 8;
    final ServerPlayer player;
    final CombatTestHost.Body body;
    final RecordingConnection connection;

    CombatTestPlayer(GameTestHelper context) {
        // 独立玩家避免测试间共享功法和暂停状态。
        this(context, UUID.randomUUID());
    }

    CombatTestPlayer(GameTestHelper context, UUID uuid) {
        // JOIN、通道注册、发送与断线事件使用生产路径。
        var world = context.getLevel();
        var server = world.getServer();
        body = CombatTestHost.bind(uuid);
        connection = new RecordingConnection();
        player = new ServerPlayer(server, world,
                new GameProfile(uuid, "Combat" + uuid.toString().substring(0, UUID_NAME_LENGTH)));
        server.getPlayerList().placeNewPlayer(connection, player);
    }

    void registerKnown() {
        // 模拟客户端在 JOIN 后声明接收通道，触发正式的通道注册回调。
        send(new ResourceLocation("minecraft", "register"),
                CombatChannels.KNOWN.toString().getBytes(StandardCharsets.UTF_8));
    }

    void registerSlot() {
        // 模拟装了本模组的客户端声明槽位指令通道；不声明的玩家视同客户端未装本模组。
        send(new ResourceLocation("minecraft", "register"),
                CombatChannels.SLOT.toString().getBytes(StandardCharsets.UTF_8));
    }

    void press(byte[] bytes) {
        // 不直接调用业务处理器，验证全局接收器确实注册且执行。
        send(CombatChannels.PRESS, bytes);
    }

    void learn() {
        // 只学旧版本，目录中的新版不能被客户端 ID 自动授予。
        TechniqueReference reference = TechniqueReference.newBuilder()
                .setId(CombatTestContent.TECHNIQUE)
                .setVersion(1)
                .build();
        TechniqueFabric.service().learn(player.getUUID(), reference);
    }

    private void send(ResourceLocation channel, byte[] bytes) {
        // 复制后经过正式 handler；回调必须在返回前取得独立的有界字节。
        var buffer = PacketByteBufs.create();
        buffer.writeBytes(bytes);
        try {
            player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(channel, buffer));
        } finally {
            buffer.release();
        }
    }

    @Override
    public void close() {
        // 先发断线事件取消动作，再撤销宿主认领。
        player.connection.onDisconnect(Component.literal("战斗测试完成"));
        CombatTestHost.unbind(player.getUUID());
    }

    static final class RecordingConnection extends Connection {
        final List<KnownTechniques> known = new ArrayList<>();
        final List<SlotCommand> slots = new ArrayList<>();
        final List<String> overlays = new ArrayList<>();

        RecordingConnection() {
            // 无外部网络，保留服务端发包语义用于断言。
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public boolean isConnected() {
            // 玩家在测试清理前保持已连接状态。
            return true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            // 玩家管理器日志需要有效地址，不建立真实连接。
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public void send(Packet<?> packet) {
            // 覆盖两个公开发送入口，避免连接基类把包积存在待发队列。
            record(packet);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener callbacks) {
            // 回调在此不产生网络确认，测试只观测服务端提交的内容。
            record(packet);
        }

        private void record(Packet<?> packet) {
            // 在发送时复制数据，后续不依赖 FriendlyByteBuf 的引用计数生命周期。
            if (packet instanceof ClientboundCustomPayloadPacket payload) {
                if (payload.getIdentifier().equals(CombatChannels.KNOWN)) {
                    known.add(CombatProtocol.decodeKnown(bytes(payload)));
                }
                if (payload.getIdentifier().equals(CombatChannels.SLOT)) {
                    slots.add(CombatProtocol.decodeSlot(bytes(payload)));
                }
            }
            if (packet instanceof ClientboundSystemChatPacket message && message.overlay()) {
                overlays.add(message.content().getString());
            }
        }

        private static byte[] bytes(ClientboundCustomPayloadPacket payload) {
            // 只读拷贝，不移动读指针。
            var buffer = payload.getData();
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        }
    }
}
