package dev.kizuna.meridian.combat.fabric;

import dev.kizuna.meridian.combat.core.CombatCamera;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;

/** 原版方块形状探测适配器；客户端镜头和服务端几何测试复用同一实现。 */
public final class CombatCameraCollision {
    private CombatCameraCollision() {
        // 碰撞探测不保存世界引用。
    }

    public static CombatCamera.Offset clip(BlockView world, Entity entity, Vec3d eye, CombatCamera.Offset desired) {
        // 与原版相机一致使用 VISUAL 方块形状，不把流体当作墙。
        return CombatCamera.clip(desired, (start, end) -> {
            Vec3d from = eye.add(start.x(), start.y(), start.z());
            Vec3d to = eye.add(end.x(), end.y(), end.z());
            var context = new RaycastContext(from, to, RaycastContext.ShapeType.VISUAL,
                    RaycastContext.FluidHandling.NONE, entity);
            var hit = world.raycast(context);
            if (hit.getType() == HitResult.Type.MISS) {
                return 1;
            }
            return from.distanceTo(hit.getPos()) / from.distanceTo(to);
        });
    }
}
