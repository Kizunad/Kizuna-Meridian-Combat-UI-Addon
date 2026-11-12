package dev.kizuna.meridian.combat.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.kizuna.meridian.combat.core.KnownProjectionGate.Reading;
import java.util.UUID;
import org.junit.Test;

public class KnownProjectionGateTest {
    private static final Object CATALOG = new Object();
    private static final Reading READING = new Reading(7, 150, false, CATALOG);

    @Test
    public void sameReadingSkipsAndAnyChangedFieldRecomputes() {
        // 读数四项全同才跳过；版本、时钟偏移、主手、目录任一变化都要重算。
        var gate = new KnownProjectionGate();
        UUID player = UUID.randomUUID();
        assertTrue(gate.due(player, READING));
        gate.projected(player, READING);
        assertFalse(gate.due(player, new Reading(7, 150, false, CATALOG)));
        assertTrue(gate.due(player, new Reading(8, 150, false, CATALOG)));
        assertTrue(gate.due(player, new Reading(7, 200, false, CATALOG)));
        assertTrue(gate.due(player, new Reading(7, 150, true, CATALOG)));
        assertTrue(gate.due(player, new Reading(7, 150, false, new Object())));
        assertTrue(gate.due(UUID.randomUUID(), READING));
    }

    @Test
    public void missingReadingAlwaysRecomputesAndDropsTheOldRecord() {
        // 宿主答不出读数时照旧每次重算；之后读数恢复，也不能拿更早的记录当作没变。
        var gate = new KnownProjectionGate();
        UUID player = UUID.randomUUID();
        gate.projected(player, READING);
        assertTrue(gate.due(player, null));
        gate.projected(player, null);
        assertTrue(gate.due(player, READING));
        gate.projected(player, READING);
        gate.forget(player);
        assertTrue(gate.due(player, READING));
        gate.projected(player, READING);
        gate.clear();
        assertTrue(gate.due(player, READING));
    }
}
