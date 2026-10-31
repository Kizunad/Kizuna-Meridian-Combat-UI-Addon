package dev.kizuna.meridian.combat.core;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.SlotCommand;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.SlotOperation;
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
    public void slotCommandRejectsUnknownOperationAndOutOfRangeSlot() {
        // 槽位指令只接受三种操作；设置与清空的槽号必须在 1–10，列表不看槽号。
        var set = slot(SlotOperation.SLOT_OPERATION_SET, CombatIds.SLOT_COUNT, "sample:step");
        assertEquals("sample:step", CombatProtocol.decodeSlot(set).getTechniqueId());
        assertEquals(SlotOperation.SLOT_OPERATION_LIST,
                CombatProtocol.decodeSlot(slot(SlotOperation.SLOT_OPERATION_LIST, 0, "")).getOperation());
        assertThrows(IllegalArgumentException.class, () -> CombatProtocol.decodeSlot(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> CombatProtocol.decodeSlot(new byte[CombatProtocol.MAX_SLOT_BYTES + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> CombatProtocol.decodeSlot(slot(SlotOperation.SLOT_OPERATION_UNSPECIFIED, 1, "")));
        assertThrows(IllegalArgumentException.class,
                () -> CombatProtocol.decodeSlot(slot(SlotOperation.SLOT_OPERATION_CLEAR, 0, "")));
        assertThrows(IllegalArgumentException.class,
                () -> CombatProtocol.decodeSlot(slot(SlotOperation.SLOT_OPERATION_SET, CombatIds.SLOT_COUNT + 1,
                        "sample:step")));
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

    private static byte[] slot(SlotOperation operation, int number, String id) {
        // 列表也编码一个零槽号，验证解码不对它做槽号校验。
        return SlotCommand.newBuilder()
                .setOperation(operation)
                .setSlot(number)
                .setTechniqueId(id)
                .build()
                .toByteArray();
    }

    private static byte[] press(String id) {
        // 消息不包含客户端可伪造的版本、身体或效果值。
        return TechniquePress.newBuilder().setTechniqueId(id).build().toByteArray();
    }
}
