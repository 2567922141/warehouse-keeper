package com.ds.warehouse.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 → 服务端：按需查询一页数据（批次 5 阶段 1）。
 *
 * <p>为什么不让服务端整包推：容器级数据太大（几百只箱子 × 54 格 = MB 级，还可能每 2 秒重发一次）。
 * 所以改成「面板打开哪个页签就问哪个页签」，服务端只扫已经建好的内存快照，一页最多 50 行。
 * 现有的整包快照（{@link SnapshotPayload}）保留：仓库页 / 取货页 / 搬运工页继续用它。
 *
 * <p>字段与网页端 {@code /api/items} 等接口的查询参数一一对应，服务端用同一份
 * {@code web.WebSnapshot} 出数据，所以网页和面板看到的数字永远一致。
 *
 * @param kind     {@link #KIND_OVERVIEW} / {@link #KIND_ITEMS} / {@link #KIND_ITEM} /
 *                 {@link #KIND_CONTAINERS} / {@link #KIND_CONTAINER}
 * @param keyword  物品名 / 物品 id 的模糊匹配（物品列表、容器列表用）
 * @param category 分类过滤（空 / 「全部」= 不过滤）
 * @param region   仓库名（空 / 「全部」= 所有仓库）
 * @param key      物品 id（{@link #KIND_ITEM}）或容器 key（{@link #KIND_CONTAINER}）
 * @param sort     排序方式（count / count_asc / name / id / category / refs）
 * @param page     第几页（从 1 开始）
 * @param size     每页多少行（服务端会夹到 1..{@link #MAX_SIZE}）
 */
public record ViewQueryPayload(int kind, String keyword, String category, String region,
                               String key, String sort, int page, int size)
        implements CustomPacketPayload {

    /** 总览：区域汇总 + 分类统计 + 容器数 + 扫描状态 */
    public static final int KIND_OVERVIEW = 1;
    /** 物品列表（分页、排序、筛选） */
    public static final int KIND_ITEMS = 2;
    /** 单个物品详情（全部存放位置） */
    public static final int KIND_ITEM = 3;
    /** 容器清单（某一区域 / 全部） */
    public static final int KIND_CONTAINERS = 4;
    /** 单个容器的逐格内容 */
    public static final int KIND_CONTAINER = 5;
    /** 操作日志（阶段 2）：最近的操作记录，按 page/size 翻页 */
    public static final int KIND_AUDIT = 6;

    /** 面板一页最多显示多少行；服务端也会按这个上限夹一次 */
    public static final int MAX_SIZE = 50;

    public static final CustomPacketPayload.Type<ViewQueryPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("warehouse-keeper", "view_query"));

    /**
     * 统一夹一次：字符串截断到协议上限、page ≥ 1、size 夹到 1..{@link #MAX_SIZE}。
     *
     * <p>{@code FriendlyByteBuf.writeUtf(s, max)} 在超长时会抛 {@code EncoderException}，
     * 而关键字来自玩家的搜索框（可以粘贴一长串）—— 在这里夹住，发包路径就永远抛不出来。
     */
    public ViewQueryPayload {
        keyword = cut(keyword, 128);
        category = cut(category, 64);
        region = cut(region, 64);
        key = cut(key, 160);
        sort = cut(sort, 32);
        page = Math.max(1, page);
        size = Math.max(1, Math.min(MAX_SIZE, size));
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ViewQueryPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeVarInt(p.kind);
                buf.writeUtf(p.keyword, 128);
                buf.writeUtf(p.category, 64);
                buf.writeUtf(p.region, 64);
                buf.writeUtf(p.key, 160);
                buf.writeUtf(p.sort, 32);
                buf.writeVarInt(p.page);
                buf.writeVarInt(p.size);
            }, buf -> new ViewQueryPayload(buf.readVarInt(), buf.readUtf(128), buf.readUtf(64),
                    buf.readUtf(64), buf.readUtf(160), buf.readUtf(32),
                    buf.readVarInt(), buf.readVarInt()));

    /** 面板调用：把用户输入的查询压成一条包 */
    public static ViewQueryPayload of(int kind, String keyword, String category, String region,
                                      String key, String sort, int page, int size) {
        return new ViewQueryPayload(kind, keyword == null ? "" : keyword,
                category == null ? "" : category, region == null ? "" : region,
                key == null ? "" : key, sort == null ? "" : sort, Math.max(1, page), size);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
