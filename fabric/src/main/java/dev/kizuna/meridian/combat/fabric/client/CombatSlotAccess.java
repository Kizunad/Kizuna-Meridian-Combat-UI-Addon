package dev.kizuna.meridian.combat.fabric.client;

import dev.kizuna.meridian.combat.core.CombatIds;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 客户端线程上的槽位门面；所有写入复用命令的校验与持久化入口，不提供施放能力。 */
public final class CombatSlotAccess {
    public enum Connection { DISCONNECTED, CONNECTED }
    public enum Status { SAVED, SESSION_ONLY, REJECTED }
    public record Result(Status status, String message) { }
    public record Snapshot(Connection connection, KnownTechniques known, List<String> slots) {
        public Snapshot {
            // 生成消息不可变，槽列表复制后发布，外部无法改写客户端配置。
            slots = List.copyOf(slots);
        }
    }

    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static Snapshot snapshot = new Snapshot(Connection.DISCONNECTED, KnownTechniques.getDefaultInstance(),
            Collections.nCopies(CombatIds.SLOT_COUNT, ""));
    private static CombatClient client;

    private CombatSlotAccess() {
        // 实例由 Fabric 创建，接入方只持有不可变展示快照。
    }

    static void initialize(CombatClient instance) {
        // 客户端入口登记一次，共用现有配置文件和世界分档。
        client = Objects.requireNonNull(instance);
    }

    public static Snapshot snapshot() {
        // 断线快照不暴露上一连接的已学数据或槽位。
        return snapshot;
    }

    public static Result set(int number, String techniqueId) {
        // UI 与命令共同经过当前连接的已学校验。
        return Objects.requireNonNull(client, "Combat 客户端尚未初始化").setSlot(number, techniqueId);
    }

    public static Result clear(int number) {
        // 清空也经过同一保存入口，失败不会被误报为已持久化。
        return Objects.requireNonNull(client, "Combat 客户端尚未初始化").clearSlot(number);
    }

    public static void consumeConfigurationKey(int key) {
        // 配置键即使仍按着离开界面，也必须先物理松开才能恢复游戏内槽位语义。
        Objects.requireNonNull(client, "Combat 客户端尚未初始化").consumeConfigurationKey(key);
    }

    public static AutoCloseable subscribe(Runnable listener) {
        // 窗口 scope 关闭时移除订阅，避免残留视图跨连接收到事件。
        LISTENERS.add(Objects.requireNonNull(listener));
        return () -> LISTENERS.remove(listener);
    }

    static void publish(Snapshot value) {
        // 相同投影不重建 UI，冷却更新仍会发布其变化。
        if (snapshot.equals(value)) {
            return;
        }
        snapshot = value;
        List.copyOf(LISTENERS).forEach(Runnable::run);
    }
}
