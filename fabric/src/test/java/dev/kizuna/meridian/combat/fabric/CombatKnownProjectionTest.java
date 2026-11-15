package dev.kizuna.meridian.combat.fabric;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.BlockKind;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** HUD 的相对手臂投影与身体失能回归，不需要游戏世界或独立身体账本。 */
public final class CombatKnownProjectionTest {
    private static final double CAPACITY = 100;
    private static final double LIFESPAN = 1000;
    private static final String LEFT = "left";
    private static final String RIGHT = "right";

    @Test
    public void usableOffHandBindingRemainsAvailableForEitherMainHandSide() {
        // 主手绑定失能时仍检查副手，左右惯用手都用宿主的实际解剖侧。
        TechniqueDefinition technique = technique();
        for (HandSide side : List.of(HandSide.LEFT, HandSide.RIGHT)) {
            String healthy = RIGHT;
            if (side == HandSide.LEFT) {
                healthy = LEFT;
            }
            BodySnapshot body = body(healthy);
            assertFalse(TechniqueBinder.bind(body, technique, Hand.MAIN_HAND, side).hasBinding());
            assertEquals(TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                    CombatKnownProjection.bindingFailure(body, technique, side));
        }
    }

    @Test
    public void missingOrUnavailableArmsStayUnavailable() {
        // 新入口修复缺少手位事实，不能把真实双臂失能误标为可施放。
        BodySnapshot unavailable = body("");
        TechniqueDefinition technique = technique();
        assertEquals(TechniqueFailure.MODULE_UNAVAILABLE,
                CombatKnownProjection.bindingFailure(unavailable, technique, HandSide.RIGHT));
        assertEquals(TechniqueFailure.INVALID_REQUEST,
                CombatKnownProjection.bindingFailure(body(RIGHT), technique, HandSide.HAND_SIDE_UNSPECIFIED));
    }

    @Test
    public void shortageNamesQiBeforeStrengthAndEitherUsableHandClearsIt() {
        // 两只手的绑定各问一遍「付得起吗」「做得了吗」：有一只都行就不算缺；都不行时，有付不起的报真元，否则报力气。
        TechniqueBinding main = TechniqueBinding.newBuilder().setBodyId("main").build();
        TechniqueBinding off = TechniqueBinding.newBuilder().setBodyId("off").build();
        var both = List.of(main, off);
        assertEquals(BlockKind.BLOCK_NONE, CombatKnownProjection.shortage(both, binding -> true, binding -> true));
        assertEquals(BlockKind.BLOCK_NONE, CombatKnownProjection.shortage(both, off::equals, off::equals));
        assertEquals(BlockKind.BLOCK_QI, CombatKnownProjection.shortage(both, binding -> false, binding -> true));
        assertEquals(BlockKind.BLOCK_QI, CombatKnownProjection.shortage(both, main::equals, binding -> false));
        assertEquals(BlockKind.BLOCK_EXERTION, CombatKnownProjection.shortage(both, binding -> true, binding -> false));
        assertEquals(BlockKind.BLOCK_EXERTION, CombatKnownProjection.shortage(List.of(main), binding -> true, binding -> false));
        assertEquals(BlockKind.BLOCK_NONE, CombatKnownProjection.shortage(List.of(), binding -> false, binding -> false));
    }

    @Test
    public void onlyQiAndStrengthShortagesKeepTheProjectionRecomputing() {
        // 真元、力气会自己恢复，投影里有它们时要接着重算；身体绑不上的不会自己好，不用。
        var plain = KnownTechnique.newBuilder().setId("projection_test:shot").build();
        assertFalse(CombatKnownProjection.recovering(known(plain)));
        assertFalse(CombatKnownProjection.recovering(known(plain.toBuilder().setBlock(BlockKind.BLOCK_BODY).build())));
        assertTrue(CombatKnownProjection.recovering(known(plain.toBuilder().setBlock(BlockKind.BLOCK_QI).build())));
        assertTrue(CombatKnownProjection.recovering(known(plain.toBuilder().setBlock(BlockKind.BLOCK_EXERTION).build())));
    }

    private static KnownTechniques known(KnownTechnique entry) {
        return KnownTechniques.newBuilder().addTechniques(entry).build();
    }

    private static TechniqueDefinition technique() {
        // 拉弦要求与握持手相反的一条活体组织，隔离经脉和费用以检查绑定缺陷。
        RelativeArmSlot arm = RelativeArmSlot.newBuilder()
                .setArm(DrawStrengthArm.OTHER_ARM)
                .addLeftModuleIds(LEFT)
                .addRightModuleIds(RIGHT)
                .build();
        TechniqueSlot slot = TechniqueSlot.newBuilder()
                .setId("draw")
                .setModule(ModuleKind.TISSUE)
                .setKind("projection_test:muscle")
                .setMinimumCount(1)
                .setMaximumCount(1)
                .setRelativeArm(arm)
                .build();
        return TechniqueDefinition.newBuilder()
                .setId("projection_test:shot")
                .setMinimumRealm(Realm.TRUE_QI)
                .addSlots(slot)
                .build();
    }

    private static BodySnapshot body(String healthyArm) {
        // 单主体双组织使用真实协议校验；组织 HP 决定哪条臂可用。
        TemplateReference template = TemplateReference.newBuilder()
                .setId("projection_test:body")
                .setVersion(1)
                .build();
        VitalityState vitality = VitalityState.newBuilder()
                .setLifespanLimitSeconds(LIFESPAN)
                .setAgeSeconds(0)
                .setSpentLifespanSeconds(0)
                .setDead(false /* dead */)
                .build();
        TimeCursor time = TimeCursor.newBuilder()
                .setDomain("projection_test:world")
                .setEpoch("test")
                .setElapsedMillis(0)
                .build();
        PartState part = PartState.newBuilder()
                .setId("body")
                .setHp(CAPACITY)
                .build();
        BodyMessages.BodySnapshot snapshot = BodyMessages.BodySnapshot.newBuilder()
                .setSchemaVersion(BodySnapshot.CURRENT_SCHEMA_VERSION)
                .setId("projection-test")
                .setTemplate(template)
                .setStructure(structure())
                .setRealm(Realm.TRUE_QI)
                .setVitality(vitality)
                .setSettledAt(time)
                .setRevision(0)
                .addPartStates(part)
                .addTissueStates(tissueState(LEFT, healthyArm))
                .addTissueStates(tissueState(RIGHT, healthyArm))
                .build();
        return BodyProtobuf.fromMessage(snapshot);
    }

    private static BodyStructure structure() {
        // 只声明绑定所需的公共组织能力，测试不依赖 Human 模板。
        PartDefinition part = PartDefinition.newBuilder()
                .setId("body")
                .setDisplayName("主体")
                .setMaxHp(CAPACITY)
                .build();
        return BodyStructure.newBuilder()
                .addParts(part)
                .addTissues(tissue(LEFT))
                .addTissues(tissue(RIGHT))
                .build();
    }

    private static TissueDefinition tissue(String id) {
        // 两侧具有相同基础能力，差别仅来自当前 HP。
        TissueCapacity capacity = TissueCapacity.newBuilder()
                .setStrengthNewtons(CAPACITY)
                .setBurstWatts(CAPACITY)
                .setEnduranceJoules(CAPACITY)
                .build();
        return TissueDefinition.newBuilder()
                .setId(id)
                .setDisplayName(id)
                .setPartId("body")
                .setKind("projection_test:muscle")
                .setMaxHp(CAPACITY)
                .setFatigueCapacity(CAPACITY)
                .setCapacity(capacity)
                .build();
    }

    private static TissueState tissueState(String id, String healthyArm) {
        // 空身份表示双臂失能；不设置与绑定无关的成长或做功状态。
        double hp = 0;
        if (id.equals(healthyArm)) {
            hp = CAPACITY;
        }
        return TissueState.newBuilder()
                .setId(id)
                .setHp(hp)
                .setFatigue(0)
                .build();
    }
}
