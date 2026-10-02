package com.ds.warehouse.index;

import net.minecraft.core.BlockPos;

import com.ds.warehouse.config.Region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 仓库索引：容器 → 物品 的双向聚合。
 *
 * 运行时是纯内存结构（查询要快就必须在内存里）。持久化交给 {@link IndexStore}：
 * 存到 config/warehouse-keeper/index/，**不写 world/**。
 */
public final class WarehouseIndex {

    /** key = dimension@x,y,z */
    public final Map<String, ContainerRecord> containers = new LinkedHashMap<>();
    /** key = 物品 id */
    public final Map<String, ItemEntry> items = new LinkedHashMap<>();

    /**
     * 附魔索引：附魔注册名（含命名空间）→ (容器 key → 该附魔在这个容器里的最高等级)。
     *
     * <p>内层用 TreeMap，所以 {@link #enchantHits} 拿到的顺序天然按容器 key 排好。
     * 条目由 {@link #addItemMeta} 写入，容器记录消失时由本类的各条删除路径同步清掉。
     */
    private final Map<String, Map<String, Integer>> enchants = new TreeMap<>();
    /** 自定义名索引（名字已转小写）→ 出现过这个名字的容器 key；TreeSet 保证顺序稳定 */
    private final Map<String, Set<String>> customNames = new TreeMap<>();

    // ---- 最近一次扫描的统计 ----
    public long scannedContainers;
    public long scannedChunks;
    public long skippedChunks;
    public long totalStacks;
    public long totalItems;
    public long lastScanMillis;
    public String lastScanRegion = "-";

    /** 这份索引是从硬盘恢复来的，而不是本次会话刚扫出来的 */
    public boolean restoredFromDisk;
    /** 从硬盘恢复时，那份索引的保存时间戳；0 = 不是恢复来的 */
    public long restoredSavedAt;

    public static String key(String dimension, BlockPos pos) {
        return dimension + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * 索引内容的版本号：只要 {@link #clear()} / {@link #accept} / {@link #reaggregate()} 被调用过就 +1。
     *
     * <p>网页快照靠它判断「索引真的变了没有」。不能靠 items.size()/totalStacks/区域数去猜：
     * 从一叠 64 个钻石里拿走 7 个，物品种类数、非空格子数、区域数**全都没变**，
     * 只有 {@code totalItems} 变了 —— 早先就是这样漏掉了搬运工取货后的重建，网页一直显示旧数字。
     */
    private long revision;

    public long revision() {
        return revision;
    }

    public void clear() {
        revision++;
        containers.clear();
        items.clear();
        enchants.clear();
        customNames.clear();
        scannedContainers = 0;
        scannedChunks = 0;
        skippedChunks = 0;
        totalStacks = 0;
        totalItems = 0;
        lastScanMillis = 0;
        lastScanRegion = "-";
        restoredFromDisk = false;
        restoredSavedAt = 0;
    }

    /** 接收一个容器快照并聚合进索引 */
    public void accept(ContainerRecord rec) {
        // 被排除的方块（雕纹书架 / 架子，见 Containers）一律不许进索引。
        // 这里是「记录进入索引」的唯一入口：扫描与 IndexStore 从磁盘恢复都走它，所以旧索引里
        // 已经存在的 chiseled_bookshelf / shelf 记录在本次开档恢复时就被丢掉了，不必另做一次清理。
        if (Containers.excluded(rec.blockId)) {
            return;
        }
        revision++;
        if (rec.partner != null) {
            // 同一个物理箱子只许留一条记录：合并过的那条进来了，就把搭档坐标上的旧记录清掉
            String ghostKey = key(rec.dimension, rec.partner);
            ContainerRecord ghost = containers.remove(ghostKey);
            if (ghost != null) {
                deaggregate(ghost);
                dropMeta(ghostKey);
            }
        }
        String ckey = key(rec.dimension, rec.pos);
        ContainerRecord old = containers.put(ckey, rec);
        if (old == null) {
            scannedContainers++;
        } else {
            // 同一个坐标被扫到两次（容器同时落在两个重叠区域里）时，必须先把旧记录的聚合减掉，
            // 否则物品总数会凭空翻倍。实测：2,101,2 同时在 roof 与 t1 里，全量扫描后 dirt 1664 → 3328。
            deaggregate(old);
        }
        // 这一格的记录刚被换成新内容，附魔 / 自定义名索引必须跟着重算：
        // IndexRefresh 轮询发现箱内物品变了时只会调 accept（不会另调 addItemMeta），
        // 只加不减的话「把附魔书取走」之后索引里会一直留着那本书的附魔。
        dropMeta(ckey);
        rebuildMeta(rec);
        aggregate(rec);
    }

    /**
     * 清掉「双联箱搭档坐标上的幽灵记录」。
     *
     * <p>拼箱、区域边界、局部重扫、以及从磁盘恢复老索引，都可能让同一个物理箱子在索引里
     * 留下两条记录（合并的那条 + 搭档坐标上一条旧的）。搬运工整理时会把它们当成两个小箱子，
     * 于是出现「从大箱子搬到大箱子另一半」这种自己搬给自己的移动。每轮扫描收尾扫一遍即可。
     *
     * <p>{@link #accept} 只能加不能减，所以这里删完必须整份重算 items。
     *
     * @return 清掉了几条
     */
    public int dropGhostRecords() {
        int n = 0;
        for (ContainerRecord rec : new ArrayList<>(containers.values())) {
            if (rec.partner == null) {
                continue;
            }
            String ghostKey = key(rec.dimension, rec.partner);
            if (containers.remove(ghostKey) != null) {
                dropMeta(ghostKey);
                n++;
            }
        }
        if (n > 0) {
            reaggregate();
        }
        return n;
    }

    /**
     * 只把「落在这些区域里」的容器记录删掉，别的地方的记录原样留着。
     *
     * <p>这是「局部重扫」的前提。{@link #clear()} 会把整份索引清空，所以只扫一个区域时绝不能
     * 用它 —— 那样没被扫到的区域会从索引里凭空消失，网页和指令都会显示少东西，
     * 直到下一次全量扫描才回来。
     *
     * <p>调用方删完必须自己扫一遍并用 {@link #reaggregate()} 重算汇总。
     *
     * @return 删掉了几条
     */
    public int removeContainersIn(List<Region> regions) {
        if (regions == null || regions.isEmpty()) {
            return 0;
        }
        int n = 0;
        Iterator<Map.Entry<String, ContainerRecord>> it = containers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ContainerRecord> e = it.next();
            if (insideAny(e.getValue(), regions)) {
                it.remove();
                dropMeta(e.getKey());
                revision++;
                n++;
            }
        }
        return n;
    }

    /**
     * 删掉一条容器记录，并把它的贡献从汇总里减掉（<b>不会</b>重算整份 items）。
     *
     * <p>给「按需局部重扫」（{@link com.ds.warehouse.index.IndexRefresh}）用：轮询发现箱子被炸掉、
     * 被 {@code setblock} 换掉时，就地删掉这一条即可。以前这里只能 {@code containers.remove(key)}
     * 再整份 {@link #reaggregate()}，大仓库会因此每秒重算一遍。
     *
     * @return 真的删掉了才返回 true
     */
    public boolean removeContainer(String key) {
        ContainerRecord old = containers.remove(key);
        if (old == null) {
            return false;
        }
        dropMeta(key);
        revision++;
        deaggregate(old);
        scannedContainers = containers.size();
        return true;
    }

    /**
     * 记下一个容器里的附魔与自定义名（扫描时每个容器提交一次，别每格一次）。
     *
     * <p>同一个容器多次调用按「附魔取最大等级、自定义名取并集」合并，所以重叠区域扫两遍也不会把等级刷小。
     * 容器记录被删掉时必须由调用方清掉这里的条目 —— 本类的 {@link #clear()} / {@link #accept} /
     * {@link #removeContainer(String)} / {@link #removeContainersIn(List)} / {@link #dropGhostRecords()}
     * / {@link #removeContainersOutside(Region)} 都做了。
     *
     * @param enchantIdToMaxLevel 附魔注册名（含命名空间）→ 该容器里的最高等级
     * @param customNamesLower    物品自定义名（已转小写）
     */
    public void addItemMeta(String containerKey, Map<String, Integer> enchantIdToMaxLevel, Set<String> customNamesLower) {
        if (containerKey == null) {
            return;
        }
        if (enchantIdToMaxLevel != null) {
            for (Map.Entry<String, Integer> e : enchantIdToMaxLevel.entrySet()) {
                String id = e.getKey();
                Integer level = e.getValue();
                if (id == null || level == null || level <= 0) {
                    continue;
                }
                enchants.computeIfAbsent(id, k -> new TreeMap<>()).merge(containerKey, level, Math::max);
            }
        }
        if (customNamesLower != null) {
            for (String name : customNamesLower) {
                if (name == null || name.isEmpty()) {
                    continue;
                }
                customNames.computeIfAbsent(name, k -> new TreeSet<>()).add(containerKey);
            }
        }
    }

    /**
     * 哪些容器里有这个附魔、最高几级。
     *
     * @return containerKey → 该附魔在这个容器里的最高等级（按 containerKey 排序）；没命中就是空表
     */
    public Map<String, Integer> enchantHits(String enchantId) {
        Map<String, Integer> per = enchantId == null ? null : enchants.get(enchantId);
        return per == null ? new LinkedHashMap<>() : new LinkedHashMap<>(per);
    }

    /** 已经索引到的附魔 id（含命名空间，顺序稳定） */
    public Set<String> enchantIds() {
        return new TreeSet<>(enchants.keySet());
    }

    /**
     * 哪些容器里有带这个自定义名的物品。名字要先转小写（和索引时一致）。
     *
     * @return 容器 key（顺序稳定）；没命中就是空集
     */
    public Set<String> customNameHits(String nameLower) {
        Set<String> keys = nameLower == null ? null : customNames.get(nameLower);
        return keys == null ? new TreeSet<>() : new TreeSet<>(keys);
    }

    /** 已经索引到的自定义名（小写，顺序稳定） */
    public Set<String> customNames() {
        return new TreeSet<>(customNames.keySet());
    }

    /** 把一个容器在附魔 / 自定义名索引里的条目全部清掉（记录被删掉或被新内容替换时用） */
    private void dropMeta(String containerKey) {
        if (containerKey == null) {
            return;
        }
        for (Iterator<Map.Entry<String, Map<String, Integer>>> it = enchants.entrySet().iterator(); it.hasNext(); ) {
            Map<String, Integer> per = it.next().getValue();
            per.remove(containerKey);
            if (per.isEmpty()) {
                it.remove();
            }
        }
        for (Iterator<Map.Entry<String, Set<String>>> it = customNames.entrySet().iterator(); it.hasNext(); ) {
            Set<String> keys = it.next().getValue();
            keys.remove(containerKey);
            if (keys.isEmpty()) {
                it.remove();
            }
        }
    }

    /**
     * 按一条记录里的 ItemStack 重算它的附魔 / 自定义名条目（旧条目由调用方先 {@link #dropMeta} 清掉）。
     *
     * <p>{@link #accept} 是「记录进入索引」的唯一入口，而 {@link com.ds.warehouse.index.IndexRefresh}
     * 轮询发现箱内物品变了时只会调 {@code accept}、不会另调 {@link #addItemMeta}，所以这里必须以
     * 记录里的实际内容为准再收一次，否则取走附魔物品后索引还留着旧附魔。
     */
    private void rebuildMeta(ContainerRecord rec) {
        Map<String, Integer> ench = new TreeMap<>();
        Set<String> names = new LinkedHashSet<>();
        for (ContainerRecord.StoredStack ss : rec.contents) {
            if (ss.stack().getCount() <= 0) {
                continue;
            }
            Scanner.collectMeta(ss.stack(), ench, names);
        }
        addItemMeta(key(rec.dimension, rec.pos), ench, names);
    }

    private static boolean insideAny(ContainerRecord rec, List<Region> regions) {
        for (Region r : regions) {
            if (Scanner.dimensionOf(r).equals(rec.dimension) && r.contains(rec.pos)) {
                return true;
            }
        }
        return false;
    }

    private void aggregate(ContainerRecord rec) {
        for (ContainerRecord.StoredStack ss : rec.contents) {
            int count = ss.stack().getCount();
            if (count <= 0) {
                continue;
            }
            totalStacks++;
            totalItems += count;
            String id = ItemIds.of(ss.stack());
            ItemEntry entry = items.computeIfAbsent(id, k -> new ItemEntry(k, ss.stack().getHoverName().getString()));
            entry.total += count;
            entry.maxStackSize = Math.max(entry.maxStackSize, ss.stack().getMaxStackSize());
            entry.refs.add(new SlotRef(rec.dimension, rec.pos, ss.slot(), count, rec.blockId));
        }
    }

    /** {@link #aggregate} 的逆操作：把一个容器从 items 汇总里原样减掉 */
    private void deaggregate(ContainerRecord rec) {
        for (ContainerRecord.StoredStack ss : rec.contents) {
            int count = ss.stack().getCount();
            if (count <= 0) {
                continue;
            }
            totalStacks--;
            totalItems -= count;
            String id = ItemIds.of(ss.stack());
            ItemEntry entry = items.get(id);
            if (entry == null) {
                continue;
            }
            entry.total -= count;
            for (int r = 0; r < entry.refs.size(); r++) {
                SlotRef ref = entry.refs.get(r);
                if (ref.dimension().equals(rec.dimension) && ref.pos().equals(rec.pos) && ref.slot() == ss.slot()) {
                    entry.refs.remove(r);
                    break;
                }
            }
            if (entry.total <= 0 && entry.refs.isEmpty()) {
                items.remove(id);
            }
        }
    }

    /**
     * 用现有的 containers 重新聚合 items。
     *
     * 搬运工从箱子里取出东西之后，容器快照会被替换掉，这时不重新扫描整个区域也能让
     * 统计数字立刻对上（{@link #accept} 只能加不能减，所以必须整份重算）。
     */
    public void reaggregate() {
        revision++;
        items.clear();
        totalStacks = 0;
        totalItems = 0;
        for (ContainerRecord rec : containers.values()) {
            aggregate(rec);
        }
        scannedContainers = containers.size();
    }

    /** 按总数降序的物品列表 */
    public List<ItemEntry> sortedItems() {
        List<ItemEntry> list = new ArrayList<>(items.values());
        list.sort(Comparator.comparingLong((ItemEntry e) -> e.total).reversed());
        return list;
    }

    public static final class ItemEntry {
        public final String itemId;
        /** 显示名：建索引时算好；客户端推来名字表后由 ViewQueryService.refreshItemNames() 实时刷新 */
        public String displayName;
        public long total;
        public int maxStackSize = 64;
        public final List<SlotRef> refs = new ArrayList<>();

        public ItemEntry(String itemId, String displayName) {
            this.itemId = itemId;
            this.displayName = displayName;
        }

        /** 该物品占了多少个「整组」 */
        public String stacksText() {
            if (maxStackSize <= 1) {
                return total + " 个";
            }
            long full = total / maxStackSize;
            long rest = total % maxStackSize;
            if (full == 0) {
                return total + " 个";
            }
            return total + " 个 (" + full + " 组" + (rest > 0 ? " + " + rest : "") + ")";
        }
    }

    public record SlotRef(String dimension, BlockPos pos, int slot, int count, String blockId) {
        public String coordText() {
            return pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }
}
