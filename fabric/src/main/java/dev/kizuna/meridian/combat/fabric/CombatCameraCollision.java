package dev.kizuna.meridian.combat.fabric;

import dev.kizuna.meridian.combat.core.CombatCamera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 原版方块形状探测适配器；客户端镜头和服务端几何测试复用同一实现。 */
public final class CombatCameraCollision {
    private CombatCameraCollision() {
        // 碰撞探测不保存世界引用。
    }

    public static CombatCamera.Offset clip(BlockGetter world, Entity entity, Vec3 eye, CombatCamera.Offset desired) {
        // 与原版相机一致使用 VISUAL 方块形状，不把流体当作墙。
        return CombatCamera.clip(desired, (start, end) -> {
            Vec3 from = eye.add(start.x(), start.y(), start.z());
            Vec3 to = eye.add(end.x(), end.y(), end.z());
            var context = new ClipContext(from, to, ClipContext.Block.VISUAL,
                    ClipContext.Fluid.NONE, entity);
            var hit = world.clip(context);
            if (hit.getType() == HitResult.Type.MISS) {
                return 1;
            }
            return from.distanceTo(hit.getLocation()) / from.distanceTo(to);
        });
    }
}
