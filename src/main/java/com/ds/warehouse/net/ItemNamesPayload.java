package com.ds.warehouse.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 → 服务端：把「客户端语言下」的物品 / 方块名字推给服务端（批次 5 阶段 1）。
 *
 * <p>为什么需要这一层：专用服务端的 {@code Language} 通常是 en_us，{@code util.Names.item()}
 * 在服务端解析出来就是英文名（Diamond / Iron Ingot / Coal）。而「整理仓库」的排序键正是
 * 它 —— 按第一个字的拼音排序，在专用服务器上会退化成英文字母序（2026-10-01 用 RCON 实测：
 * {@code 名字解析: diamond=Diamond · coal=Coal · iron_ingot=Iron Ingot}）。单人存档的集成
 * 服务端跟着客户端走，一直是中文，所以只有联机才看得出来。
 *
 * <p>客户端在收到整包快照后推一次（内容不大：一个存档几十到几百个 id），服务端存下来，
 * {@link com.ds.warehouse.util.Names} 查名字时优先用它，于是排序键就是玩家真正看到的名字。
 *
 * @param ids   物品 / 方块 id 表，例如 {@code minecraft:diamond}
 * @param names 与 {@link #ids} 一一对应的名字，例如 {@code 钻石}
 */
public record ItemNamesPayload(List<String> ids, List<String> names) implements CustomPacketPayload {

    /** 一次最多推多少条；读包时也按它夹，防止别人塞一个超大表进来 */
    public static final int MAX_ENTRIES = 4096;

    /** 单条 id / 名字的协议上限 */
    public static final int MAX_LEN = 96;

    public static final CustomPacketPayload.Type<ItemNamesPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("warehouse-keeper", "item_names"));

    /**
     * 两个表夹成等长、每条截断到 {@link #MAX_LEN}：
     * 编解码两侧都靠「等长」这个不变量，这里夹一次就不会越界。
     */
    public ItemNamesPayload {
        int n = Math.min(ids == null ? 0 : ids.size(), names == null ? 0 : names.size());
        n = Math.min(n, MAX_ENTRIES);
        List<String> a = new ArrayList<>(n);
        List<String> b = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            a.add(trim(ids.get(i)));
            b.add(trim(names.get(i)));
        }
        ids = List.copyOf(a);
        names = List.copyOf(b);
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_LEN ? s : s.substring(0, MAX_LEN);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemNamesPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeVarInt(p.ids.size());
                for (int i = 0; i < p.ids.size(); i++) {
                    buf.writeUtf(p.ids.get(i), MAX_LEN);
                    buf.writeUtf(p.names.get(i), MAX_LEN);
                }
            }, buf -> {
                // 上下界都要夹：只夹上界时，伪造的负 varint 会让 new ArrayList<>(n) 抛异常
                int n = Math.max(0, Math.min(buf.readVarInt(), MAX_ENTRIES));
                List<String> ids = new ArrayList<>(n);
                List<String> names = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    ids.add(buf.readUtf(MAX_LEN));
                    names.add(buf.readUtf(MAX_LEN));
                }
                return new ItemNamesPayload(ids, names);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
