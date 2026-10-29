package dev.kizuna.meridian.combat.fabric.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.kizuna.meridian.combat.core.CombatIds;

import java.util.List;

/** 同一棵命令树同时供 Fabric 注册和真实 Brigadier 解析测试使用。 */
public final class CombatCommandTree {
    private CombatCommandTree() {
        // 命令解析不持有 Minecraft 单例。
    }

    public static <S> void register(CommandDispatcher<S> dispatcher, Actions<S> actions) {
        // 最后一个参数使用完整余串接收命名空间冒号，语义层再校验为单个功法 ID。
        var id = RequiredArgumentBuilder.<S, String>argument("technique", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    actions.suggestions(builder.getRemaining()).forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .executes(context -> actions.set(context.getSource(),
                        IntegerArgumentType.getInteger(context, "slot"),
                        StringArgumentType.getString(context, "technique")));
        var setSlot = CombatCommandTree.<S>slotArgument().then(id);
        var clearSlot = CombatCommandTree.<S>slotArgument()
                .executes(context -> actions.clear(context.getSource(),
                        IntegerArgumentType.getInteger(context, "slot")));
        var slots = LiteralArgumentBuilder.<S>literal("slot")
                .then(LiteralArgumentBuilder.<S>literal("set").then(setSlot))
                .then(LiteralArgumentBuilder.<S>literal("clear").then(clearSlot))
                .then(LiteralArgumentBuilder.<S>literal("list")
                        .executes(context -> actions.list(context.getSource())));
        dispatcher.register(LiteralArgumentBuilder.<S>literal("meridian_combat").then(slots));
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
        int set(S source, int slot, String technique);
        int clear(S source, int slot);
        int list(S source);
        List<String> suggestions(String prefix);
    }
}
