package dev.kizuna.meridian.combat.core;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 十槽显示模型；本地计时只影响表现，不产生施放权限或持久化冷却。 */
public final class CombatHud {
    private final Map<String, KnownTechnique> known = new HashMap<>();
    private long receivedMillis;

    public enum State { EMPTY, UNAVAILABLE, COOLDOWN, AVAILABLE }

    public record Slot(int number, String name, String texture, State state,
                       double cooldownFraction, long remainingMillis) { }

    public void update(KnownTechniques snapshot, long nowMillis) {
        // 整份替换使切服、重学和内容移除不会沿用过期的图标与冷却。
        known.clear();
        snapshot.getTechniquesList().forEach(entry -> known.put(entry.getId(), entry));
        receivedMillis = nowMillis;
    }

    public void clear() {
        // 断线与非法网络包同时清理全部暂态数据。
        known.clear();
        receivedMillis = 0;
    }

    public List<Slot> slots(List<String> bindings, long nowMillis) {
        // 保留重复绑定和十槽顺序，不按已学列表顺序重新排列。
        if (bindings.size() != CombatIds.SLOT_COUNT) {
            throw new IllegalArgumentException("HUD 必须有十个槽位");
        }
        var result = new ArrayList<Slot>();
        long elapsed = Math.max(0, nowMillis - receivedMillis);
        for (int index = 0; index < bindings.size(); index++) {
            result.add(slot(index + 1, bindings.get(index), elapsed));
        }
        return List.copyOf(result);
    }

    private Slot slot(int number, String id, long elapsed) {
        // 身体不可用优先显示叉号；冷却数据仍保留供恢复绑定后的下一次绘制。
        if (id.isEmpty()) {
            return new Slot(number, "空", "", State.EMPTY, 0, 0);
        }
        var entry = known.get(id);
        if (entry == null) {
            return new Slot(number, "未学", "", State.UNAVAILABLE, 0, 0);
        }
        String name = entry.getName();
        if (name.isEmpty()) {
            name = id;
        }
        long remaining = Math.max(0, entry.getCooldownRemainingMillis() - elapsed);
        long total = entry.getCooldownTotalMillis();
        double fraction = 0;
        if (total > 0) {
            fraction = Math.min(1, (double) remaining / total);
        }
        State state = State.AVAILABLE;
        if (!entry.getUnavailableReason().isEmpty()) {
            state = State.UNAVAILABLE;
        } else if (remaining > 0) {
            state = State.COOLDOWN;
        }
        return new Slot(number, name, texture(entry.getIconId()), state, fraction, remaining);
    }

    public static String texture(String iconId) {
        // 不允许目录穿越；资源 ID 固定映射到 gui/technique 下的 PNG。
        if (iconId.isEmpty()) {
            return "";
        }
        CombatIds.validateTechnique(iconId);
        String[] parts = iconId.split(":", 2);
        for (String segment : parts[1].split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("图标路径无效");
            }
        }
        return parts[0] + ":textures/gui/technique/" + parts[1] + ".png";
    }
}
