package dev.kizuna.meridian.combat.core;

/** 逐个物理边沿生成连接内按压序号，同一 tick 的按下和松开也不会合并。 */
public final class GuardInput {
    public enum Gate { OPEN, CLOSED }

    private int sequence;
    private int active;
    private boolean down;

    public int press(Gate gate) {
        // 架势外的按下同样记住物理状态，进入架势不会补发。
        if (down) {
            return 0;
        }
        down = true;
        if (gate == Gate.CLOSED || sequence == CombatProtocol.MAX_PRESS_ID) {
            return 0;
        }
        active = ++sequence;
        return active;
    }

    public int release() {
        // 真正松手后才允许下一次物理按下。
        down = false;
        return cancel();
    }

    public int cancel() {
        // 开屏、失焦或退出架势只结束已发出的按压，不把长按变成下一次按下。
        int released = active;
        active = 0;
        return released;
    }

    public void disconnect() {
        // 按压身份只在本连接有效，新连接从空状态开始。
        sequence = 0;
        active = 0;
        down = false;
    }
}
