package dev.kizuna.meridian.combat.fabric;

import dev.kizuna.meridian.combat.core.CombatProtocol;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import net.minecraft.resources.Identifier;

/**
 * 两端共同使用的 {@code meridian_combat:*} 字节通道。三条通道都不带长度前缀，限长与 {@link CombatProtocol} 一致；
 * 解码器自己检查包长，空的已学列表是合法的清空消息。首次使用本类时两个方向一并登记，与模组初始化顺序无关。
 */
public final class CombatChannels {
    /** 客户端逐次施放请求。 */
    public static final ProtobufChannel PRESS = ProtobufChannel.unframed(id("press"), CombatProtocol.MAX_PRESS_BYTES);
    /** 服务端推送已学功法投影。 */
    public static final ProtobufChannel KNOWN = ProtobufChannel.unframed(id("known"), CombatProtocol.MAX_KNOWN_BYTES);
    /** 服务端转发 /meridian combat slot 操作。 */
    public static final ProtobufChannel SLOT = ProtobufChannel.unframed(id("slot"), CombatProtocol.MAX_SLOT_BYTES);

    static {
        // 自定义载荷要在两端都登记才能编解码；放在类初始化里，谁先用到都能拿到已登记的通道。
        PRESS.registerServerbound();
        KNOWN.registerClientbound();
        SLOT.registerClientbound();
    }

    private CombatChannels() {
        // 只放通道常量，不实例化。
    }

    private static Identifier id(String path) {
        // 沿用 1.20.1 的命名空间与路径，线上通道 ID 不变。
        return Identifier.fromNamespaceAndPath("meridian_combat", path);
    }
}
