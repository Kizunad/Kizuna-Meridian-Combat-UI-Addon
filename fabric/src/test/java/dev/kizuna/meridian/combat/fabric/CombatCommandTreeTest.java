package dev.kizuna.meridian.combat.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.combat.core.CombatIds;
import dev.kizuna.meridian.combat.core.CombatProfiles;
import dev.kizuna.meridian.combat.core.CombatSlots;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 使用正式命令树解析完整命令，不用字符串拆分替代 Brigadier；测试动作直接套客户端槽位语义。 */
public final class CombatCommandTreeTest {
    private static final String TECHNIQUE = "sample:step";
    private static final String SLOT = "meridian combat slot ";
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    public void parsesSetClearListAndRejectsBadArguments() throws Exception {
        // 冒号标识、末槽、未学功法和槽号边界均走正式分支。
        var actions = new Actions();
        var dispatcher = dispatcher(actions);
        assertEquals(1, dispatcher.execute(SLOT + "set 10 " + TECHNIQUE, "player"));
        assertEquals(TECHNIQUE, actions.profiles.slots().get(CombatIds.SLOT_COUNT - 1));
        assertEquals(1, dispatcher.execute(SLOT + "list", "player"));
        assertEquals(CombatIds.SLOT_COUNT, actions.lines.size());
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute(SLOT + "set 0 " + TECHNIQUE, "player"));
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute(SLOT + "clear 11", "player"));
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.execute(SLOT + "set 1 sample:unknown", "player"));
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.execute(SLOT + "set 1 sample:step extra", "player"));
        assertEquals(1, dispatcher.execute(SLOT + "clear 10", "player"));
        assertEquals("", actions.profiles.slots().get(CombatIds.SLOT_COUNT - 1));
    }

    @Test
    public void completionContainsOnlyCurrentKnownTechniques() throws Exception {
        // 补全来自同一语义层，清空网络知识后立即消失。
        var actions = new Actions();
        var dispatcher = dispatcher(actions);
        var parsed = dispatcher.parse(SLOT + "set 1 sample:", "player");
        var suggestions = dispatcher.getCompletionSuggestions(parsed).get().getList();
        assertEquals(1, suggestions.size());
        assertEquals(TECHNIQUE, suggestions.get(0).getText());
        actions.slots.updateKnown(Map.of());
        assertTrue(dispatcher.getCompletionSuggestions(parsed).get().isEmpty());
    }

    @Test
    public void slotCompletionOffersBoundsAndFiltersPrefix() throws Exception {
        // 两条命令都必须能发现有效槽位，输入前缀后不夹带不匹配建议。
        var dispatcher = dispatcher(new Actions());
        for (String operation : List.of("set", "clear")) {
            String command = SLOT + operation + " ";
            var all = dispatcher.getCompletionSuggestions(dispatcher.parse(command, "player")).get().getList();
            assertEquals(CombatIds.SLOT_COUNT, all.size());
            var filtered = dispatcher.getCompletionSuggestions(dispatcher.parse(command + "1", "player"))
                    .get()
                    .getList()
                    .stream()
                    .map(suggestion -> suggestion.getText())
                    .toList();
            assertEquals(List.of("1", Integer.toString(CombatIds.SLOT_COUNT)), filtered);
        }
    }

    private static CommandDispatcher<String> dispatcher(Actions actions) {
        // 与注册点相同的三级路径：meridian → combat → slot，slot 之下由正式子树填充。
        var slot = LiteralArgumentBuilder.<String>literal("slot");
        CombatCommandTree.attach(slot, actions);
        var dispatcher = new CommandDispatcher<String>();
        dispatcher.register(LiteralArgumentBuilder.<String>literal("meridian")
                .then(LiteralArgumentBuilder.<String>literal("combat").then(slot)));
        return dispatcher;
    }

    private static final class Actions implements CombatCommandTree.Actions<String> {
        private final CombatProfiles profiles = new CombatProfiles();
        private final CombatSlots slots = new CombatSlots(profiles);
        private List<String> lines = List.of();

        private Actions() {
            // 测试使用真实分档与槽位语义，不依赖 Minecraft 客户端启动。
            profiles.enter("server:test", NOW);
            slots.updateKnown(Map.of(TECHNIQUE, "轻步"));
        }

        @Override
        public int set(String source, int slot, String technique) {
            // 解析后的参数经过正式已学校验。
            slots.set(slot, technique, NOW);
            return 1;
        }

        @Override
        public int clear(String source, int slot) {
            // 清空只影响当前分档。
            profiles.clear(slot, NOW);
            return 1;
        }

        @Override
        public int list(String source) {
            // 保存整份反馈用于断言十槽列表。
            lines = slots.lines();
            return 1;
        }

        @Override
        public List<String> suggestions(String source, String prefix) {
            // 只补全服务端已确认的列表。
            return slots.suggestions(prefix);
        }
    }
}
