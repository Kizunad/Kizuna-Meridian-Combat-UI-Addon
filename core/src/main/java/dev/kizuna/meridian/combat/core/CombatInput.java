package dev.kizuna.meridian.combat.core;

/** 可独立验证的客户端架势状态，不改变服务器和玩家数值。 */
public final class CombatInput {
    public enum State { OUTSIDE, ACTIVE }
    public enum Availability { READY, DEAD, SPECTATOR, DISCONNECTED }
    public enum Screen { CLOSED, OPEN }
    public enum Support { SUPPORTED, UNSUPPORTED }

    private State state = State.OUTSIDE;

    public State state() {
        // 状态读取不消费原版按键。
        return state;
    }

    public String toggle(Availability availability, Screen screen, Support support) {
        // 架势与槽位独立，进入仅校验玩家状态、界面和服务器通道。
        if (screen == Screen.OPEN) {
            return "请先关闭界面再切换战斗架势";
        }
        if (state == State.ACTIVE) {
            state = State.OUTSIDE;
            return "已退出战斗架势";
        }
        if (availability != Availability.READY) {
            return "当前状态无法进入战斗架势";
        }
        if (support != Support.SUPPORTED) {
            return "服务器不支持战斗架势";
        }
        state = State.ACTIVE;
        return "已进入战斗架势";
    }

    public boolean consumesHotbar(Availability availability, Screen screen) {
        // 界面打开只暂停拦截，死亡或断线由 reconcile 清除架势。
        return state == State.ACTIVE && availability == Availability.READY && screen == Screen.CLOSED;
    }

    public void reconcile(Availability availability) {
        // 死亡、旁观和断线不能把架势带到下一具身体或下一连接。
        if (availability != Availability.READY) {
            state = State.OUTSIDE;
        }
    }
}
