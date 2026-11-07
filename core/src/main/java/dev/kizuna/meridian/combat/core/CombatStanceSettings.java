package dev.kizuna.meridian.combat.core;

import java.util.Objects;

/** 共享姿态偏好；视角锁表示人物与镜头联动，目标身份和镜头角度不持久化。 */
public record CombatStanceSettings(ViewLock viewLock, double targetRadius) {
    public enum ViewLock { OFF, ON }
    public static final double MIN_RADIUS = 1;
    public static final double MAX_RADIUS = 128;
    public static final double DEFAULT_RADIUS = 50;
    public static final CombatStanceSettings DEFAULT = new CombatStanceSettings(ViewLock.OFF, DEFAULT_RADIUS);

    public CombatStanceSettings {
        // 半径按玩家三维位置判定，拒绝非有限值和配置范围外值。
        Objects.requireNonNull(viewLock);
        if (!Double.isFinite(targetRadius) || targetRadius < MIN_RADIUS || targetRadius > MAX_RADIUS) {
            throw new IllegalArgumentException("自动解除距离必须为 1 至 128 格的有限数值");
        }
    }

    public CombatStanceSettings toggleLock() {
        // 切换只改变联动偏好，当前镜头角度保持不变。
        ViewLock next = ViewLock.ON;
        if (viewLock == ViewLock.ON) {
            next = ViewLock.OFF;
        }
        return new CombatStanceSettings(next, targetRadius);
    }
}
