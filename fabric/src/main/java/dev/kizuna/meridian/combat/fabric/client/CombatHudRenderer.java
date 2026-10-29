package dev.kizuna.meridian.combat.fabric.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.kizuna.meridian.combat.core.CombatHud;
import dev.kizuna.meridian.combat.core.CombatHudFeedback;
import dev.kizuna.meridian.combat.core.CombatHudLayout;
import dev.kizuna.meridian.combat.core.CombatIds;
import dev.kizuna.meridian.combat.core.CombatProfiles;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.util.Arrays;
import java.util.List;

import static dev.kizuna.meridian.combat.core.CombatHudLayout.ICON_INSET;
import static dev.kizuna.meridian.combat.core.CombatHudLayout.ICON_SIZE;
import static dev.kizuna.meridian.combat.core.CombatHudLayout.SLOT_PITCH;
import static dev.kizuna.meridian.combat.core.CombatHudLayout.SLOT_SIZE;

/** 只绘制客户端投影，不参与施放校验；槽框可由资源包整套替换。 */
final class CombatHudRenderer {
    private static final Identifier FRAMES = new Identifier("meridian_combat", "textures/gui/combat/slot_frames.png");
    private static final Identifier FAILURE =
            new Identifier("meridian_combat", "textures/gui/combat/failure_badge.png");
    private static final int FRAME_COUNT = 5;
    private static final int EMPTY_FRAME = 1;
    private static final int FAILED_FRAME = 2;
    private static final int PRESSED_FRAME = 3;
    private static final int RELEASED_FRAME = 4;
    private static final int BADGE_SIZE = 7;
    private static final int SURVIVAL_OFFSET = 86;
    private static final int CREATIVE_OFFSET = 61;
    private static final int HEALTH_PER_ROW = 20;
    private static final int HEALTH_ROW_HEIGHT = 10;
    private static final int MIN_HEALTH_ROW_HEIGHT = 3;
    private static final int HALF = 2;
    private static final int TIMER_Y = 7;
    private static final int TEXT_COLOR = 0xffffffff;
    private static final int KEY_COLOR = 0xffaaaaaa;
    private static final int OUTLINE_COLOR = 0xff000000;
    private static final int COOLDOWN_COLOR = 0x99000000;
    private static final long NANOS_PER_MILLI = 1_000_000;
    private static final long MILLIS_PER_SECOND = 1000;
    private final CombatHud hud;
    private final CombatProfiles profiles;
    private final KeyBinding tenthKey;
    private final CombatHudFeedback feedback = new CombatHudFeedback();
    private final CombatHudIcons icons = new CombatHudIcons();

    CombatHudRenderer(CombatHud hud, CombatProfiles profiles, KeyBinding tenthKey) {
        // 只保存键绑定对象，显示名每帧读取以响应设置和语言变更。
        this.hud = hud;
        this.profiles = profiles;
        this.tenthKey = tenthKey;
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(icons);
    }

    void press(int number) {
        // 输入被消费时才反馈，是否可施放仍由原有选择和服务端校验决定。
        feedback.press(number);
    }

    void clearFeedback() {
        // 退出架势或断线后不展示旧的按下帧。
        feedback.clear();
    }

    void render(DrawContext context) {
        // 生存模式避开血条与护甲，多排生命值时跟随原版行间距上移。
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) {
            return;
        }
        int left = CombatHudLayout.left(context.getScaledWindowWidth());
        int top = context.getScaledWindowHeight() - bottomOffset(client);
        List<String> keys = Arrays.stream(client.options.hotbarKeys)
                .map(key -> key.getBoundKeyLocalizedText().getString()).toList();
        String lastKey = tenthKey.getBoundKeyLocalizedText().getString();
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(left, top, 0);
        for (var slot : hud.slots(profiles.slots(), nowMillis())) {
            int x = (slot.number() - 1) * SLOT_PITCH;
            drawSlot(context, client, slot, x);
            String label = CombatHudLayout.keyLabel(slot.number(), keys, lastKey);
            String fittedLabel = client.textRenderer.trimToWidth(label, SLOT_PITCH - HALF);
            context.drawCenteredTextWithShadow(client.textRenderer, fittedLabel,
                    x + SLOT_SIZE / HALF, CombatHudLayout.LABEL_Y, KEY_COLOR);
        }
        matrices.pop();
        context.draw();
        RenderSystem.disableBlend();
        feedback.advanceFrame();
    }

    private static int bottomOffset(MinecraftClient client) {
        // 与原版每行十颗心及多行压缩规则保持一致，创造模式靠近快捷栏。
        if (!client.interactionManager.hasStatusBars()) {
            return CREATIVE_OFFSET;
        }
        int rows = (int) Math.ceil((client.player.getMaxHealth() + client.player.getAbsorptionAmount())
                / HEALTH_PER_ROW);
        int spacing = Math.max(MIN_HEALTH_ROW_HEIGHT, HEALTH_ROW_HEIGHT - (rows - HALF));
        return SURVIVAL_OFFSET + Math.max(0, rows - 1) * spacing;
    }

    private void drawSlot(DrawContext context, MinecraftClient client, CombatHud.Slot slot, int x) {
        // 相邻槽只绘制一次共享边，避免半透明外框重复混合。
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        var frame = feedback.frame(slot.number());
        int frameIndex = frameIndex(slot.state(), frame);
        int width = SLOT_PITCH;
        if (slot.number() == CombatIds.SLOT_COUNT) {
            width = SLOT_SIZE;
        }
        context.drawTexture(FRAMES, x, 0, frameIndex * SLOT_SIZE, 0,
                width, SLOT_SIZE, FRAME_COUNT * SLOT_SIZE, SLOT_SIZE);
        if (slot.state() == CombatHud.State.EMPTY) {
            return;
        }
        int insetY = ICON_INSET;
        if (frame == CombatHudFeedback.Frame.PRESSED) {
            insetY++;
        }
        drawIcon(context, client, slot, x, insetY);
        if (slot.state() == CombatHud.State.COOLDOWN) {
            int top = CombatHudLayout.cooldownTop(slot.cooldownFraction());
            context.fill(x + ICON_INSET, top, x + ICON_INSET + ICON_SIZE,
                    ICON_INSET + ICON_SIZE, COOLDOWN_COLOR);
            long seconds = (slot.remainingMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND;
            drawTimer(context, client, Long.toString(seconds), x);
        } else if (slot.state() == CombatHud.State.UNAVAILABLE) {
            context.drawTexture(FAILURE, x + SLOT_SIZE - BADGE_SIZE - 1, 1, 0, 0,
                    BADGE_SIZE, BADGE_SIZE, BADGE_SIZE, BADGE_SIZE);
        }
    }

    private static int frameIndex(CombatHud.State state, CombatHudFeedback.Frame feedback) {
        // 按下亮框短暂覆盖基础边框，失败角标和冷却遮罩仍保留。
        if (feedback == CombatHudFeedback.Frame.PRESSED) {
            return PRESSED_FRAME;
        }
        if (feedback == CombatHudFeedback.Frame.RELEASED) {
            return RELEASED_FRAME;
        }
        if (state == CombatHud.State.EMPTY) {
            return EMPTY_FRAME;
        }
        if (state == CombatHud.State.UNAVAILABLE) {
            return FAILED_FRAME;
        }
        return 0;
    }

    private void drawIcon(DrawContext context, MinecraftClient client, CombatHud.Slot slot, int x, int y) {
        // 冷却和失败共享灰阶缓存；资源包缺图时保留短名称，空槽不进入本方法。
        if (!slot.texture().isEmpty()) {
            var source = new Identifier(slot.texture());
            if (client.getResourceManager().getResource(source).isPresent()) {
                Identifier texture;
                if (slot.state() != CombatHud.State.AVAILABLE) {
                    texture = icons.grayscale(source).orElse(source);
                } else {
                    texture = source;
                }
                context.drawTexture(texture, x + ICON_INSET, y, 0, 0,
                        ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
                return;
            }
        }
        String label = client.textRenderer.trimToWidth(slot.name(), ICON_SIZE);
        context.drawCenteredTextWithShadow(client.textRenderer, label, x + SLOT_SIZE / HALF, TIMER_Y, TEXT_COLOR);
    }

    private static void drawTimer(DrawContext context, MinecraftClient client, String text, int slotX) {
        // 四向一像素黑描边让倒计时在亮暗图标上都可读。
        int x = slotX + (SLOT_SIZE - client.textRenderer.getWidth(text)) / HALF;
        context.drawText(client.textRenderer, text, x - 1, TIMER_Y, OUTLINE_COLOR, false /* shadow */);
        context.drawText(client.textRenderer, text, x + 1, TIMER_Y, OUTLINE_COLOR, false /* shadow */);
        context.drawText(client.textRenderer, text, x, TIMER_Y - 1, OUTLINE_COLOR, false /* shadow */);
        context.drawText(client.textRenderer, text, x, TIMER_Y + 1, OUTLINE_COLOR, false /* shadow */);
        context.drawText(client.textRenderer, text, x, TIMER_Y, TEXT_COLOR, false /* shadow */);
    }

    static long nowMillis() {
        // 单调时钟避免修改系统时间导致冷却表现跳变。
        return System.nanoTime() / NANOS_PER_MILLI;
    }
}
