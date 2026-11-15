package dev.kizuna.meridian.combat.fabric.client;

import dev.kizuna.meridian.combat.core.CombatCast;
import dev.kizuna.meridian.combat.core.CombatCastBar;
import dev.kizuna.meridian.combat.core.CombatHud;
import dev.kizuna.meridian.combat.core.CombatHudFeedback;
import dev.kizuna.meridian.combat.core.CombatHudLayout;
import dev.kizuna.meridian.combat.core.CombatIds;
import dev.kizuna.meridian.combat.core.CombatProfiles;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.BlockKind;
import dev.kizuna.meridian.technique.fabric.client.TechniqueStaggerClient;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static dev.kizuna.meridian.combat.core.CombatHudLayout.ICON_INSET;
import static dev.kizuna.meridian.combat.core.CombatHudLayout.ICON_SIZE;
import static dev.kizuna.meridian.combat.core.CombatHudLayout.SLOT_PITCH;
import static dev.kizuna.meridian.combat.core.CombatHudLayout.SLOT_SIZE;
import static dev.kizuna.meridian.combat.core.CombatHudLayout.STAGGER_Y;

/** 只绘制客户端投影，不参与施放校验；槽框可由资源包整套替换。 */
final class CombatHudRenderer {
    private static final Identifier FRAMES =
            Identifier.fromNamespaceAndPath("meridian_combat", "textures/gui/combat/slot_frames.png");
    private static final Identifier FAILURE =
            Identifier.fromNamespaceAndPath("meridian_combat", "textures/gui/combat/failure_badge.png");
    private static final Identifier SHORT_OF_QI =
            Identifier.fromNamespaceAndPath("meridian_combat", "textures/gui/combat/qi_badge.png");
    private static final Identifier SHORT_OF_STRENGTH =
            Identifier.fromNamespaceAndPath("meridian_combat", "textures/gui/combat/exertion_badge.png");
    private static final Identifier WRONG_ITEM =
            Identifier.fromNamespaceAndPath("meridian_combat", "textures/gui/combat/item_badge.png");
    private static final Identifier BUSY =
            Identifier.fromNamespaceAndPath("meridian_combat", "textures/gui/combat/busy_badge.png");
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
    /** 施放条：槽条正上方三像素高的一条，起手、出招、收势、收招段四种灰度，中性配色。 */
    private static final int CAST_BAR_Y = -4;
    private static final int CAST_BAR_HEIGHT = 3;
    private static final int CAST_BAR_BACK = 0x99000000;
    private static final int WARMUP_COLOR = 0xff8c8c8c;
    private static final int ACTIVE_COLOR = 0xfff0f0f0;
    private static final int RECOVERY_COLOR = 0xff5c5c5c;
    private static final int WIND_DOWN_COLOR = 0xff343434;
    /** 承接窗口和「现在能接」的槽框用同一个暖色，记着的按键用白色。 */
    private static final int FOLLOW_UP_COLOR = 0xffe8d27a;
    private static final int BUFFERED_COLOR = 0xffffffff;
    private static final int CURSOR_COLOR = 0xffffffff;
    private static final long NANOS_PER_MILLI = 1_000_000;
    private static final long MILLIS_PER_SECOND = 1000;
    private final CombatHud hud;
    private final CombatProfiles profiles;
    private final KeyMapping tenthKey;
    private final CombatHudFeedback feedback = new CombatHudFeedback();
    private final CombatHudIcons icons = new CombatHudIcons();

    CombatHudRenderer(CombatHud hud, CombatProfiles profiles, KeyMapping tenthKey) {
        // 只保存键绑定对象，显示名每帧读取以响应设置和语言变更。
        this.hud = hud;
        this.profiles = profiles;
        this.tenthKey = tenthKey;
        ResourceLoader.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(Identifier.fromNamespaceAndPath("meridian_combat", "hud_icons"), icons);
    }

    void press(int number) {
        // 输入被消费时才反馈，是否可施放仍由原有选择和服务端校验决定。
        feedback.press(number);
    }

    void clearFeedback() {
        // 退出架势或断线后不展示旧的按下帧。
        feedback.clear();
    }

    void render(GuiGraphicsExtractor context) {
        // 没有施放状态可读的调用方（只画槽）照「没在施放」画。
        render(context, CombatCast.NONE);
    }

    void render(GuiGraphicsExtractor context, CombatCast cast) {
        // 生存模式避开血条与护甲，多排生命值时跟随原版行间距上移。
        var client = Minecraft.getInstance();
        if (client.player == null || client.gui.hud.isHidden()) {
            return;
        }
        int left = CombatHudLayout.left(context.guiWidth());
        int top = context.guiHeight() - bottomOffset(client);
        List<String> keys = Arrays.stream(client.options.keyHotbarSlots)
                .map(key -> key.getTranslatedKeyMessage().getString()).toList();
        String lastKey = tenthKey.getTranslatedKeyMessage().getString();
        var matrices = context.pose();
        matrices.pushMatrix();
        matrices.translate(left, top);
        // 冷却按世界 tick 对照服务端给的结束时刻，暂停和卡顿时跟着世界一起停。
        List<CombatHud.Slot> slots = hud.slots(profiles.slots(), client.level.getGameTime(), cast);
        for (var slot : slots) {
            int x = (slot.number() - 1) * SLOT_PITCH;
            drawSlot(context, client, slot, x);
            String label = CombatHudLayout.keyLabel(slot.number(), keys, lastKey);
            String fittedLabel = client.font.plainSubstrByWidth(label, SLOT_PITCH - HALF);
            context.centeredText(client.font, fittedLabel,
                    x + SLOT_SIZE / HALF, CombatHudLayout.LABEL_Y, KEY_COLOR);
        }
        // 相邻两槽共用一条边，后画的槽框会盖住前一槽的右边：提示框等十槽都画完再描。
        slots.forEach(slot -> drawMark(context, slot));
        drawCastBar(context, cast);
        drawStaggers(context, client);
        matrices.popMatrix();
        feedback.advanceFrame();
    }

    private static void drawCastBar(GuiGraphicsExtractor context, CombatCast cast) {
        // 一式占着人、或者它的承接窗口还开着的时候才画；走过的时间由一根竖线指着，打击时刻各一根短刻度。
        if (cast.elapsedMillis() >= cast.spanMillis()) {
            return;
        }
        CombatCastBar.Layout bar = CombatCastBar.layout(cast, CombatHudLayout.WIDTH);
        int top = CAST_BAR_Y;
        int bottom = CAST_BAR_Y + CAST_BAR_HEIGHT;
        context.fill(0, top, CombatHudLayout.WIDTH, bottom, CAST_BAR_BACK);
        context.fill(0, top, bar.warmupEnd(), bottom, WARMUP_COLOR);
        context.fill(bar.warmupEnd(), top, bar.activeEnd(), bottom, ACTIVE_COLOR);
        context.fill(bar.activeEnd(), top, Math.max(bar.activeEnd(), bar.free()), bottom, RECOVERY_COLOR);
        context.fill(Math.max(bar.activeEnd(), bar.free()), top, bar.recoveryEnd(), bottom, WIND_DOWN_COLOR);
        for (CombatCastBar.Span window : bar.windows()) {
            // 承接窗口画在条的最下面一行。
            context.fill(window.from(), bottom - 1, Math.max(window.from() + 1, window.to()), bottom, FOLLOW_UP_COLOR);
        }
        for (int hit : bar.hits()) {
            context.fill(hit, top - 1, hit + 1, bottom, CURSOR_COLOR);
        }
        context.fill(bar.cursor(), top - 1, bar.cursor() + 1, bottom + 1, CURSOR_COLOR);
    }

    private static void drawStaggers(GuiGraphicsExtractor context, Minecraft client) {
        // 槽位栏上方一行静态文字：自身僵直靠左、准星目标僵直靠右，只读服务端同步的显示副本。
        long own = TechniqueStaggerClient.remainingMillis(client.player);
        if (own > 0) {
            Component text = Component.translatable("hud.meridian_combat.stagger.self", seconds(own));
            context.text(client.font, text, 0, STAGGER_Y, KEY_COLOR);
        }
        if (client.crosshairPickEntity == null) {
            return;
        }
        long target = TechniqueStaggerClient.remainingMillis(client.crosshairPickEntity);
        if (target > 0) {
            Component text = Component.translatable("hud.meridian_combat.stagger.target", seconds(target));
            int x = CombatHudLayout.WIDTH - client.font.width(text);
            context.text(client.font, text, x, STAGGER_Y, KEY_COLOR);
        }
    }

    private static String seconds(long millis) {
        // 僵直不足一秒，保留一位小数才看得出长短。
        return String.format(Locale.ROOT, "%.1f", (double) millis / MILLIS_PER_SECOND);
    }

    static int messageLift(Minecraft client) {
        // 槽条随生命值行数上移时，动作栏消息跟着抬到它上方。
        return CombatHudLayout.messageLift(bottomOffset(client));
    }

    private static int bottomOffset(Minecraft client) {
        // 与原版每行十颗心及多行压缩规则保持一致，创造模式靠近快捷栏。
        if (!client.gameMode.canHurtPlayer()) {
            return CREATIVE_OFFSET;
        }
        int rows = (int) Math.ceil((client.player.getMaxHealth() + client.player.getAbsorptionAmount())
                / HEALTH_PER_ROW);
        int spacing = Math.max(MIN_HEALTH_ROW_HEIGHT, HEALTH_ROW_HEIGHT - (rows - HALF));
        return SURVIVAL_OFFSET + Math.max(0, rows - 1) * spacing;
    }

    private void drawSlot(GuiGraphicsExtractor context, Minecraft client, CombatHud.Slot slot, int x) {
        // 相邻槽只绘制一次共享边，避免半透明外框重复混合；GUI 贴图管线自带半透明混合。
        var frame = feedback.frame(slot.number());
        int frameIndex = frameIndex(slot.state(), frame);
        int width = SLOT_PITCH;
        if (slot.number() == CombatIds.SLOT_COUNT) {
            width = SLOT_SIZE;
        }
        context.blit(RenderPipelines.GUI_TEXTURED, FRAMES, x, 0, frameIndex * SLOT_SIZE, 0,
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
        }
        if (slot.block() != BlockKind.BLOCK_NONE) {
            // 右下角的小图标说明为什么放不出来；冷却里的也叠，冷却走完还放不出来时玩家心里有数。
            context.blit(RenderPipelines.GUI_TEXTURED, badge(slot.block()), x + SLOT_SIZE - BADGE_SIZE - 1,
                    SLOT_SIZE - BADGE_SIZE - 1, 0, 0, BADGE_SIZE, BADGE_SIZE, BADGE_SIZE, BADGE_SIZE);
        }
    }

    private static Identifier badge(BlockKind block) {
        return switch (block) {
            case BLOCK_QI -> SHORT_OF_QI;
            case BLOCK_EXERTION -> SHORT_OF_STRENGTH;
            case BLOCK_ITEM -> WRONG_ITEM;
            case BLOCK_BUSY -> BUSY;
            default -> FAILURE;
        };
    }

    private static void drawMark(GuiGraphicsExtractor context, CombatHud.Slot slot) {
        // 沿槽框描一圈：暖色是这一式现在正好能接上，白色是服务端替自己记着这一式的按键。
        if (slot.mark() == CombatHud.Mark.NONE) {
            return;
        }
        int color = slot.mark() == CombatHud.Mark.BUFFERED ? BUFFERED_COLOR : FOLLOW_UP_COLOR;
        outline(context, (slot.number() - 1) * SLOT_PITCH, 0, SLOT_SIZE, color);
    }

    private static void outline(GuiGraphicsExtractor context, int x, int y, int size, int color) {
        // 一像素宽的方框。
        context.fill(x, y, x + size, y + 1, color);
        context.fill(x, y + size - 1, x + size, y + size, color);
        context.fill(x, y + 1, x + 1, y + size - 1, color);
        context.fill(x + size - 1, y + 1, x + size, y + size - 1, color);
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

    private void drawIcon(GuiGraphicsExtractor context, Minecraft client, CombatHud.Slot slot, int x, int y) {
        // 冷却和失败共享灰阶缓存；资源包缺图时保留短名称，空槽不进入本方法。
        if (!slot.texture().isEmpty()) {
            var found = icons.source(slot.texture());
            if (found.isPresent()) {
                Identifier source = found.get();
                Identifier texture;
                if (slot.state() != CombatHud.State.AVAILABLE) {
                    texture = icons.grayscale(source).orElse(source);
                } else {
                    texture = source;
                }
                context.blit(RenderPipelines.GUI_TEXTURED, texture, x + ICON_INSET, y, 0, 0,
                        ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
                return;
            }
        }
        String label = client.font.plainSubstrByWidth(slot.name(), ICON_SIZE);
        context.centeredText(client.font, label, x + SLOT_SIZE / HALF, TIMER_Y, TEXT_COLOR);
    }

    private static void drawTimer(GuiGraphicsExtractor context, Minecraft client, String text, int slotX) {
        // 四向一像素黑描边让倒计时在亮暗图标上都可读。
        int x = slotX + (SLOT_SIZE - client.font.width(text)) / HALF;
        context.text(client.font, text, x - 1, TIMER_Y, OUTLINE_COLOR, false /* shadow */);
        context.text(client.font, text, x + 1, TIMER_Y, OUTLINE_COLOR, false /* shadow */);
        context.text(client.font, text, x, TIMER_Y - 1, OUTLINE_COLOR, false /* shadow */);
        context.text(client.font, text, x, TIMER_Y + 1, OUTLINE_COLOR, false /* shadow */);
        context.text(client.font, text, x, TIMER_Y, TEXT_COLOR, false /* shadow */);
    }

    static long nowMillis() {
        // 单调时钟避免修改系统时间导致冷却表现跳变。
        return System.nanoTime() / NANOS_PER_MILLI;
    }
}
