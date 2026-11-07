package dev.kizuna.meridian.combat.fabric.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.combat.fabric.client.CombatClient;
import dev.kizuna.meridian.combat.fabric.client.CombatViewController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 保留原版鼠标预处理，仅替换越肩模式的角度接收者。 */
@Mixin(MouseHandler.class)
abstract class MouseMixin {
    @Inject(method = "onButton", at = @At("HEAD"))
    private void combat$buttonReleased(long window, MouseButtonInfo button, int action, CallbackInfo callback) {
        // 原版控制页允许绑定鼠标键，其释放与键盘使用同一去重边界。
        if (action == GLFW.GLFW_RELEASE && window == Minecraft.getInstance().getWindow().handle()) {
            CombatClient.releaseSettingsKey(InputConstants.Type.MOUSE.getOrCreate(button.button()));
        }
    }

    @Redirect(method = "turnPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void combat$look(LocalPlayer player, double deltaX, double deltaY) {
        // 越肩由控制器协调独立绕看与人物联动，目标锁优先级也在同一入口判断。
        var controller = CombatViewController.forShoulder(player);
        if (controller != null) {
            controller.look(deltaX, deltaY);
        } else {
            player.turn(deltaX, deltaY);
        }
    }
}
