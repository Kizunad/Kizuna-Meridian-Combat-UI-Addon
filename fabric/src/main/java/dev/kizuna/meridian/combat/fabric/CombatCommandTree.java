package dev.kizuna.meridian.combat.fabric;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.combat.core.CombatIds;

import java.util.List;

/** /meridian combat slot 的子树；同一棵树同时供注册点登记和真实 Brigadier 解析测试使用。 */
public final class CombatCommandTree {
    private CombatCommandTree() {
        // 命令解析不持有 Minecraft 单例。
    }

    public static <S> void attach(LiteralArgumentBuilder<S> slot, Actions<S> actions) {
        // 最后一个参数使用完整余串接收命名空间冒号，语义层再校验为单个功法 ID。
        var id = RequiredArgumentBuilder.<S, String>argument("technique", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    actions.suggestions(context.getSource(), builder.getRemaining()).forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .executes(context -> actions.set(context.getSource(),
                        IntegerArgumentType.getInteger(context, "slot"),
                        StringArgumentType.getString(context, "technique")));
        var setSlot = CombatCommandTree.<S>slotArgument().then(id);
        var clearSlot = CombatCommandTree.<S>slotArgument()
                .executes(context -> actions.clear(context.getSource(),
                        IntegerArgumentType.getInteger(context, "slot")));
        slot.then(LiteralArgumentBuilder.<S>literal("set").then(setSlot))
                .then(LiteralArgumentBuilder.<S>literal("clear").then(clearSlot))
                .then(LiteralArgumentBuilder.<S>literal("list")
                        .executes(context -> actions.list(context.getSource())));
    }

    private static <S> RequiredArgumentBuilder<S, Integer> slotArgument() {
        // set 与 clear 共用有界槽号和补全，不要求用户记住十槽范围。
        return RequiredArgumentBuilder.<S, Integer>argument("slot",
                        IntegerArgumentType.integer(1, CombatIds.SLOT_COUNT))
                .suggests((context, builder) -> {
                    String prefix = builder.getRemaining();
                    for (int slot = 1; slot <= CombatIds.SLOT_COUNT; slot++) {
                        if (Integer.toString(slot).startsWith(prefix)) {
                            builder.suggest(slot);
                        }
                    }
                    return builder.buildFuture();
                });
    }

    public interface Actions<S> {
        int set(S source, int slot, String technique) throws CommandSyntaxException;
        int clear(S source, int slot) throws CommandSyntaxException;
        int list(S source) throws CommandSyntaxException;
        List<String> suggestions(S source, String prefix);
    }
}
