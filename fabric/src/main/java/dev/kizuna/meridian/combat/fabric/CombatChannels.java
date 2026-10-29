package dev.kizuna.meridian.combat.fabric;

import net.minecraft.util.Identifier;

/** 两端共同使用的 Fabric 通道标识。 */
public final class CombatChannels {
    public static final Identifier PRESS = new Identifier("meridian_combat", "press");
    public static final Identifier KNOWN = new Identifier("meridian_combat", "known");

    private CombatChannels() {
        // 通道标识不随连接变化。
    }
}
