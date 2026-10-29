package dev.kizuna.meridian.combat.core;

/** 镜头相对输入与人物转身的纯计算，不依赖 Minecraft 实体。 */
public final class CombatViewMath {
    private static final float HALF_TURN = 180;
    private static final float FULL_TURN = 360;

    private CombatViewMath() {
        // 工具类不持有输入或实体状态。
    }

    public static Axes relativeInput(float forward, float sideways, float cameraYaw, float entityYaw) {
        // 将镜头局部输入换到人物局部坐标，保留原版潜行、用物品和斜向输入幅度。
        double difference = Math.toRadians(cameraYaw - entityYaw);
        double sine = Math.sin(difference);
        double cosine = Math.cos(difference);
        return new Axes((float) (forward * cosine + sideways * sine),
                (float) (sideways * cosine - forward * sine));
    }

    public static float movementYaw(float forward, float sideways, float cameraYaw, float currentYaw) {
        // 相反按键抵消或没有输入时，人物保持方向且镜头可独立绕看。
        if (forward == 0 && sideways == 0) {
            return currentYaw;
        }
        CombatDirection direction = CombatDirection.fromInput(forward, sideways, cameraYaw);
        return (float) Math.toDegrees(Math.atan2(-direction.x(), direction.z()));
    }

    public static float turnYaw(float current, float target, float maximumStep) {
        // 沿最短角差限速且不越过目标；保留连续 yaw 以便原版逐帧插值。
        float difference = wrapYaw(target - current);
        float step = Math.max(-maximumStep, Math.min(maximumStep, difference));
        return current + step;
    }

    public static float wrapYaw(float yaw) {
        // 把角差统一到负一百八十度至正一百八十度之间。
        float remainder = yaw % FULL_TURN;
        if (remainder >= HALF_TURN) {
            return remainder - FULL_TURN;
        }
        if (remainder < -HALF_TURN) {
            return remainder + FULL_TURN;
        }
        return remainder;
    }

    public record Axes(float forward, float sideways) { }
}
