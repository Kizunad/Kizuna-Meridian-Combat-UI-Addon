package dev.kizuna.meridian.combat.core;

import java.util.Objects;

/** 架势视角会话；仅保存客户端镜头角，不写入人物或网络状态。 */
public final class CombatView {
    public enum Mode { FIRST_PERSON, SHOULDER }
    public enum Perspective { FIRST_PERSON, THIRD_PERSON_BACK, THIRD_PERSON_FRONT }
    private static final float MAX_PITCH = 90;
    private Mode mode;
    private Perspective original;
    private Angles camera = new Angles(0, 0);

    public CombatView(Mode mode) {
        // 已持久化的模式可跨连接沿用，进入前视角只在会话内保存。
        this.mode = Objects.requireNonNull(mode);
    }

    public void enter(Perspective perspective, Angles facing) {
        // 重复协调不能覆盖最初视角，否则退出时会恢复成架势视角。
        if (!active()) {
            original = Objects.requireNonNull(perspective);
            camera = Objects.requireNonNull(facing);
        }
    }

    public Perspective exit() {
        // 主动退出、死亡、旁观和断线复用同一个恢复路径。
        Perspective restored = Objects.requireNonNull(original, "没有活动的架势视角");
        original = null;
        return restored;
    }

    public void toggle(Angles facing) {
        // 切回越肩时从人物朝向起步；切回第一人称不把绕看角写回人物。
        if (!active()) {
            return;
        }
        if (mode == Mode.FIRST_PERSON) {
            mode = Mode.SHOULDER;
            camera = Objects.requireNonNull(facing);
        } else {
            mode = Mode.FIRST_PERSON;
        }
    }

    public void rotate(float yawDelta, float pitchDelta) {
        // 鼠标只更新镜头，俯仰沿用原版上下九十度边界。
        if (shoulder()) {
            float pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, camera.pitch() + pitchDelta));
            camera = new Angles(CombatViewMath.wrapYaw(camera.yaw() + yawDelta), pitch);
        }
    }

    public boolean active() {
        // 原视角同时标识当前会话是否持有恢复责任。
        return original != null;
    }

    public boolean shoulder() {
        // 架势外保留偏好，但不接管任何输入或渲染。
        return active() && mode == Mode.SHOULDER;
    }

    public Mode mode() {
        // 模式可单独持久化，不包含临时镜头角或进入前视角。
        return mode;
    }

    public Angles camera() {
        // 不可变快照不会被渲染调用方改写。
        return camera;
    }

    public Perspective perspective() {
        // 越肩利用原版第三人称人物渲染；两种模式均覆盖架势内 F5 选择。
        if (mode == Mode.SHOULDER) {
            return Perspective.THIRD_PERSON_BACK;
        }
        return Perspective.FIRST_PERSON;
    }

    public CombatDirection castDirection(float forward, float sideways, float entityYaw) {
        // 越肩的有输入施放沿人物当前朝向；无输入继续传零给宿主默认方向。
        if (shoulder() && (forward != 0 || sideways != 0)) {
            return CombatDirection.fromInput(1, 0, entityYaw);
        }
        return CombatDirection.fromInput(forward, sideways, entityYaw);
    }

    public record Angles(float yaw, float pitch) {
        public Angles {
            // 拒绝非有限旋转，避免后续三角计算污染速度和相机矩阵。
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("战斗视角角度必须有限");
            }
        }
    }
}
