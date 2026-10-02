package com.ds.warehouse.command;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

/**
 * {@link NameArgument} 的注册与序列化支持。
 *
 * <p>自定义参数类型要能被服务端同步给客户端（否则指令树构建时会在
 * {@code ArgumentTypeInfos} 里找不到对应类型而报错），必须：
 * <ol>
 *   <li>实现 {@link ArgumentTypeInfo}（网络 / JSON 两个方向都不带参数，所以是空实现）；</li>
 *   <li>在模组初始化阶段调用 {@link #register()}，把这个类型登记进 Fabric 的参数类型表。</li>
 * </ol>
 *
 * <p>客户端与服务端都会加载本模组，所以两边都会注册，联机与单人集成服务器都成立。
 */
public final class NameArgumentInfo implements ArgumentTypeInfo<NameArgument, NameArgumentInfo.Template> {

    public static final NameArgumentInfo INSTANCE = new NameArgumentInfo();

    /** 注册键：warehouse-keeper:name */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("warehouse-keeper", "name");

    private NameArgumentInfo() {
    }

    /** 必须在指令树构建之前调用（见 {@code WarehouseMod.onInitialize}）。 */
    public static void register() {
        ArgumentTypeRegistry.registerArgumentType(ID, NameArgument.class, INSTANCE);
    }

    @Override
    public void serializeToNetwork(Template template, FriendlyByteBuf buf) {
        // 没有参数需要写：类型本身就是它的全部信息
    }

    @Override
    public Template deserializeFromNetwork(FriendlyByteBuf buf) {
        return new Template();
    }

    @Override
    public void serializeToJson(Template template, JsonObject json) {
        // 同上：无参数
    }

    @Override
    public Template unpack(NameArgument argument) {
        return new Template();
    }

    /** 无参模板：每次实例化都得到一个无状态的 {@link NameArgument}。 */
    public static final class Template implements ArgumentTypeInfo.Template<NameArgument> {

        @Override
        public NameArgument instantiate(CommandBuildContext context) {
            return NameArgument.name();
        }

        @Override
        public ArgumentTypeInfo<NameArgument, ?> type() {
            return NameArgumentInfo.INSTANCE;
        }
    }
}
