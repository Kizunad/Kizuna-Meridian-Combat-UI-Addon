package dev.kizuna.meridian.combat.fabric.mixin.client;

import dev.kizuna.meridian.combat.fabric.client.CombatViewController;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 在原版速度合成入口换算移动基向量，不修改按键轴和实体朝向。 */
@Mixin(Entity.class)
abstract class EntityMovementMixin {
    @ModifyVariable(method = "moveRelative", at = @At("HEAD"), argsOnly = true)
    private Vec3 combat$relativeInput(Vec3 input) {
        // 保留潜行、疾跑判定和物品减速使用的原始输入字段。
        var entity = (Entity) (Object) this;
        var controller = CombatViewController.forShoulder(entity);
        if (controller != null) {
            return controller.relativeInput(entity.getYRot(), input);
        }
        return input;
    }
}
