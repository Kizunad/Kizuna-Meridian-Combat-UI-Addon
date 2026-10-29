package dev.kizuna.meridian.combat.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 每连接滑动一秒窗口，网络线程入队前限制服务端工作量。 */
public final class PressLimiter {
    public static final int MAX_PER_SECOND = 20;
    public static final long WINDOW_NANOS = 1_000_000_000L;
    private final Map<UUID, Deque<Long>> accepted = new HashMap<>();

    public synchronized boolean accept(UUID player, long nowNanos) {
        // 滑动窗口避免固定秒边界瞬时接纳双倍请求。
        Deque<Long> times = accepted.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && nowNanos - times.getFirst() >= WINDOW_NANOS) {
            times.removeFirst();
        }
        if (times.size() >= MAX_PER_SECOND) {
            return false;
        }
        times.addLast(nowNanos);
        return true;
    }

    public synchronized void disconnect(UUID player) {
        // 断线释放窗口，避免服务器长期保留离线玩家。
        accepted.remove(player);
    }

    public synchronized void clear() {
        // 集成服务器关闭后不得复用旧世界的限流状态。
        accepted.clear();
    }
}
