package dev.kizuna.meridian.combat.fabric.mixin.client;

import dev.kizuna.meridian.combat.fabric.client.CombatViewController;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 人物转身接到本 tick 输入更新之后，模型朝向跟随真实实体 yaw。 */
@Mixin(LocalPlayer.class)
abstract class ClientPlayerMixin {
    @Inject(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER))
    private void combat$turn(CallbackInfo callback) {
        // 原版随后移动并发送真实人物角度，不需要自定义视角同步协议。
        var player = (LocalPlayer) (Object) this;
        var controller = CombatViewController.forShoulder(player);
        if (controller != null) {
            controller.turn(player);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void combat$alignModel(CallbackInfo callback) {
        // 避免原版按速度自动扭转身体，让画面中的人物与真实 yaw 一致。
        var player = (LocalPlayer) (Object) this;
        if (CombatViewController.forShoulder(player) != null) {
            float yaw = player.getYRot();
            player.setYBodyRot(yaw);
            player.setYHeadRot(yaw);
        }
    }
}
