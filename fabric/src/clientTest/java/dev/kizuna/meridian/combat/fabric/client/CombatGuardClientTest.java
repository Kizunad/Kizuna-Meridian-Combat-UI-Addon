package dev.kizuna.meridian.combat.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.kizuna.meridian.combat.core.CombatCast;
import dev.kizuna.meridian.combat.core.CombatHud;
import dev.kizuna.meridian.combat.core.CombatInput;
import dev.kizuna.meridian.combat.core.CombatProfiles;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.server.TechniqueService.CastStateChanged;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/** 真客户端经原版输入回调发包，以服务端阶段事件核对按下与松开的结果。 */
final class CombatGuardClientTest {
    private static final String GUARD = "meridian_combat_client_test:guard";
    private static final int TIMEOUT_TICKS = 200;
    private static final int QUIET_TICKS = 5;
    private static final int MOUSE_BUTTON = GLFW.GLFW_MOUSE_BUTTON_4;
    private final Minecraft client = Minecraft.getInstance();
    private final Queue<CastStateChanged> states = new ConcurrentLinkedQueue<>();
    private final Set<String> ended = ConcurrentHashMap.newKeySet();
    private final Queue<Step> steps = new ArrayDeque<>();
    private final CombatInput input;
    private final CombatHud hud;
    private final CombatProfiles profiles;
    private final KeyMapping guardKey;
    private final Method keyboard;
    private final Method mouse;
    private final Method focus;
    private final CompletableFuture<?> subscribed;
    private Step step;
    private int startTick;
    private int tick;
    private long expectedCasts;

    private record Step(String name, CheckedAction start, BooleanSupplier complete) { }

    @FunctionalInterface
    private interface CheckedAction {
        // 反射仅在测试源码集触发原版回调，失败交给 hud 测试统一报告。
        void run() throws Exception;
    }

    CombatGuardClientTest(CombatClient owner) throws Exception {
        // 获取正式状态，不增加可写的生产测试接口。
        input = field(owner, "input", CombatInput.class);
        hud = field(owner, "hud", CombatHud.class);
        profiles = field(owner, "profiles", CombatProfiles.class);
        var guard = field(owner, "guard", CombatGuardInput.class);
        guardKey = field(guard, "key", KeyMapping.class);
        keyboard = method(KeyboardHandler.class, "keyPress", long.class, int.class, KeyEvent.class);
        mouse = method(MouseHandler.class, "onButton", long.class, MouseButtonInfo.class, int.class);
        focus = method(Window.class, "onFocus", long.class, boolean.class);
        var window = client.getWindow();
        focus.invoke(window, window.handle(), true /* focused */);
        var uuid = client.player.getUUID();
        subscribed = client.getSingleplayerServer().submit(() -> {
            var service = TechniqueFabric.service();
            service.addCastStateListener(event -> {
                if (event.entityUuid().equals(uuid)
                        && event.active().getRequest().getTechnique().getId().equals(GUARD)) {
                    states.add(event);
                }
            });
            service.addCastEndListener(event -> {
                if (event.entityUuid().equals(uuid)) {
                    ended.add(event.castId());
                }
            });
        });
        enqueueKeyboardCases();
        enqueueInterruptions();
    }

    private void enqueueKeyboardCases() {
        // 架势外、同 tick 短按、按键连发及鼠标改绑都经生产 Mixin。
        steps.add(new Step("outside-no-packet", () -> keyTap(GLFW.GLFW_KEY_G), this::quietWithoutNewCast));
        steps.add(new Step("enter-stance", () -> keyTap(GLFW.GLFW_KEY_R),
                () -> input.state() == CombatInput.State.ACTIVE));
        steps.add(new Step("same-tick-tap", () -> {
            expectedCasts++;
            keyTap(GLFW.GLFW_KEY_G);
        }, this::shortTapEnded));
        steps.add(new Step("keyboard-hold-and-busy", this::startKeyboardGuard, this::heldAndBusy));
        steps.add(new Step("keyboard-repeat", () -> key(GLFW.GLFW_KEY_G, GLFW.GLFW_REPEAT), this::quietWithoutNewCast));
        steps.add(new Step("keyboard-release", () -> key(GLFW.GLFW_KEY_G, GLFW.GLFW_RELEASE), this::releasedAndEnded));
        steps.add(new Step("mouse-rebind-tap", () -> {
            guardKey.setKey(InputConstants.Type.MOUSE.getOrCreate(MOUSE_BUTTON));
            KeyMapping.resetMapping();
            expectedCasts++;
            button(GLFW.GLFW_PRESS);
            button(GLFW.GLFW_RELEASE);
        }, this::shortTapEnded));
        steps.add(new Step("restore-keyboard", () -> {
            guardKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_G));
            KeyMapping.resetMapping();
        }, () -> true));
    }

    private void enqueueInterruptions() {
        // 开屏和焦点往返在同一帧完成，必须依靠事件而不是下一 tick 的状态轮询。
        steps.add(new Step("screen-hold", this::startKeyboardGuard, this::heldAndBusy));
        steps.add(new Step("screen-release", () -> {
            client.gui.setScreen(new PauseScreen(false /* showPauseMenu */));
            client.gui.setScreen(null);
        }, this::releasedAndEnded));
        steps.add(new Step("screen-no-resume", () -> key(GLFW.GLFW_KEY_G, GLFW.GLFW_REPEAT),
                this::quietWithoutNewCast));
        steps.add(new Step("screen-physical-release", () -> key(GLFW.GLFW_KEY_G, GLFW.GLFW_RELEASE), () -> true));
        steps.add(new Step("focus-hold", this::startKeyboardGuard, this::heldAndBusy));
        steps.add(new Step("focus-release", () -> {
            var window = client.getWindow();
            long handle = window.handle();
            focus.invoke(window, handle, false /* focused */);
            focus.invoke(window, handle, true /* focused */);
        }, this::releasedAndEnded));
        steps.add(new Step("focus-no-resume", () -> key(GLFW.GLFW_KEY_G, GLFW.GLFW_REPEAT),
                this::quietWithoutNewCast));
        steps.add(new Step("focus-physical-release", () -> key(GLFW.GLFW_KEY_G, GLFW.GLFW_RELEASE), () -> true));
        steps.add(new Step("exit-hold", this::startKeyboardGuard, this::heldAndBusy));
        steps.add(new Step("exit-release", () -> keyTap(GLFW.GLFW_KEY_R), this::releasedAndEnded));
        steps.add(new Step("reenter-no-resume", () -> keyTap(GLFW.GLFW_KEY_R), this::quietWithoutNewCast));
        steps.add(new Step("exit-physical-release", () -> key(GLFW.GLFW_KEY_G, GLFW.GLFW_RELEASE), () -> true));
        enqueueDeath();
        steps.add(new Step("guard-not-a-slot", this::checkSlotRejection, () -> true));
        steps.add(new Step("leave-stance", () -> keyTap(GLFW.GLFW_KEY_R),
                () -> input.state() == CombatInput.State.OUTSIDE));
    }

    private void enqueueDeath() {
        // 模拟客户端收到死亡血量，服务端仍验证一次正常收势；真实死亡清理由宿主负责。
        steps.add(new Step("death-hold", this::startKeyboardGuard, this::heldAndBusy));
        steps.add(new Step("death-release", () -> client.player.setHealth(0), this::releasedAndEnded));
        steps.add(new Step("revive-no-resume", () -> {
            client.player.setHealth(client.player.getMaxHealth());
            client.gui.setScreen(null);
            keyTap(GLFW.GLFW_KEY_R);
        }, this::quietWithoutNewCast));
        steps.add(new Step("death-physical-release", () -> key(GLFW.GLFW_KEY_G, GLFW.GLFW_RELEASE), () -> true));
    }

    boolean tick(int nowTick) throws Exception {
        // 每步有独立超时，失败不能被截图成功掩盖。
        tick = nowTick;
        if (!subscribed.isDone()) {
            return false;
        }
        subscribed.join();
        if (step == null) {
            step = steps.poll();
            if (step == null) {
                return true;
            }
            startTick = nowTick;
            step.start().run();
            return false;
        }
        if (nowTick - startTick > TIMEOUT_TICKS) {
            throw new IllegalStateException("格挡客户端超时：" + step.name() + " states=" + states);
        }
        if (step.complete().getAsBoolean()) {
            System.out.println("P5_GUARD_PASS " + step.name());
            step = null;
        }
        return false;
    }

    private void startKeyboardGuard() throws Exception {
        // 每次物理按下应只生成一次新的服务端施放身份。
        expectedCasts++;
        key(GLFW.GLFW_KEY_G, GLFW.GLFW_PRESS);
    }

    private boolean heldAndBusy() {
        // 未知结束边界不能让十槽变空闲，施放条也不能假造一个很长的结束点。
        if (castIds().size() != expectedCasts || states.isEmpty()) {
            return false;
        }
        CombatCast cast = CombatCasts.current(0);
        if (cast.endTimes() != CombatCast.EndTimes.UNKNOWN) {
            return false;
        }
        if (!cast.casting() || !cast.busy("sample:other") || cast.spanMillis() != 0) {
            throw new IllegalStateException("持续守式显示成空闲或虚构有限时间条");
        }
        var firstSlot = hud.slots(profiles.slots(), client.level.getGameTime(), cast).getFirst();
        if (firstSlot.state() != CombatHud.State.BUSY) {
            throw new IllegalStateException("持续守式期间十槽没有显示忙碌");
        }
        String id = lastCastId();
        return states.stream().anyMatch(state -> state.active().getRequest().getCastId().equals(id)
                && state.phase() == CastPhase.EFFECTIVE);
    }

    private boolean shortTapEnded() {
        // 服务端记录不得包含 EFFECTIVE，证明同帧短按没有短暂守窗。
        if (!releasedAndEnded()) {
            return false;
        }
        String id = lastCastId();
        if (states.stream().anyMatch(state -> state.active().getRequest().getCastId().equals(id)
                && state.phase() == CastPhase.EFFECTIVE)) {
            throw new IllegalStateException("短按出现有效守窗");
        }
        return true;
    }

    private boolean releasedAndEnded() {
        // 先看权威请求已收到，再等完整收势结束；不能只看客户端松键状态。
        if (castIds().size() != expectedCasts) {
            return false;
        }
        String id = lastCastId();
        return states.stream().anyMatch(state -> state.active().getRequest().getCastId().equals(id)
                && state.active().getSustained().hasEndRequestedMillis()) && ended.contains(id);
    }

    private boolean quietWithoutNewCast() {
        // 留出网络和服务端处理时间，重复、架势外及恢复焦点都不能增加施放。
        if (tick - startTick < QUIET_TICKS) {
            return false;
        }
        if (castIds().size() != expectedCasts) {
            throw new IllegalStateException("没有新按下却重新施放了守式");
        }
        return true;
    }

    private List<String> castIds() {
        // 阶段更新可以重复同一施放，计数按服务端 cast 身份去重。
        return states.stream().map(state -> state.active().getRequest().getCastId()).distinct().toList();
    }

    private String lastCastId() {
        // 前面的数量检查保证需要身份的步骤已经收到起手状态。
        return castIds().getLast();
    }

    private void checkSlotRejection() {
        // 守式仍在知识列表供详情显示，槽位门面必须拒绝并保持十槽配置原样。
        var before = List.copyOf(profiles.slots());
        var result = CombatSlotAccess.set(1, GUARD);
        if (result.status() != CombatSlotAccess.Status.REJECTED || !before.equals(profiles.slots())) {
            throw new IllegalStateException("守式占用了十槽");
        }
        if (CombatSlotAccess.snapshot().known().getTechniquesList().stream()
                .noneMatch(entry -> entry.getId().equals(GUARD) && entry.getGuard())) {
            throw new IllegalStateException("知识列表遗漏天生守式");
        }
    }

    private void keyTap(int key) throws Exception {
        // 连续调用两个原版回调，中间不推进 tick。
        key(key, GLFW.GLFW_PRESS);
        key(key, GLFW.GLFW_RELEASE);
    }

    private void key(int key, int action) throws Exception {
        // 调用真实 KeyboardHandler，包含 Mixin 和原版 KeyMapping 分发。
        keyboard.invoke(client.keyboardHandler, client.getWindow().handle(), action, new KeyEvent(key, 0, 0));
    }

    private void button(int action) throws Exception {
        // 鼠标改绑同样调用真实 MouseHandler，不直接调用格挡处理方法。
        mouse.invoke(client.mouseHandler, client.getWindow().handle(), new MouseButtonInfo(MOUSE_BUTTON, 0), action);
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws Exception {
        // 仅测试读取私有原版回调，正式代码不反射。
        Method result = type.getDeclaredMethod(name, arguments);
        result.setAccessible(true /* accessible */);
        return result;
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        // 测试读取实际对象，避免第二套假客户端状态。
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true /* accessible */);
        return type.cast(field.get(owner));
    }
}
