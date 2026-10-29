package dev.kizuna.meridian.combat.fabric.mixin.client;

import dev.kizuna.meridian.combat.fabric.client.CombatViewController;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 保留原版鼠标预处理，仅替换越肩模式的角度接收者。 */
@Mixin(Mouse.class)
abstract class MouseMixin {
    @Redirect(method = "updateMouse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void combat$look(ClientPlayerEntity player, double deltaX, double deltaY) {
        // 镜头角不触碰玩家 yaw/pitch，因此不会进入原版移动包或选中射线。
        var controller = CombatViewController.forShoulder(player);
        if (controller != null) {
            controller.look(deltaX, deltaY);
        } else {
            player.changeLookDirection(deltaX, deltaY);
        }
    }
}
