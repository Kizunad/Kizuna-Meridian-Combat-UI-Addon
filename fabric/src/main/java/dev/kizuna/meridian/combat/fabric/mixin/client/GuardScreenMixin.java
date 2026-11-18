package dev.kizuna.meridian.combat.fabric.mixin.client;

import dev.kizuna.meridian.combat.fabric.client.CombatClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 开屏即时结束格挡，关闭界面不恢复旧按压。 */
@Mixin(Gui.class)
abstract class GuardScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void combat$releaseGuard(Screen screen, CallbackInfo callback) {
        // 即使开关界面都在两个 tick 之间，也不能继续守。
        if (screen != null) {
            CombatClient.releaseGuard();
        }
    }
}
