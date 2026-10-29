package dev.kizuna.meridian.combat.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** 命令和按键共用的槽位语义，已学列表始终来自当前连接。 */
public final class CombatSlots {
    private final CombatProfiles profiles;
    private Map<String, String> known = Map.of();

    public CombatSlots(CombatProfiles profiles) {
        // 文件模型与网络知识分离，切服时只清理后者。
        this.profiles = profiles;
    }

    public void updateKnown(Map<String, String> entries) {
        // 每次完整替换，服务端移除内容后旧槽位保留但立即失效。
        known = Map.copyOf(entries);
    }

    public List<String> suggestions(String prefix) {
        // 补全只暴露服务器当前确认的已学功法。
        return known.keySet().stream().filter(id -> id.startsWith(prefix)).sorted().toList();
    }

    public void set(int number, String id, Instant now) {
        // 未学功法必须在触碰文件模型之前拒绝。
        CombatIds.validateTechnique(id);
        if (!known.containsKey(id)) {
            throw new IllegalArgumentException("这门功法尚未学会，不能放入战斗槽");
        }
        profiles.set(number, id, now);
    }

    public Selection select(int number) {
        // 按键只发送有效槽，空槽与失效槽保留不同的中文原因。
        String id = profiles.slots().get(CombatIds.slotIndex(number));
        if (id.isEmpty()) {
            return new Selection("", "第 " + number + " 槽为空");
        }
        if (!known.containsKey(id)) {
            return new Selection("", "第 " + number + " 槽的功法未学会");
        }
        return new Selection(id, "");
    }

    public List<String> lines() {
        // 列表显示全部十槽，重复功法与失效配置不被合并。
        return IntStream.rangeClosed(1, CombatIds.SLOT_COUNT).mapToObj(this::line).toList();
    }

    private String line(int number) {
        // 名称只用于展示，标识仍显示以便手动修改命令。
        String id = profiles.slots().get(CombatIds.slotIndex(number));
        if (id.isEmpty()) {
            return "第 " + number + " 槽：空";
        }
        String name = known.get(id);
        if (name == null) {
            return "第 " + number + " 槽：" + id + "（失效：未学会）";
        }
        return "第 " + number + " 槽：" + name + "（" + id + "）";
    }

    public record Selection(String techniqueId, String reason) { }
}
