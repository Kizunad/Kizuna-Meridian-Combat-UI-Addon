package dev.kizuna.meridian.combat.gametest;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.combat.core.CombatProtocol;
import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 接入真实玩家管理器和 Fabric 接收器，仅把套接字换成记录连接。 */
final class CombatTestPlayer implements AutoCloseable {
    private static final int UUID_NAME_LENGTH = 8;
    final ServerPlayerEntity player;
    final CombatTestHost.Body body;
    final RecordingConnection connection;

    CombatTestPlayer(TestContext context) {
        // 独立玩家避免测试间共享功法和暂停状态。
        this(context, UUID.randomUUID());
    }

    CombatTestPlayer(TestContext context, UUID uuid) {
        // JOIN、通道注册、发送与断线事件使用生产路径。
        var world = context.getWorld();
        var server = world.getServer();
        body = CombatTestHost.bind(uuid);
        connection = new RecordingConnection();
        player = new ServerPlayerEntity(server, world,
                new GameProfile(uuid, "Combat" + uuid.toString().substring(0, UUID_NAME_LENGTH)));
        server.getPlayerManager().onPlayerConnect(connection, player);
    }

    void registerKnown() {
        // 模拟客户端在 JOIN 后声明接收通道，触发正式的通道注册回调。
        send(new Identifier("minecraft", "register"),
                CombatChannels.KNOWN.toString().getBytes(StandardCharsets.UTF_8));
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
        TechniqueFabric.service().learn(player.getUuid(), reference);
    }

    private void send(Identifier channel, byte[] bytes) {
        // 复制后经过正式 handler；回调必须在返回前取得独立的有界字节。
        var buffer = PacketByteBufs.create();
        buffer.writeBytes(bytes);
        try {
            player.networkHandler.onCustomPayload(new CustomPayloadC2SPacket(channel, buffer));
        } finally {
            buffer.release();
        }
    }

    @Override
    public void close() {
        // 先发断线事件取消动作，再撤销宿主认领。
        player.networkHandler.onDisconnected(Text.literal("战斗测试完成"));
        CombatTestHost.unbind(player.getUuid());
    }

    static final class RecordingConnection extends ClientConnection {
        final List<KnownTechniques> known = new ArrayList<>();
        final List<String> overlays = new ArrayList<>();

        RecordingConnection() {
            // 无外部网络，保留服务端发包语义用于断言。
            super(NetworkSide.SERVERBOUND);
        }

        @Override
        public boolean isOpen() {
            // 玩家在测试清理前保持已连接状态。
            return true;
        }

        @Override
        public SocketAddress getAddress() {
            // 玩家管理器日志需要有效地址，不建立真实连接。
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public void send(Packet<?> packet) {
            // 覆盖两个公开发送入口，避免连接基类把包积存在待发队列。
            record(packet);
        }

        @Override
        public void send(Packet<?> packet, PacketCallbacks callbacks) {
            // 回调在此不产生网络确认，测试只观测服务端提交的内容。
            record(packet);
        }

        private void record(Packet<?> packet) {
            // 在发送时复制数据，后续不依赖 PacketByteBuf 的引用计数生命周期。
            if (packet instanceof CustomPayloadS2CPacket payload
                    && payload.getChannel().equals(CombatChannels.KNOWN)) {
                var buffer = payload.getData();
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.getBytes(buffer.readerIndex(), bytes);
                known.add(CombatProtocol.decodeKnown(bytes));
            }
            if (packet instanceof GameMessageS2CPacket message && message.overlay()) {
                overlays.add(message.content().getString());
            }
        }
    }
}
