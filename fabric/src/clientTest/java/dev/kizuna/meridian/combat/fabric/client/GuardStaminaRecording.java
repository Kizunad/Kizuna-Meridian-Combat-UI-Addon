package dev.kizuna.meridian.combat.fabric.client;

import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthMessages.FatigueRelief;
import dev.kizuna.meridian.protocol.pb.HealthMessages.RestoreFatigue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** 真 G 验收用同一名玩家；每帧取真实客户端画面，费用只从集成服务端读取。 */
public final class GuardStaminaRecording {
    private static final int FRAME_INTERVAL = 2;
    private static final double INITIAL_BUDGET = 41000;
    private static final double STRIKE_COST = 9000;
    private static final double FULL_FATIGUE = 100;
    private static final double RESTART = 0.2;
    private static final long MILLIS_PER_TICK = 50;
    private static final String FORM = "meridian_basic_techniques:guard";
    private final Minecraft client = Minecraft.getInstance();
    private final UUID playerId = client.player.getUUID();
    private static boolean filming;
    private CompletableFuture<?> work;
    private CompletableFuture<Boolean> query;
    private String overlay;
    private Path frames;
    private int lastTick;
    private int requested;
    private int written;
    private long deadline;

    GuardStaminaRecording() {
        // 验客户端收到的是可翻译的独立原因，防止服务端把键名当文字发给玩家。
        ClientReceiveMessageEvents.GAME.register((message, actionBar) -> {
            if (actionBar && message.getContents() instanceof TranslatableContents text
                    && !message.getString().equals(text.getKey())) {
                overlay = text.getKey();
                System.out.println("GUARD_OVERLAY " + overlay);
            }
        });
    }

    void prepare(String style) throws IOException {
        // 每段从一笔已知余量起守，真实恢复、真实命中与起式走在线宿主。
        frames = Files.createDirectories(Path.of("../guard-stamina-frames", style));
        filming = true;
        client.gui.hud.getChat().clearMessages(false /* clearHistory */);
        client.gui.toastManager().clear();
        requested = 0;
        written = 0;
        overlay = "";
        work = client.getSingleplayerServer().submit(() -> {
            var player = player();
            var server = client.getSingleplayerServer();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                    "difficulty peaceful");
            var hand = ItemStack.EMPTY;
            if (style.equals("sword")) {
                hand = new ItemStack(Items.IRON_SWORD);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, hand);
            var host = host();
            restore(host);
            setBudget(host, INITIAL_BUDGET);
        });
    }

    private static void setBudget(TechniqueHost host, double joules) {
        // 夹具使用权威活动按当前余量分摊；保留战斗造成的部位损伤。
        var body = host.inspect().body();
        var stamina = HumanStamina.from(body);
        var costs = stamina.cost(Math.max(0, stamina.availableJoules() - joules));
        if (!costs.isEmpty()) {
            var activity = BodyActivity.newBuilder().addAllTissueFatigue(costs).build();
            host.submit(new TechniqueHost.Submission(UUID.randomUUID().toString(), body.revision(), activity));
        }
    }

    void prepareBoundary() {
        // 固定测试世界时钟，用已保存的禁守进度构造相邻两拍；实际准入走原版按键与服务端宿主。
        overlay = "";
        var server = client.getSingleplayerServer();
        work = server.submit(() -> {
            server.tickRateManager().setFrozen(true /* frozen */);
            var host = host();
            restore(host);
            var body = host.inspect().body();
            deadline = body.settledAt().elapsedMillis() + MILLIS_PER_TICK;
            var progress = TechniqueState.read(body).toBuilder().putGuardBreakEnds(FORM, deadline).build();
            var activity = BodyActivity.newBuilder().putProgress(TechniqueState.KEY, TechniqueState.pack(progress));
            host.submit(new TechniqueHost.Submission(UUID.randomUUID().toString(), body.revision(), activity.build()));
            setBudget(host, HumanStamina.from(body).capacityJoules() * RESTART);
        });
    }

    void stepToDeadline() {
        // 只推进一拍，等待实际身体处于截止时刻再设置精确余力。
        var server = client.getSingleplayerServer();
        work = server.submit(() -> {
            if (!server.tickRateManager().stepGameIfPaused(1)) {
                throw new IllegalStateException("测试世界未冻结");
            }
        });
    }

    boolean atDeadline() {
        // 在线身体按十八块肌肉的精度换算，边界用一单位末位容差检查。
        return ready() && poll(() -> {
            var host = host();
            var body = host.inspect().body();
            long now = body.settledAt().elapsedMillis();
            if (now < deadline) {
                return false;
            }
            double capacity = HumanStamina.from(body).capacityJoules();
            double required = capacity * RESTART;
            setBudget(host, required);
            double actual = HumanStamina.from(host.inspect().body()).availableJoules();
            if (now != deadline || Math.abs(actual - required) > Math.ulp(capacity)) {
                throw new IllegalStateException("真 G 双门槛夹具越界 " + now + "/" + deadline + " J=" + actual);
            }
            return true;
        });
    }

    void resumeTime() {
        // 准入已在冻结的精确边界成功，再恢复自然时间完成起手。
        var server = client.getSingleplayerServer();
        work = server.submit(() -> server.tickRateManager().setFrozen(false /* frozen */));
    }

    void restoreSlotFixture() {
        // 还原后续旧用例所需的持斧和体力，保持十槽忙碌断言的前提。
        work = client.getSingleplayerServer().submit(() -> {
            restore(host());
            player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        });
    }

    public static boolean filming() {
        // 取景只在两段实际按键录像内生效。
        return filming;
    }

    private ServerPlayer player() {
        // 仅在集成服务端线程上访问对应服务端实体。
        return client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
    }

    private TechniqueHost host() {
        // 和正式按 G 共用宿主与当前在线身体。
        return TechniqueFabric.service().statusHost(playerId).orElseThrow();
    }

    private void restore(TechniqueHost host) {
        // 用可信消疲命令准备同一身体的第二段录像。
        var body = host.inspect().body();
        var restore = RestoreFatigue.newBuilder().setSourceId("meridian_combat_client_test:rest");
        for (String muscle : HumanStamina.from(body).workingMuscles()) {
            var dose = FatigueRelief.newBuilder().setTissueId(muscle).setAmount(FULL_FATIGUE).build();
            restore.addDoses(dose);
        }
        var command = BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision())
                .setRestoreFatigue(restore)
                .build();
        String id = UUID.randomUUID().toString();
        var effect = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setBodyCommand(command)
                .build();
        var request = HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setSubjectId(playerId.toString())
                .setEffect(effect)
                .build();
        byte[] payload = HealthHostProtobuf.encodeRequest(request);
        var hosts = FabricLoader.getInstance().getEntrypoints("meridian_health_host", BodyHealthHost.class);
        for (var health : hosts) {
            var result = HealthHostProtobuf.decodeResponse(health.exchange(payload));
            if (result.hasBody()) {
                return;
            }
        }
        throw new IllegalStateException("测试玩家消疲失败");
    }

    void hit() {
        // 真实普通伤害使用前方位置和固定头部标签，保留护甲、吸收与兵器分摊。
        work = client.getSingleplayerServer().submit(() -> {
            var guard = TechniqueFabric.service().activeGuard(playerId).orElseThrow();
            if (!guard.technique().getId().equals(FORM)) {
                throw new IllegalStateException("G 未启动正式格挡 " + guard.technique());
            }
            var host = host();
            double before = HumanStamina.from(host.inspect().body()).availableJoules();
            strike();
            double after = HumanStamina.from(host.inspect().body()).availableJoules();
            if (before - after < STRIKE_COST - Math.ulp(before)) {
                throw new IllegalStateException("格挡命中未支付 " + STRIKE_COST + " J");
            }
        });
    }

    private void strike() {
        // /damage 经原版受伤处理；坐标按被击玩家面向求前方。
        var server = client.getSingleplayerServer();
        String command = "execute as @p at @s run damage @s 10 meridian_combat_client_test:guard_cost at ^ ^ ^2";
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    boolean hitPaid() {
        // 维持费使余额降到单击费用以下时才打第二下，确认命中当刻已经撤窗。
        return ready() && poll(() -> {
            var body = host().inspect().body();
            double available = HumanStamina.from(body).availableJoules();
            if (available > STRIKE_COST) {
                return false;
            }
            strike();
            var active = TechniqueState.read(host().inspect().body()).getActiveCast();
            if (!active.getSustained().hasWeaponContactOnBreak()) {
                throw new IllegalStateException("第二次命中未造成破防，命中前余力 " + available
                        + "，命中后 " + HumanStamina.from(host().inspect().body()).availableJoules()
                        + "，守式 " + active.getSustained());
            }
            // 把第二次按 G 固定在这笔破防上，慢帧不能跳过整段禁守。
            deadline = TechniqueState.read(host().inspect().body()).getGuardBreakEndsOrThrow(FORM);
            client.getSingleplayerServer().tickRateManager().setFrozen(true /* frozen */);
            return true;
        });
    }

    void stepPastBreak() {
        // 走真实禁守时钟，期满时保留自然恢复后的余力。
        var server = client.getSingleplayerServer();
        work = server.submit(() -> {
            long now = host().inspect().body().settledAt().elapsedMillis();
            int ticks = Math.toIntExact(Math.ceilDiv(deadline - now, MILLIS_PER_TICK));
            if (!server.tickRateManager().stepGameIfPaused(ticks)) {
                throw new IllegalStateException("破防时钟未冻结");
            }
        });
    }

    boolean pastBreak() {
        // 禁守截止以身体时钟为准，定位完成后再从客户端重新按键。
        return ready() && poll(() -> host().inspect().body().settledAt().elapsedMillis() >= deadline);
    }

    boolean recovered() {
        // 服务器跨过真实自然恢复门槛后才允许下一次物理按压。
        return poll(() -> HumanStamina.from(host().inspect().body()).remainingRatio() >= RESTART);
    }

    private boolean poll(Supplier<Boolean> check) {
        // 每次最多有一个服务器查询，渲染线程只观察已经完成的结果。
        if (query == null) {
            query = client.getSingleplayerServer().submit(check);
        }
        if (!query.isDone()) {
            return false;
        }
        boolean result = query.join();
        query = null;
        return result;
    }

    boolean ready() {
        // 失败须交给客户端测试报告，不能仅以 future 完成判定成功。
        if (!work.isDone()) {
            return false;
        }
        work.join();
        return true;
    }

    boolean message(String reason) {
        // 两类拒绝均须经过 overlay 包，并在客户端实际完成翻译。
        return ("meridian_human_host.guard." + reason).equals(overlay);
    }

    void capture(int tick) {
        // 每两个游戏 tick 保存一帧；帧序号按回读发起顺序固定。
        if (frames == null || tick - lastTick < FRAME_INTERVAL) {
            return;
        }
        lastTick = tick;
        client.gui.hud.getChat().clearMessages(false /* clearHistory */);
        client.gui.toastManager().clear();
        Path file = frames.resolve("frame-%04d.png".formatted(requested++));
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(file);
                written++;
            } catch (IOException failure) {
                throw new IllegalStateException("格挡录像帧写入失败 " + file, failure);
            }
        });
    }

    void finish() {
        // 停止发新回读，再等已经请求的帧写完。
        frames = null;
        filming = false;
        System.out.println("GUARD_STAMINA_FRAMES " + requested);
    }

    boolean saved() {
        // 防止最后一帧还在回读时切换下一段。
        return requested > 0 && requested == written;
    }
}
