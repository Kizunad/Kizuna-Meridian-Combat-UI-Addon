package dev.kizuna.meridian.combat.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 已学列表要不要重算：身体的轻量读数和上一次投影时一样，投影结果也一样，不必再向宿主取整具身体。 */
public final class KnownProjectionGate {
    /**
     * 一次投影所依据的读数：身体版本、身体时钟相对世界 tick 的偏移（毫秒）、是否左撇子、功法目录。
     * 偏移不变说明身体时间和世界同步在走，已经发出去的冷却结束 tick 仍然成立。
     */
    public record Reading(long revision, long clockOffsetMillis, boolean leftHanded, Object catalog) { }

    private final Map<UUID, Reading> projected = new HashMap<>();
    /** 上一次投影里有会随时间自己恢复的不可用（真元、力气）的玩家：恢复不改身体版本，读数没变也要重算。 */
    private final Set<UUID> recovering = new HashSet<>();

    /** reading 为 null 表示宿主答不出轻量读数，一律重算。 */
    public boolean due(UUID player, Reading reading) {
        return reading == null || recovering.contains(player) || !reading.equals(projected.get(player));
    }

    /**
     * 记下刚完成的投影所依据的读数；答不出读数时清掉旧记录。
     *
     * @param recovers 这份投影里有没有会随时间自己恢复的不可用
     */
    public void projected(UUID player, Reading reading, boolean recovers) {
        if (recovers) {
            recovering.add(player);
        } else {
            recovering.remove(player);
        }
        if (reading == null) {
            projected.remove(player);
            return;
        }
        projected.put(player, reading);
    }

    public void forget(UUID player) {
        projected.remove(player);
        recovering.remove(player);
    }

    public void clear() {
        projected.clear();
        recovering.clear();
    }
}
