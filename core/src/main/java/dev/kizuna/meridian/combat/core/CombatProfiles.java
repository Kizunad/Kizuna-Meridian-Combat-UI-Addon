package dev.kizuna.meridian.combat.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 客户端十槽分档；不保存服务端权限或功法运行状态。 */
public final class CombatProfiles {
    public static final int MAX_PROFILES = 64;
    private final Map<String, Profile> profiles;
    private String current;
    private CombatViewSettings view;

    public CombatProfiles() {
        // 首次进入服务器前没有当前档。
        this("", Map.of());
    }

    public CombatProfiles(String lastProfile, Map<String, Profile> profiles) {
        // 旧文件没有视角配置时使用第一人称默认值。
        this(lastProfile, profiles, CombatViewSettings.DEFAULT);
    }

    public CombatProfiles(String lastProfile, Map<String, Profile> profiles, CombatViewSettings view) {
        // 载入整份快照时检查引用，避免半份损坏数据被写回。
        if (profiles.size() > MAX_PROFILES || (!lastProfile.isEmpty() && !profiles.containsKey(lastProfile))) {
            throw new IllegalArgumentException("分档数量或上一档引用无效");
        }
        profiles.keySet().forEach(CombatProfiles::validateProfileKey);
        this.profiles = new LinkedHashMap<>(profiles);
        this.current = lastProfile;
        this.view = java.util.Objects.requireNonNull(view);
    }

    public CombatViewSettings view() {
        // 视角偏好跨服务器共享，不跟随槽位档案复制或淘汰。
        return view;
    }

    public void selectViewMode(CombatView.Mode mode) {
        // 模式更改保留同份配置中的相机参数。
        view = view.withMode(mode);
    }

    public void enter(String key, Instant now) {
        // 新档复制上一档的槽位，访问时刻决定容量淘汰顺序。
        validateProfileKey(key);
        Profile previous = profiles.get(current);
        Profile existing = profiles.get(key);
        List<String> slots = emptySlots();
        if (existing != null) {
            slots = existing.slots();
        } else if (previous != null) {
            slots = previous.slots();
        }
        profiles.put(key, new Profile(now, slots));
        current = key;
        evictOldest();
    }

    public void set(int number, String id, Instant now) {
        // 重复功法可以占多个槽，冷却由功法服务独立校验。
        CombatIds.validateTechnique(id);
        replace(number, id, now);
    }

    public void clear(int number, Instant now) {
        // 空字符串是文件和内存统一的空槽表示。
        replace(number, "", now);
    }

    public List<String> slots() {
        // 连接前只暴露空槽，不让上一服务器的运行状态泄漏到菜单。
        Profile profile = profiles.get(current);
        if (profile == null) {
            return emptySlots();
        }
        return profile.slots();
    }

    public String current() {
        // 持久化只记录最后访问的分档键。
        return current;
    }

    public Map<String, Profile> snapshot() {
        // 序列化获取不可变副本，不能从外部改写档案。
        return Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    private void replace(int number, String id, Instant now) {
        // 所有修改必须先有当前连接对应的分档。
        int index = CombatIds.slotIndex(number);
        Profile profile = profiles.get(current);
        if (profile == null) {
            throw new IllegalArgumentException("请先进入世界再设置战斗槽");
        }
        var slots = new ArrayList<>(profile.slots());
        slots.set(index, id);
        profiles.put(current, new Profile(now, slots));
    }

    private void evictOldest() {
        // 相同时刻也不能淘汰刚进入的当前档。
        if (profiles.size() <= MAX_PROFILES) {
            return;
        }
        String oldest = profiles.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(current))
                .min(Comparator.comparing(entry -> entry.getValue().lastUsed()))
                .orElseThrow().getKey();
        profiles.remove(oldest);
    }

    private static void validateProfileKey(String key) {
        // 两种分档前缀不能互相碰撞，后缀不能为空。
        int length = key.length();
        if (!(key.startsWith("server:") && length > "server:".length())
                && !(key.startsWith("world:") && length > "world:".length())) {
            throw new IllegalArgumentException("战斗分档名称无效");
        }
    }

    private static List<String> emptySlots() {
        // 列表长度固定，调用方不能插入额外槽位。
        return Collections.nCopies(CombatIds.SLOT_COUNT, "");
    }

    public record Profile(Instant lastUsed, List<String> slots) {
        public Profile(Instant lastUsed, List<String> slots) {
            // 每档必须正好十槽，非法标识视为文件损坏而非静默丢弃。
            this.lastUsed = java.util.Objects.requireNonNull(lastUsed);
            this.slots = List.copyOf(slots);
            if (this.slots.size() != CombatIds.SLOT_COUNT) {
                throw new IllegalArgumentException("每个分档必须有十个槽");
            }
            for (String id : this.slots) {
                if (!id.isEmpty()) {
                    CombatIds.validateTechnique(id);
                }
            }
        }
    }
}
