package dev.kizuna.meridian.combat.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import org.junit.Test;

import static org.junit.Assert.*;

/** 有界连接映射不把客户端序号当作可信施放身份。 */
public final class CombatGuardSessionTest {
    @Test
    public void duplicatesAndFailedPressDoNotReplaceTheSuccessfulIdentity() {
        // 新按压被服务拒绝时，旧按压仍能松开自己的式。
        var session = new CombatGuardSession();
        var request = request("first");
        assertTrue(session.accept(1));
        session.started(1, request);
        assertFalse(session.accept(1));
        assertTrue(session.accept(2));
        assertNull(session.release(2));
        var released = session.release(1);
        assertEquals(request.getBodyId(), released.getBodyId());
        assertEquals(request.getCastId(), released.getCastId());
        assertNull(session.release(1));
        assertFalse(session.accept(2));
    }

    @Test
    public void staleReleaseAndEndNeverClearTheLaterCast() {
        // 历史终态和松开均按身份匹配，新连接也没有旧映射。
        var session = new CombatGuardSession();
        assertTrue(session.accept(1));
        session.started(1, request("first"));
        session.release(1);
        assertTrue(session.accept(2));
        session.started(2, request("second"));
        session.ended("first");
        assertNull(session.release(1));
        assertEquals("second", session.release(2).getCastId());
        assertNull(new CombatGuardSession().release(2));
    }

    private static CastRequest request(String castId) {
        // 身份来自可信请求，其余字段与连接去重无关。
        return CastRequest.newBuilder()
                .setBodyId("server-body")
                .setCastId(castId)
                .build();
    }
}
