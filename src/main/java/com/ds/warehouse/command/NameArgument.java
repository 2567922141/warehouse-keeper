package com.ds.warehouse.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 允许中文的名字参数类型（仓库名、假人名、玩家名通用）。
 *
 * <p>为什么需要它：Brigadier 的 {@code StringArgumentType.word()} 与 {@code string()}
 * 在**不带引号**时只放行 {@code [0-9A-Za-z_.+-]}（见 Brigadier 1.3.10 的
 * {@code StringReader.isAllowedInUnquotedString}），所以 {@code /warehouse region save 一楼仓库}
 * 会在「一」这个字符上报 "Expected whitespace to end one argument"，仓库名只能写成字母数字下划线；
 * 而 {@code greedyString()} 虽然能裸写中文，却只能当整条指令的最后一个参数，做不了
 * 「名字 + 方向 + 格数」这种后面还有参数的节点。
 *
 * <p>这里补的就是缺的那一档：**不带引号读到空白为止**（任何字符都算名字的一部分），
 * 需要带空格时用 {@code "..."}（由 Brigadier 的 {@code readQuotedString()} 处理引号与转义）。
 *
 * <p>自定义参数类型必须注册序列化器才能在联机/单人集成服务器下同步指令树，
 * 见 {@link NameArgumentInfo}（在 {@code WarehouseMod.onInitialize} 里注册）。
 */
public final class NameArgument implements ArgumentType<String> {

    private static final SimpleCommandExceptionType MISSING_NAME =
            new SimpleCommandExceptionType(Component.literal("缺少名字参数"));

    private static final List<String> EXAMPLES = List.of("一楼仓库", "\"东边 仓库\"");

    private NameArgument() {
    }

    public static NameArgument name() {
        return new NameArgument();
    }

    /**
     * 读取参数值。
     *
     * <p>不用 {@code StringArgumentType.getString} 只是为了让调用点看起来对得上参数类型，
     * 两者本质都是 {@code context.getArgument(key, String.class)}，可以混用。
     */
    public static String get(CommandContext<?> ctx, String key) {
        return ctx.getArgument(key, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        // 带引号：交给 Brigadier 自己拆引号（含 \" 转义），允许名字里有空格
        if (reader.canRead() && reader.peek() == '"') {
            String quoted = reader.readQuotedString();
            // 空名字（`""`）要在解析期就拒掉：否则命令台/rcon 能建出引用不到的空名仓库（审查发现 5）
            if (quoted.isEmpty()) {
                throw MISSING_NAME.createWithContext(reader);
            }
            return quoted;
        }
        // 不带引号：一直读到空白为止
        int start = reader.getCursor();
        while (reader.canRead() && !isBlank(reader.peek())) {
            reader.skip();
        }
        if (reader.getCursor() == start) {
            throw MISSING_NAME.createWithContext(reader);
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public List<String> getExamples() {
        return EXAMPLES;
    }

    /** 指令文本里的空白：空格、制表符、换行都算参数结束（避免名字吞掉后面的参数）。 */
    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
