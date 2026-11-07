package dev.kizuna.meridian.combat.core;

import java.util.Objects;

/** 全客户端共享的视角偏好；长度单位为方块，转身速度单位为度每 tick。 */
public record CombatViewSettings(CombatView.Mode mode, double shoulderOffset, double heightOffset,
                                 double distance, float turnDegreesPerTick) {
    public static final double MAX_OFFSET = 4;
    public static final double MIN_DISTANCE = 0.25;
    public static final double MAX_DISTANCE = 8;
    public static final float MIN_TURN = 0.1f;
    public static final float MAX_TURN = 180;
    private static final double DEFAULT_SHOULDER_OFFSET = 0.65;
    private static final double DEFAULT_HEIGHT_OFFSET = 0.35;
    private static final double DEFAULT_DISTANCE = 3;
    private static final float DEFAULT_TURN_DEGREES_PER_TICK = 18;
    public static final CombatViewSettings DEFAULT =
            new CombatViewSettings(CombatView.Mode.FIRST_PERSON, DEFAULT_SHOULDER_OFFSET,
                    DEFAULT_HEIGHT_OFFSET, DEFAULT_DISTANCE, DEFAULT_TURN_DEGREES_PER_TICK);

    public CombatViewSettings {
        // 允许左右肩与上下偏移，所有几何参数必须有限。
        Objects.requireNonNull(mode);
        validateRange(shoulderOffset, -MAX_OFFSET, MAX_OFFSET);
        validateRange(heightOffset, -MAX_OFFSET, MAX_OFFSET);
        validateRange(distance, MIN_DISTANCE, MAX_DISTANCE);
        validateRange(turnDegreesPerTick, MIN_TURN, MAX_TURN);
    }

    public CombatViewSettings withMode(CombatView.Mode selected) {
        // 切换模式保留用户配置的几何参数与转身速度。
        return new CombatViewSettings(selected, shoulderOffset, heightOffset, distance, turnDegreesPerTick);
    }

    private static void validateRange(double value, double minimum, double maximum) {
        // 非法配置整份进入原件保护，不能静默裁剪后写回。
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException("战斗视角参数超出范围");
        }
    }
}
