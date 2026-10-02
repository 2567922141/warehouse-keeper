package com.ds.warehouse.web;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.Region;
import com.ds.warehouse.config.RegionStore;
import com.ds.warehouse.index.ContainerRecord;
import com.ds.warehouse.index.ItemIds;
import com.ds.warehouse.index.Scanner;
import com.ds.warehouse.index.WarehouseIndex;
import com.ds.warehouse.util.Categories;
import com.ds.warehouse.util.Names;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 仓库数据快照（原为网页端准备，网页端已移除，现在供游戏内面板的按需查询使用）。
 *
 * 为什么要有这一层：
 * 索引（WarehouseIndex）会被扫描、局部重扫与整理过程不断修改，而面板查询
 * 需要一份内容稳定的数据。所以这里在**服务端线程上**把索引压成一份不可变的快照，
 * 查询侧只读快照 —— 零锁、零竞态。
 *
 * 快照重建时机由 Scanner.tick()（每 tick 调用一次）驱动：
 *   - 标记了 dirty（清空索引 / 扫描开始 / 扫描结束）
 *   - 扫描进行中，每 20 tick（1 秒）刷一次进度
 *
 * 关于「按仓库查看」：
 * 一个存档里可以有多个仓库区域。容器的归属是**按坐标几何判断**的 —— 用当前
 * regions.json 里的区域去框每个容器的坐标。这样做的好处是：区域改大改小之后，
 * 网页上的归属立刻跟着变，不需要重新扫描，也不用往索引文件里加字段。
 * 区域可以重叠，一个容器因此可以同时属于多个仓库。
 */
public final class WebSnapshot {

    /** 扫描进行中时，进度刷新间隔（tick） */
    private static final int REFRESH_TICKS_WHILE_SCANNING = 20;

    /** 「全部仓库」这个视图的 key */
    public static final String ALL = "";

    private static volatile WebSnapshot INSTANCE;
    private static boolean dirty = true;
    private static int tickCounter;

    // ---- 快照内容（全部不可变） ----

    public final long generatedAt = System.currentTimeMillis();
    public final boolean scannerRunning;
    public final String scannerProgress;
    public final long scannedContainers;
    public final long scannedChunks;
    public final long skippedChunks;
    public final long totalStacks;
    public final long totalItems;
    public final long lastScanMillis;
    public final String lastScanRegion;

    /** 这份数据是从硬盘恢复的（还没重新扫描过）—— 网页上要提示用户「可能不是最新」 */
    public final boolean restoredFromDisk;
    /** 恢复时硬盘记录的保存时间戳 */
    public final long restoredSavedAt;

    /** 有哪些仓库可以选（网页顶部那一排按钮），按 regions.json 里的顺序 */
    public final List<RegionRow> regions;

    /** 「全部仓库」视图 —— 这些字段保持旧含义，其它代码不用改 */
    public final List<ItemRow> items;
    public final List<ContainerRow> containers;
    public final List<CategoryRow> categories;
    private final Map<String, ItemRow> byId;
    /** key = dimension@x,y,z，点开某个容器卡片时按 key 取它的详细内容 */
    private final Map<String, ContainerDetail> details;

    /** key = 仓库名（{@link #ALL} 表示全部），value = 那个仓库筛选后的视图 */
    private final Map<String, View> views;

    private WebSnapshot(WarehouseIndex idx) {
        this.scannerRunning = Scanner.isRunning();
        this.scannerProgress = Scanner.progressText();
        this.scannedContainers = idx.scannedContainers;
        this.scannedChunks = idx.scannedChunks;
        this.skippedChunks = idx.skippedChunks;
        this.totalStacks = idx.totalStacks;
        this.totalItems = idx.totalItems;
        this.lastScanMillis = idx.lastScanMillis;
        this.lastScanRegion = idx.lastScanRegion;
        this.restoredFromDisk = idx.restoredFromDisk;
        this.restoredSavedAt = idx.restoredSavedAt;

        List<ContainerRecord> allRecs = new ArrayList<>(idx.containers.values());

        // 1) 算出每个容器落在哪些仓库里（区域可以重叠 → 一个容器可以属于多个仓库）
        Map<String, Region> defs = new LinkedHashMap<>(RegionStore.REGIONS);
        Map<String, List<ContainerRecord>> perRegion = new LinkedHashMap<>();
        for (String name : defs.keySet()) {
            perRegion.put(name, new ArrayList<>());
        }
        Map<String, List<String>> owner = new HashMap<>();
        for (ContainerRecord rec : allRecs) {
            List<String> mine = new ArrayList<>();
            for (Map.Entry<String, Region> en : defs.entrySet()) {
                Region r = en.getValue();
                if (r == null || !dimensionOf(r).equals(rec.dimension)) {
                    continue;
                }
                if (r.contains(rec.pos)) {
                    mine.add(en.getKey());
                    perRegion.get(en.getKey()).add(rec);
                }
            }
            owner.put(WarehouseIndex.key(rec.dimension, rec.pos), List.copyOf(mine));
        }

        // 2) 每个容器的详细内容（点开卡片时用），顺便带上它属于哪些仓库
        Map<String, ContainerDetail> dets = new HashMap<>();
        for (ContainerRecord rec : allRecs) {
            String key = WarehouseIndex.key(rec.dimension, rec.pos);
            dets.put(key, new ContainerDetail(rec, owner.getOrDefault(key, List.of())));
        }
        this.details = Map.copyOf(dets);

        // 3) 「全部」视图 + 每个仓库一个视图
        Map<String, View> vs = new LinkedHashMap<>();
        View all = buildView(allRecs, owner);
        vs.put(ALL, all);
        List<RegionRow> rows = new ArrayList<>(defs.size());
        for (Map.Entry<String, List<ContainerRecord>> en : perRegion.entrySet()) {
            View v = buildView(en.getValue(), owner);
            vs.put(en.getKey(), v);
            rows.add(new RegionRow(en.getKey(), v));
        }
        this.views = Map.copyOf(vs);
        this.regions = List.copyOf(rows);

        this.items = all.items;
        this.byId = all.byId;
        this.categories = all.categories;
        this.containers = all.containers;
    }

    /** 把一批容器聚合成一份可查询的视图（物品 / 容器 / 分类 / 汇总） */
    private static View buildView(List<ContainerRecord> recs, Map<String, List<String>> owner) {
        Map<String, Agg> aggs = new LinkedHashMap<>();
        List<ContainerRow> rows = new ArrayList<>(recs.size());
        long capacity = 0;
        long usedSlots = 0;
        long totalItems = 0;
        int empty = 0;

        for (ContainerRecord rec : recs) {
            String key = WarehouseIndex.key(rec.dimension, rec.pos);
            rows.add(new ContainerRow(rec, owner.getOrDefault(key, List.of())));
            capacity += rec.size;
            usedSlots += rec.contents.size();
            if (rec.contents.isEmpty()) {
                empty++;
            }
            for (ContainerRecord.StoredStack ss : rec.contents) {
                int count = ss.stack().getCount();
                if (count <= 0) {
                    continue;
                }
                totalItems += count;
                String id = ItemIds.of(ss.stack());
                Agg a = aggs.get(id);
                if (a == null) {
                    a = new Agg(id, ss.stack().getHoverName().getString());
                    aggs.put(id, a);
                }
                a.total += count;
                a.maxStack = Math.max(a.maxStack, ss.stack().getMaxStackSize());
                a.locs.add(new LocRow(new WarehouseIndex.SlotRef(
                        rec.dimension, rec.pos, ss.slot(), count, rec.blockId)));
            }
        }

        rows.sort(Comparator.comparing(ContainerRow::posText));

        List<ItemRow> itemRows = new ArrayList<>(aggs.size());
        for (Agg a : aggs.values()) {
            itemRows.add(new ItemRow(a));
        }
        itemRows.sort(Comparator.comparingLong((ItemRow r) -> r.count).reversed());

        Map<String, ItemRow> byId = new HashMap<>();
        for (ItemRow r : itemRows) {
            byId.put(r.id, r);
        }
        return new View(List.copyOf(itemRows), Map.copyOf(byId), List.copyOf(rows),
                buildCategories(itemRows), capacity, usedSlots, totalItems, empty);
    }

    private static String dimensionOf(Region r) {
        String d = r.dimension;
        return d == null || d.isBlank() ? "minecraft:overworld" : d;
    }

    private static List<CategoryRow> buildCategories(List<ItemRow> items) {
        Map<String, long[]> agg = new LinkedHashMap<>();
        Map<String, Integer> kinds = new LinkedHashMap<>();
        // 行顺序 = Categories.order()（创造栏页签顺序 + 「其他」），与命令层 /warehouse categories stats 一致；
        // CategoryRow.name 仍是**键**，显示名由客户端自己翻（协议字段语义没变）
        for (String c : Categories.order()) {
            agg.put(c, new long[]{0, 0});
            kinds.put(c, 0);
        }
        for (ItemRow r : items) {
            long[] v = agg.computeIfAbsent(r.category, k -> new long[]{0, 0});
            v[0] += r.count;
            v[1] += r.refs;
            kinds.merge(r.category, 1, Integer::sum);
        }
        List<CategoryRow> out = new ArrayList<>();
        for (Map.Entry<String, long[]> en : agg.entrySet()) {
            long[] v = en.getValue();
            out.add(new CategoryRow(en.getKey(), kinds.getOrDefault(en.getKey(), 0), v[0], v[1]));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // 重建调度

    /** 索引内容发生变化（清空 / 扫描开始 / 扫描结束）时调用 */
    public static void markDirty() {
        dirty = true;
    }

    private static boolean lastRunning;
    private static long lastRevision = -1;
    private static int lastItemCount = -1;
    private static long lastStacks = -1;
    private static long lastTotalItems = -1;
    private static int lastRegionCount = -1;

    /**
     * 每 tick 调用（必须排在 Scanner.tick() 之后）。
     *
     * 重建策略：
     *  - 扫描进行中：每 1 秒重建一次（否则每 tick 都会因为新增物品而变，白白重建）
     *  - 空闲时：只在索引真的变了（清空 / 扫描结束 / 仓库增删）时重建
     */
    public static void serverTick() {
        tickCounter++;
        boolean running = Scanner.isRunning();
        WarehouseIndex idx = WarehouseMod.INDEX;
        int regionCount = RegionStore.REGIONS.size();

        boolean scanRefresh = running && tickCounter % REFRESH_TICKS_WHILE_SCANNING == 0;
        // revision 是「索引内容真的变过」的唯一可靠信号：搬运工从一叠 64 个里拿走 7 个，
        // 物品种类数 / 非空格子数 / 区域数都不会变，早先就是因此漏掉了重建。
        boolean idleChanged = !running
                && (lastRunning
                        || idx.revision() != lastRevision
                        || idx.items.size() != lastItemCount
                        || idx.totalStacks != lastStacks
                        || idx.totalItems != lastTotalItems
                        || regionCount != lastRegionCount);

        if (!dirty && !scanRefresh && !idleChanged) {
            return;
        }
        dirty = false;
        lastRunning = running;
        lastRevision = idx.revision();
        lastItemCount = idx.items.size();
        lastStacks = idx.totalStacks;
        lastTotalItems = idx.totalItems;
        lastRegionCount = regionCount;
        try {
            INSTANCE = new WebSnapshot(idx);
        } catch (Throwable t) {
            WarehouseMod.LOGGER.error("[warehouse-keeper] 生成网页快照失败", t);
        }
    }

    public static WebSnapshot current() {
        WebSnapshot s = INSTANCE;
        if (s == null) {
            // 还没到 tick 就先来了一次请求（例如刚开服）——退化成一次性构建
            s = new WebSnapshot(WarehouseMod.INDEX);
            INSTANCE = s;
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 查询

    /** 按仓库名取视图；名字为空 / 「全部」/ 找不到都退回全部视图 */
    public View view(String region) {
        View all = views.get(ALL);
        if (region == null) {
            return all;
        }
        String k = region.trim();
        if (k.isEmpty() || k.equals("全部") || k.equalsIgnoreCase("all")) {
            return all;
        }
        View v = views.get(k);
        return v == null ? all : v;
    }

    /** 支持关键字 + 分类 + 仓库过滤，按数量降序 */
    public List<ItemRow> query(String keyword, String category, String region) {
        List<ItemRow> src = view(region).items;
        String q = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        boolean allCat = category == null || category.isEmpty() || category.equals("全部");
        if (q.isEmpty() && allCat) {
            return src;
        }
        List<ItemRow> out = new ArrayList<>();
        for (ItemRow r : src) {
            if (!allCat && !r.category.equals(category)) {
                continue;
            }
            if (!q.isEmpty()
                    && !r.id.toLowerCase(Locale.ROOT).contains(q)
                    && !r.name.toLowerCase(Locale.ROOT).contains(q)
                    // r.name 是建快照时算好的：专用服务端的 Language 多半是 en_us，那时还没有客户端推来的
                    // 名字表，缓存里就是英文名 —— 中文搜索必须用 Names.item() 实时再取一次。
                    && !Names.item(r.id).toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    public List<ItemRow> query(String keyword, String category) {
        return query(keyword, category, null);
    }

    /** 某个仓库里的容器（region 为空 = 全部） */
    public List<ContainerRow> containers(String region) {
        return view(region).containers;
    }

    public ItemRow item(String id) {
        if (id == null) {
            return null;
        }
        return byId.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** 单个容器的详细内容（网页上点开容器卡片时用） */
    public ContainerDetail container(String key) {
        if (key == null) {
            return null;
        }
        return details.get(key.trim());
    }

    // ------------------------------------------------------------------
    // 行结构（Gson 直接序列化这些字段）

    /** 一个仓库筛选后的完整视图 */
    public static final class View {
        public final List<ItemRow> items;
        public final List<ContainerRow> containers;
        public final List<CategoryRow> categories;
        public final long capacity;
        public final long usedSlots;
        public final long totalItems;
        public final int emptyContainers;
        final Map<String, ItemRow> byId;

        View(List<ItemRow> items, Map<String, ItemRow> byId, List<ContainerRow> containers,
                List<CategoryRow> categories, long capacity, long usedSlots, long totalItems,
                int emptyContainers) {
            this.items = items;
            this.byId = byId;
            this.containers = containers;
            this.categories = categories;
            this.capacity = capacity;
            this.usedSlots = usedSlots;
            this.totalItems = totalItems;
            this.emptyContainers = emptyContainers;
        }
    }

    /** 网页顶部那一排仓库按钮用的汇总 */
    public static final class RegionRow {
        public final String name;
        public final int containers;
        public final int kinds;
        public final long items;
        public final int usedSlots;
        public final int capacity;

        RegionRow(String name, View v) {
            this.name = name;
            this.containers = v.containers.size();
            this.kinds = v.items.size();
            this.items = v.totalItems;
            this.usedSlots = (int) v.usedSlots;
            this.capacity = (int) v.capacity;
        }
    }

    /** 聚合过程中的临时累加器（不是快照字段，只在校验内使用） */
    private static final class Agg {
        final String id;
        final String name;
        long total;
        int maxStack = 64;
        final List<LocRow> locs = new ArrayList<>();

        Agg(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class ItemRow {
        public final String id;
        public final String name;
        public final long count;
        public final String stacks;
        public final int maxStack;
        public final String category;
        public final int refs;
        public final List<LocRow> locations;

        ItemRow(Agg a) {
            this.id = a.id;
            this.name = a.name;
            this.count = a.total;
            this.maxStack = a.maxStack;
            this.category = Categories.of(a.id);
            this.refs = a.locs.size();
            this.locations = List.copyOf(a.locs);
            this.stacks = stacksText(a.total, a.maxStack);
        }

        ItemRow(WarehouseIndex.ItemEntry e) {
            this.id = e.itemId;
            this.name = e.displayName;
            this.count = e.total;
            this.stacks = e.stacksText();
            this.maxStack = e.maxStackSize;
            this.category = Categories.of(e.itemId);
            this.refs = e.refs.size();
            List<LocRow> locs = new ArrayList<>(e.refs.size());
            for (WarehouseIndex.SlotRef ref : e.refs) {
                locs.add(new LocRow(ref));
            }
            this.locations = List.copyOf(locs);
        }

        private static String stacksText(long total, int maxStack) {
            if (maxStack <= 1) {
                return total + " 个";
            }
            long full = total / maxStack;
            long rest = total % maxStack;
            if (full == 0) {
                return total + " 个";
            }
            return total + " 个 (" + full + " 组" + (rest > 0 ? " + " + rest : "") + ")";
        }
    }

    public static final class LocRow {
        public final String dimension;
        /** 维度的显示名（主世界 / 下界 / 末地…） */
        public final String dimensionName;
        public final String pos;
        public final int x;
        public final int y;
        public final int z;
        public final int slot;
        public final int count;
        public final String block;
        /** 方块显示名（箱子 / 水晶箱…），界面优先显示这个，block 留作搜索与排查 */
        public final String blockName;

        LocRow(WarehouseIndex.SlotRef ref) {
            this.dimension = ref.dimension();
            this.dimensionName = Names.dimension(ref.dimension());
            this.x = ref.pos().getX();
            this.y = ref.pos().getY();
            this.z = ref.pos().getZ();
            this.pos = x + "," + y + "," + z;
            this.slot = ref.slot();
            this.count = ref.count();
            this.block = ref.blockId();
            this.blockName = Names.block(ref.blockId());
        }
    }

    public static final class ContainerRow {
        /** 唯一标识 dimension@x,y,z，点开卡片时拿它去 /api/container 取内容 */
        public final String key;
        public final String dimension;
        /** 维度的显示名（主世界 / 下界 / 末地…） */
        public final String dimensionName;
        public final String pos;
        public final int x;
        public final int y;
        public final int z;
        public final String block;
        /** 方块显示名（箱子 / 水晶箱…），界面优先显示这个，block 留作搜索与排查 */
        public final String blockName;
        public final int size;
        public final int used;
        /** 是不是原版双联箱（两个箱子拼成的大箱子） */
        public final boolean doubleChest;
        /** 这个箱子落在哪些仓库区域里（可能不止一个，区域可以重叠） */
        public final List<String> regions;

        ContainerRow(ContainerRecord rec, List<String> regions) {
            this.dimension = rec.dimension;
            this.dimensionName = Names.dimension(rec.dimension);
            this.x = rec.pos.getX();
            this.y = rec.pos.getY();
            this.z = rec.pos.getZ();
            this.pos = x + "," + y + "," + z;
            this.key = rec.dimension + "@" + pos;
            this.block = rec.blockId;
            this.blockName = Names.block(rec.blockId);
            this.size = rec.size;
            this.used = rec.contents.size();
            this.doubleChest = rec.doubleChest;
            this.regions = regions == null ? List.of() : regions;
        }

        String posText() {
            return pos;
        }
    }

    /** 一个容器里到底装了什么（点开卡片时返回） */
    public static final class ContainerDetail {
        public final String key;
        public final String dimension;
        public final String dimensionName;
        public final String pos;
        public final int x;
        public final int y;
        public final int z;
        public final String block;
        public final String blockName;
        public final int size;
        public final int used;
        public final boolean doubleChest;
        /** 这个箱子落在哪些仓库区域里 */
        public final List<String> regions;
        /** 这个容器里物品的总个数（不是槽位数） */
        public final long totalItems;
        /** 按槽位升序 —— 和游戏里打开箱子看到的顺序一致 */
        public final List<SlotRow> items;

        ContainerDetail(ContainerRecord rec, List<String> regions) {
            this.dimension = rec.dimension;
            this.dimensionName = Names.dimension(rec.dimension);
            this.x = rec.pos.getX();
            this.y = rec.pos.getY();
            this.z = rec.pos.getZ();
            this.pos = x + "," + y + "," + z;
            this.key = rec.dimension + "@" + pos;
            this.block = rec.blockId;
            this.blockName = Names.block(rec.blockId);
            this.size = rec.size;
            this.used = rec.contents.size();
            this.doubleChest = rec.doubleChest;
            this.regions = regions == null ? List.of() : regions;
            List<SlotRow> rows = new ArrayList<>(rec.contents.size());
            long total = 0;
            for (ContainerRecord.StoredStack ss : rec.contents) {
                rows.add(new SlotRow(ss));
                total += ss.stack().getCount();
            }
            // 扫描时就是按槽位从小到大塞进来的，这里再排一次纯粹是保险
            rows.sort(Comparator.comparingInt((SlotRow r) -> r.slot));
            this.items = List.copyOf(rows);
            this.totalItems = total;
        }
    }

    /** 容器里某一格的东西 */
    public static final class SlotRow {
        public final int slot;
        public final String id;
        public final String name;
        public final int count;
        public final String category;
        /** 自定义名原文（用铁砧 / 命名牌改过才有）；没有时是空串 */
        public final String customName;
        /** 附魔，形如 {@code minecraft:sharpness@5,minecraft:unbreaking@3}；没有时是空串 */
        public final String ench;

        SlotRow(ContainerRecord.StoredStack ss) {
            this.slot = ss.slot();
            this.id = ItemIds.of(ss.stack());
            this.name = ss.stack().getHoverName().getString();
            this.count = ss.stack().getCount();
            this.category = Categories.of(id);
            // 附魔 / 自定义名只有 ItemStack 上有，id + 数量之外的信息都从这里现取（优化11）
            this.customName = Scanner.customNameText(ss.stack());
            this.ench = Scanner.enchantText(ss.stack());
        }
    }

    public static final class CategoryRow {
        public final String name;
        public final int kinds;
        public final long items;
        public final long slots;

        CategoryRow(String name, int kinds, long items, long slots) {
            this.name = name;
            this.kinds = kinds;
            this.items = items;
            this.slots = slots;
        }
    }
}
