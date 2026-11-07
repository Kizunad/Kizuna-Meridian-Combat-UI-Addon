package dev.kizuna.meridian.combat.fabric.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.combat.fabric.client.CombatClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版释放事件连接到姿态按键的长按去重。 */
@Mixin(KeyboardHandler.class)
abstract class KeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void combat$keyReleased(long window, int action, KeyEvent event, CallbackInfo callback) {
        // 仅接纳当前窗口的释放；按下行为仍由原版 KeyMapping 和客户端 tick 处理。
        if (action == GLFW.GLFW_RELEASE && window == Minecraft.getInstance().getWindow().handle()) {
            CombatClient.releaseSettingsKey(InputConstants.getKey(event));
        }
    }
}
