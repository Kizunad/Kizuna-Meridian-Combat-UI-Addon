package dev.kizuna.meridian.combat.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.combat.core.CombatConfigFile;
import dev.kizuna.meridian.combat.core.CombatDirection;
import dev.kizuna.meridian.combat.core.CombatIds;
import dev.kizuna.meridian.combat.core.CombatInput;
import dev.kizuna.meridian.combat.core.CombatHud;
import dev.kizuna.meridian.combat.core.CombatProfiles;
import dev.kizuna.meridian.combat.core.CombatProtocol;
import dev.kizuna.meridian.combat.core.CombatSlots;
import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.SlotCommand;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** START_CLIENT_TICK 消费架势内按键，视角控制器接管越肩输入和渲染。 */
public final class CombatClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_combat");
    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("meridian_combat", "general"));
    private final CombatHud hud = new CombatHud();
    private final CombatInput input = new CombatInput();
    private final CombatConfigFile file = new CombatConfigFile(FabricLoader.getInstance().getConfigDir()
            .resolve("kizuna-meridian-combat.json"));
    private CombatProfiles profiles;
    private CombatSlots slots;
    private KeyMapping stanceKey;
    private KeyMapping slotTenKey;
    private KeyMapping viewKey;
    private CombatViewController views;
    private CombatHudRenderer renderer;
    private String pendingWarning = "";

    @Override
    public void onInitializeClient() {
        // 初始化本地状态与独立按键，不更改原版快捷栏键绑定。
        load();
        stanceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.meridian_combat.stance",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, KEY_CATEGORY));
        slotTenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.meridian_combat.slot_ten",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_0, KEY_CATEGORY));
        viewKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.meridian_combat.view",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KEY_CATEGORY));
        ClientTickEvents.START_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> join(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> disconnect());
        registerKnown();
        registerSlotCommands();
        renderer = new CombatHudRenderer(hud, profiles, slotTenKey);
        // 挂在原版 HUD 最后，与旧 HudRenderCallback 一样叠在所有原版 HUD 元素之上。
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("meridian_combat", "hud"),
                (context, deltaTracker) -> {
                    if (input.state() == CombatInput.State.ACTIVE) {
                        renderer.render(context);
                    }
                });
    }

    private void load() {
        // 损坏或更新版本原件留存，本次使用空配置并在进入世界后提示一次。
        try {
            profiles = file.load();
        } catch (IOException failure) {
            profiles = new CombatProfiles();
            pendingWarning = failure.getMessage();
            LOGGER.warn(pendingWarning, failure);
        }
        slots = new CombatSlots(profiles);
        views = new CombatViewController(profiles);
    }

    private void join(Minecraft client) {
        // 使用集成服务器的实际目录名，避免重命名存档显示名称后换档。
        slots.updateKnown(Map.of());
        hud.clear();
        String key;
        var server = client.getSingleplayerServer();
        if (server != null) {
            var folder = server.getWorldPath(LevelResource.ROOT).normalize().getFileName();
            key = CombatIds.worldProfile(folder.toString());
        } else {
            key = CombatIds.serverProfile(client.getCurrentServer().ip);
        }
        profiles.enter(key, Instant.now());
        save(client, "");
    }

    private void disconnect() {
        // 清理已学缓存与 HUD 投影，并按断线退出架势；旧连接的包在断线后由原版直接丢弃。
        slots.updateKnown(Map.of());
        hud.clear();
        CombatInput.State before = input.state();
        input.reconcile(CombatInput.Availability.DISCONNECTED);
        notifyStance(before);
    }

    private void registerKnown() {
        // 26.2 的收包回调已在客户端线程，直接解码并整份替换。空包是合法的空列表，所以不用 accepts，只丢弃超长包。
        ClientPlayNetworking.registerGlobalReceiver(CombatChannels.KNOWN.type(), (payload, context) -> {
            if (payload.bytes().length <= CombatProtocol.MAX_KNOWN_BYTES) {
                applyKnown(payload.bytes());
            }
        });
    }

    private void applyKnown(byte[] payload) {
        // 非法列表不发布部分内容，也不继续信任旧缓存。
        try {
            var known = new LinkedHashMap<String, String>();
            var snapshot = CombatProtocol.decodeKnown(payload);
            snapshot.getTechniquesList()
                    .forEach(entry -> known.put(entry.getId(), entry.getName()));
            slots.updateKnown(known);
            hud.update(snapshot, CombatHudRenderer.nowMillis());
        } catch (IllegalArgumentException failure) {
            slots.updateKnown(Map.of());
            hud.clear();
            LOGGER.warn("无法读取服务器已学功法", failure);
        }
    }

    private void registerSlotCommands() {
        // 服务端 /meridian combat slot 只转发操作；回调已在客户端线程，丢弃空包与超长包后解码并改本地槽位。
        ClientPlayNetworking.registerGlobalReceiver(CombatChannels.SLOT.type(), (payload, context) -> {
            if (CombatChannels.SLOT.accepts(payload)) {
                applySlotCommand(context.client(), payload.bytes());
            }
        });
    }

    private void applySlotCommand(Minecraft client, byte[] payload) {
        // 设置与按键选择使用同一套已学列表；成功与失败提示走动作栏，十槽列表进聊天以便完整阅读。
        SlotCommand command;
        try {
            command = CombatProtocol.decodeSlot(payload);
        } catch (IllegalArgumentException failure) {
            LOGGER.warn("无法读取服务器槽位指令", failure);
            return;
        }
        int number = command.getSlot();
        try {
            switch (command.getOperation()) {
                case SLOT_OPERATION_SET -> {
                    slots.set(number, command.getTechniqueId(), Instant.now());
                    save(client, "已设置第 " + number + " 槽");
                }
                case SLOT_OPERATION_CLEAR -> {
                    // 清空当前档不修改其他服务器的配置。
                    profiles.clear(number, Instant.now());
                    save(client, "已清空第 " + number + " 槽");
                }
                case SLOT_OPERATION_LIST -> {
                    slots.lines().forEach(line -> client.player.sendSystemMessage(Component.literal(line)));
                }
            }
        } catch (IllegalArgumentException failure) {
            actionbar(client, failure.getMessage());
        }
    }

    private void tick(Minecraft client) {
        // 先处理失效状态，再决定是否取走原版快捷栏按键次数。
        CombatInput.Availability availability = availability(client);
        CombatInput.State before = input.state();
        input.reconcile(availability);
        CombatInput.Screen screen = CombatInput.Screen.CLOSED;
        if (client.gui.screen() != null) {
            screen = CombatInput.Screen.OPEN;
        }
        if (!pendingWarning.isEmpty() && client.player != null) {
            actionbar(client, pendingWarning);
            pendingWarning = "";
        }
        while (stanceKey.consumeClick()) {
            toggle(client, availability, screen);
        }
        notifyStance(before);
        consumeViewKeys(client, screen);
        if (input.consumesHotbar(availability, screen)) {
            consumeSlots(client);
        } else {
            while (slotTenKey.consumeClick()) {
                // 独立按键在架势外不产生行为，也不积存到下次进入。
            }
        }
    }

    private void toggle(Minecraft client, CombatInput.Availability availability, CombatInput.Screen screen) {
        // 接收通道握手是进入条件，不增加额外的架势同步消息。
        CombatInput.Support support = CombatInput.Support.UNSUPPORTED;
        if (client.getConnection() != null && ClientPlayNetworking.canSend(CombatChannels.PRESS.type())) {
            support = CombatInput.Support.SUPPORTED;
        }
        actionbar(client, input.toggle(availability, screen, support));
    }

    private void consumeViewKeys(Minecraft client, CombatInput.Screen screen) {
        // 架势外或开屏时丢弃独立视角键计数；架势内 F5 交由两模式选择键替代。
        boolean active = views.view().active();
        while (viewKey.consumeClick()) {
            if (active && screen == CombatInput.Screen.CLOSED && client.player != null) {
                views.toggle(client.player);
                views.reconcile(client, input.state());
                String mode = "第一人称";
                if (views.view().shoulder()) {
                    mode = "越肩视角";
                }
                save(client, "架势视角：" + mode);
            }
        }
        if (active) {
            while (client.options.keyTogglePerspective.consumeClick()) {
                // 不让原版三种视角覆盖架势会话；进入前的 F5 选择仍在退出时恢复。
            }
        }
    }

    private void consumeSlots(Minecraft client) {
        // 直接沿用原版键位；滚轮不通过这些按下次数，因此仍能正常切物品。
        for (int index = 0; index < client.options.keyHotbarSlots.length; index++) {
            while (client.options.keyHotbarSlots[index].consumeClick()) {
                press(client, index + 1);
            }
        }
        while (slotTenKey.consumeClick()) {
            press(client, CombatIds.SLOT_COUNT);
        }
    }

    private void press(Minecraft client, int number) {
        // 越肩出招取人物当前朝向；无输入传零，第一人称保留原有移动方向语义。
        renderer.press(number);
        CombatSlots.Selection selection = slots.select(number);
        String reason = selection.reason();
        if (!reason.isEmpty()) {
            actionbar(client, reason);
            return;
        }
        var options = client.options;
        int forward = axis(options.keyUp, options.keyDown);
        int sideways = axis(options.keyLeft, options.keyRight);
        CombatDirection direction = views.view().castDirection(forward, sideways, client.player.getYRot());
        TechniquePress request = TechniquePress.newBuilder()
                .setTechniqueId(selection.techniqueId())
                .setMoveX(direction.x())
                .setMoveZ(direction.z())
                .build();
        ClientPlayNetworking.send(CombatChannels.PRESS.payload(request.toByteArray()));
    }

    private static int axis(KeyMapping positive, KeyMapping negative) {
        // 相反方向同时按下互相抵消，斜向由方向模型归一化。
        int result = 0;
        if (positive.isDown()) {
            result++;
        }
        if (negative.isDown()) {
            result--;
        }
        return result;
    }

    private static CombatInput.Availability availability(Minecraft client) {
        // 自动退出只依据当前连接与生命状态，开界面不取消架势。
        if (client.player == null || client.getConnection() == null) {
            return CombatInput.Availability.DISCONNECTED;
        }
        if (!client.player.isAlive()) {
            return CombatInput.Availability.DEAD;
        }
        if (client.player.isSpectator()) {
            return CombatInput.Availability.SPECTATOR;
        }
        return CombatInput.Availability.READY;
    }

    private void notifyStance(CombatInput.State before) {
        // 主动切换与死亡断线复用相同钩子，动画订阅者可统一清理。
        CombatInput.State after = input.state();
        views.reconcile(Minecraft.getInstance(), after);
        if (before != after) {
            renderer.clearFeedback();
            CombatStanceEvents.CHANGED.invoker().onChanged(after);
        }
    }

    private void save(Minecraft client, String successMessage) {
        // 保存结果与命令成功合并提示，避免动作栏后一次消息覆盖写入故障。
        boolean hasSuccessMessage = !successMessage.isEmpty();
        if (!file.writable()) {
            if (hasSuccessMessage) {
                actionbar(client, successMessage + "（原文件已保护，仅当前会话生效）");
            }
            return;
        }
        try {
            file.save(profiles);
        } catch (IOException failure) {
            LOGGER.warn("战斗配置保存失败", failure);
            actionbar(client, successMessage + "；战斗配置保存失败，本次设置仅在当前会话生效");
            return;
        }
        if (hasSuccessMessage) {
            actionbar(client, successMessage);
        }
    }

    private static void actionbar(Minecraft client, String message) {
        // 操作反馈保留动作栏，与十槽图标同时显示。
        if (client.player != null) {
            client.player.sendOverlayMessage(Component.literal(message));
        }
    }
}
