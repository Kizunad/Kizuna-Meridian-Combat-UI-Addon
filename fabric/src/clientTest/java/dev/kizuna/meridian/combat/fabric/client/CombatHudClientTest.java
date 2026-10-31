package dev.kizuna.meridian.combat.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.combat.core.CombatHud;
import dev.kizuna.meridian.combat.core.CombatIds;
import dev.kizuna.meridian.combat.core.CombatProfiles;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 以可控显示快照驱动正式渲染器，截图包含真实原版快捷栏与生存状态栏。 */
public final class CombatHudClientTest implements ClientModInitializer {
    private static final int READY_TICKS = 60;
    private static final int SETTLE_FRAMES = 12;
    private static final int INITIAL_SCALE = 2;
    private static final int FINAL_SCALE = 3;
    private static final long COOLDOWN_MILLIS = 3000;
    private static final long TOTAL_MILLIS = 5000;
    private static final Path SCREENS = Path.of("../hud-screens");
    private static final Path REPORT = Path.of("../clienttest-results.txt");
    private static final List<String> CASES = List.of("four-states", "empty", "pressed", "released", "restored");
    private int ticks;
    private int frames;
    private int scale = INITIAL_SCALE;
    private int caseIndex;
    private int captures;
    private boolean finished;
    private CombatHud hud;
    private CombatProfiles profiles;
    private CombatHudRenderer renderer;
    private CompletableFuture<Void> survival;

    @Override
    public void onInitializeClient() {
        // 等待世界与资源遮罩消失后，逐帧运行与保存 GUI 截图。
        ClientTickEvents.END_CLIENT_TICK.register(client -> ticks++);
        HudRenderCallback.EVENT.register((context, delta) -> render(context));
    }

    private void render(GuiGraphics context) {
        // 截图夹具只存在于测试源码集，不向正式客户端增加调试状态或网络接口。
        var client = Minecraft.getInstance();
        if (finished || ticks < READY_TICKS || client.player == null || client.getOverlay() != null) {
            return;
        }
        try {
            if (renderer == null) {
                initialize(client);
                return;
            }
            if (!survival.isDone()) {
                return;
            }
            survival.join();
            if (client.options.guiScale().get() != scale) {
                client.options.guiScale().set(scale);
                client.resizeDisplay();
                return;
            }
            drawCase(context, client);
        } catch (Exception failure) {
            finish(failure.toString());
        }
    }

    private void initialize(Minecraft client) throws Exception {
        // 读取正式入口已创建的对象，确保截图使用真实键位与同一个绘制实现。
        var entry = FabricLoader.getInstance().getEntrypoints("client", ClientModInitializer.class).stream()
                .filter(CombatClient.class::isInstance).findFirst().orElseThrow();
        hud = field(entry, "hud", CombatHud.class);
        profiles = field(entry, "profiles", CombatProfiles.class);
        renderer = field(entry, "renderer", CombatHudRenderer.class);
        KeyMapping tenthKey = field(entry, "slotTenKey", KeyMapping.class);
        client.options.keyHotbarSlots[0].setKey(InputConstants.getKey(GLFW.GLFW_KEY_Q, 0));
        tenthKey.setKey(InputConstants.getKey(GLFW.GLFW_KEY_Z, 0));
        KeyMapping.resetMapping();
        client.options.hideGui = false;
        client.options.pauseOnLostFocus = false;
        client.setScreen(null);
        Files.createDirectories(SCREENS);
        var server = client.getSingleplayerServer();
        var uuid = client.player.getUUID();
        survival = server.submit(() -> {
            server.getPlayerList().getPlayer(uuid).setGameMode(GameType.SURVIVAL);
        });
    }

    private void drawCase(GuiGraphics context, Minecraft client) throws Exception {
        // 前两例等待稳定画面；后三例连续保存，验证实际两个反馈帧与恢复帧。
        client.gui.getChat().clearMessages(false /* clearHistory */);
        client.getToasts().clear();
        String name = CASES.get(caseIndex);
        var now = Instant.now();
        for (int number = 1; number <= CombatIds.SLOT_COUNT; number++) {
            profiles.clear(number, now);
        }
        if (!name.equals("empty")) {
            profiles.set(1, "hud:available", now);
            profiles.set(2, "hud:cooldown", now);
            profiles.set(4, "hud:unavailable", now);
        }
        hud.update(snapshot(), CombatHudRenderer.nowMillis());
        if (name.equals("pressed")) {
            renderer.press(1);
        }
        renderer.render(context);
        if (caseIndex < 2 && ++frames < SETTLE_FRAMES) {
            return;
        }
        context.flush();
        try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
            image.writeToFile(SCREENS.resolve("gui-" + scale + "-" + name + ".png"));
        }
        captures++;
        frames = 0;
        if (++caseIndex == CASES.size()) {
            caseIndex = 0;
            if (++scale > FINAL_SCALE) {
                finish(null);
            }
        }
    }

    private static KnownTechniques snapshot() {
        // 四态共享同一张正式功法图标，便于直接比较去色、冷却与失败表现。
        var available = KnownTechnique.newBuilder()
                .setId("hud:available")
                .setName("叩门")
                .setIconId("meridian_door_breaker:knock")
                .build();
        var cooldown = available.toBuilder()
                .setId("hud:cooldown")
                .setCooldownRemainingMillis(COOLDOWN_MILLIS)
                .setCooldownTotalMillis(TOTAL_MILLIS)
                .build();
        var unavailable = available.toBuilder()
                .setId("hud:unavailable")
                .setUnavailableReason("经脉未通")
                .build();
        return KnownTechniques.newBuilder()
                .addTechniques(available)
                .addTechniques(cooldown)
                .addTechniques(unavailable)
                .build();
    }

    private static <T> T field(Object entry, String name, Class<T> type) throws Exception {
        // 反射仅用于测试夹具，不为验收在正式入口暴露可变状态。
        var field = CombatClient.class.getDeclaredField(name);
        field.setAccessible(true /* accessible */);
        return type.cast(field.get(entry));
    }

    private void finish(String failure) {
        // 独立报告让渲染断言和截图失败传递给 Gradle。
        finished = true;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\ncaptures=" + captures
                    + "\nerror=" + failure + "\n");
        } catch (Exception reportFailure) {
            throw new IllegalStateException(reportFailure);
        }
        Minecraft.getInstance().stop();
    }
}
