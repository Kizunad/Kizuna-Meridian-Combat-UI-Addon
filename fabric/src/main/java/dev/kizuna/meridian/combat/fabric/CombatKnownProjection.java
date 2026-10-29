package dev.kizuna.meridian.combat.fabric;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueState;

import java.util.TreeMap;

/** HUD 的只读身体投影；是否真正施放仍由功法服务原子校验。 */
final class CombatKnownProjection {
    private CombatKnownProjection() {
        // 投影不持有身体或客户端槽位。
    }

    static KnownTechniques snapshot(TechniqueRegistry registry, BodySnapshot body) {
        // 每个身份只展示目录中仍存在的最高已学版本，与实际施放选择一致。
        var progress = TechniqueState.read(body);
        var definitions = new TreeMap<String, TechniqueDefinition>();
        for (var learned : progress.getKnownList()) {
            registry.find(learned.getTechnique()).ifPresent(definition -> definitions.merge(
                    definition.getId(), definition, CombatKnownProjection::newer));
        }
        var result = KnownTechniques.newBuilder();
        long now = body.settledAt().elapsedMillis();
        for (var definition : definitions.values()) {
            String id = definition.getId();
            long end = Math.max(progress.getCooldownsOrDefault(id, 0), progress.getCurveEndsOrDefault(id, 0));
            long remaining = Math.max(0, end - now);
            long total = Math.max(definition.getCooldownMillis(), remaining);
            var binding = TechniqueBinder.bind(body, definition);
            var entry = KnownTechnique.newBuilder()
                    .setId(id)
                    .setName(definition.getPresentation().getDescription())
                    .setIconId(definition.getPresentation().getIconId())
                    .setCooldownRemainingMillis(remaining)
                    .setCooldownTotalMillis(total)
                    .setUnavailableReason(reason(binding.getFailure()))
                    .build();
            result.addTechniques(entry);
        }
        return result.build();
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
