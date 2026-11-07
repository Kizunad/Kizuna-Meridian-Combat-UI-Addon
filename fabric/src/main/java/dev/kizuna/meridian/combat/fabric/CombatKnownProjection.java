package dev.kizuna.meridian.combat.fabric;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSide;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueState;

import java.util.TreeMap;
import java.util.HashMap;
import java.util.Map;

/** HUD 的只读身体投影；是否真正施放仍由功法服务原子校验。 */
final class CombatKnownProjection {
    private CombatKnownProjection() {
        // 投影不持有身体或客户端槽位。
    }

    static KnownTechniques snapshot(TechniqueRegistry registry, BodySnapshot body, HandSide mainHandSide) {
        // 每个身份只展示目录中仍存在的最高已学版本，与实际施放选择一致。
        var progress = TechniqueState.read(body);
        var definitions = new TreeMap<String, TechniqueDefinition>();
        for (var learned : progress.getKnownList()) {
            registry.find(learned.getTechnique()).ifPresent(definition -> definitions.merge(
                    definition.getId(), definition, CombatKnownProjection::newer));
        }
        var result = KnownTechniques.newBuilder();
        long now = body.settledAt().elapsedMillis();
        var volumes = volumes(registry);
        for (var definition : definitions.values()) {
            String id = definition.getId();
            long end = Math.max(progress.getCooldownsOrDefault(id, 0), progress.getCurveEndsOrDefault(id, 0));
            long remaining = Math.max(0, end - now);
            TechniqueFailure failure = bindingFailure(body, definition, mainHandSide);
            result.addTechniques(entry(registry, volumes, definition, remaining, failure));
        }
        return result.build();
    }

    private static Map<TechniqueReference, TechniqueVolume> volumes(TechniqueRegistry registry) {
        // 按精确版本索引真实卷册，不从名称或资源路径猜测分类。
        var result = new HashMap<TechniqueReference, TechniqueVolume>();
        for (var volume : registry.volumes()) {
            volume.getTechniquesList().forEach(reference -> result.put(reference, volume));
        }
        return result;
    }

    private static KnownTechnique entry(TechniqueRegistry registry,
                                        Map<TechniqueReference, TechniqueVolume> volumes,
                                        TechniqueDefinition definition, long remaining, TechniqueFailure failure) {
        // 简介与名称分别来自内容声明，仅同步槽位窗口需要的显示字段。
        var presentation = definition.getPresentation();
        var result = KnownTechnique.newBuilder()
                .setId(definition.getId())
                .setName(presentation.getDisplayName())
                .setDescription(presentation.getDescription())
                .setIconId(presentation.getIconId())
                .setCooldownRemainingMillis(remaining)
                .setCooldownTotalMillis(Math.max(definition.getCooldownMillis(), remaining))
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
        return result.build();
    }

    static TechniqueFailure bindingFailure(BodySnapshot body, TechniqueDefinition definition, HandSide mainHandSide) {
        // HUD 只投影身体能力：任一手位可绑定即可展示，实际持物仍由施放服务检查。
        var main = TechniqueBinder.bind(body, definition, Hand.MAIN_HAND, mainHandSide);
        if (main.hasBinding()) {
            return TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED;
        }
        var off = TechniqueBinder.bind(body, definition, Hand.OFF_HAND, mainHandSide);
        if (off.hasBinding()) {
            return TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED;
        }
        return main.getFailure();
    }

    private static TechniqueDefinition newer(TechniqueDefinition first, TechniqueDefinition second) {
        // 同一身份不把目录的新版本自动授予仅学过旧版的玩家。
        if (second.getVersion() > first.getVersion()) {
            return second;
        }
        return first;
    }

    private static String reason(TechniqueFailure failure) {
        // 只描述公开绑定查询能确认的失败，费用、承接和持物保留施放时的权威提示。
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
