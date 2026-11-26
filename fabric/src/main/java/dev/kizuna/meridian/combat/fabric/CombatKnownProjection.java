package dev.kizuna.meridian.combat.fabric;

import dev.kizuna.meridian.combat.core.CombatHud;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.BlockKind;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BindingOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSide;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueBinding;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.server.TechniqueAvailability;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueBreathingPractice;
import dev.kizuna.meridian.technique.server.TechniqueKnowledge;
import dev.kizuna.meridian.technique.server.TechniqueReferenceText;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/** HUD 的只读身体投影；是否真正施放仍由功法服务原子校验。 */
final class CombatKnownProjection {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_combat");

    private CombatKnownProjection() {
        // 投影不持有身体或客户端槽位。
    }

    /** gameTick 是玩家所在世界当前的总 tick；冷却按它换算成结束 tick，身体时间和世界同步走时这个数不变。 */
    static KnownTechniques snapshot(TechniqueRegistry registry, BodySnapshot body, HeldItems items,
                                    long gameTick) {
        // 每个身份只展示目录中仍存在的最高已学或天生版本，与实际施放选择一致。
        HandSide mainHandSide = items.getMainHandSide();
        var progress = TechniqueState.read(body);
        var result = KnownTechniques.newBuilder();
        long now = body.settledAt().elapsedMillis();
        var volumes = volumes(registry);
        var practice = TechniqueBreathingPractice.active(body);
        for (var definition : knownDefinitions(registry, body)) {
            String id = definition.getId();
            long end = Math.max(progress.getCooldownsOrDefault(id, 0), progress.getCurveEndsOrDefault(id, 0));
            long remaining = Math.max(0, end - now);
            long endTick = 0;
            if (remaining > 0) {
                // 向上取整到 tick：客户端不会比服务端先显示可用。
                endTick = gameTick + (remaining + CombatHud.MILLIS_PER_TICK - 1) / CombatHud.MILLIS_PER_TICK;
            }
            BindingOutcome outcome = binding(body, definition, mainHandSide);
            var entry = entry(registry, volumes, definition, endTick, outcome.getFailure());
            if (definition.hasMaintainedBreathing()) {
                entry.setMaintainedBreathing(true /* maintainedBreathing */);
                practice.filter(active -> active.getTechniqueId().equals(id))
                        .ifPresent(active -> entry.setActivePracticeId(active.getCastId()));
            }
            if (outcome.hasBinding()) {
                // 先看手里的东西对不对，再看真元和力气：兵器都没拿对时，报缺真元没有用。
                BlockKind block = BlockKind.BLOCK_ITEM;
                if (TechniqueAvailability.holdable(registry, definition, items)) {
                    block = shortage(body, definition, mainHandSide);
                }
                entry.setBlock(block).setUnavailableReason(shortageText(block));
            } else {
                entry.setBlock(BlockKind.BLOCK_BODY);
            }
            addDetails(entry, registry, definition, body, outcome);
            result.addTechniques(entry.build());
        }
        return result.build();
    }

    private static Iterable<TechniqueDefinition> knownDefinitions(TechniqueRegistry registry, BodySnapshot body) {
        // 同一标识只投影最高已学版本，并按标识排序。
        var definitions = new TreeMap<String, TechniqueDefinition>();
        for (var reference : TechniqueKnowledge.references(registry, body)) {
            registry.find(reference).ifPresent(definition -> definitions.merge(
                    definition.getId(), definition, CombatKnownProjection::newer));
        }
        return definitions.values();
    }

    private static void addDetails(KnownTechnique.Builder entry, TechniqueRegistry registry,
                                   TechniqueDefinition definition, BodySnapshot body, BindingOutcome outcome) {
        // 部位说明依赖绑定。任一段说明生成失败时清掉已写入的行，基础条目仍可发送。
        try {
            entry.addAllDetails(TechniqueReferenceText.lines(registry, definition));
            if (outcome.hasBinding()) {
                entry.addAllDetails(TechniqueReferenceText.modules(body, outcome.getBinding()));
            }
        } catch (RuntimeException failure) {
            entry.clearDetails();
            LOGGER.warn("无法生成已学功法说明：{}", definition.getId(), failure);
        }
    }

    private static Map<TechniqueReference, TechniqueVolume> volumes(TechniqueRegistry registry) {
        // 按精确版本索引真实卷册，不从名称或资源路径猜测分类。
        var result = new HashMap<TechniqueReference, TechniqueVolume>();
        for (var volume : registry.volumes()) {
            volume.getTechniquesList().forEach(reference -> result.put(reference, volume));
        }
        return result;
    }

    private static KnownTechnique.Builder entry(TechniqueRegistry registry,
                                        Map<TechniqueReference, TechniqueVolume> volumes,
                                        TechniqueDefinition definition, long endTick, TechniqueFailure failure) {
        // 简介与名称分别来自内容声明，仅同步槽位窗口需要的显示字段。
        var presentation = definition.getPresentation();
        var result = KnownTechnique.newBuilder()
                .setId(definition.getId())
                .setName(presentation.getDisplayName())
                .setDescription(presentation.getDescription())
                .setIconId(presentation.getIconId())
                .setCooldownEndTick(endTick)
                .setCooldownTotalMillis(definition.getCooldownMillis())
                .setGuard(CombatCasting.heldGuard(definition))
                .setUnavailableReason(reason(failure));
        var reference = TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
        var volume = volumes.get(reference);
        if (volume != null) {
            result.setVolumeId(volume.getId());
            result.setVolumeName(volume.getDisplayName());
            registry.bookOf(reference).ifPresent(book -> result.setBookName(book.getDisplayName()));
        }
        return result;
    }

    static TechniqueFailure bindingFailure(BodySnapshot body, TechniqueDefinition definition, HandSide mainHandSide) {
        // HUD 只投影身体能力：任一手位可绑定即可展示，实际持物仍由施放服务检查。
        return binding(body, definition, mainHandSide).getFailure();
    }

    private static BindingOutcome binding(BodySnapshot body, TechniqueDefinition definition, HandSide mainHandSide) {
        // 先试主手，再试副手；都绑不上时报主手的原因。
        var main = TechniqueBinder.bind(body, definition, Hand.MAIN_HAND, mainHandSide);
        if (main.hasBinding()) {
            return main;
        }
        var off = TechniqueBinder.bind(body, definition, Hand.OFF_HAND, mainHandSide);
        if (off.hasBinding()) {
            return off;
        }
        return main;
    }

    /**
     * 身体绑得上、手里的东西也对以后，再用和起手相同的只读预检看真元付不付得起、筋肉脏腑做不做得了这份功。
     * 承接和着地仍由施放时校验。
     */
    private static BlockKind shortage(BodySnapshot body, TechniqueDefinition definition, HandSide mainHandSide) {
        var bindings = new ArrayList<TechniqueBinding>();
        for (Hand hand : List.of(Hand.MAIN_HAND, Hand.OFF_HAND)) {
            var outcome = TechniqueBinder.bind(body, definition, hand, mainHandSide);
            if (outcome.hasBinding()) {
                bindings.add(outcome.getBinding());
            }
        }
        return shortage(bindings, binding -> TechniqueAvailability.affordable(body, definition, binding),
                binding -> TechniqueAvailability.workable(body, definition, binding));
    }

    /** 两只手各自的绑定里有一个付得起也做得了，就放得出来；都不行时，有付不起真元的报真元，否则报力气。 */
    static BlockKind shortage(List<TechniqueBinding> bindings, Predicate<TechniqueBinding> affordable,
                              Predicate<TechniqueBinding> workable) {
        boolean paid = true;
        for (TechniqueBinding binding : bindings) {
            if (!affordable.test(binding)) {
                paid = false;
            } else if (workable.test(binding)) {
                return BlockKind.BLOCK_NONE;
            }
        }
        if (bindings.isEmpty()) {
            return BlockKind.BLOCK_NONE;
        }
        if (paid) {
            return BlockKind.BLOCK_EXERTION;
        }
        return BlockKind.BLOCK_QI;
    }

    private static String shortageText(BlockKind shortage) {
        return switch (shortage) {
            case BLOCK_QI -> "真元不济";
            case BLOCK_EXERTION -> "气力不继";
            case BLOCK_ITEM -> "手里的东西不对";
            default -> "";
        };
    }

    /** 这份投影里有没有会随时间自己恢复的不可用（真元、力气）：有的话身体读数没变也要接着重算。 */
    static boolean recovering(KnownTechniques snapshot) {
        return snapshot.getTechniquesList().stream().anyMatch(entry ->
                entry.getBlock() == BlockKind.BLOCK_QI || entry.getBlock() == BlockKind.BLOCK_EXERTION);
    }

    private static TechniqueDefinition newer(TechniqueDefinition first, TechniqueDefinition second) {
        // 同一身份不把目录的新版本自动授予仅学过旧版的玩家。
        if (second.getVersion() > first.getVersion()) {
            return second;
        }
        return first;
    }

    private static String reason(TechniqueFailure failure) {
        // 身体绑定这一步的失败；持物、真元和力气见 snapshot 和 shortage，承接保留施放时的权威提示。
        return switch (failure) {
            case TECHNIQUE_FAILURE_UNSPECIFIED -> "";
            case BODY_UNAVAILABLE -> "身体不可用";
            case REALM_TOO_LOW -> "境界不足";
            case MERIDIAN_NOT_OPENED -> "经脉未通";
            case INSUFFICIENT_CAPABILITY -> "力量不足";
            default -> "身体未准备好";
        };
    }
}
