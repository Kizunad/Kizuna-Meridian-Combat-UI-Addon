package dev.kizuna.meridian.combat.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** 编辑边界丢弃排队输入，并要求仍按住的物理键先释放。 */
final class CombatScreenInput {
    private final Set<InputConstants.Key> blocked = new HashSet<>();

    void clear(Minecraft client) {
        // 清理所有原版和模组映射，避免移动、攻击、施放在关屏首帧漏出。
        for (var mapping : client.options.keyMappings) {
            var key = InputConstants.getKey(mapping.saveString());
            if (physicalDown(client, key)) {
                blocked.add(key);
            }
            discard(mapping);
        }
    }

    void tick(Minecraft client) {
        // repeat 不能重新激活刚录入或编辑的键，只有物理释放才移除屏障。
        blocked.removeIf(key -> !physicalDown(client, key));
        for (var mapping : client.options.keyMappings) {
            if (blocked.contains(InputConstants.getKey(mapping.saveString()))) {
                discard(mapping);
            }
        }
    }

    private static void discard(KeyMapping mapping) {
        // 点击计数和按住状态分别清理，不能只消费其中一项。
        mapping.setDown(false /* pressed */);
        while (mapping.consumeClick()) {
            // 消费残留点击不产生游戏行为。
        }
    }

    private static boolean physicalDown(Minecraft client, InputConstants.Key key) {
        // 未绑定和扫描码不交给 GLFW 键码查询，鼠标独立检查。
        var type = key.getType();
        int value = key.getValue();
        if (type == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().handle(), value) == GLFW.GLFW_PRESS;
        }
        return type == InputConstants.Type.KEYSYM && value != GLFW.GLFW_KEY_UNKNOWN
                && InputConstants.isKeyDown(client.getWindow(), value);
    }
}
