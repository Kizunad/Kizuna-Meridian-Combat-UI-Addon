package dev.kizuna.meridian.combat.core;

/** Minecraft 朝向与移动按键合成的单位水平方向。 */
public record CombatDirection(float x, float z) {
    public static CombatDirection fromInput(float forward, float sideways, float yawDegrees) {
        // 没有输入严格传零，由服务端宿主决定默认方向。
        double length = Math.hypot(forward, sideways);
        if (length == 0) {
            return new CombatDirection(0, 0);
        }
        double yaw = Math.toRadians(yawDegrees);
        double sine = Math.sin(yaw);
        double cosine = Math.cos(yaw);
        double x = (sideways * cosine - forward * sine) / length;
        double z = (forward * cosine + sideways * sine) / length;
        return new CombatDirection((float) x, (float) z);
    }
}
