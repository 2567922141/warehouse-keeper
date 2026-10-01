package com.ds.warehouse.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 客户端：一次查询的结果（JSON 一段，见 {@code ViewQueryService} 里各 kind 的形状）。
 *
 * <p>和整包快照一样走 {@code registerLarge}（4 MB），因为「容器清单」和「逐格内容」
 * 可能一次就是几百行、几十 KB；单页上限 50 行只约束物品列表。
 *
 * @param kind 回显请求里的 kind，客户端据此把结果塞进对应页签的缓存
 * @param json UTF-8 的 JSON 对象；解析失败时客户端只把这一页显示成空，不会崩
 */
public record ViewResultPayload(int kind, byte[] json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ViewResultPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("warehouse-keeper", "view_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ViewResultPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeVarInt(p.kind);
                buf.writeByteArray(p.json);
            }, buf -> new ViewResultPayload(buf.readVarInt(), buf.readByteArray()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
