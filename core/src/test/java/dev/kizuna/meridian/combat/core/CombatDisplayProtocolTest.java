package dev.kizuna.meridian.combat.core;

import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechnique;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import org.junit.Test;
import static org.junit.Assert.*;

/** 追加显示字段保持旧包兼容，并在解码前后维持有界输入。 */
public final class CombatDisplayProtocolTest {
    @Test
    public void descriptionAndVolumeSurviveRoundTripWithoutChangingOldFields() {
        // 旧包仍能读取，独立简介不能覆盖名称或原有图标。
        var old = KnownTechnique.newBuilder()
                .setId("sample:kick")
                .setName("踢腿")
                .setIconId("sample:kick")
                .build();
        assertEquals("", decode(old).getDescription());
        var updated = old.toBuilder()
                .setDescription("抬腿踢向前方，收腿站稳。")
                .setVolumeId("sample:road")
                .setVolumeName("行路卷")
                .setBookName("腿法")
                .build();
        var decoded = decode(updated);
        assertEquals(updated, decoded);
        assertEquals("踢腿", decoded.getName());
    }

    @Test
    public void oversizedDescriptionAndInvalidVolumeAreRejected() {
        // 文字不能绕过总包上限或单字段上限，卷身份与功法身份遵循同一约束。
        var bounded = KnownTechnique.newBuilder()
                .setId("sample:kick")
                .setDescription("文".repeat(CombatProtocol.MAX_DESCRIPTION_LENGTH))
                .build();
        assertEquals(bounded, decode(bounded));
        var oversized = bounded.toBuilder()
                .setDescription(bounded.getDescription() + "文")
                .build();
        assertThrows(IllegalArgumentException.class, () -> decode(oversized));
        var invalid = bounded.toBuilder()
                .setVolumeId("不是合法身份")
                .build();
        assertThrows(IllegalArgumentException.class, () -> decode(invalid));
    }

    private static KnownTechnique decode(KnownTechnique entry) {
        // 整份投影走正式解码入口，不直接解析生成消息来绕过边界校验。
        var snapshot = KnownTechniques.newBuilder().addTechniques(entry).build();
        return CombatProtocol.decodeKnown(snapshot.toByteArray()).getTechniques(0);
    }
}
