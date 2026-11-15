package dev.kizuna.meridian.combat.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.combat.core.KnownProjectionGate.Reading;
import java.util.UUID;
import org.junit.Test;

public class KnownProjectionGateTest {
    private static final Object CATALOG = new Object();
    private static final int HELD = 11;
    private static final Reading READING = new Reading(7, 150, false, HELD, CATALOG);

    @Test
    public void sameReadingSkipsAndAnyChangedFieldRecomputes() {
        // 读数五项全同才跳过；版本、时钟偏移、主手、手里的东西、目录任一变化都要重算。
        var gate = new KnownProjectionGate();
        UUID player = UUID.randomUUID();
        assertTrue(gate.due(player, READING));
        gate.projected(player, READING, false /* recovers */);
        assertFalse(gate.due(player, new Reading(7, 150, false, HELD, CATALOG)));
        assertTrue(gate.due(player, new Reading(8, 150, false, HELD, CATALOG)));
        assertTrue(gate.due(player, new Reading(7, 200, false, HELD, CATALOG)));
        assertTrue(gate.due(player, new Reading(7, 150, true, HELD, CATALOG)));
        assertTrue(gate.due(player, new Reading(7, 150, false, HELD + 1, CATALOG)));
        assertTrue(gate.due(player, new Reading(7, 150, false, HELD, new Object())));
        assertTrue(gate.due(UUID.randomUUID(), READING));
    }

    @Test
    public void missingReadingAlwaysRecomputesAndDropsTheOldRecord() {
        // 宿主答不出读数时照旧每次重算；之后读数恢复，也不能拿更早的记录当作没变。
        var gate = new KnownProjectionGate();
        UUID player = UUID.randomUUID();
        gate.projected(player, READING, false /* recovers */);
        assertTrue(gate.due(player, null));
        gate.projected(player, null, false /* recovers */);
        assertTrue(gate.due(player, READING));
        gate.projected(player, READING, false /* recovers */);
        gate.forget(player);
        assertTrue(gate.due(player, READING));
        gate.projected(player, READING, false /* recovers */);
        gate.clear();
        assertTrue(gate.due(player, READING));
    }

    @Test
    public void recoveringProjectionKeepsRecomputingUntilItClears() {
        // 真元、力气随时间恢复不改身体版本：上一份投影里有这类不可用时，读数没变也接着重算，直到投影里没有了。
        var gate = new KnownProjectionGate();
        UUID player = UUID.randomUUID();
        gate.projected(player, READING, true /* recovers */);
        assertTrue(gate.due(player, READING));
        gate.projected(player, READING, false /* recovers */);
        assertFalse(gate.due(player, READING));
        gate.projected(player, READING, true /* recovers */);
        gate.forget(player);
        gate.projected(player, READING, false /* recovers */);
        assertFalse(gate.due(player, READING));
    }
}
