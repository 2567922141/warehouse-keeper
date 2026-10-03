package com.ds.warehouse.net;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.index.ContainerRecord;
import com.ds.warehouse.index.IndexRefresh;
import com.ds.warehouse.index.Scanner;
import com.ds.warehouse.index.WarehouseIndex;
import com.ds.warehouse.util.Admin;
import com.ds.warehouse.util.Audit;
import com.ds.warehouse.util.Names;
import com.ds.warehouse.web.WebSnapshot;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 面板「按需查询」的服务端一半（批次 5 阶段 1）。
 *
 * <p>规矩（写在优化清单 §8 的风险 R6 里）：
 * <ul>
 *   <li>只读已经建好的内存快照 {@link WebSnapshot#current()}，**不扫世界、不碰区块**；</li>
 *   <li>物品列表一页最多 {@link ViewQueryPayload#MAX_SIZE} 行；</li>
 *   <li>每个玩家只保留「最新想查什么」一条，**每 tick 全服最多处理 1 条**，来晚了就被更新的顶掉；</li>
 *   <li>查询抛异常也回一个空结果（客户端不会一直转圈，更不会崩）。</li>
 * </ul>
 *
 * <p>数据来源与网页端是同一个 {@code WebSnapshot}，所以面板和网页的数字永远一致，
 * 阶段 3 删掉网页端时这份查询逻辑可以原样留下。
 */
public final class ViewQueryService {

    /** 单包上限（registerLarge 用）：容器清单 + 逐格内容可能一次几十 KB */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    /** 审计页最多往前看多少条（阶段 2：面板上「最近 500 条」） */
    private static final int AUDIT_MAX = 500;

    private static final Gson GSON = new Gson();

    /** 每个玩家最新的一条查询；服务端线程独占，不需要加锁 */
    private static final Map<UUID, ViewQueryPayload> LATEST = new LinkedHashMap<>();

    /**
     * 因为「索引正在刷新」而被挂起的查询：玩家 UUID → 已经等了多少个 tick。
     *
     * <p>用户要求「用到仓库索引的功能，执行前先刷新一次索引」。面板查询就是这种功能：
     * 界面上的数字全部来自索引聚合出的快照，索引陈旧时直接回答就会把旧数据画上去。
     * 所以这里最多等 {@link #WAIT_MAX_TICKS} 个 tick，等不来就先答现状（绝不把玩家卡住）。
     */
    private static final Map<UUID, Integer> WAIT = new LinkedHashMap<>();

    /** 挂起上限：60 tick = 3 秒。超过就按现状回答，界面不会一直转圈 */
    private static final int WAIT_MAX_TICKS = 60;

    /**
     * 这几类查询的画面完全由索引聚合出来，值得为它等一小会儿。
     *
     * <p>物品详情与审计不依赖扫描结果（前者看单个物品，后者读日志文件），不挂起。
     */
    private static boolean needsFreshIndex(int kind) {
        return kind == ViewQueryPayload.KIND_OVERVIEW
                || kind == ViewQueryPayload.KIND_ITEMS
                || kind == ViewQueryPayload.KIND_CONTAINERS
                || kind == ViewQueryPayload.KIND_CONTAINER;
    }

    private ViewQueryService() {
    }

    /** 在公共入口调用一次：注册两个包 + 收下查询请求 */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay()
                .register(ViewQueryPayload.TYPE, ViewQueryPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .registerLarge(ViewResultPayload.TYPE, ViewResultPayload.CODEC, MAX_BYTES);
        // 接收器由 Fabric 在服务端线程上调用，而 tick() 也在服务端线程上读这个表，
        // 所以这里直接写、不用再 execute 一次
        ServerPlayNetworking.registerGlobalReceiver(ViewQueryPayload.TYPE, (payload, context) ->
                LATEST.put(context.player().getUUID(), payload));
        // 客户端推来的「本地化名字表」：专用服务端语言是 en_us，拼音排序要靠它拿到中文名
        PayloadTypeRegistry.serverboundPlay()
                .register(ItemNamesPayload.TYPE, ItemNamesPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ItemNamesPayload.TYPE, (payload, context) -> {
            // 名字表是**全局静态表**（Names.CLIENT 被整表替换），会同时影响 /warehouse list、find、
            // 面板显示与取货的名字解析，所以只收管理员推的：否则任何玩家一条包就能把全服的物品名改掉。
            if (!Admin.isAdmin(WarehouseMod.server(), context.player())) {
                WarehouseMod.LOGGER.info("[warehouse-keeper] 已忽略非管理员推来的名字表（玩家 {}）",
                        context.player().getName().getString());
                return;
            }
            Names.acceptClientNames(payload.ids(), payload.names());
            refreshItemNames();
            WarehouseMod.LOGGER.info("[warehouse-keeper] 收到客户端推来的名字表：{} 条（玩家 {}）",
                    payload.ids().size(), context.player().getName().getString());
        });
        WarehouseMod.LOGGER.info("[warehouse-keeper] 面板查询通道已注册：C2S view_query / S2C view_result（单包上限 {} KB）",
                MAX_BYTES / 1024);
    }

    /**
     * 用刚收到的客户端名字表刷新索引里的显示名。
     *
     * <p>索引是名字表到达之前建的（专用服务端的 {@code Language} 是 en_us），所以
     * {@code ItemEntry.displayName} 里存的是英文名。这里按 id 重算一遍，让
     * {@code /warehouse list}、{@code /warehouse find}、取货提示与之后重建的快照都显示中文。
     */
    private static void refreshItemNames() {
        int changed = 0;
        for (var e : WarehouseMod.INDEX.items.values()) {
            String live = Names.item(e.itemId);
            if (live != null && !live.isEmpty() && !live.equals(e.displayName)) {
                e.displayName = live;
                changed++;
            }
        }
        if (changed > 0) {
            WarehouseMod.LOGGER.info("[warehouse-keeper] 已按客户端名字表刷新 {} 个物品的显示名", changed);
        }
    }

    /**
     * 一个服务端 tick 里最多答几条查询。
     *
     * <p>面板点一下仓库行会同时发 3 条（物品/箱子/概览），原来每 tick 只答 1 条 ⇒ 第 2、3 条要排队，
     * 看起来就是「点了没反应，等一下才出来」。
     */
    private static final int MAX_PER_TICK = 3;
    /**
     * 「箱子」页一次最多回多少行。
     *
     * <p>客户端这一页没有分页控件，所以不能按请求的 size 截断；这里只挡「一个仓库几千只箱子」
     * 这种把整份列表塞回客户端的极端情况（面板本身也显示不完）。
     */
    private static final int CONTAINER_MAX = 300;
    /** 一个 tick 里花在面板查询上的时间上限（毫秒）—— 超了就留到下个 tick，别拖长服务端 tick */
    private static final long BUDGET_NS = 25_000_000L;

    /** 每个服务端 tick 调一次：在时间预算内尽量多答几条 */
    public static void tick(MinecraftServer server) {
        long start = System.nanoTime();
        for (int n = 0; n < MAX_PER_TICK; n++) {
            if (!answerOne(server)) {
                return;
            }
            if (n + 1 < MAX_PER_TICK && System.nanoTime() - start >= BUDGET_NS) {
                return;
            }
        }
    }

    /** @return 是否真的处理了一条（队列为空时返回 false） */
    private static boolean answerOne(MinecraftServer server) {
        if (LATEST.isEmpty()) {
            return false;
        }
        Iterator<Map.Entry<UUID, ViewQueryPayload>> it = LATEST.entrySet().iterator();
        Map.Entry<UUID, ViewQueryPayload> head = it.next();
        it.remove();
        ServerPlayer player = server.getPlayerList().getPlayer(head.getKey());
        if (player == null) {
            // 玩家已经不在线：顺手清掉等待计数，否则这条 WAIT 会永久残留，
            // 该玩家下次进服的第一条查询会被白等一轮。
            WAIT.remove(head.getKey());
            return true;
        }
        ViewQueryPayload q = head.getValue();
        // 「执行前先刷新索引」：索引正在刷新 / 还有仓库没重扫完时，把这类查询挂起一小会儿再答
        if (needsFreshIndex(q.kind()) && (IndexRefresh.stale() || Scanner.isRunning())) {
            int waited = WAIT.getOrDefault(head.getKey(), 0);
            if (waited < WAIT_MAX_TICKS) {
                WAIT.put(head.getKey(), waited + 1);
                LATEST.put(head.getKey(), q);
                return true;
            }
        }
        WAIT.remove(head.getKey());
        byte[] json;
        long t0 = System.nanoTime();
        try {
            json = GSON.toJson(build(q, server, player)).getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 面板查询失败（kind={}）", q.kind(), t);
            json = "{}".getBytes(StandardCharsets.UTF_8);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        // 性能闸门（优化清单 §8 风险 R6）：只在偏慢时留痕，免得每 tick 刷日志
        if (ms >= 50) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 面板查询偏慢：kind={} 用时 {} ms（{} 字节，玩家 {}）",
                    q.kind(), ms, json.length, player.getName().getString());
        }
        // 回包大小与发送都不许把异常冒到 tick 上：超限就回空结果，发送失败只记日志
        if (json.length > MAX_BYTES) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 面板查询结果过大（kind={}，{} 字节），已改为回空结果",
                    q.kind(), json.length);
            json = "{}".getBytes(StandardCharsets.UTF_8);
        }
        try {
            if (ServerPlayNetworking.canSend(player, ViewResultPayload.TYPE)) {
                ServerPlayNetworking.send(player, new ViewResultPayload(q.kind(), json));
            }
        } catch (Throwable t) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 面板查询回包发送失败（kind={}）", q.kind(), t);
        }
        return true;
    }
    private static Map<String, Object> build(ViewQueryPayload q, MinecraftServer server, ServerPlayer player) {
        WebSnapshot s = WebSnapshot.current();
        return switch (q.kind()) {
            case ViewQueryPayload.KIND_OVERVIEW -> overview(s, q);
            case ViewQueryPayload.KIND_ITEMS -> items(s, q);
            case ViewQueryPayload.KIND_ITEM -> item(s, q);
            case ViewQueryPayload.KIND_CONTAINERS -> containers(s, q);
            case ViewQueryPayload.KIND_CONTAINER -> container(s, q);
            case ViewQueryPayload.KIND_AUDIT -> audit(q, server, player);
            default -> Map.of("error", "未知查询类型：" + q.kind());
        };
    }

    /**
     * 操作日志（阶段 2）：最近 {@link #AUDIT_MAX} 条，按页发。
     *
     * <p>日志里有「谁取走了什么」，所以只答管理员；非管理员回一句 error，客户端照常显示。
     */
    private static Map<String, Object> audit(ViewQueryPayload q, MinecraftServer server, ServerPlayer player) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Admin.isAdmin(server, player)) {
            out.put("error", "只有管理员能看操作日志。");
            return out;
        }
        int size = Math.max(1, Math.min(ViewQueryPayload.MAX_SIZE, q.size()));
        int shown = Math.min(Audit.size(), AUDIT_MAX);
        int pages = Math.max(1, (shown + size - 1) / size);
        int page = Math.max(1, Math.min(pages, q.page()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Audit.Entry e : Audit.recent(size, (page - 1) * size)) {
            if (rows.size() >= shown) {
                break;
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("ts", e.ts());
            o.put("time", Audit.timeText(e.ts()));
            o.put("who", e.who());
            o.put("action", e.action());
            o.put("detail", e.detail());
            rows.add(o);
        }
        out.put("total", shown);
        out.put("allTotal", Audit.size());
        out.put("max", AUDIT_MAX);
        out.put("page", page);
        out.put("pages", pages);
        out.put("size", size);
        out.put("entries", rows);
        return out;
    }

    /** 顶部汇总 + 区域列表 + 分类统计（对应网页的 /api/overview） */
    private static Map<String, Object> overview(WebSnapshot s, ViewQueryPayload q) {
        WebSnapshot.View v = s.view(q.region());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", s.generatedAt);
        out.put("scannerRunning", s.scannerRunning);
        out.put("scannerProgress", s.scannerProgress);
        out.put("scannedChunks", s.scannedChunks);
        out.put("skippedChunks", s.skippedChunks);
        out.put("lastScanMillis", s.lastScanMillis);
        out.put("lastScanRegion", s.lastScanRegion);
        out.put("restoredFromDisk", s.restoredFromDisk);
        out.put("restoredSavedAt", s.restoredSavedAt);
        out.put("capacity", v.capacity);
        out.put("usedSlots", v.usedSlots);
        out.put("totalItems", v.totalItems);
        out.put("emptyContainers", v.emptyContainers);
        out.put("itemKinds", v.items.size());
        out.put("boxCount", v.containers.size());
        out.put("items", v.items.size());
        out.put("region", q.region());
        List<RegionBrief> regs = new ArrayList<>(s.regions.size());
        for (WebSnapshot.RegionRow r : s.regions) {
            regs.add(new RegionBrief(r.name, r.containers, r.kinds, r.items, r.usedSlots, r.capacity));
        }
        out.put("regions", regs);
        List<CategoryBrief> cats = new ArrayList<>(v.categories.size());
        for (WebSnapshot.CategoryRow c : v.categories) {
            cats.add(new CategoryBrief(c.name, c.kinds, c.items, c.slots));
        }
        out.put("categories", cats);
        return out;
    }

    /** 物品列表（分页 + 排序 + 关键字/分类筛选）；只发列表要用的字段，不带 locations */
    private static Map<String, Object> items(WebSnapshot s, ViewQueryPayload q) {
        List<WebSnapshot.ItemRow> hits = new ArrayList<>(s.query(q.keyword(), q.category(), q.region()));
        sort(hits, q.sort());
        int size = Math.max(1, Math.min(ViewQueryPayload.MAX_SIZE, q.size()));
        int pages = Math.max(1, (hits.size() + size - 1) / size);
        int page = Math.min(Math.max(1, q.page()), pages);
        int from = (page - 1) * size;
        int to = Math.min(hits.size(), from + size);
        List<ItemBrief> rows = new ArrayList<>(Math.max(0, to - from));
        for (WebSnapshot.ItemRow r : hits.subList(from, to)) {
            String[] meta = itemMeta(r);
            rows.add(new ItemBrief(r.id, r.name, r.count, r.stacks, r.category, r.refs,
                    meta[0], meta[1]));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", hits.size());
        out.put("page", page);
        out.put("pages", pages);
        out.put("size", size);
        out.put("sort", q.sort());
        out.put("category", q.category());
        out.put("keyword", q.keyword());
        out.put("region", q.region());
        out.put("items", rows);
        return out;
    }

    /** 单个物品详情：全部存放位置（对应网页 /api/item） */
    private static Map<String, Object> item(WebSnapshot s, ViewQueryPayload q) {
        WebSnapshot.ItemRow row = s.item(q.key());
        if (row == null) {
            return Map.of("error", "索引中不存在该物品。");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", row);
        return out;
    }

    /** 容器清单（可按坐标 / 方块名模糊筛） */
    private static Map<String, Object> containers(WebSnapshot s, ViewQueryPayload q) {
        List<WebSnapshot.ContainerRow> src = s.containers(q.region());
        String needle = q.keyword() == null ? "" : q.keyword().trim().toLowerCase(Locale.ROOT);
        List<ContainerBrief> rows = new ArrayList<>();
        for (WebSnapshot.ContainerRow c : src) {
            if (!needle.isEmpty()
                    && !c.pos.contains(needle)
                    && !c.blockName.toLowerCase(Locale.ROOT).contains(needle)
                    && !c.block.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            rows.add(new ContainerBrief(c.key, c.pos, c.dimensionName, c.blockName, c.size, c.used,
                    c.doubleChest, List.copyOf(c.regions)));
        }
        // 行数上限：面板「箱子」页是一次性列出全部箱子的（客户端没有分页），所以不能用
        // q.size() 截断；但也不能无上限 —— 单个仓库几千只箱子会被整份序列化回客户端
        //（响应几百 KB、占用服务端 tick，而请求本身只有几十字节）。这里给一个宽松上限，
        // 超出时在结果里标记 truncated，客户端照旧显示前十……到上限为止。
        boolean truncated = rows.size() > CONTAINER_MAX;
        List<ContainerBrief> page = truncated ? List.copyOf(rows.subList(0, CONTAINER_MAX)) : List.copyOf(rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("region", q.region());
        out.put("keyword", q.keyword());
        out.put("truncated", truncated);
        out.put("containers", page);
        return out;
    }

    /** 单个容器的逐格内容（对应网页 /api/container） */
    private static Map<String, Object> container(WebSnapshot s, ViewQueryPayload q) {
        WebSnapshot.ContainerDetail d = s.container(q.key());
        if (d == null) {
            return Map.of("error", "索引中不存在该容器，可能刚重新扫描过，请刷新后重试。");
        }
        // 逐格附魔 / 自定义名必须从 ItemStack 上读，而快照里的 SlotRow 只留了 id / 名字 / 数量，
        // 所以按槽位再从索引记录里取一次 ItemStack。索引正好在这一刻被换掉时只退化成「无附魔 / 无自定义名」，
        // 不影响 id、名字、数量这些从快照来的字段。
        Map<Integer, ItemStack> stacks = new HashMap<>();
        ContainerRecord rec = WarehouseMod.INDEX.containers.get(d.key);
        if (rec != null) {
            for (ContainerRecord.StoredStack ss : rec.contents) {
                stacks.put(ss.slot(), ss.stack());
            }
        }
        List<SlotDetail> rows = new ArrayList<>(d.items.size());
        for (WebSnapshot.SlotRow r : d.items) {
            ItemStack st = stacks.get(r.slot);
            rows.add(new SlotDetail(r.slot, r.id, r.name,
                    st == null ? "" : Scanner.customNameText(st),
                    r.count, r.category,
                    st == null ? "" : Scanner.enchantText(st)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("container", new ContainerDetailBrief(d, rows));
        return out;
    }

    /** 与网页端 /api/items 完全一样的排序口径 */
    private static void sort(List<WebSnapshot.ItemRow> hits, String mode) {
        String m = mode == null ? "" : mode;
        Collator cn = Collator.getInstance(Locale.CHINA);
        switch (m) {
            case "count_asc" -> hits.sort(Comparator.comparingLong(r -> r.count));
            case "name" -> hits.sort(Comparator.comparing(r -> r.name, cn));
            case "id" -> hits.sort(Comparator.comparing(r -> r.id));
            case "category" -> hits.sort(Comparator
                    .comparing((WebSnapshot.ItemRow r) -> r.category)
                    .thenComparing(r -> r.name, cn));
            case "refs" -> hits.sort(Comparator
                    .comparingInt((WebSnapshot.ItemRow r) -> r.refs).reversed()
                    .thenComparing(r -> r.name, cn));
            default -> {
                // 快照本身就是数量降序，保持原序即可
            }
        }
    }

    // ------------------------------------------------------------------
    // 发包用的瘦身结构（面板只画这些字段）

    private record RegionBrief(String name, int containers, int kinds, long items, int usedSlots,
                               int capacity) {
    }

    private record CategoryBrief(String name, int kinds, long items, long slots) {
    }

    /**
     * 物品列表（物品维度）的一行。
     *
     * <p>{@code ench} / {@code customName}（0.23.0 · 优化11）：物品是按 id 聚合的，所以这两个字段给的是
     * 该物品在<b>所有槽位</b>上的并集 —— {@code ench} 是去重后的 {@code 注册名@等级} 用 {@code ,}
     * 连接（与 {@code SlotDetail.ench} 同格式，客户端同一个解析器就能显示）；{@code customName} 是
     * 去重后的自定义名用 {@code ; } 连接。无附魔 / 未改名时是空串（老服务端返回缺字段，客户端照旧当空）。
     */
    private record ItemBrief(String id, String name, long count, String stacks, String category,
                             int refs, String ench, String customName) {
    }

    /** 物品维度一行最多拼多少条附魔 / 多少个自定义名（一件物品可能散落在几百只箱子里） */
    private static final int ITEM_META_MAX = 12;
    private static final int ITEM_NAME_MAX = 3;

    /**
     * 物品维度（物品列表一行）的附魔 / 自定义名并集。
     *
     * <p>只读内存索引里的 ItemStack —— 不扫世界、不碰区块（与 {@link #container} 逐格取的是同一份
     * 数据）。0.23.0 起索引从磁盘恢复时会把逐格附魔装回 ItemStack（{@code IndexStore} format 4），
     * 所以重启之后这里照样有货。
     *
     * @return {@code [ench, customName]}，都没命中就是两个空串
     */
    private static String[] itemMeta(WebSnapshot.ItemRow r) {
        TreeSet<String> ench = new TreeSet<>();
        TreeSet<String> names = new TreeSet<>();
        if (r != null && r.locations != null) {
            for (WebSnapshot.LocRow loc : r.locations) {
                if (ench.size() >= ITEM_META_MAX && names.size() >= ITEM_NAME_MAX) {
                    break;
                }
                ContainerRecord rec = WarehouseMod.INDEX.containers.get(
                        WarehouseIndex.key(loc.dimension, new BlockPos(loc.x, loc.y, loc.z)));
                if (rec == null) {
                    continue;
                }
                for (ContainerRecord.StoredStack ss : rec.contents) {
                    if (ss.slot() != loc.slot) {
                        continue;
                    }
                    ItemStack st = ss.stack();
                    String e = Scanner.enchantText(st);
                    if (!e.isEmpty()) {
                        for (String one : e.split(",")) {
                            String t = one.trim();
                            if (!t.isEmpty() && ench.size() < ITEM_META_MAX) {
                                ench.add(t);
                            }
                        }
                    }
                    String n = Scanner.customNameText(st);
                    if (!n.isEmpty() && names.size() < ITEM_NAME_MAX) {
                        names.add(n);
                    }
                }
            }
        }
        return new String[]{String.join(",", ench), String.join("; ", names)};
    }

    private record ContainerBrief(String key, String pos, String dimensionName, String blockName,
                                  int size, int used, boolean doubleChest, List<String> regions) {
    }

    /**
     * 容器详情（对应网页 /api/container 与面板的「箱子 → 点一行 → 容器详情」）。
     * 字段与 {@link WebSnapshot.ContainerDetail} 一一对应，只把 {@code items} 换成带附魔 / 自定义名的
     * {@link SlotDetail} 列表。
     */
    private record ContainerDetailBrief(String key, String dimension, String dimensionName, String pos,
                                        int x, int y, int z, String block, String blockName, int size,
                                        int used, boolean doubleChest, List<String> regions,
                                        long totalItems, List<SlotDetail> items) {
        ContainerDetailBrief(WebSnapshot.ContainerDetail d, List<SlotDetail> items) {
            this(d.key, d.dimension, d.dimensionName, d.pos, d.x, d.y, d.z, d.block, d.blockName,
                    d.size, d.used, d.doubleChest, d.regions, d.totalItems, items);
        }
    }

    /**
     * 容器详情里的一格。
     *
     * <p>{@code ench}：这一格物品的附魔，形如 {@code minecraft:sharpness@5,minecraft:unbreaking@3}
     * （注册名@等级，按注册名排序，用 {@code ,} 连接）；没有附魔时是空串。
     *
     * <p>{@code customName}：物品的自定义名原文（铁砧 / 命名牌改过的名字）；没有时是空串。
     * 契约里这个字段叫 {@code name}，但 {@link WebSnapshot.SlotRow#name} 已经是「物品显示名」
     * （客户端 {@code WarehouseScreen.queryItemName} 会拿它当本地名的回退），把 {@code name}
     * 改成自定义名会让面板上的物品名在客户端查不到本地名时变成空串，所以这里保留
     * {@code name} = 显示名，自定义名另给 {@code customName}。
     */
    private record SlotDetail(int slot, String id, String name, String customName, int count,
                              String category, String ench) {
    }
}
