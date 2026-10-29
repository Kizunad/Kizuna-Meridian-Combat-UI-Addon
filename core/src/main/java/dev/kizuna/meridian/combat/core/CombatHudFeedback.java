package dev.kizuna.meridian.combat.core;

import java.util.Arrays;

/** 按下后两个绘制帧依次点亮，第三帧恢复槽位本身的状态。 */
public final class CombatHudFeedback {
    private static final int PRESSED_FRAMES = 2;
    private final int[] remaining = new int[CombatIds.SLOT_COUNT];

    public enum Frame { NORMAL, PRESSED, RELEASED }

    public void press(int number) {
        // 输入已经被架势消费时才记录反馈，空槽与拒绝也能回应按键。
        int index = CombatIds.slotIndex(number);
        remaining[index] = PRESSED_FRAMES;
    }

    public Frame frame(int number) {
        // 同一帧内查询不改变状态，框和图标使用同一阶段。
        int index = CombatIds.slotIndex(number);
        int count = remaining[index];
        if (count == PRESSED_FRAMES) {
            return Frame.PRESSED;
        }
        if (count == 1) {
            return Frame.RELEASED;
        }
        return Frame.NORMAL;
    }

    public void advanceFrame() {
        // 渲染结束统一推进，避免槽位绘制顺序影响持续帧数。
        for (int index = 0; index < remaining.length; index++) {
            remaining[index] = Math.max(0, remaining[index] - 1);
        }
    }

    public void clear() {
        // 切服和退出架势时不保留尚未显示的按下帧。
        Arrays.fill(remaining, 0);
    }
}
