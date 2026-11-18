package dev.kizuna.meridian.combat.gametest;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.AddonMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;

import java.util.List;
import java.util.UUID;

/** 测试专用无费用功法与最小身体，不进入发行包。 */
final class CombatTestContent {
    static final String TECHNIQUE = "combat_test:step";
    static final String OTHER = "combat_test:other";
    static final String GUARD = "combat_test:guard";
    static final long GUARD_WARMUP = 200;
    static final long GUARD_RECOVERY = 300;
    private static final double GUARD_ANGLE = 120;
    private static final double GUARD_MULTIPLIER = 0.7;
    static final long WARMUP_MILLIS = 100;
    static final long COOLDOWN_MILLIS = 2000;
    private static final double MAX_HP = 100;
    static final double LIFESPAN_SECONDS = 100_000;

    private CombatTestContent() {
        // 内容只服务于公开服务和网络接线测试。
    }

    static TechniqueRegistry registry() {
        // 同一标识同时存在两个版本，验证只学旧版时不会自动提升权限。
        TechniqueDefinition first = definition(TECHNIQUE, 1);
        TechniqueDefinition newer = definition(TECHNIQUE, 2);
        TechniqueDefinition other = definition(OTHER, 1);
        TechniqueCatalog catalog = TechniqueCatalog.newBuilder()
                .setSchemaVersion(1)
                .setNamespace("combat_test")
                .addBindingTemplates(templateReference())
                .addDefinitions(first)
                .addDefinitions(newer)
                .addDefinitions(other)
                .addDefinitions(guard())
                .build();
        return TechniqueRegistry.load(addons(), List.of(catalog));
    }

    private static TechniqueDefinition guard() {
        // 天生守式只验证输入与身份，不依赖正式内容或人体挡位。
        CastPhases phases = CastPhases.newBuilder()
                .setWarmupMillis(GUARD_WARMUP)
                .setActiveDuration(ActiveDuration.ACTIVE_DURATION_UNTIL_RELEASE)
                .setRecoveryMillis(GUARD_RECOVERY)
                .setInterruptibleByDamage(true /* interruptibleByDamage */)
                .build();
        Guard guard = Guard.newBuilder()
                .setAngle(quantity(GUARD_ANGLE, Unit.DEGREE))
                .setDamageMultiplier(quantity(GUARD_MULTIPLIER, Unit.RATIO))
                .addDamageTags("combat_test:blockable")
                .build();
        TechniqueEffect effect = TechniqueEffect.newBuilder()
                .setAtMillis(GUARD_WARMUP)
                .setGuard(guard)
                .build();
        return definition(GUARD, 1).toBuilder()
                .setCategory(Category.DEFENSE)
                .setInnate(true /* innate */)
                .setPhases(phases)
                .setCooldownMillis(0)
                .setAiHints(guardHints())
                .addEffects(effect)
                .build();
    }

    private static AiHints guardHints() {
        // 守式角色与持续模式成组，测试不接入普通辅助选式。
        SupportAiHints support = SupportAiHints.newBuilder().setRole(AiRole.AI_ROLE_GUARD).build();
        return AiHints.newBuilder()
                .setMinimumDistance(quantity(0, Unit.METER))
                .setMaximumDistance(quantity(1, Unit.METER))
                .setMaximumHpRatio(quantity(1, Unit.RATIO))
                .setMinimumQiRatio(quantity(0, Unit.RATIO))
                .setCategoryWeight(quantity(1, Unit.RATIO))
                .setSupport(support)
                .build();
    }

    private static Quantity quantity(double value, Unit unit) {
        // 显式单位通过正式目录校验。
        return Quantity.newBuilder().setValue(value).setUnit(unit).build();
    }

    static BodySnapshot body(UUID uuid) {
        // 每个玩家具有唯一身体，生命与时钟通过 Framework 的真实编解码校验。
        VitalityState vitality = VitalityState.newBuilder()
                .setLifespanLimitSeconds(LIFESPAN_SECONDS)
                .setAgeSeconds(0)
                .setSpentLifespanSeconds(0)
                .setDead(false /* dead */)
                .build();
        TimeCursor time = TimeCursor.newBuilder()
                .setDomain("combat_test:world")
                .setEpoch("test")
                .setElapsedMillis(0)
                .build();
        PartState part = PartState.newBuilder()
                .setId("body")
                .setHp(MAX_HP)
                .build();
        BodyMessages.BodySnapshot snapshot = BodyMessages.BodySnapshot.newBuilder()
                .setSchemaVersion(BodySnapshot.CURRENT_SCHEMA_VERSION)
                .setId("test-" + uuid)
                .setTemplate(templateReference())
                .setStructure(structure())
                .setRealm(Realm.TRUE_QI)
                .setVitality(vitality)
                .setSettledAt(time)
                .setRevision(0)
                .addPartStates(part)
                .build();
        return BodyProtobuf.fromMessage(snapshot);
    }

    private static TechniqueDefinition definition(String id, int version) {
        // 短起手加长冷却，允许测试完成施放后仍被冷却拒绝。
        CastPhases phases = CastPhases.newBuilder()
                .setWarmupMillis(WARMUP_MILLIS)
                .setActiveMillis(0)
                .setRecoveryMillis(0)
                .setInterruptibleByDamage(true /* interruptibleByDamage */)
                .build();
        Presentation presentation = Presentation.newBuilder()
                .setDisplayName("测试轻步")
                .setDescription("迈出轻盈的一步。")
                .setIconId("combat_test:step")
                .build();
        return TechniqueDefinition.newBuilder()
                .setId(id)
                .setVersion(version)
                .setCategory(Category.MOVEMENT)
                .setMinimumRealm(Realm.WANDERING_QI)
                .setCost(TechniqueCost.getDefaultInstance())
                .setPhases(phases)
                .setCooldownMillis(COOLDOWN_MILLIS)
                .setPresentation(presentation)
                .build();
    }

    private static AddonRegistry addons() {
        // 模板注册同样经过框架校验，不绕过附属装配契约。
        ContentVersionRange versions = ContentVersionRange.newBuilder()
                .setMinimum(1)
                .setMaximum(1)
                .build();
        AddonManifest manifest = AddonManifest.newBuilder()
                .setNamespace("combat_test")
                .setContentVersion(1)
                .setApiVersions(versions)
                .addSupportedHosts("test:server")
                .build();
        BodyTemplate template = BodyTemplate.newBuilder()
                .setReference(templateReference())
                .setStructure(structure())
                .build();
        AddonBundle bundle = AddonBundle.newBuilder()
                .setSchemaVersion(1)
                .setManifest(manifest)
                .addTemplates(template)
                .build();
        AddonHostProfile host = AddonHostProfile.newBuilder()
                .setHostId("test:server")
                .setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)
                .build();
        AddonRegistration registration = AddonRegistration.newBuilder()
                .setProtocolVersion(1)
                .setHost(host)
                .addBundles(bundle)
                .build();
        return AddonRegistry.load(registration);
    }

    private static TemplateReference templateReference() {
        // 测试模板身份固定，身体身份按玩家独立生成。
        return TemplateReference.newBuilder()
                .setId("combat_test:body")
                .setVersion(1)
                .build();
    }

    private static BodyStructure structure() {
        // 没有费用或模块需求，单个主体足以验证通道与权限。
        PartDefinition part = PartDefinition.newBuilder()
                .setId("body")
                .setDisplayName("测试主体")
                .setMaxHp(MAX_HP)
                .build();
        return BodyStructure.newBuilder().addParts(part).build();
    }
}
