package com.ds.warehouse.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 客户端：仓库区域 + 索引快照。
 *
 * <p>为什么需要它：游戏内面板原来直接读客户端本机的
 * {@code config/warehouse-keeper/regions.json}，只有在「单机 / 客户端与服务端同一个进程」
 * 时才成立。玩家连别人的服务器时，客户端上既没有那个文件、也没有服务端的索引，
 * 面板就空了（旧版本干脆显示「连的是别人的服务器，看不了」）。
 *
 * <p>现在服务端把区域定义和每个仓库的物品视图压成一段 JSON 发过来，
 * 客户端只负责画。没装本模组的客户端不会被推送（服务端先问 canSend）。
 */
public record SnapshotPayload(byte[] json) implements CustomPacketPayload {

    // 注意：CustomPacketPayload.createType(String) 只收「路径」，命名空间会被强制成 minecraft:，
    // 写了 "warehouse-keeper:snapshot" 会变成 minecraft:warehouse-keeper:snapshot 并抛
    // IdentifierException（26.2 实测）。所以这里自己造 Type(Identifier)。
    public static final CustomPacketPayload.Type<SnapshotPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("warehouse-keeper", "snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> CODEC =
            StreamCodec.of((buf, payload) -> buf.writeByteArray(payload.json),
                    buf -> new SnapshotPayload(buf.readByteArray()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
