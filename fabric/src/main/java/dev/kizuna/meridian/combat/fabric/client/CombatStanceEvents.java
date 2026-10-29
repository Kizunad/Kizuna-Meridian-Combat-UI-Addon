package dev.kizuna.meridian.combat.fabric.client;

import dev.kizuna.meridian.combat.core.CombatInput;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** 仅客户端的架势变化钩子；动画接入可以订阅，不向服务器同步架势。 */
public final class CombatStanceEvents {
    public static final Event<Changed> CHANGED = EventFactory.createArrayBacked(Changed.class,
            listeners -> state -> {
                for (Changed listener : listeners) {
                    listener.onChanged(state);
                }
            });

    private CombatStanceEvents() {
        // 首版只发布输入模式，不播放动作。
    }

    @FunctionalInterface
    public interface Changed {
        void onChanged(CombatInput.State state);
    }
}
