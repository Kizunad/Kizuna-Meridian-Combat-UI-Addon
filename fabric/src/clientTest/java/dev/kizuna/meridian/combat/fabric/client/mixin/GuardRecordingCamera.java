package dev.kizuna.meridian.combat.fabric.client.mixin;

import dev.kizuna.meridian.combat.fabric.client.GuardStaminaRecording;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 测试录像保持本机玩家为相机主体，从正前方取景。 */
@Mixin(Camera.class)
abstract class GuardRecordingCamera {
    private static final int FRONT_OFFSET = 12;
    private static final int HALF_TURN = 180;
    private static final double DISTANCE = 2.7;
    private static final double HEIGHT = 1.15;

    @Shadow private boolean detached;
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPosition(Vec3 position);

    @Inject(method = "alignWithEntity", at = @At("HEAD"), cancellable = true)
    private void recordGuard(CallbackInfo callback) {
        // 只替换相机矩阵；实际渲染模型、持物和动画均来自本地玩家。
        if (GuardStaminaRecording.filming()) {
            var player = Minecraft.getInstance().player;
            float yaw = player.getYRot() + FRONT_OFFSET;
            var offset = Vec3.directionFromRotation(0, yaw).scale(DISTANCE);
            setRotation(yaw + HALF_TURN, 0);
            setPosition(player.position().add(offset).add(0, HEIGHT, 0));
            detached = true;
            callback.cancel();
        }
    }
}
