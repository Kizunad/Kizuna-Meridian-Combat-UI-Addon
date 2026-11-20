package dev.kizuna.meridian.combat.fabric.client;

import dev.kizuna.inventoryui.client.status.StatusIcons;
import dev.kizuna.inventoryui.status.StatusArrangement;
import dev.kizuna.inventoryui.status.StatusGroups;
import dev.kizuna.meridian.combat.core.CombatInput;
import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** 从生产战斗槽发两次物理按键，核对原版效果同步、显示和结束。 */
final class CombatPracticeClientTest {
    private enum Stage { LEARN, ENTER, ACTIVE, CAPTURED, ENDED, RESTARTED, REPLAYED, EXIT, DONE }
    private static final String ID = "meridian_combat_client_test:practice";
    private static final int SLOT = 2;
    private static final int TIMEOUT = 240;
    private static final int PRESENTED_ICON_FRAMES = 4;
    private static final int FIRST_RELEASE = 2;
    private static final int DELAYED_RELEASE = 4;
    private static final int CURRENT_RELEASE = 5;
    private final Minecraft client;
    private final CombatInput stance;
    private final Path screen;
    private final CompletableFuture<?> learned;
    private final Method input;
    private final Holder<MobEffect> effect;
    private final CompletableFuture<Void> captured = new CompletableFuture<>();
    private Stage stage = Stage.LEARN;
    private int started = -1;
    private int iconFrames;
    private String firstCast;
    private CompletableFuture<Void> replayed;

    CombatPracticeClientTest(Minecraft client, Path directory, CombatInput stance) throws ReflectiveOperationException {
        // 客户端和服务器分别经真实注册、学习和按键入口。
        this.client = client;
        this.stance = stance;
        screen = directory.resolve("practice-active.png");
        var uuid = client.player.getUUID();
        learned = client.getSingleplayerServer().submit(() -> TechniqueFabric.service().learn(uuid, ID));
        input = KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, KeyEvent.class);
        input.setAccessible(true /* accessible */);
        effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(ID)).orElseThrow();
    }

    boolean tick(int tick) throws ReflectiveOperationException {
        // 每个状态只发送一次按键，等服务端回传的新身份再请求关闭。
        if (stage == Stage.DONE) {
            return true;
        }
        if (started < 0) {
            started = tick;
        }
        if (tick - started > TIMEOUT) {
            throw new IllegalStateException("运功客户端超时 " + stage);
        }
        var known = CombatSlotAccess.snapshot().known().getTechniquesList().stream()
                .filter(entry -> entry.getId().equals(ID)).findFirst();
        if (known.isEmpty()) {
            if (learned.isDone()) {
                learned.join();
            }
            return false;
        }
        advance(known.orElseThrow());
        return false;
    }

    private void advance(KnownTechnique known) throws ReflectiveOperationException {
        // 画面确认原版列表和功法类的图标后才发第二次按键。
        switch (stage) {
            case LEARN -> {
                CombatSlotAccess.set(SLOT, ID);
                tap(GLFW.GLFW_KEY_R);
                stage = Stage.ENTER;
            }
            case ENTER -> {
                if (stance.state() == CombatInput.State.ACTIVE) {
                    tap(GLFW.GLFW_KEY_2);
                    stage = Stage.ACTIVE;
                }
            }
            case ACTIVE -> captureActive(known);
            case CAPTURED -> {
                if (captured.isDone()) {
                    captured.join();
                    tap(GLFW.GLFW_KEY_2);
                    stage = Stage.ENDED;
                }
            }
            case ENDED -> {
                if (!client.player.hasEffect(effect) && known.getActivePracticeId().isEmpty() && !iconVisible()) {
                    tap(GLFW.GLFW_KEY_2);
                    stage = Stage.RESTARTED;
                }
            }
            case RESTARTED -> checkRestart(known.getActivePracticeId());
            case REPLAYED -> leaveAfterReplay(known);
            case EXIT -> {
                if (stance.state() == CombatInput.State.OUTSIDE) {
                    stage = Stage.DONE;
                }
            }
            default -> { }
        }
    }

    private void checkRestart(String current) {
        // 客户端看到第二次运功后才重放第一次收法。
        if (!current.isEmpty() && !current.equals(firstCast) && client.player.hasEffect(effect)) {
            var server = client.getSingleplayerServer();
            var uuid = client.player.getUUID();
            replayed = server.submit(() -> replayReleases(server.getPlayerList().getPlayer(uuid), current));
            stage = Stage.REPLAYED;
        }
    }

    private void leaveAfterReplay(KnownTechnique known) throws ReflectiveOperationException {
        // 服务端断言与客户端同步必须都完成，才能退出架势。
        if (!replayed.isDone()) {
            return;
        }
        replayed.join();
        if (!client.player.hasEffect(effect) && known.getActivePracticeId().isEmpty()) {
            tap(GLFW.GLFW_KEY_R);
            CombatSlotAccess.clear(SLOT);
            stage = Stage.EXIT;
        }
    }

    private void captureActive(KnownTechnique known) {
        // 新手弹窗和图标同占右上角，等清空弹窗后的画面真正显示再回读。
        client.gui.toastManager().clear();
        var instance = client.player.getEffect(effect);
        if (instance != null && instance.showIcon() && instance.isInfiniteDuration()
                && !known.getActivePracticeId().isEmpty() && iconVisible()) {
            iconFrames++;
            if (iconFrames >= PRESENTED_ICON_FRAMES) {
                firstCast = known.getActivePracticeId();
                capture();
                stage = Stage.CAPTURED;
            }
        }
    }

    private void replayReleases(ServerPlayer player, String current) {
        // 同一连接分别重送旧包、送递增序号但引用旧身份的包，最后只用当前身份收法。
        release(player, FIRST_RELEASE, firstCast);
        release(player, DELAYED_RELEASE, firstCast);
        if (!player.hasEffect(effect)) {
            throw new IllegalStateException("迟到的收法包结束了后来发动的运功");
        }
        release(player, CURRENT_RELEASE, current);
        if (player.hasEffect(effect)) {
            throw new IllegalStateException("当前运功的收法包未生效");
        }
    }

    private static void release(ServerPlayer player, int sequence, String castId) {
        // 从服务端游戏连接重放网络载荷，与真实客户端按键走同一个正式接收器。
        var press = TechniquePress.newBuilder()
                .setTechniqueId(ID)
                .setPracticePressId(sequence)
                .setEndPracticeId(castId)
                .build();
        var payload = CombatChannels.PRESS.payload(press.toByteArray());
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(payload));
    }

    private boolean iconVisible() {
        // 此场景只有少量状态，功法效果应以独立格显示，并归入目录指定的类。
        return StatusIcons.lastLayout().slots().stream()
                .filter(StatusArrangement.Single.class::isInstance)
                .map(StatusArrangement.Single.class::cast)
                .map(StatusArrangement.Single::effect)
                .anyMatch(displayed -> ID.equals(displayed.id())
                        && ID.equals(StatusGroups.current().groupOf(displayed).id()));
    }

    private void tap(int key) throws ReflectiveOperationException {
        // 经过原版键盘回调，战斗槽按住与松开在同一帧内发生。
        var event = new KeyEvent(key, 0, 0);
        long window = client.getWindow().handle();
        input.invoke(client.keyboardHandler, window, GLFW.GLFW_PRESS, event);
        input.invoke(client.keyboardHandler, window, GLFW.GLFW_RELEASE, event);
    }

    private void capture() {
        // 异步回读完成后才继续收法。
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(screen);
                captured.complete(null);
            } catch (IOException failure) {
                captured.completeExceptionally(new UncheckedIOException(failure));
            }
        });
    }
}
