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
import dev.kizuna.meridian.combat.core.CombatViewSettings;
import dev.kizuna.meridian.combat.core.CombatStanceSettings;
import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.SlotCommand;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** START_CLIENT_TICK 消费架势内按键，视角控制器接管越肩输入和渲染。 */
public final class CombatClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_combat");
    private static CombatClient instance;
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
    private KeyMapping lockViewKey;
    private KeyMapping faceTargetKey;
    private boolean lockHeld;
    private boolean targetHeld;
    private final CombatScreenInput settingsInput = new CombatScreenInput();
    private CombatViewController views;
    private CombatHudRenderer renderer;
    private String pendingWarning = "";
    private KnownTechniques known = KnownTechniques.getDefaultInstance();
    private CombatSlotAccess.Connection connection = CombatSlotAccess.Connection.DISCONNECTED;
    private final Set<Integer> configurationKeys = new HashSet<>();

    @Override
    public void onInitializeClient() {
        // 初始化本地状态与独立按键，不更改原版快捷栏键绑定。
        load();
        CombatSlotAccess.initialize(this);
        stanceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.meridian_combat.stance",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, KEY_CATEGORY));
        slotTenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.meridian_combat.slot_ten",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_0, KEY_CATEGORY));
        viewKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.meridian_combat.view",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KEY_CATEGORY));
        lockViewKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.meridian_combat.lock_view",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY));
        faceTargetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.meridian_combat.face_target",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY));
        instance = this;
        CombatSettingsAccess.initialize(this);
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
                        renderTarget(Minecraft.getInstance(), context);
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
        known = KnownTechniques.getDefaultInstance();
        String key;
        var server = client.getSingleplayerServer();
        if (server != null) {
            var folder = server.getWorldPath(LevelResource.ROOT).normalize().getFileName();
            key = CombatIds.worldProfile(folder.toString());
        } else {
            key = CombatIds.serverProfile(client.getCurrentServer().ip);
        }
        profiles.enter(key, Instant.now());
        connection = CombatSlotAccess.Connection.CONNECTED;
        save(client, "");
        publishSlots();
    }

    private void disconnect() {
        // 清理已学缓存与 HUD 投影，并按断线退出架势；旧连接的包在断线后由原版直接丢弃。
        slots.updateKnown(Map.of());
        hud.clear();
        known = KnownTechniques.getDefaultInstance();
        connection = CombatSlotAccess.Connection.DISCONNECTED;
        configurationKeys.clear();
        publishSlots();
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
            var names = new LinkedHashMap<String, String>();
            var snapshot = CombatProtocol.decodeKnown(payload);
            snapshot.getTechniquesList()
                    .forEach(entry -> names.put(entry.getId(), entry.getName()));
            slots.updateKnown(names);
            known = snapshot;
            hud.update(snapshot, CombatHudRenderer.nowMillis());
        } catch (IllegalArgumentException failure) {
            slots.updateKnown(Map.of());
            known = KnownTechniques.getDefaultInstance();
            hud.clear();
            LOGGER.warn("无法读取服务器已学功法", failure);
        }
        publishSlots();
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
                    setSlot(number, command.getTechniqueId());
                }
                case SLOT_OPERATION_CLEAR -> {
                    clearSlot(number);
                }
                case SLOT_OPERATION_LIST -> {
                    slots.lines().forEach(line -> client.player.sendSystemMessage(Component.literal(line)));
                }
            }
        } catch (IllegalArgumentException failure) {
            actionbar(client, failure.getMessage());
        }
    }

    CombatSlotAccess.Result setSlot(int number, String techniqueId) {
        // 释放拖动或收到命令时重新检查已学状态，客户端展示选择不能授予功法。
        var client = Minecraft.getInstance();
        try {
            requireConnection();
            slots.set(number, techniqueId, Instant.now());
            var result = save(client, "已设置第 " + number + " 槽");
            publishSlots();
            return result;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            actionbar(client, failure.getMessage());
            return new CombatSlotAccess.Result(CombatSlotAccess.Status.REJECTED, failure.getMessage());
        }
    }

    CombatSlotAccess.Result clearSlot(int number) {
        // 清空只修改当前世界的槽位，与指令共享保存失败反馈。
        var client = Minecraft.getInstance();
        try {
            requireConnection();
            profiles.clear(number, Instant.now());
            var result = save(client, "已清空第 " + number + " 槽");
            publishSlots();
            return result;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            actionbar(client, failure.getMessage());
            return new CombatSlotAccess.Result(CombatSlotAccess.Status.REJECTED, failure.getMessage());
        }
    }

    private void requireConnection() {
        // 断线后不允许通过残留窗口修改上一世界的配置。
        if (connection != CombatSlotAccess.Connection.CONNECTED) {
            throw new IllegalStateException("尚未连接世界，不能配置功法槽位");
        }
    }

    private void publishSlots() {
        // 断线清理展示但保留文件中的分档；HUD 仍直接读取同一个 profiles。
        var displayed = profiles.slots();
        if (connection == CombatSlotAccess.Connection.DISCONNECTED) {
            displayed = Collections.nCopies(CombatIds.SLOT_COUNT, "");
        }
        CombatSlotAccess.publish(new CombatSlotAccess.Snapshot(connection, known, displayed));
    }

    private void tick(Minecraft client) {
        // 先处理失效状态，再决定是否取走原版快捷栏按键次数。
        settingsInput.tick(client);
        if (views.editing()) {
            settingsInput.clear(client);
        }
        discardConfigurationClicks(client);
        CombatInput.Availability availability = availability(client);
        CombatInput.State before = input.state();
        input.reconcile(availability);
        CombatInput.Screen screen = CombatInput.Screen.CLOSED;
        if (client.gui.screen() != null) {
            screen = CombatInput.Screen.OPEN;
            discardSlotClicks(client);
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
        consumeSettingsKeys(client);
        if (input.consumesHotbar(availability, screen)) {
            consumeSlots(client);
        } else {
            while (slotTenKey.consumeClick()) {
                // 独立按键在架势外不产生行为，也不积存到下次进入。
            }
        }
    }

    CombatViewController views() {
        // 设置门面与正式输入共享同一控制器。
        return views;
    }

    CombatSettingsAccess.Snapshot settings() {
        // 发布只读快照，配置保护状态必须让编辑器可见。
        return new CombatSettingsAccess.Snapshot(profiles.view(), profiles.stance(), file.writable());
    }

    CombatSettingsAccess.Result saveSettings(CombatViewSettings view, CombatStanceSettings stance) {
        // 候选配置写盘成功才发布，失败不会改变正式相机参数。
        if (!file.writable()) {
            return new CombatSettingsAccess.Result(CombatSettingsAccess.Status.FAILED,
                    "原配置处于保护模式，无法持久化；请退出游戏后修复原文件");
        }
        try {
            file.save(profiles.configured(view, stance));
            profiles.configure(view, stance);
            views.view().configureLock(stance.viewLock());
            return new CombatSettingsAccess.Result(CombatSettingsAccess.Status.SAVED, "Combat 偏好已保存");
        } catch (IOException failure) {
            LOGGER.warn("Combat 偏好保存失败", failure);
            return new CombatSettingsAccess.Result(CombatSettingsAccess.Status.FAILED,
                    "Combat 偏好保存失败，请检查配置目录是否可写；草稿已保留，可重试或取消");
        }
    }

    KeyMapping settingsKey(CombatSettingsAccess.Action action) {
        // 返回原版注册的映射，没有第二份键位配置。
        return switch (action) {
            case LOCK_VIEW -> lockViewKey;
            case FACE_TARGET -> faceTargetKey;
        };
    }

    CombatSettingsAccess.Result saveSettingsKeys(CombatSettingsAccess.Keys keys) {
        // 原版 save 会吞掉 IO 错误，因此写后检查磁盘的两个字段，失败只恢复这两个映射。
        var previousLock = InputConstants.getKey(lockViewKey.saveString());
        var previousTarget = InputConstants.getKey(faceTargetKey.saveString());
        lockViewKey.setKey(keys.lockView());
        faceTargetKey.setKey(keys.faceTarget());
        KeyMapping.resetMapping();
        var game = Minecraft.getInstance();
        game.options.save();
        try {
            var path = FabricLoader.getInstance().getGameDir().resolve("options.txt");
            var lines = java.nio.file.Files.readAllLines(path);
            if (!lines.contains("key_" + lockViewKey.getName() + ":" + lockViewKey.saveString())
                    || !lines.contains("key_" + faceTargetKey.getName() + ":" + faceTargetKey.saveString())) {
                throw new IOException("options.txt 未保存所选键位");
            }
            clearSettingsInput();
            return new CombatSettingsAccess.Result(CombatSettingsAccess.Status.SAVED, "快捷键已保存");
        } catch (IOException failure) {
            LOGGER.warn("Combat 快捷键保存失败", failure);
            lockViewKey.setKey(previousLock);
            faceTargetKey.setKey(previousTarget);
            KeyMapping.resetMapping();
            return new CombatSettingsAccess.Result(CombatSettingsAccess.Status.FAILED,
                    "快捷键保存失败（options.txt），草稿已保留，可重试或取消");
        }
    }

    void clearSettingsInput() {
        // 光标与屏幕交由 UI 管理，Combat 只负责丢弃游戏输入。
        settingsInput.clear(Minecraft.getInstance());
    }

    public static void releaseSettingsKey(InputConstants.Key key) {
        // 松开与再次按下可能位于同一 tick，释放事件必须立即解除长按屏障。
        var owner = instance;
        if (owner == null) {
            return;
        }
        if (owner.lockViewKey.matches(key)) {
            owner.lockHeld = false;
        }
        if (owner.faceTargetKey.matches(key)) {
            owner.targetHeld = false;
        }
    }

    private void consumeSettingsKeys(Minecraft client) {
        // 长按及系统 repeat 只触发一次，界面打开时仍消费但不执行。
        boolean lockClicked = lockViewKey.consumeClick();
        boolean targetClicked = faceTargetKey.consumeClick();
        while (lockViewKey.consumeClick() || faceTargetKey.consumeClick()) {
            // 同帧重复点击合并，不积存到下次可操作时。
        }
        if (client.gui.screen() == null && client.isWindowActive()
                && CombatViewController.forShoulder(client.player) != null) {
            if (lockClicked && !lockHeld) {
                var result = saveSettings(profiles.view(), profiles.stance().toggleLock());
                actionbar(client, result.message());
            }
            if (targetClicked && !targetHeld) {
                actionbar(client, views.toggleTarget(client));
            }
        }
        lockHeld = lockViewKey.isDown();
        targetHeld = faceTargetKey.isDown();
    }

    private void renderTarget(Minecraft client, net.minecraft.client.gui.GuiGraphicsExtractor context) {
        // 目标反馈遵守十槽 HUD 的隐藏及开屏规则。
        if (client.gui.hud.isHidden() || client.gui.screen() != null) {
            return;
        }
        String name = views.targetName();
        if (!name.isEmpty()) {
            int margin = client.font.lineHeight;
            context.text(client.font, Component.literal("锁定：" + name), margin, margin,
                    net.minecraft.util.ARGB.white(1.0f), true /* shadow */);
        }
    }

    void consumeConfigurationKey(int key) {
        // 只接纳窗口的主键盘数字键，不改变玩家原有键绑定。
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            configurationKeys.add(key);
        }
    }

    private void discardConfigurationClicks(Minecraft client) {
        // 持续按住配置键关屏时，原版后续 repeat 仍可能排队；直到物理松开前只丢弃同键点击。
        var iterator = configurationKeys.iterator();
        while (iterator.hasNext()) {
            int key = iterator.next();
            var event = new net.minecraft.client.input.KeyEvent(key, 0, 0);
            for (var mapping : client.options.keyHotbarSlots) {
                discardMatchingClicks(mapping, event);
            }
            discardMatchingClicks(slotTenKey, event);
            if (!InputConstants.isKeyDown(client.getWindow(), key)) {
                iterator.remove();
            }
        }
    }

    private static void discardMatchingClicks(KeyMapping mapping, net.minecraft.client.input.KeyEvent event) {
        // 按实际按键匹配而不是槽号匹配，兼容玩家自行更改原版快捷栏键位。
        if (mapping.matches(event)) {
            while (mapping.consumeClick()) {
                // 点击仅属于配置窗口，不施放也不切换原版快捷栏。
            }
        }
    }

    private void discardSlotClicks(Minecraft client) {
        // 配置界面的数字输入不能积存到关屏后的第一帧施放或切换快捷栏。
        for (var key : client.options.keyHotbarSlots) {
            while (key.consumeClick()) {
                // 开屏期间原版快捷栏键没有游戏内语义。
            }
        }
        while (slotTenKey.consumeClick()) {
            // 第十槽是独立按键，也必须清空排队次数。
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

    private CombatSlotAccess.Result save(Minecraft client, String successMessage) {
        // 保存结果与命令成功合并提示，避免动作栏后一次消息覆盖写入故障。
        boolean hasSuccessMessage = !successMessage.isEmpty();
        if (!file.writable()) {
            String message = successMessage + "（原文件已保护，仅当前会话生效）";
            actionbar(client, message);
            return new CombatSlotAccess.Result(CombatSlotAccess.Status.SESSION_ONLY, message);
        }
        try {
            file.save(profiles);
        } catch (IOException failure) {
            LOGGER.warn("战斗配置保存失败", failure);
            String message = successMessage + "；战斗配置保存失败，本次设置仅在当前会话生效";
            actionbar(client, message);
            return new CombatSlotAccess.Result(CombatSlotAccess.Status.SESSION_ONLY, message);
        }
        if (hasSuccessMessage) {
            actionbar(client, successMessage);
        }
        return new CombatSlotAccess.Result(CombatSlotAccess.Status.SAVED, successMessage);
    }

    private static void actionbar(Minecraft client, String message) {
        // 操作反馈保留动作栏，与十槽图标同时显示。
        if (client.player != null) {
            client.player.sendOverlayMessage(Component.literal(message));
        }
    }
}
