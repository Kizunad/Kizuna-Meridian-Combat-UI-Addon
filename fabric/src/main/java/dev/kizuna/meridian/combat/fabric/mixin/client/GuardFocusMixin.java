package dev.kizuna.meridian.combat.fabric.mixin.client;

import com.mojang.blaze3d.platform.Window;
import dev.kizuna.meridian.combat.fabric.client.CombatClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GLFW 焦点边沿即时结束格挡，不依赖恢复焦点后的物理键轮询。 */
@Mixin(Window.class)
abstract class GuardFocusMixin {
    @Inject(method = "onFocus", at = @At("HEAD"))
    private void combat$releaseGuard(long window, boolean focused, CallbackInfo callback) {
        // 只处理游戏窗口，焦点恢复没有重新按下的含义。
        var client = Minecraft.getInstance();
        if (!focused && client != null && client.getWindow() == (Object) this) {
            CombatClient.releaseGuard();
        }
    }
}
