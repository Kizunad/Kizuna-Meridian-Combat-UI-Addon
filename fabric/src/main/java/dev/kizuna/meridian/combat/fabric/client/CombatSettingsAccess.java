package dev.kizuna.meridian.combat.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.combat.core.CombatCameraEdit;
import dev.kizuna.meridian.combat.core.CombatStanceSettings;
import dev.kizuna.meridian.combat.core.CombatViewSettings;
import java.util.Objects;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** 客户端设置门面；不依赖 UI 框架，不返回组件或可变配置集合。 */
public final class CombatSettingsAccess {
    public enum Action { LOCK_VIEW, FACE_TARGET }
    public enum Status { SAVED, FAILED }
    public record Result(Status status, String message) { }
    public record Snapshot(CombatViewSettings view, CombatStanceSettings stance, boolean writable) { }
    public record Keys(InputConstants.Key lockView, InputConstants.Key faceTarget) {
        public Keys {
            // 未绑定使用原版 UNKNOWN，空引用不是合法键位草稿。
            Objects.requireNonNull(lockView);
            Objects.requireNonNull(faceTarget);
        }
    }
    private static CombatClient client;

    private CombatSettingsAccess() {
        // 所有调用都回到唯一 Combat 客户端与配置路径。
    }

    static void initialize(CombatClient instance) {
        // Fabric 客户端入口显式接线，缺少接线不提供降级状态。
        client = Objects.requireNonNull(instance);
    }

    private static CombatClient client() {
        // 访问前统一检查线程和初始化，调用者不会拿到半初始化状态。
        validateClientAccess();
        return client;
    }

    private static void validateClientAccess() {
        // 配置和相机状态仅在客户端线程访问。
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("Combat 设置只能在客户端线程操作");
        }
        Objects.requireNonNull(client, "Combat 客户端尚未初始化");
    }

    public static Snapshot snapshot() {
        // 返回共享偏好快照，临时镜头角与目标身份不在其中。
        return client().settings();
    }

    public static Result save(CombatStanceSettings settings) {
        // 父页保存仅更新本页拥有的字段，不回滚预览已提交的相机参数。
        var owner = client();
        return owner.saveSettings(owner.settings().view(), settings);
    }

    public static KeyMapping keyMapping(Action action) {
        // 与原版控制页共用映射；UI 应只在明确保存时改变绑定。
        return client().settingsKey(action);
    }

    public static Result saveKeys(Keys keys) {
        // options 与 Combat 文件各自提交并报告结果，不宣称跨文件原子性。
        return client().saveSettingsKeys(keys);
    }

    public static String previewUnavailableReason() {
        // 架势外允许编辑偏好，但不自动为预览进入战斗姿态。
        var game = Minecraft.getInstance();
        validateClientAccess();
        if (CombatViewController.forShoulder(game.player) == null) {
            return "须先 R 进入架势、V 切到越肩模式";
        }
        return "";
    }

    public static Preview beginPreview() {
        // 门面持有令牌；真正相机覆盖仍由 Combat 控制器维护。
        var controller = client().views();
        clearInput();
        return new Preview(controller, controller.beginPreview());
    }

    public static void clearInput() {
        // 页关闭、录入和预览切换都清理排队及跨屏幕长按。
        client().clearSettingsInput();
    }

    public static final class Preview implements AutoCloseable {
        private final CombatViewController controller;
        private final CombatCameraEdit editor;
        private boolean closed;

        private Preview(CombatViewController controller, CombatCameraEdit editor) {
            // 构造器只在门面开始正式预览后调用。
            this.controller = controller;
            this.editor = editor;
        }

        public boolean active() {
            // 失效后 UI 可以返回父页，但不得借此恢复战斗状态。
            validateClientAccess();
            return !closed && controller.previewing(editor);
        }

        public CombatViewSettings draft() {
            // 几何快照可用于数值展示，不暴露编辑器内部。
            validateClientAccess();
            return editor.draft();
        }

        public void step(CombatCameraEdit.Axes axes, long nowNanos) {
            // UI 只传输入轴与单调时间，速度、归一化与边界算法归 Combat。
            if (active()) {
                editor.step(axes, nowNanos);
            }
        }

        public void reset() {
            // 恢复默认不提前保存，也不修改父页的其他字段。
            if (active()) {
                editor.reset();
            }
        }

        public boolean clipped() {
            // 反馈来自正式碰撞路径，不把收缩参数写入配置。
            validateClientAccess();
            return controller.clipped();
        }

        public Result save() {
            // 保存失败继续保留覆盖与草稿，只有成功才结束预览。
            if (!active()) {
                return new Result(Status.FAILED, "预览会话已失效，未保存");
            }
            var owner = client();
            Result result = owner.saveSettings(editor.draft(), owner.settings().stance());
            if (result.status() == Status.SAVED) {
                close();
            }
            return result;
        }

        @Override
        public void close() {
            // 重复取消或 scope 清理只结束一次，不恢复失效会话。
            validateClientAccess();
            if (!closed) {
                closed = true;
                controller.endPreview(editor);
                clearInput();
            }
        }
    }
}
