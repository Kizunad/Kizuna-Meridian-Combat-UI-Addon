package dev.kizuna.meridian.combat.fabric.mixin.client;

import dev.kizuna.meridian.combat.fabric.client.CombatViewController;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 在原版速度合成入口换算移动基向量，不修改按键轴和实体朝向。 */
@Mixin(Entity.class)
abstract class EntityMovementMixin {
    @ModifyVariable(method = "updateVelocity", at = @At("HEAD"), argsOnly = true)
    private Vec3d combat$relativeInput(Vec3d input) {
        // 保留潜行、疾跑判定和物品减速使用的原始输入字段。
        var entity = (Entity) (Object) this;
        var controller = CombatViewController.forShoulder(entity);
        if (controller != null) {
            return controller.relativeInput(entity.getYaw(), input);
        }
        return input;
    }
}
