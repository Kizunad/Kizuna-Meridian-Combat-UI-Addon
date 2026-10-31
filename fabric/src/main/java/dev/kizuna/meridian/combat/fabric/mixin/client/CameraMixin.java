package dev.kizuna.meridian.combat.fabric.mixin.client;

import dev.kizuna.meridian.combat.fabric.CombatCameraCollision;
import dev.kizuna.meridian.combat.fabric.client.CombatViewController;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 复用原版插值后的眼位，只替换越肩相机旋转、偏移和整段遮挡收缩。 */
@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow private BlockGetter level;
    @Shadow private Entity entity;
    @Shadow private Vec3 position;
    @Shadow private boolean detached;

    /** 更新相机矩阵，实体角度保持不变。 */
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    /** 更新相机位置及方块坐标缓存。 */
    @Shadow protected abstract void setPosition(Vec3 pos);

    @Inject(method = "setup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", shift = At.Shift.AFTER),
            cancellable = true)
    private void combat$shoulder(CallbackInfo callback) {
        // 在原版后退之前接管，碰撞覆盖肩侧和抬高偏移而不只是后方距离。
        var controller = CombatViewController.forShoulder(entity);
        if (controller == null) {
            return;
        }
        var angles = controller.view().camera();
        setRotation(angles.yaw(), angles.pitch());
        var offset = CombatCameraCollision.clip(level, entity, position, controller.cameraOffset());
        setPosition(position.add(offset.x(), offset.y(), offset.z()));
        detached = true;
        callback.cancel();
    }
}
