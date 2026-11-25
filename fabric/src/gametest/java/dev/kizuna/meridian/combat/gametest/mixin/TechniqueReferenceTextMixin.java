package dev.kizuna.meridian.combat.gametest.mixin;

import dev.kizuna.meridian.combat.gametest.CombatTestContent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.server.TechniqueReferenceText;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(TechniqueReferenceText.class)
abstract class TechniqueReferenceTextMixin {
    @Inject(method = "lines", at = @At("HEAD"))
    private static void failBrokenDetails(TechniqueRegistry registry, TechniqueDefinition definition,
                                          CallbackInfoReturnable<List<String>> callback) {
        // 只让指定测试条目的说明抛错，目录加载、学会、绑定和网络推送均走正式路径。
        if (CombatTestContent.BROKEN_DETAILS.equals(definition.getId())) {
            throw new IllegalStateException("测试功法的说明无法生成");
        }
    }
}
