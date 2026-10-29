package dev.kizuna.meridian.combat.gametest;

import dev.kizuna.meridian.combat.core.CombatCamera;
import dev.kizuna.meridian.combat.core.CombatView;
import dev.kizuna.meridian.combat.core.CombatViewSettings;
import dev.kizuna.meridian.combat.fabric.CombatCameraCollision;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** 在真实方块世界核对镜头遮挡和人物射线，服务端不加载客户端 Mixin。 */
public final class CombatViewGameTest implements FabricGameTest {
    private static final BlockPos EYE_BLOCK = new BlockPos(3, 3, 3);
    private static final BlockPos BACK_WALL = new BlockPos(3, 3, 1);
    private static final BlockPos SIDE_WALL = new BlockPos(2, 3, 3);
    private static final BlockPos FACING_WALL = new BlockPos(1, 3, 3);
    private static final BlockPos PLAYER_FEET = new BlockPos(3, 2, 3);
    private static final float WEST_YAW = 90;
    private static final double REACH = 4;
    private static final double CENTER = 0.5;
    private static final double EPSILON = 0.000001;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void cameraClipsAgainstBackAndShoulderWalls(TestContext context) {
        // 真实 VISUAL 方块形状阻挡后方和右肩偏移，拆除遮挡后立即恢复原距离。
        try (var fixture = new CombatTestPlayer(context)) {
            var world = context.getWorld();
            Vec3d eye = Vec3d.ofCenter(context.getAbsolutePos(EYE_BLOCK));
            var desired = CombatCamera.desired(new CombatView.Angles(0, 0), CombatViewSettings.DEFAULT);
            context.setBlockState(BACK_WALL, Blocks.STONE);
            var back = CombatCameraCollision.clip(world, fixture.player, eye, desired);
            double backSurface = context.getAbsolutePos(BACK_WALL).getZ() + 1;
            context.assertTrue(eye.z + back.z() > backSurface + CombatCamera.PROBE_RADIUS,
                    "镜头越过后墙或没有留出近裁面空间");
            context.assertTrue(Math.abs(back.z()) < Math.abs(desired.z()), "后墙没有拉近镜头");
            context.setBlockState(BACK_WALL, Blocks.AIR);
            sideWall(context, Blocks.STONE);
            var side = CombatCameraCollision.clip(world, fixture.player, eye, desired);
            double sideSurface = context.getAbsolutePos(SIDE_WALL).getX() + 1;
            context.assertTrue(eye.x + side.x() > sideSurface + CombatCamera.PROBE_RADIUS,
                    "肩侧墙壁没有拉近镜头");
            context.assertTrue(Math.abs(side.z()) < Math.abs(desired.z()), "侧墙未收缩整段偏移");
            sideWall(context, Blocks.AIR);
            var clear = CombatCameraCollision.clip(world, fixture.player, eye, desired);
            context.assertTrue(clear.equals(desired), "遮挡移除后没有恢复完整偏移");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void rayAndCastFollowEntityFacingDespiteCameraOrbit(TestContext context) {
        // 镜头朝北、人物朝西时，原版方块射线和正式施放方向仍朝西。
        try (var fixture = new CombatTestPlayer(context)) {
            var feet = context.getAbsolutePos(PLAYER_FEET);
            fixture.player.setPosition(feet.getX() + CENTER, feet.getY(), feet.getZ() + CENTER);
            fixture.player.setYaw(WEST_YAW);
            fixture.player.setHeadYaw(WEST_YAW);
            fixture.player.setBodyYaw(WEST_YAW);
            fixture.player.setPitch(0);
            var view = new CombatView(CombatView.Mode.SHOULDER);
            view.enter(CombatView.Perspective.FIRST_PERSON, new CombatView.Angles(WEST_YAW, 0));
            view.rotate(WEST_YAW, 0);
            context.setBlockState(FACING_WALL, Blocks.STONE);
            context.setBlockState(BACK_WALL, Blocks.STONE);
            var hit = fixture.player.raycast(REACH, 1, false /* includeFluids */);
            context.assertTrue(hit instanceof BlockHitResult block
                    && block.getBlockPos().equals(context.getAbsolutePos(FACING_WALL)),
                    "选中射线未沿真实人物朝向");
            context.assertTrue(fixture.player.getYaw() == WEST_YAW && fixture.player.getPitch() == 0,
                    "镜头绕看改变了服务端人物朝向");
            assertCharacterCast(context, fixture, view);
        }
        context.complete();
    }

    private static void assertCharacterCast(TestContext context, CombatTestPlayer fixture, CombatView view) {
        // 正式通道和功法服务收到的方向应等于人物当前朝向，与按键目标和镜头无关。
        fixture.learn();
        var direction = view.castDirection(-1, 1, fixture.player.getYaw());
        var request = TechniquePress.newBuilder()
                .setTechniqueId(CombatTestContent.TECHNIQUE)
                .setMoveX(direction.x())
                .setMoveZ(direction.z())
                .build();
        fixture.press(request.toByteArray());
        var cast = TechniqueState.read(fixture.body.current()).getActiveCast().getRequest();
        var actual = cast.getDirection();
        context.assertTrue(Math.abs(actual.getX() + 1) < EPSILON && Math.abs(actual.getZ()) < EPSILON,
                "正式施放沿镜头或移动目标方向发出");
    }

    private static void sideWall(TestContext context, Block block) {
        // 侧墙覆盖整段后退路径，不能只在眼位旁摆一个不会命中的方块。
        int wallX = SIDE_WALL.getX();
        int wallY = SIDE_WALL.getY();
        int endZ = SIDE_WALL.getZ();
        for (int z = 0; z <= endZ; z++) {
            context.setBlockState(new BlockPos(wallX, wallY, z), block);
        }
    }
}
