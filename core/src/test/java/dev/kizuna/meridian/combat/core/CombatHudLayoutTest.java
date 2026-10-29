package dev.kizuna.meridian.combat.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class CombatHudLayoutTest {
    private static final int FULL_HD_WIDTH = 1920;
    private static final int HALF = 2;
    private static final int VANILLA_WIDTH = 182;
    private static final int OVERHANG = 10;
    private static final int MIN_GUI_SCALE = 1;
    private static final int MAX_GUI_SCALE = 4;
    private static final double HALF_COOLDOWN = 0.5;
    private static final int HALF_ICON = 8;

    @Test
    public void integerScalesKeepVanillaCenterAndFixedOverhang() {
        // 偶数与奇数窗口都和原版整数中心对齐，缩放不改变逻辑像素间距。
        for (int scale = MIN_GUI_SCALE; scale <= MAX_GUI_SCALE; scale++) {
            int width = FULL_HD_WIDTH / scale;
            assertCentered(width);
            assertCentered(width + 1);
        }
    }

    @Test
    public void labelsUseRemappedHotbarAndIndependentTenthKey() {
        // 改键、鼠标键和未绑定显示名都原样使用，不从槽号推断标签。
        var keys = List.of("Q", "Mouse 4", "未绑定", "4", "5", "6", "7", "8", "9");
        assertEquals("Q", CombatHudLayout.keyLabel(1, keys, "Z"));
        assertEquals("Mouse 4", CombatHudLayout.keyLabel(2, keys, "Z"));
        assertEquals("未绑定", CombatHudLayout.keyLabel(3, keys, "Z"));
        assertEquals("Z", CombatHudLayout.keyLabel(CombatIds.SLOT_COUNT, keys, "Z"));
        assertThrows(IllegalArgumentException.class, () -> CombatHudLayout.keyLabel(0, keys, "Z"));
    }

    @Test
    public void cooldownShrinksDownwardToFixedBottom() {
        // 满冷却覆盖全图，半冷却只盖下半部，到期后覆盖高度为零。
        assertEquals(CombatHudLayout.ICON_INSET, CombatHudLayout.cooldownTop(1));
        assertEquals(CombatHudLayout.ICON_INSET + HALF_ICON, CombatHudLayout.cooldownTop(HALF_COOLDOWN));
        assertEquals(CombatHudLayout.ICON_INSET + CombatHudLayout.ICON_SIZE, CombatHudLayout.cooldownTop(0));
    }

    private static void assertCentered(int width) {
        // 十槽比原版九槽左右各宽十像素，避免对齐时引入半像素。
        int vanillaLeft = width / HALF - VANILLA_WIDTH / HALF;
        assertEquals(vanillaLeft - OVERHANG, CombatHudLayout.left(width));
        assertEquals(VANILLA_WIDTH + OVERHANG * HALF, CombatHudLayout.WIDTH);
    }
}
