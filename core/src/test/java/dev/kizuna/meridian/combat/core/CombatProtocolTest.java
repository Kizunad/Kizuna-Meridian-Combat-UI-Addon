package dev.kizuna.meridian.combat.core;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

/** 协议边界与入队前限流的防滥用回归。 */
public final class CombatProtocolTest {
    @Test
    public void protocolRejectsMalformedOversizedAndNonFiniteDirection() {
        // 非法包在宿主查询前被拒绝，未知但合法的标识留给功法库判定。
        byte[] valid = press("sample:step");
        assertEquals("sample:step", CombatProtocol.decodePress(valid).getTechniqueId());
        assertThrows(IllegalArgumentException.class, () -> CombatProtocol.decodePress(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> CombatProtocol.decodePress(new byte[CombatProtocol.MAX_PRESS_BYTES + 1]));
        int invalidWireTag = 15;
        assertThrows(IllegalArgumentException.class,
                () -> CombatProtocol.decodePress(new byte[]{(byte) invalidWireTag}));
        var nonFinite = TechniquePress.newBuilder()
                .setTechniqueId("sample:step")
                .setMoveX(Float.NaN)
                .build();
        assertThrows(IllegalArgumentException.class, () -> CombatProtocol.decodePress(nonFinite.toByteArray()));
        assertEquals(0, CombatProtocol.decodeKnown(new byte[0]).getTechniquesCount());
        assertThrows(IllegalArgumentException.class,
                () -> CombatProtocol.decodeKnown(new byte[CombatProtocol.MAX_KNOWN_BYTES + 1]));
    }

    @Test
    public void slidingWindowRejectsBurstAndReleasesAtBoundary() {
        // 任意一秒最多二十次，不能在固定秒边界瞬时接受四十次。
        var limiter = new PressLimiter();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        long start = PressLimiter.WINDOW_NANOS - 1;
        for (int index = 0; index < PressLimiter.MAX_PER_SECOND; index++) {
            assertTrue(limiter.accept(first, start));
        }
        assertFalse(limiter.accept(first, PressLimiter.WINDOW_NANOS));
        assertTrue(limiter.accept(second, start));
        assertFalse(limiter.accept(first, start + PressLimiter.WINDOW_NANOS - 1));
        assertTrue(limiter.accept(first, start + PressLimiter.WINDOW_NANOS));
        limiter.disconnect(first);
        assertTrue(limiter.accept(first, start + PressLimiter.WINDOW_NANOS));
        limiter.clear();
        assertTrue(limiter.accept(second, start));
    }

    private static byte[] press(String id) {
        // 消息不包含客户端可伪造的版本、身体或效果值。
        return TechniquePress.newBuilder().setTechniqueId(id).build().toByteArray();
    }
}
