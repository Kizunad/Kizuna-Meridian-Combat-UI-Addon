package dev.kizuna.meridian.combat.fabric.mixin.client;

import dev.kizuna.meridian.combat.fabric.client.CombatViewController;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 疾跑跳跃保留原版冲量大小，只在越肩移动时更换水平基向量。 */
@Mixin(LivingEntity.class)
abstract class LivingEntityJumpMixin {
    @Redirect(method = "jumpFromGround", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"))
    private float combat$jumpYaw(LivingEntity entity) {
        // 其他实体与架势外路径仍读取自身真实朝向。
        var controller = CombatViewController.forShoulder(entity);
        if (controller != null) {
            return controller.jumpYaw((LocalPlayer) entity);
        }
        return entity.getYRot();
    }
}
