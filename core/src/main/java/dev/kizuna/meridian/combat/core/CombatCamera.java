package dev.kizuna.meridian.combat.core;

/** 越肩几何与碰撞收缩；世界适配器仅提供每条探测线的命中比例。 */
public final class CombatCamera {
    public static final double PROBE_RADIUS = 0.1;
    public static final double WALL_GAP = 0.05;
    private static final double[] PROBE_OFFSETS = {-PROBE_RADIUS, PROBE_RADIUS};

    private CombatCamera() {
        // 相机计算不保存世界或渲染帧状态。
    }

    public static Offset desired(CombatView.Angles camera, CombatViewSettings settings) {
        // 右、上、后均按相机基向量偏移，使人物在画面左下；俯仰也参与遮挡探测。
        double yaw = Math.toRadians(camera.yaw());
        double pitch = Math.toRadians(camera.pitch());
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double sinPitch = Math.sin(pitch);
        double cosPitch = Math.cos(pitch);
        double right = settings.shoulderOffset();
        double up = settings.heightOffset();
        double back = settings.distance();
        return new Offset(sinYaw * cosPitch * back - cosYaw * right - sinYaw * sinPitch * up,
                sinPitch * back + cosPitch * up,
                -cosYaw * cosPitch * back - sinYaw * right + cosYaw * sinPitch * up);
    }

    public static Offset clip(Offset desired, Trace trace) {
        // 中心和八角探测整段右上后偏移，不能只检测后退距离而漏掉肩侧墙壁。
        double desiredX = desired.x();
        double desiredY = desired.y();
        double desiredZ = desired.z();
        double nearest = trace.fraction(new Offset(0, 0, 0), desired);
        for (double x : PROBE_OFFSETS) {
            for (double y : PROBE_OFFSETS) {
                for (double z : PROBE_OFFSETS) {
                    var start = new Offset(x, y, z);
                    var end = new Offset(x + desiredX, y + desiredY, z + desiredZ);
                    nearest = Math.min(nearest, trace.fraction(start, end));
                }
            }
        }
        if (nearest >= 1) {
            return desired;
        }
        double length = Math.sqrt(desiredX * desiredX + desiredY * desiredY + desiredZ * desiredZ);
        double safeFraction = Math.max(0, nearest - WALL_GAP / length);
        if (safeFraction == 0) {
            return new Offset(0, 0, 0);
        }
        return new Offset(desiredX * safeFraction, desiredY * safeFraction, desiredZ * safeFraction);
    }

    @FunctionalInterface
    public interface Trace {
        /** 无遮挡返回 1；命中返回起点到终点之间的比例。 */
        double fraction(Offset start, Offset end);
    }

    public record Offset(double x, double y, double z) { }
}
