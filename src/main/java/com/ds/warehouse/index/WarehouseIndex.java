package com.ds.warehouse.index;

import net.minecraft.core.BlockPos;

import com.ds.warehouse.config.Region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        revision++;
        if (rec.partner != null) {
            // 同一个物理箱子只许留一条记录：合并过的那条进来了，就把搭档坐标上的旧记录清掉
            ContainerRecord ghost = containers.remove(key(rec.dimension, rec.partner));
            if (ghost != null) {
                deaggregate(ghost);
            }
        }
        ContainerRecord old = containers.put(key(rec.dimension, rec.pos), rec);
        if (old == null) {
            scannedContainers++;
        } else {
            // 同一个坐标被扫到两次（容器同时落在两个重叠区域里）时，必须先把旧记录的聚合减掉，
            // 否则物品总数会凭空翻倍。实测：2,101,2 同时在 roof 与 t1 里，全量扫描后 dirt 1664 → 3328。
            deaggregate(old);
        }
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
            if (containers.remove(key(rec.dimension, rec.partner)) != null) {
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
            ContainerRecord rec = it.next().getValue();
            if (insideAny(rec, regions)) {
                it.remove();
                revision++;
                n++;
            }
        }
        return n;
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
