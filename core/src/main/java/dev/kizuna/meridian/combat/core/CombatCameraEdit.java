package dev.kizuna.meridian.combat.core;

/** 独立相机草稿，按单调时间和归一化输入调整期望偏移。 */
public final class CombatCameraEdit {
    public static final double BLOCKS_PER_SECOND = 2;
    public static final double MAX_FRAME_SECONDS = 0.1;
    private static final double NANOS_PER_SECOND = 1_000_000_000;
    private final CombatViewSettings original;
    private CombatViewSettings draft;
    private long lastNanos;

    public CombatCameraEdit(CombatViewSettings original, long nowNanos) {
        // 草稿不持有配置文件，正式持久化由客户端服务负责。
        this.original = original;
        draft = original;
        lastNanos = nowNanos;
    }

    public CombatViewSettings draft() {
        // 每帧返回不可变快照，碰撞收缩不能写回期望参数。
        return draft;
    }

    public void reset() {
        // 默认按钮只改几何，模式与转身速度保持进入时设置。
        var defaults = CombatViewSettings.DEFAULT;
        draft = geometry(defaults.shoulderOffset(), defaults.heightOffset(), defaults.distance());
    }

    public void step(Axes axes, long nowNanos) {
        // 大帧间隔只推进有限步长；相反输入由事件状态抵消后传入。
        double elapsed = Math.max(0, Math.min(MAX_FRAME_SECONDS, (nowNanos - lastNanos) / NANOS_PER_SECOND));
        lastNanos = nowNanos;
        int rightAxis = axes.right();
        int upAxis = axes.up();
        int backAxis = axes.back();
        double length = Math.sqrt(rightAxis * rightAxis + upAxis * upAxis + backAxis * backAxis);
        if (length == 0) {
            return;
        }
        double step = BLOCKS_PER_SECOND * elapsed / Math.max(1, length);
        double right = clamp(draft.shoulderOffset() + rightAxis * step,
                -CombatViewSettings.MAX_OFFSET, CombatViewSettings.MAX_OFFSET);
        double up = clamp(draft.heightOffset() + upAxis * step,
                -CombatViewSettings.MAX_OFFSET, CombatViewSettings.MAX_OFFSET);
        double back = clamp(draft.distance() + backAxis * step,
                CombatViewSettings.MIN_DISTANCE, CombatViewSettings.MAX_DISTANCE);
        draft = geometry(right, up, back);
    }

    private CombatViewSettings geometry(double right, double up, double back) {
        // 数值和范围继续由唯一设置 record 校验。
        return new CombatViewSettings(original.mode(), right, up, back, original.turnDegreesPerTick());
    }

    private static double clamp(double value, double minimum, double maximum) {
        // 连续输入到达边界时停止，不将合法编辑变成异常。
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Axes(int right, int up, int back) {
        public static final Axes ZERO = new Axes(0, 0, 0);

        public Axes {
            // 只接纳按键轴，不允许外部绕过速度限制。
            if (right < -1 || right > 1 || up < -1 || up > 1 || back < -1 || back > 1) {
                throw new IllegalArgumentException("相机编辑轴必须为 -1、0 或 1");
            }
        }
    }
}
