package dev.kizuna.meridian.combat.core;

import java.util.List;

/** 施放条的排版：把一次施放的各个时刻换成条上的像素位置。纯计算，画法在渲染器里。 */
public final class CombatCastBar {
    /**
     * 条上各处的横坐标，都在 0 到 width 之间。
     *
     * @param warmupEnd 起手段画到这里
     * @param activeEnd 出招段画到这里
     * @param free 收招段从这里开始（这以后能直接起下一式）
     * @param recoveryEnd 收势画到这里；后面是只剩承接窗口还开着的一小段
     * @param cursor 此刻走到这里
     * @param hits 各次打击的位置
     * @param windows 各个承接窗口占的一段
     */
    public record Layout(int warmupEnd, int activeEnd, int free, int recoveryEnd, int cursor,
                         List<Integer> hits, List<Span> windows) { }

    /** 条上的一段，两端都算。 */
    public record Span(int from, int to) { }

    private CombatCastBar() {
        // 只有一个纯函数入口。
    }

    public static Layout layout(CombatCast cast, int width) {
        // 整条表示从起手到「收势结束和最晚关的承接窗口里晚的那个」；时刻按比例落到像素上，越界的夹到两端。
        long span = Math.max(1, cast.spanMillis());
        List<Integer> hits = cast.hitMillis().stream().map(hit -> pixel(hit, span, width)).toList();
        List<Span> windows = cast.windows().stream()
                .map(window -> new Span(pixel(window.openMillis(), span, width),
                        pixel(window.closeMillis(), span, width)))
                .toList();
        long free = Math.min(cast.freeMillis(), cast.recoveryEndMillis());
        return new Layout(pixel(cast.warmupEndMillis(), span, width), pixel(cast.activeEndMillis(), span, width),
                pixel(free, span, width), pixel(cast.recoveryEndMillis(), span, width),
                pixel(cast.elapsedMillis(), span, width), hits, windows);
    }

    private static int pixel(long millis, long span, int width) {
        long clamped = Math.max(0, Math.min(span, millis));
        return Math.toIntExact(clamped * width / span);
    }
}
