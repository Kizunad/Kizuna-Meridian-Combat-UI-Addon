package dev.kizuna.meridian.combat.fabric.client;

import dev.kizuna.meridian.combat.core.CombatCast;
import dev.kizuna.meridian.technique.fabric.client.TechniqueCastStatusClient;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastStatus;

/** 把功法库同步来的「自己这一次施放」换成十槽和施放条用的样子。只读显示副本。 */
final class CombatCasts {
    private CombatCasts() {
        // 只有一个换算入口。
    }

    static CombatCast current(float partialTick) {
        // 没在施放时只剩「记着的按键」这一条；在施放时把各时刻原样带过去，此刻走到哪按客户端的世界时间算。
        var current = TechniqueCastStatusClient.current(partialTick);
        if (current.isEmpty()) {
            return CombatCast.waiting(TechniqueCastStatusClient.buffered());
        }
        CastStatus status = current.get().status();
        var windows = status.getFollowUpsList().stream()
                .map(hint -> new CombatCast.Window(hint.getTechniqueId(), hint.getOpenMillis(), hint.getCloseMillis()))
                .toList();
        return new CombatCast((long) current.get().elapsedMillis(), status.getWarmupEndMillis(),
                status.getActiveEndMillis(), status.getRecoveryEndMillis(), status.getFreeMillis(),
                status.getHitMillisList(), windows, status.getBufferedTechniqueId());
    }
}
