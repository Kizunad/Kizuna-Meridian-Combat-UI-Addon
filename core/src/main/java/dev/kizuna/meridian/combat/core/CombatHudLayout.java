package dev.kizuna.meridian.combat.core;

import java.util.List;

/** 与原版快捷栏共用整数中心线；纹理和文字均不做分数缩放。 */
public final class CombatHudLayout {
    public static final int SLOT_SIZE = 22;
    public static final int SLOT_PITCH = 20;
    public static final int ICON_SIZE = 16;
    public static final int ICON_INSET = 3;
    public static final int LABEL_Y = 24;
    public static final int WIDTH = (CombatIds.SLOT_COUNT - 1) * SLOT_PITCH + SLOT_SIZE;
    /** 僵直提示写在槽条上边缘之上的这一行；它和槽条之间留给施放条。 */
    public static final int STAGGER_Y = -15;
    /** 原版动作栏消息的中线离屏幕底边的距离。 */
    public static final int VANILLA_MESSAGE_OFFSET = 68;
    /** 动作栏一行连底衬在中线之下占的高度：文字从中线上 4 像素起笔、行高 9，底衬再多 2 像素。 */
    private static final int MESSAGE_BELOW_CENTER = 7;
    private static final int MESSAGE_GAP = 1;
    private static final int HALF = 2;

    private CombatHudLayout() {
        // 纯布局工具不保存窗口或键位状态。
    }

    public static int left(int screenWidth) {
        // 与原版 screenWidth / 2 的取整一致，奇数宽度也不会半像素偏移。
        return screenWidth / HALF - WIDTH / HALF;
    }

    public static String keyLabel(int number, List<String> hotbarKeys, String tenthKey) {
        // 前九槽复用原版键位，第十槽使用独立绑定，调用方每帧读取当前显示名。
        int index = CombatIds.slotIndex(number);
        if (hotbarKeys.size() != CombatIds.SLOT_COUNT - 1) {
            throw new IllegalArgumentException("原版快捷栏必须有九个键位");
        }
        if (number == CombatIds.SLOT_COUNT) {
            return tenthKey;
        }
        return hotbarKeys.get(index);
    }

    public static int messageLift(int barTopOffset) {
        // 架势中动作栏消息整行移到槽条和僵直提示之上，留一像素空隙；原版位置本就在上方时不动。
        int centerOffset = barTopOffset - STAGGER_Y + MESSAGE_BELOW_CENTER + MESSAGE_GAP;
        return Math.max(0, centerOffset - VANILLA_MESSAGE_OFFSET);
    }

    public static int cooldownTop(double fraction) {
        // 遮罩底边固定，剩余量减少时上边缘向下收起。
        return ICON_INSET + ICON_SIZE - (int) Math.ceil(ICON_SIZE * fraction);
    }
}
