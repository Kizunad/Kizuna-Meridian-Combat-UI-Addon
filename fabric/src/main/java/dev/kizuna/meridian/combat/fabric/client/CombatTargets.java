package dev.kizuna.meridian.combat.fabric.client;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 实际渲染相机中心射线与实体生命周期适配，不改变原版攻击命中。 */
final class CombatTargets {
    private Entity target;
    private UUID identity;

    Entity current() {
        // 仅供同一控制器读取，外部设置门面不暴露实体引用。
        return target;
    }

    void clear() {
        // 清理后从不自动选取或恢复目标。
        target = null;
        identity = null;
    }

    void validate(Minecraft client, double radius) {
        // 同时检查引用、UUID、数字 ID 当前映射和世界，防止卸载后 ID 重用。
        if (target != null && (!valid(client, target, radius) || !target.getUUID().equals(identity)
                || client.level.getEntity(target.getId()) != target)) {
            clear();
        }
    }

    String toggle(Minecraft client, double radius) {
        // 已有目标时只解除，第二次独立按下才会重新选择。
        if (target != null) {
            clear();
            return "已解除目标锁定";
        }
        target = select(client, radius);
        if (target == null) {
            return "未找到可锁定目标";
        }
        identity = target.getUUID();
        return "锁定：" + target.getName().getString();
    }

    static Entity select(Minecraft client, double radius) {
        // 延伸长度包含相机到玩家距离，最终范围始终由玩家位置裁定。
        var camera = client.gameRenderer.mainCamera();
        Vec3 start = camera.position();
        double length = radius + start.distanceTo(client.player.position());
        Vec3 end = start.add(new Vec3(camera.forwardVector()).scale(length));
        var trace = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player);
        var block = client.level.clip(trace);
        double nearest = start.distanceToSqr(block.getLocation());
        Entity selected = null;
        var search = new AABB(start, end).inflate(1);
        for (var candidate : client.level.getEntities(client.player, search, entity -> valid(client, entity, radius))) {
            var bounds = candidate.getBoundingBox();
            var hit = bounds.clip(start, end);
            double distance;
            if (bounds.contains(start)) {
                distance = 0;
            } else if (hit.isPresent()) {
                distance = start.distanceToSqr(hit.get());
            } else {
                continue;
            }
            if (distance < nearest) {
                nearest = distance;
                selected = candidate;
            }
        }
        return selected;
    }

    private static boolean valid(Minecraft client, Entity entity, double radius) {
        // pickable 排除展示和特效实体；生物还需要活着，但允许有效非生物实体。
        return client.player != null && client.level != null && entity != client.player
                && entity.level() == client.level && !entity.isRemoved() && !entity.isSpectator()
                && entity.isPickable() && (!(entity instanceof LivingEntity living) || living.isAlive())
                && client.player.position().distanceToSqr(entity.position()) <= radius * radius;
    }

    static Vec3 aim(Entity entity) {
        // 生物朝向眼位，其他可选实体朝向包围盒中心。
        if (entity instanceof LivingEntity) {
            return entity.getEyePosition();
        }
        return entity.getBoundingBox().getCenter();
    }
}
