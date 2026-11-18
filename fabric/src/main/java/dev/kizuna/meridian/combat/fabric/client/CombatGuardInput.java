package dev.kizuna.meridian.combat.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.combat.core.CombatInput;
import dev.kizuna.meridian.combat.core.GuardInput;
import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniqueRelease;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Supplier;

/** 格挡沿原版可改键映射取物理边沿，不通过 tick 合并点击次数。 */
final class CombatGuardInput {
    private final KeyMapping key;
    private final GuardInput input = new GuardInput();
    private final Supplier<CombatInput.State> stance;
    private final Supplier<KnownTechniques> known;
    private InputConstants.Key heldKey;
    private LocalPlayer player;
    private ClientLevel level;

    CombatGuardInput(KeyMapping.Category category, Supplier<CombatInput.State> stance,
                     Supplier<KnownTechniques> known) {
        // 键位由原版 options 保存，格挡不在十槽另占位置。
        this.stance = stance;
        this.known = known;
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.meridian_combat.guard",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
    }

    boolean keyEvent(InputConstants.Key eventKey, int action) {
        // 松开按原按键识别，即使按住期间改绑也能结束旧按压。
        if (!key.matches(eventKey) && !eventKey.equals(heldKey)) {
            return false;
        }
        var client = Minecraft.getInstance();
        reconcile(client);
        boolean allowed = available(client);
        if (action == GLFW.GLFW_RELEASE) {
            heldKey = null;
            sendRelease(input.release());
        } else if (action == GLFW.GLFW_PRESS) {
            heldKey = eventKey;
            press(client);
        }
        // 原版仍需收到释放以清理架势外开始的物品使用或其他共享键位。
        return allowed && action != GLFW.GLFW_RELEASE;
    }

    private void press(Minecraft client) {
        // 目录投影给出守式身份，客户端不复制具体内容或准入规则。
        String technique = known.get().getTechniquesList().stream()
                .filter(KnownTechnique::getGuard)
                .map(KnownTechnique::getId)
                .findFirst().orElse("");
        boolean allowed = available(client);
        GuardInput.Gate gate = GuardInput.Gate.CLOSED;
        if (allowed && !technique.isEmpty()) {
            gate = GuardInput.Gate.OPEN;
        }
        int pressId = input.press(gate);
        if (pressId != 0) {
            var press = TechniquePress.newBuilder()
                    .setTechniqueId(technique)
                    .setPressId(pressId)
                    .build();
            ClientPlayNetworking.send(CombatChannels.PRESS.payload(press.toByteArray()));
        } else if (allowed && technique.isEmpty()) {
            client.player.sendOverlayMessage(Component.translatable("message.meridian_combat.guard_unavailable"));
        }
    }

    void reconcile(Minecraft client) {
        // 新实体、新世界和不再可输入都算松开；恢复可输入后必须重新按键。
        if (client.player != player || client.level != level || !available(client)
                || heldKey != null && !key.matches(heldKey)) {
            cancel();
        }
        player = client.player;
        level = client.level;
        while (key.consumeClick()) {
            // 原版录入或重新绑定产生的点击不作为游戏按压。
        }
    }

    private boolean available(Minecraft client) {
        // 架势只是客户端输入模式，服务端仍逐次核对身体和施放权限。
        return stance.get() == CombatInput.State.ACTIVE && client.player != null && client.player.isAlive()
                && !client.player.isSpectator() && client.gui.screen() == null && client.isWindowActive()
                && client.getConnection() != null && ClientPlayNetworking.canSend(CombatChannels.PRESS.type())
                && ClientPlayNetworking.canSend(CombatChannels.RELEASE.type());
    }

    void cancel() {
        // 本地先结束按压；功法库接收一次请求后自行完成收势。
        sendRelease(input.cancel());
    }

    private static void sendRelease(int pressId) {
        // 断线时网络已关闭，服务端断线生命周期负责清理施放。
        if (pressId == 0 || Minecraft.getInstance().getConnection() == null
                || !ClientPlayNetworking.canSend(CombatChannels.RELEASE.type())) {
            return;
        }
        var release = TechniqueRelease.newBuilder().setPressId(pressId).build();
        ClientPlayNetworking.send(CombatChannels.RELEASE.payload(release.toByteArray()));
    }

    void disconnect() {
        // 不向后来连接发送旧松开，也不把按压序号跨连接保存。
        input.disconnect();
        heldKey = null;
        player = null;
        level = null;
    }
}
