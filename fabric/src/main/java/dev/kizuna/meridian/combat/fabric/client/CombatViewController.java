package dev.kizuna.meridian.combat.fabric.client;

import dev.kizuna.meridian.combat.core.CombatCamera;
import dev.kizuna.meridian.combat.core.CombatInput;
import dev.kizuna.meridian.combat.core.CombatProfiles;
import dev.kizuna.meridian.combat.core.CombatView;
import dev.kizuna.meridian.combat.core.CombatViewMath;
import dev.kizuna.meridian.combat.core.CombatCameraEdit;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** 把纯 Java 视角会话接到客户端实体、按键与原版视角设置。 */
public final class CombatViewController {
    private static final float LOOK_DEGREES = 0.15f;
    private static CombatViewController instance;
    private final CombatProfiles profiles;
    private final CombatView view;
    private final CombatTargets targets = new CombatTargets();
    private CombatCameraEdit preview;
    private CombatView.Angles previewAngles;
    private Object sessionWorld;
    private Entity sessionPlayer;
    private boolean clipped;

    public CombatViewController(CombatProfiles profiles) {
        // 客户端初始化之前 Mixin 可执行，但此时不接管游戏行为。
        this.profiles = profiles;
        this.view = new CombatView(profiles.view().mode());
        view.configureLock(profiles.stance().viewLock());
        instance = this;
    }

    public static CombatViewController forShoulder(Entity entity) {
        // 只接管当前本地玩家，死亡和旁观无需等待下一 tick 就停止覆盖。
        var client = Minecraft.getInstance();
        var player = client.player;
        CombatViewController controller = instance;
        if (controller == null || !controller.view.shoulder() || player == null || entity != player
                || client.getCameraEntity() != player || !player.isAlive() || player.isSpectator()) {
            return null;
        }
        return controller;
    }

    public void reconcile(Minecraft client, CombatInput.State state) {
        // 原视角只在首次进入时记录；所有退出原因均恢复同一快照。
        boolean valid = state == CombatInput.State.ACTIVE && client.player != null
                && client.getCameraEntity() == client.player && client.player.isAlive() && !client.player.isSpectator();
        if (sessionWorld != client.level || sessionPlayer != client.player) {
            invalidate();
            sessionWorld = client.level;
            sessionPlayer = client.player;
        }
        if (valid) {
            view.enter(toCore(client.options.getCameraType()), facing(client.player));
            view.configureLock(profiles.stance().viewLock());
            applyPerspective(client, toMinecraft(view.perspective()));
        } else if (view.active()) {
            invalidate();
            applyPerspective(client, toMinecraft(view.exit()));
        }
        if (!view.shoulder()) {
            invalidate();
        }
        targets.validate(client, profiles.stance().targetRadius());
    }

    public void toggle(LocalPlayer player) {
        // 按键改变的偏好交由原有整份文件保存路径持久化。
        view.toggle(facing(player));
        profiles.selectViewMode(view.mode());
    }

    public CombatView view() {
        // 输入适配器复用同一会话计算施放方向。
        return view;
    }

    public void look(double deltaX, double deltaY) {
        // MouseHandler 已处理灵敏度、平滑与反转，这里只沿用 Entity 的角度缩放。
        var client = Minecraft.getInstance();
        if (preview != null || client.gui.screen() != null || !client.isWindowActive()) {
            return;
        }
        view.rotate((float) deltaX * LOOK_DEGREES, (float) deltaY * LOOK_DEGREES);
        if (view.locked() && targets.current() == null) {
            followCamera(client.player);
        }
    }

    public Vec3 relativeInput(float entityYaw, Vec3 input) {
        // 仅换算加速度输入，实体真实 yaw 从未被临时改成镜头 yaw。
        var axes = CombatViewMath.relativeInput((float) input.z, (float) input.x,
                view.camera().yaw(), entityYaw);
        return new Vec3(axes.sideways(), input.y, axes.forward());
    }

    public void turn(LocalPlayer player) {
        // ClientInput.tick 后读取本 tick 移动轴，在原版移动与发包之前限速转身。
        // 26.2 的移动向量已归一化（x 向左、y 向前），朝向只取方向，与原先的两个冲量等价。
        if (Minecraft.getInstance().gui.screen() != null || preview != null) {
            return;
        }
        float turnLimit = profiles.view().turnDegreesPerTick();
        Entity targetEntity = targets.current();
        if (targetEntity != null) {
            Vec3 delta = CombatTargets.aim(targetEntity).subtract(player.getEyePosition());
            var offset = new CombatCamera.Offset(delta.x, delta.y, delta.z);
            var angles = CombatViewMath.face(offset, facing(player), turnLimit);
            player.setYRot(angles.yaw());
            player.setXRot(angles.pitch());
            return;
        }
        if (view.locked()) {
            followCamera(player);
            return;
        }
        var move = player.input.getMoveVector();
        float currentYaw = player.getYRot();
        float target = CombatViewMath.movementYaw(move.y, move.x, view.camera().yaw(), currentYaw);
        float yaw = CombatViewMath.turnYaw(currentYaw, target, turnLimit);
        player.setYRot(yaw);
    }

    private void followCamera(LocalPlayer player) {
        // 原版 turn 同步插值基线和乘坐朝向；取最短角差，避免跨正负一百八十度时绕整圈。
        var camera = view.camera();
        float yawDelta = CombatViewMath.wrapYaw(camera.yaw() - player.getYRot());
        float pitchDelta = camera.pitch() - player.getXRot();
        player.turn(yawDelta / LOOK_DEGREES, pitchDelta / LOOK_DEGREES);
    }

    public float jumpYaw(LocalPlayer player) {
        // 疾跑跳跃的额外水平冲量也跟随移动方向，不能被未转完的人物拖偏。
        var move = player.input.getMoveVector();
        return CombatViewMath.movementYaw(move.y, move.x, view.camera().yaw(), player.getYRot());
    }

    public CombatCamera.Offset cameraOffset() {
        // 几何参数来自同一份已校验本地配置。
        var settings = profiles.view();
        if (preview != null) {
            settings = preview.draft();
        }
        return CombatCamera.desired(view.camera(), settings);
    }

    String toggleTarget(Minecraft client) {
        // 按键入口已校验第三人称和无界面，实体选择与清理集中维护。
        return targets.toggle(client, profiles.stance().targetRadius());
    }

    String targetName() {
        // HUD 只展示已锁定目标名称，不提供额外透视效果。
        Entity target = targets.current();
        if (target == null) {
            return "";
        }
        return target.getName().getString();
    }

    CombatCameraEdit beginPreview() {
        // 草稿只覆盖当前有效会话，进入时解除自动朝向。
        if (forShoulder(Minecraft.getInstance().player) != this || preview != null) {
            throw new IllegalStateException("须先 R 进入架势、V 切到越肩模式");
        }
        targets.clear();
        previewAngles = view.camera();
        preview = new CombatCameraEdit(profiles.view(), System.nanoTime());
        return preview;
    }

    boolean previewing(CombatCameraEdit editor) {
        // 用草稿身份拒绝已失效或前一窗口的编辑器。
        return preview == editor && forShoulder(Minecraft.getInstance().player) == this;
    }

    boolean editing() {
        // 输入适配器在原版移动和快捷键处理之前清理编辑期间的游戏输入。
        return preview != null;
    }

    void endPreview(CombatCameraEdit editor) {
        // 只恢复仍存活会话的原角度；不会复活已退出的架势。
        if (preview == editor) {
            view.restoreCamera(previewAngles);
            preview = null;
            previewAngles = null;
        }
    }

    public void cameraClipped(CombatCamera.Offset desired, CombatCamera.Offset actual) {
        // 只记录碰撞反馈，期望参数继续保存在独立草稿。
        clipped = !desired.equals(actual);
    }

    boolean clipped() {
        // 供编辑器显示当前帧碰撞说明。
        return clipped;
    }

    private void invalidate() {
        // 失效清理不恢复角度、不保存草稿，也不再选择目标。
        preview = null;
        previewAngles = null;
        targets.clear();
    }

    private static CombatView.Angles facing(Entity entity) {
        // 进入与模式切换只读人物角度，绕看不会写回人物俯仰。
        return new CombatView.Angles(entity.getYRot(), entity.getXRot());
    }

    private static void applyPerspective(Minecraft client, CameraType selected) {
        // 复用原版 F5 切换的着色器语义，只在实际变化时执行；26.2 原版切换不再要求刷新地形。
        CameraType previous = client.options.getCameraType();
        if (previous == selected) {
            return;
        }
        client.options.setCameraType(selected);
        if (previous.isFirstPerson() != selected.isFirstPerson()) {
            Entity cameraEntity = null;
            if (selected.isFirstPerson()) {
                cameraEntity = client.getCameraEntity();
            }
            client.gameRenderer.checkEntityPostEffect(cameraEntity);
        }
    }

    private static CombatView.Perspective toCore(CameraType perspective) {
        // 显式映射避免依赖两个枚举的序号一致。
        return switch (perspective) {
            case FIRST_PERSON -> CombatView.Perspective.FIRST_PERSON;
            case THIRD_PERSON_BACK -> CombatView.Perspective.THIRD_PERSON_BACK;
            case THIRD_PERSON_FRONT -> CombatView.Perspective.THIRD_PERSON_FRONT;
        };
    }

    private static CameraType toMinecraft(CombatView.Perspective perspective) {
        // 退出架势时精确恢复正面第三人称等原版选择。
        return switch (perspective) {
            case FIRST_PERSON -> CameraType.FIRST_PERSON;
            case THIRD_PERSON_BACK -> CameraType.THIRD_PERSON_BACK;
            case THIRD_PERSON_FRONT -> CameraType.THIRD_PERSON_FRONT;
        };
    }
}
