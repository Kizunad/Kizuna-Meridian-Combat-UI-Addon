package dev.kizuna.meridian.combat.fabric;

import net.minecraft.resources.ResourceLocation;

/** 两端共同使用的 Fabric 通道标识。 */
public final class CombatChannels {
    public static final ResourceLocation PRESS = new ResourceLocation("meridian_combat", "press");
    public static final ResourceLocation KNOWN = new ResourceLocation("meridian_combat", "known");
    public static final ResourceLocation SLOT = new ResourceLocation("meridian_combat", "slot");

    private CombatChannels() {
        // 通道标识不随连接变化。
    }
}
