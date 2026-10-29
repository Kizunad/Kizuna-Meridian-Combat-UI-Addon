package dev.kizuna.meridian.combat.core;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;

import java.util.HashSet;

/** 有界 Protobuf 解码；正式网络入口在对应游戏线程调用。 */
public final class CombatProtocol {
    public static final int MAX_PRESS_BYTES = 1024;
    public static final int MAX_KNOWN_BYTES = 1_048_576;
    public static final int MAX_KNOWN_COUNT = 4096;
    private static final int MAX_NAME_LENGTH = 1024;
    public static final long MAX_COOLDOWN_MILLIS = 86_400_000;

    private CombatProtocol() {
        // 两条通道复用一致的边界规则。
    }

    public static TechniquePress decodePress(byte[] payload) {
        // 必须先限制字节数，非法浮点不能进入宿主的方向计算。
        if (payload.length == 0 || payload.length > MAX_PRESS_BYTES) {
            throw new IllegalArgumentException("战斗请求大小无效");
        }
        try {
            TechniquePress request = TechniquePress.parseFrom(payload);
            CombatIds.validateTechnique(request.getTechniqueId());
            if (!Float.isFinite(request.getMoveX()) || !Float.isFinite(request.getMoveZ())) {
                throw new IllegalArgumentException("移动方向必须为有限数");
            }
            return request;
        } catch (InvalidProtocolBufferException failure) {
            throw new IllegalArgumentException("战斗请求格式无效", failure);
        }
    }

    public static KnownTechniques decodeKnown(byte[] payload) {
        // 空消息是合法的空已学列表，不沿用上一服务器的缓存。
        if (payload.length > MAX_KNOWN_BYTES) {
            throw new IllegalArgumentException("已学列表超过大小上限");
        }
        try {
            KnownTechniques entries = KnownTechniques.parseFrom(payload);
            if (entries.getTechniquesCount() > MAX_KNOWN_COUNT) {
                throw new IllegalArgumentException("已学功法数量超过上限");
            }
            var identities = new HashSet<String>();
            for (var entry : entries.getTechniquesList()) {
                String id = entry.getId();
                CombatIds.validateTechnique(id);
                CombatHud.texture(entry.getIconId()); // 校验资源路径，非法段会抛出 IllegalArgumentException。
                long remaining = entry.getCooldownRemainingMillis();
                long total = entry.getCooldownTotalMillis();
                if (!identities.add(id) || entry.getName().length() > MAX_NAME_LENGTH
                        || entry.getUnavailableReason().length() > MAX_NAME_LENGTH
                        || remaining < 0 || total < remaining || total > MAX_COOLDOWN_MILLIS) {
                    throw new IllegalArgumentException("功法投影的身份、文字或冷却范围无效");
                }
            }
            return entries;
        } catch (InvalidProtocolBufferException failure) {
            throw new IllegalArgumentException("已学列表格式无效", failure);
        }
    }
}
