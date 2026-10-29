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

    public static int cooldownTop(double fraction) {
        // 遮罩底边固定，剩余量减少时上边缘向下收起。
        return ICON_INSET + ICON_SIZE - (int) Math.ceil(ICON_SIZE * fraction);
    }
}
