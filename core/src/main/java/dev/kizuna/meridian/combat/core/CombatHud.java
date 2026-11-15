package dev.kizuna.meridian.combat.core;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.BlockKind;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 十槽显示模型；冷却按世界 tick 对照服务端给的结束时刻，只影响表现，不产生施放权限或持久化冷却。 */
public final class CombatHud {
    public static final long MILLIS_PER_TICK = 50;
    private final Map<String, Display> known = new HashMap<>();

    /** 一门已学功法不随时间变的显示内容：列表到达时算好，逐帧只算冷却。 */
    private record Display(KnownTechnique entry, String name, String texture) { }

    /**
     * SHORT 是真元、力气这会儿不够，或手里的东西不对：图标变灰、叠原因角标，不标红框；在冷却里时仍算 COOLDOWN，角标照叠。
     * BUSY 是正在放别的式、这会儿按不出来：同样变灰叠角标。
     */
    public enum State { EMPTY, UNAVAILABLE, SHORT, BUSY, COOLDOWN, AVAILABLE }

    /** 槽框上的提示：FOLLOW_UP 是这一式现在正好能接上，BUFFERED 是服务端替自己记着这一式的按键。 */
    public enum Mark { NONE, FOLLOW_UP, BUFFERED }

    /**
     * @param block 放不出来属于哪一类，决定右下角叠哪个小图标；没有时为 BLOCK_NONE
     */
    public record Slot(int number, String name, String texture, State state,
                       double cooldownFraction, long remainingMillis, BlockKind block, Mark mark) { }

    public void update(KnownTechniques snapshot) {
        // 整份替换使切服、重学和内容移除不会沿用过期的图标与冷却。
        known.clear();
        for (KnownTechnique entry : snapshot.getTechniquesList()) {
            String name = entry.getName();
            if (name.isEmpty()) {
                name = entry.getId();
            }
            known.put(entry.getId(), new Display(entry, name, texture(entry.getIconId())));
        }
    }

    public void clear() {
        // 断线与非法网络包同时清理全部暂态数据。
        known.clear();
    }

    /** gameTick 是客户端当前世界总 tick，与服务端填 cooldown_end_tick 用的是同一时基。 */
    public List<Slot> slots(List<String> bindings, long gameTick) {
        return slots(bindings, gameTick, CombatCast.NONE);
    }

    /** cast 是自己这一次施放此刻的样子：正在放别的式时各槽变灰，能接的和记着的槽亮提示。 */
    public List<Slot> slots(List<String> bindings, long gameTick, CombatCast cast) {
        // 保留重复绑定和十槽顺序，不按已学列表顺序重新排列。
        if (bindings.size() != CombatIds.SLOT_COUNT) {
            throw new IllegalArgumentException("HUD 必须有十个槽位");
        }
        var result = new ArrayList<Slot>();
        for (int index = 0; index < bindings.size(); index++) {
            result.add(slot(index + 1, bindings.get(index), gameTick, cast));
        }
        return List.copyOf(result);
    }

    private Slot slot(int number, String id, long gameTick, CombatCast cast) {
        // 身体不可用优先显示叉号；冷却数据仍保留供恢复绑定后的下一次绘制。
        if (id.isEmpty()) {
            return new Slot(number, "空", "", State.EMPTY, 0, 0, BlockKind.BLOCK_NONE, Mark.NONE);
        }
        Display display = known.get(id);
        if (display == null) {
            return new Slot(number, "未学", "", State.UNAVAILABLE, 0, 0, BlockKind.BLOCK_BODY, Mark.NONE);
        }
        KnownTechnique entry = display.entry();
        long remaining = Math.max(0, entry.getCooldownEndTick() - gameTick) * MILLIS_PER_TICK;
        long total = entry.getCooldownTotalMillis();
        double fraction = 0;
        if (remaining > 0) {
            // 剩余超过声明的冷却（招式曲线还没走完）或没有声明冷却时画满格。
            fraction = 1;
            if (total > 0) {
                fraction = Math.min(1, (double) remaining / total);
            }
        }
        BlockKind block = entry.getBlock();
        boolean lacking = block == BlockKind.BLOCK_QI || block == BlockKind.BLOCK_EXERTION
                || block == BlockKind.BLOCK_ITEM;
        State state = State.AVAILABLE;
        if (!entry.getUnavailableReason().isEmpty() && !lacking) {
            // 旧服务端只发原因文字、不发类别：照身体不可用画。
            state = State.UNAVAILABLE;
            block = BlockKind.BLOCK_BODY;
        } else if (remaining > 0) {
            state = State.COOLDOWN;
        } else if (lacking) {
            state = State.SHORT;
        } else if (cast.busy(id)) {
            state = State.BUSY;
            block = BlockKind.BLOCK_BUSY;
        }
        return new Slot(number, display.name(), display.texture(), state, fraction, remaining, block,
                mark(id, state, cast));
    }

    private static Mark mark(String id, State state, CombatCast cast) {
        // 记着的按键优先标；能接上的只标眼下真能按出来的槽。
        if (cast.buffered().equals(id)) {
            return Mark.BUFFERED;
        }
        if (state == State.AVAILABLE && cast.casting() && cast.followUpOpen(id)) {
            return Mark.FOLLOW_UP;
        }
        return Mark.NONE;
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
