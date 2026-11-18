package dev.kizuna.meridian.combat.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EndActiveRequest;

/** 一个连接最多保留一份守式身份和一个已见按压序号，不保存身体状态。 */
final class CombatGuardSession {
    private int lastPressId;
    private int activePressId;
    private EndActiveRequest active;

    boolean accept(int pressId) {
        // 成功和失败的按下均不可重放；旧序号不需要额外的历史集合。
        if (pressId <= lastPressId) {
            return false;
        }
        lastPressId = pressId;
        return true;
    }

    void started(int pressId, CastRequest request) {
        // 只有成功施放才替换映射，忙时的新按压不能丢掉旧守式的松开身份。
        activePressId = pressId;
        active = EndActiveRequest.newBuilder()
                .setBodyId(request.getBodyId())
                .setCastId(request.getCastId())
                .build();
    }

    EndActiveRequest release(int pressId) {
        // 先取走再交服务接收，重复松开或迟到松开都没有第二次副作用。
        if (active == null || activePressId != pressId) {
            return null;
        }
        EndActiveRequest released = active;
        active = null;
        return released;
    }

    void ended(String castId) {
        // 取消、死亡和施放失败的终态只清对应身份，不影响后来新起的式。
        if (active != null && active.getCastId().equals(castId)) {
            active = null;
        }
    }
}
