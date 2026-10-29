package dev.kizuna.meridian.combat.fabric.client;

import dev.kizuna.meridian.combat.core.CombatCamera;
import dev.kizuna.meridian.combat.core.CombatInput;
import dev.kizuna.meridian.combat.core.CombatProfiles;
import dev.kizuna.meridian.combat.core.CombatView;
import dev.kizuna.meridian.combat.core.CombatViewMath;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/** 把纯 Java 视角会话接到客户端实体、按键与原版视角设置。 */
public final class CombatViewController {
    private static final float LOOK_DEGREES = 0.15f;
    private static CombatViewController instance;
    private final CombatProfiles profiles;
    private final CombatView view;

    public CombatViewController(CombatProfiles profiles) {
        // 客户端初始化之前 Mixin 可执行，但此时不接管游戏行为。
        this.profiles = profiles;
        this.view = new CombatView(profiles.view().mode());
        instance = this;
    }

    public static CombatViewController forShoulder(Entity entity) {
        // 只接管当前本地玩家，死亡和旁观无需等待下一 tick 就停止覆盖。
        var client = MinecraftClient.getInstance();
        var player = client.player;
        CombatViewController controller = instance;
        if (controller == null || !controller.view.shoulder() || player == null || entity != player
                || client.getCameraEntity() != player || !player.isAlive() || player.isSpectator()) {
            return null;
        }
        return controller;
    }

    public void reconcile(MinecraftClient client, CombatInput.State state) {
        // 原视角只在首次进入时记录；所有退出原因均恢复同一快照。
        if (state == CombatInput.State.ACTIVE && client.player != null) {
            view.enter(toCore(client.options.getPerspective()), facing(client.player));
            applyPerspective(client, toMinecraft(view.perspective()));
        } else if (view.active()) {
            applyPerspective(client, toMinecraft(view.exit()));
        }
    }

    public void toggle(ClientPlayerEntity player) {
        // 按键改变的偏好交由原有整份文件保存路径持久化。
        view.toggle(facing(player));
        profiles.selectViewMode(view.mode());
    }

    public CombatView view() {
        // 输入适配器复用同一会话计算施放方向。
        return view;
    }

    public void look(double deltaX, double deltaY) {
        // Mouse 已处理灵敏度、平滑与反转，这里只沿用 Entity 的角度缩放。
        view.rotate((float) deltaX * LOOK_DEGREES, (float) deltaY * LOOK_DEGREES);
    }

    public Vec3d relativeInput(float entityYaw, Vec3d input) {
        // 仅换算加速度输入，实体真实 yaw 从未被临时改成镜头 yaw。
        var axes = CombatViewMath.relativeInput((float) input.z, (float) input.x,
                view.camera().yaw(), entityYaw);
        return new Vec3d(axes.sideways(), input.y, axes.forward());
    }

    public void turn(ClientPlayerEntity player) {
        // Input.tick 后读取本 tick 移动轴，在原版移动与发包之前限速转身。
        var input = player.input;
        float currentYaw = player.getYaw();
        float target = CombatViewMath.movementYaw(input.movementForward, input.movementSideways,
                view.camera().yaw(), currentYaw);
        float yaw = CombatViewMath.turnYaw(currentYaw, target, profiles.view().turnDegreesPerTick());
        player.setYaw(yaw);
    }

    public float jumpYaw(ClientPlayerEntity player) {
        // 疾跑跳跃的额外水平冲量也跟随移动方向，不能被未转完的人物拖偏。
        var input = player.input;
        return CombatViewMath.movementYaw(input.movementForward, input.movementSideways,
                view.camera().yaw(), player.getYaw());
    }

    public CombatCamera.Offset cameraOffset() {
        // 几何参数来自同一份已校验本地配置。
        return CombatCamera.desired(view.camera(), profiles.view());
    }

    private static CombatView.Angles facing(Entity entity) {
        // 进入与模式切换只读人物角度，绕看不会写回人物俯仰。
        return new CombatView.Angles(entity.getYaw(), entity.getPitch());
    }

    private static void applyPerspective(MinecraftClient client, Perspective selected) {
        // 复用原版切换的着色器与地形刷新语义，只在实际变化时执行。
        Perspective previous = client.options.getPerspective();
        if (previous == selected) {
            return;
        }
        client.options.setPerspective(selected);
        if (previous.isFirstPerson() != selected.isFirstPerson()) {
            Entity cameraEntity = null;
            if (selected.isFirstPerson()) {
                cameraEntity = client.getCameraEntity();
            }
            client.gameRenderer.onCameraEntitySet(cameraEntity);
        }
        client.worldRenderer.scheduleTerrainUpdate();
    }

    private static CombatView.Perspective toCore(Perspective perspective) {
        // 显式映射避免依赖两个枚举的序号一致。
        return switch (perspective) {
            case FIRST_PERSON -> CombatView.Perspective.FIRST_PERSON;
            case THIRD_PERSON_BACK -> CombatView.Perspective.THIRD_PERSON_BACK;
            case THIRD_PERSON_FRONT -> CombatView.Perspective.THIRD_PERSON_FRONT;
        };
    }

    private static Perspective toMinecraft(CombatView.Perspective perspective) {
        // 退出架势时精确恢复正面第三人称等原版选择。
        return switch (perspective) {
            case FIRST_PERSON -> Perspective.FIRST_PERSON;
            case THIRD_PERSON_BACK -> Perspective.THIRD_PERSON_BACK;
            case THIRD_PERSON_FRONT -> Perspective.THIRD_PERSON_FRONT;
        };
    }
}
