package dev.kizuna.meridian.combat.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.kizuna.meridian.combat.core.CombatCast;
import dev.kizuna.meridian.combat.core.CombatConfigFile;
import dev.kizuna.meridian.combat.core.CombatHud;
import dev.kizuna.meridian.combat.core.CombatIds;
import dev.kizuna.meridian.combat.core.CombatProfiles;
import dev.kizuna.meridian.combat.core.CombatSlots;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.BlockKind;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 先经服务端 /meridian combat slot 写本地配置，并确认 /meridian body status 仍由服务端执行；
 * 接着拿真身体走一遍「空手时带兵器角标，拿上斧子可用；真元耗尽，图标变灰带真元角标，补满又亮回来」，状态全由服务端真实推送；
 * 再以可控显示快照驱动正式渲染器，截图包含真实原版快捷栏与生存状态栏。
 */
public final class CombatHudClientTest implements ClientModInitializer {
    private static final int READY_TICKS = 60;
    private static final int SETTLE_FRAMES = 12;
    private static final int INITIAL_SCALE = 2;
    private static final int FINAL_SCALE = 3;
    private static final long COOLDOWN_MILLIS = 3000;
    private static final long TOTAL_MILLIS = 5000;
    private static final Path SCREENS = Path.of("../hud-screens");
    private static final Path REPORT = Path.of("../clienttest-results.txt");
    private static final List<String> CASES =
            List.of("four-states", "empty", "pressed", "released", "restored", "casting", "guarding");
    /**
     * 「casting」那一张用的施放：一式放到收势、还没到收招段；第 3 槽那一式的承接窗口开着，第 9 槽那一式被服务端记着。
     * 施放条上起手、出招、收势、收招四段和两次打击、一个承接窗口都看得见。
     */
    private static final CombatCast CASTING = new CombatCast(2200, 800, 1400, 3000, 2600, List.of(900L, 1300L),
            List.of(new CombatCast.Window("hud:follow", 2000, 3400)), "hud:buffered");
    /** 可控快照的截图之外，真元真的耗尽时再拍一张。 */
    private static final int TOTAL_CAPTURES = CASES.size() * (FINAL_SCALE - INITIAL_SCALE + 1) + 1;
    private static final String DRAINED_SCREEN = "gui-real-short-qi.png";
    /** 开脉、升真炁、全身内腔覆盖成密度 1 的真元：叩门这一式付得起。 */
    private static final List<String> READY_COMMANDS = List.of(
            "meridian opening complete @a all", "meridian body realm @a true_qi", "meridian qi set @a 300 all lumen 1");
    /** 叩门要拿着斧子。 */
    private static final String ARM_COMMAND = "item replace entity @a weapon.mainhand with minecraft:iron_axe";
    private static final String DRAIN_COMMAND = "meridian qi set @a 0 all lumen 1";
    private static final String REFILL_COMMAND = "meridian qi set @a 300 all lumen 1";
    private static final String TECHNIQUE = "meridian_door_breaker:knock";
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("kizuna-meridian-combat.json");
    /** 每个指令步骤最多等 10 秒（200 tick），超时视为失败。 */
    private static final int COMMAND_TIMEOUT_TICKS = 200;
    private final List<Component> replies = new ArrayList<>();
    private int ticks;
    private int frames;
    private int scale = INITIAL_SCALE;
    private int caseIndex;
    private int captures;
    private boolean shotPending;
    private boolean finished;
    private CombatHud hud;
    private CombatProfiles profiles;
    private CombatHudRenderer renderer;
    private CombatSlots slots;
    private CompletableFuture<Void> survival;
    private CompletableFuture<?> learned;
    private CommandStep step = CommandStep.LEARN;
    private int stepStartTick;
    private CombatClient owner;
    private CombatGuardClientTest guardTest;
    private boolean guardPassed;

    @Override
    public void onInitializeClient() {
        // 等待世界与资源遮罩消失后，逐帧运行与保存 GUI 截图。
        ClientTickEvents.END_CLIENT_TICK.register(client -> ticks++);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                replies.add(message);
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("meridian_combat_client_test", "capture"),
                (context, deltaTracker) -> render(context));
    }

    private void render(GuiGraphicsExtractor context) {
        // 截图夹具只存在于测试源码集，不向正式客户端增加调试状态或网络接口。
        var client = Minecraft.getInstance();
        if (finished || ticks < READY_TICKS || client.player == null || client.gui.overlay() != null) {
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
            if (step != CommandStep.DONE) {
                commands(client, context);
                return;
            }
            if (!guardPassed) {
                if (guardTest == null) {
                    guardTest = new CombatGuardClientTest(owner);
                }
                guardPassed = guardTest.tick(ticks);
                return;
            }
            if (scale > FINAL_SCALE) {
                // 全部画完后只等截图异步写盘。
                return;
            }
            if (client.options.guiScale().get() != scale) {
                client.options.guiScale().set(scale);
                client.resizeGui();
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
        owner = (CombatClient) entry;
        hud = field(entry, "hud", CombatHud.class);
        profiles = field(entry, "profiles", CombatProfiles.class);
        renderer = field(entry, "renderer", CombatHudRenderer.class);
        slots = field(entry, "slots", CombatSlots.class);
        KeyMapping tenthKey = field(entry, "slotTenKey", KeyMapping.class);
        client.options.keyHotbarSlots[0].setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_Q));
        tenthKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_Z));
        KeyMapping.resetMapping();
        if (client.gui.hud.isHidden()) {
            client.gui.hud.toggle();
        }
        client.options.pauseOnLostFocus = false;
        client.gui.setScreen(null);
        Files.createDirectories(SCREENS);
        var server = client.getSingleplayerServer();
        var uuid = client.player.getUUID();
        survival = server.submit(() -> {
            server.getPlayerList().getPlayer(uuid).setGameMode(GameType.SURVIVAL);
        });
        learned = server.submit(() -> TechniqueFabric.service().learn(uuid, TECHNIQUE));
        stepStartTick = ticks;
    }

    private void commands(Minecraft client, GuiGraphicsExtractor context) throws Exception {
        // 每步等上一条指令的效果落地再发下一条：先清空再设置，证明配置文件是本次写入的；
        // 最后经同一聊天指令入口发 body status，若客户端注册了 meridian 根，回复会是未知指令错误。
        if (ticks - stepStartTick > COMMAND_TIMEOUT_TICKS) {
            throw new IllegalStateException("指令步骤超时：" + step);
        }
        switch (step) {
            case LEARN -> {
                if (learned.isDone()) {
                    learned.join();
                    if (slots.suggestions("").contains(TECHNIQUE)) {
                        send(client, "meridian combat slot clear 1", CommandStep.CLEAR);
                    }
                }
            }
            case CLEAR -> {
                if (savedFirstSlot().isEmpty()) {
                    send(client, "meridian combat slot set 1 " + TECHNIQUE, CommandStep.SET);
                }
            }
            case SET -> {
                if (savedFirstSlot().equals(TECHNIQUE)) {
                    if (ClientCommands.getActiveDispatcher().getRoot().getChild("meridian") != null) {
                        throw new IllegalStateException("客户端注册了 meridian 根，会遮住服务端 /meridian");
                    }
                    replies.clear();
                    send(client, "meridian body status", CommandStep.STATUS);
                }
            }
            case STATUS -> {
                if (!replies.isEmpty()) {
                    Component reply = replies.get(0);
                    if (reply.getContents() instanceof TranslatableContents error
                            && error.getKey().startsWith("command.")) {
                        throw new IllegalStateException("/meridian body status 没有被服务端执行：" + reply.getString());
                    }
                    serverCommands(client, READY_COMMANDS, CommandStep.UNARMED);
                }
            }
            case UNARMED -> {
                // 这一步起槽里的状态都是服务端按真身体算出来推的：开脉补满了，可是空着手，叩门要斧子。
                CombatHud.Slot slot = firstSlot(client);
                if (slot.state() == CombatHud.State.SHORT && slot.block() == BlockKind.BLOCK_ITEM) {
                    serverCommands(client, List.of(ARM_COMMAND), CommandStep.READY);
                }
            }
            case READY -> {
                // 拿上斧子：换手里的东西不改身体版本，照样在下一次例行检查变成可用。
                if (firstSlot(client).state() == CombatHud.State.AVAILABLE) {
                    serverCommands(client, List.of(DRAIN_COMMAND), CommandStep.DRAINED);
                }
            }
            case DRAINED -> {
                // 真元清空：不重学、不重登，下一次例行检查就该变灰并带真元角标。
                CombatHud.Slot slot = firstSlot(client);
                if (slot.state() == CombatHud.State.SHORT && slot.block() == BlockKind.BLOCK_QI) {
                    step = CommandStep.DRAINED_SHOT;
                    stepStartTick = ticks;
                }
            }
            case DRAINED_SHOT -> {
                // 正式渲染器照真实状态画几帧，再把上一帧拍下来。
                if (frames++ < SETTLE_FRAMES) {
                    // 开脉升境界的聊天回显和进度提示会盖住战斗槽，拍之前清掉。
                    client.gui.hud.getChat().clearMessages(false /* clearHistory */);
                    client.gui.toastManager().clear();
                    renderer.render(context);
                    return;
                }
                frames = 0;
                Path file = SCREENS.resolve(DRAINED_SCREEN);
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> save(image, file));
                serverCommands(client, List.of(REFILL_COMMAND), CommandStep.REFILLED);
            }
            case REFILLED -> {
                // 补满以后身体版本不一定变，靠「上一份里有真元不够的」这条接着重算才亮得回来。
                if (firstSlot(client).state() == CombatHud.State.AVAILABLE) {
                    step = CommandStep.DONE;
                }
            }
        }
    }

    private CombatHud.Slot firstSlot(Minecraft client) {
        // 第 1 槽绑的是真学会的叩门，显示状态取正式 HUD 模型里服务端最近推来的那份。
        return hud.slots(profiles.slots(), client.level.getGameTime()).get(0);
    }

    private void serverCommands(Minecraft client, List<String> commands, CommandStep next) {
        // 管理指令以服务端身份执行，和管理员在控制台输入的一样。
        var server = client.getSingleplayerServer();
        server.submit(() -> commands.forEach(command ->
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command)));
        step = next;
        stepStartTick = ticks;
    }

    private void send(Minecraft client, String command, CommandStep next) {
        // 走正式聊天指令入口，Fabric 客户端指令拦截与服务端解析都在这条路径上。
        client.player.connection.sendCommand(command);
        step = next;
        stepStartTick = ticks;
    }

    private String savedFirstSlot() throws IOException {
        // 另建读取实例读回磁盘，确认写入的是当前分档；不动正式实例的只读保护状态。
        return new CombatConfigFile(CONFIG).load().snapshot().get(profiles.current()).slots().get(0);
    }

    private void drawCase(GuiGraphicsExtractor context, Minecraft client) {
        // 前两例等待稳定画面；后三例逐例保存，验证实际两个反馈帧与恢复帧。
        // 26.2 每帧先提取 GUI 再统一绘制，本回调时主画面还是上一帧，所以画完一例后在下一帧回调里截图，
        // 截图那一帧不画也不推进反馈帧，按下、松开、恢复仍是连续三个绘制帧。
        if (shotPending) {
            capture(client);
            return;
        }
        client.gui.hud.getChat().clearMessages(false /* clearHistory */);
        client.gui.toastManager().clear();
        String name = CASES.get(caseIndex);
        var now = Instant.now();
        for (int number = 1; number <= CombatIds.SLOT_COUNT; number++) {
            profiles.clear(number, now);
        }
        if (!name.equals("empty")) {
            profiles.set(1, "hud:available", now);
            profiles.set(2, "hud:cooldown", now);
            profiles.set(4, "hud:unavailable", now);
            profiles.set(5, "hud:short_qi", now);
            profiles.set(6, "hud:short_strength", now);
            profiles.set(7, "hud:cooling_short_qi", now);
            profiles.set(3, "hud:follow", now);
            profiles.set(8, "hud:wrong_item", now);
            profiles.set(9, "hud:buffered", now);
        }
        hud.update(snapshot());
        if (name.equals("pressed")) {
            renderer.press(1);
        }
        if (name.equals("casting")) {
            // 正在放别的式：能放的槽变灰带沙漏，能接的槽描暖色框，记着的槽描白框，槽条上方是施放条。
            renderer.render(context, CASTING);
        } else if (name.equals("guarding")) {
            // 持续式的零边界不能让槽位亮起，也不在槽条上方画虚构的长条。
            var guard = new CombatCast(TOTAL_MILLIS, 0, 0, 0, 0, List.of(), List.of(), "",
                    CombatCast.EndTimes.UNKNOWN);
            renderer.render(context, guard);
        } else {
            renderer.render(context);
        }
        if (caseIndex < 2 && ++frames < SETTLE_FRAMES) {
            return;
        }
        shotPending = true;
    }

    private void capture(Minecraft client) {
        // 截取上一帧完整画面，再切到下一例；三个缩放都画完后 scale 超出范围，只等写盘。
        shotPending = false;
        Path file = SCREENS.resolve("gui-" + scale + "-" + CASES.get(caseIndex) + ".png");
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> save(image, file));
        frames = 0;
        if (++caseIndex == CASES.size()) {
            caseIndex = 0;
            scale++;
        }
    }

    private void save(NativeImage image, Path file) {
        // 截图在之后的帧里异步回读；全部写盘后才结束，避免关闭客户端时丢图。已因失败结束时只释放图像。
        try (image) {
            if (finished) {
                return;
            }
            image.writeToFile(file);
            if (++captures == TOTAL_CAPTURES) {
                finish(null);
            }
        } catch (IOException failure) {
            finish(failure.toString());
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
                .setCooldownEndTick(Minecraft.getInstance().level.getGameTime()
                        + COOLDOWN_MILLIS / CombatHud.MILLIS_PER_TICK)
                .setCooldownTotalMillis(TOTAL_MILLIS)
                .build();
        var unavailable = available.toBuilder()
                .setId("hud:unavailable")
                .setUnavailableReason("经脉未通")
                .build();
        // 真元、力气不够：图标变灰、右下角叠原因角标，不标红框；还在冷却里的照冷却画，角标照叠。
        var shortQi = available.toBuilder()
                .setId("hud:short_qi")
                .setUnavailableReason("真元不济")
                .setBlock(BlockKind.BLOCK_QI)
                .build();
        var shortStrength = available.toBuilder()
                .setId("hud:short_strength")
                .setUnavailableReason("气力不继")
                .setBlock(BlockKind.BLOCK_EXERTION)
                .build();
        var coolingShortQi = cooldown.toBuilder()
                .setId("hud:cooling_short_qi")
                .setUnavailableReason("真元不济")
                .setBlock(BlockKind.BLOCK_QI)
                .build();
        // 手里的东西不对：和真元、力气不够一样变灰，角标是一把小剑。
        var wrongItem = available.toBuilder()
                .setId("hud:wrong_item")
                .setUnavailableReason("手里的东西不对")
                .setBlock(BlockKind.BLOCK_ITEM)
                .build();
        return KnownTechniques.newBuilder()
                .addTechniques(available.toBuilder().setId("hud:follow").build())
                .addTechniques(available.toBuilder().setId("hud:buffered").build())
                .addTechniques(wrongItem)
                .addTechniques(available)
                .addTechniques(cooldown)
                .addTechniques(unavailable.toBuilder().setBlock(BlockKind.BLOCK_BODY).build())
                .addTechniques(shortQi)
                .addTechniques(shortStrength)
                .addTechniques(coolingShortQi)
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
            Files.writeString(REPORT, "success=" + (failure == null) + "\ncommands=" + (step == CommandStep.DONE)
                    + "\nguard=" + guardPassed
                    + "\ncaptures=" + captures
                    + "\nerror=" + failure + "\n");
        } catch (Exception reportFailure) {
            throw new IllegalStateException(reportFailure);
        }
        Minecraft.getInstance().stop();
    }

    private enum CommandStep {
        LEARN,
        CLEAR,
        SET,
        STATUS,
        UNARMED,
        READY,
        DRAINED,
        DRAINED_SHOT,
        REFILLED,
        DONE
    }
}
