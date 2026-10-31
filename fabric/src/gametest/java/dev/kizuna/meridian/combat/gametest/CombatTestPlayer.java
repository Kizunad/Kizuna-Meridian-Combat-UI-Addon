package dev.kizuna.meridian.combat.gametest;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.combat.core.CombatProtocol;
import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.SlotCommand;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

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
        // JOIN、通道注册、发送与断线事件使用生产路径；连接不预先声明通道，由用例按需补注册。
        var world = context.getLevel();
        var server = world.getServer();
        body = CombatTestHost.bind(uuid);
        connection = new RecordingConnection(List.of());
        player = new ServerPlayer(server, world,
                new GameProfile(uuid, "Combat" + uuid.toString().substring(0, UUID_NAME_LENGTH)),
                ClientInformation.createDefault());
        TestPlayers.logIn(player, connection);
    }

    void registerKnown() {
        // 模拟客户端在 JOIN 后声明接收通道，触发正式的通道注册回调。
        register(CombatChannels.KNOWN);
    }

    void registerSlot() {
        // 模拟装了本模组的客户端声明槽位指令通道；不声明的玩家视同客户端未装本模组。
        register(CombatChannels.SLOT);
    }

    void press(byte[] bytes) {
        // 不直接调用业务处理器，验证全局接收器确实注册且执行。
        send(CombatChannels.PRESS.payload(bytes));
    }

    void learn() {
        // 只学旧版本，目录中的新版不能被客户端 ID 自动授予。
        TechniqueReference reference = TechniqueReference.newBuilder()
                .setId(CombatTestContent.TECHNIQUE)
                .setVersion(1)
                .build();
        TechniqueFabric.service().learn(player.getUUID(), reference);
    }

    List<KnownTechniques> known() {
        // 按发送顺序解码记录下的已学列表，与真实客户端用同一个有界解码器。
        return decoded(CombatChannels.KNOWN, CombatProtocol::decodeKnown);
    }

    List<SlotCommand> slots() {
        // 只看发给本玩家的槽位指令。
        return decoded(CombatChannels.SLOT, CombatProtocol::decodeSlot);
    }

    List<String> overlays() {
        // 动作栏消息是带 overlay 标记的系统消息，聊天区消息不计入。
        return connection.packets().stream()
                .filter(ClientboundSystemChatPacket.class::isInstance)
                .map(ClientboundSystemChatPacket.class::cast)
                .filter(ClientboundSystemChatPacket::overlay)
                .map(message -> message.content().getString())
                .toList();
    }

    private <T> List<T> decoded(ProtobufChannel channel, Function<byte[], T> decoder) {
        // 测试连接记录的是未编码的载荷对象，直接取其中的字节。
        return connection.packets().stream()
                .filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(packet -> ((ClientboundCustomPayloadPacket) packet).payload())
                .filter(payload -> payload.type().equals(channel.type()))
                .map(payload -> decoder.apply(((ProtobufChannel.Payload) payload).bytes()))
                .toList();
    }

    private void register(ProtobufChannel channel) {
        // 与真实客户端一样经 minecraft:register 声明可收通道，服务端走正式的通道注册事件。
        send(new RegistrationPayload(RegistrationPayload.REGISTER, List.of(channel.type().id())));
    }

    private void send(CustomPacketPayload payload) {
        // 经过正式的游戏阶段网络处理器，回调在返回前同步执行。
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(payload));
    }

    @Override
    public void close() {
        // 先发断线事件取消动作，再撤销宿主认领。
        connection.disconnectFromClient();
        CombatTestHost.unbind(player.getUUID());
    }
}
