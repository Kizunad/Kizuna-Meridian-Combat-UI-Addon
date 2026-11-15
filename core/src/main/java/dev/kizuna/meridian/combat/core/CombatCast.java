package dev.kizuna.meridian.combat.core;

import java.util.List;

/**
 * 自己这一次施放此刻的样子，时刻都是相对起手的毫秒。服务端同步来的显示副本换算成它，施放条和十槽逐帧读；
 * 只管显示，放不放得出来仍由服务端判。
 *
 * @param freeMillis 收招段从这一刻开始：这以后按别的式直接起手
 * @param windows 自己会的、能接在这一式后面的式和各自的承接窗口
 * @param buffered 服务端替自己记着、等能出手就放的那一式；没有时为空串
 */
public record CombatCast(long elapsedMillis, long warmupEndMillis, long activeEndMillis, long recoveryEndMillis,
                         long freeMillis, List<Long> hitMillis, List<Window> windows, String buffered) {
    /** 没有在施放，也没有记着的按键。 */
    public static final CombatCast NONE = new CombatCast(0, 0, 0, 0, 0, List.of(), List.of(), "");

    /** 一式的承接窗口：从 openMillis 到 closeMillis，两端都算。 */
    public record Window(String technique, long openMillis, long closeMillis) { }

    public CombatCast {
        hitMillis = List.copyOf(hitMillis);
        windows = List.copyOf(windows);
    }

    /** 没在施放、只是记着一次按键（等冷却）。 */
    public static CombatCast waiting(String buffered) {
        return new CombatCast(0, 0, 0, 0, 0, List.of(), List.of(), buffered);
    }

    /** 这一式还占着人：收势没走完。 */
    public boolean casting() {
        return elapsedMillis < recoveryEndMillis;
    }

    /** 这一式现在正好能接在后面。 */
    public boolean followUpOpen(String technique) {
        return windows.stream().anyMatch(window -> window.technique().equals(technique)
                && elapsedMillis >= window.openMillis() && elapsedMillis <= window.closeMillis());
    }

    /** 现在按这一式会被「正运功中」挡住：还没到收招段，它的承接窗口也没开着。 */
    public boolean busy(String technique) {
        return casting() && elapsedMillis < freeMillis && !followUpOpen(technique);
    }

    /** 施放条画多长的一段时间：收势结束，或者最晚关的承接窗口，取晚的。 */
    public long spanMillis() {
        return Math.max(recoveryEndMillis, windows.stream().mapToLong(Window::closeMillis).max().orElse(0));
    }
}
