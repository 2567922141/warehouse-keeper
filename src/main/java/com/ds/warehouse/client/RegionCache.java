package com.ds.warehouse.client;

import com.ds.warehouse.util.Names;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端侧的仓库列表缓存。
 *
 * <p>数据来源是**服务端推过来的快照**（{@link ClientSnapshot}），不再读本机的
 * {@code regions.json} —— 联机时本机根本没有那个文件。界面本身依旧不含业务逻辑：
 * 它只是把列表画出来，然后把玩家的点按翻译成一条 /warehouse 指令发给服务端。
 */
public final class RegionCache {

    /** 与 regions.json 里的一条记录一一对应。 */
    public static final class Entry {
        /** 全高度时体积估算用的世界高度（-64 ~ 320） */
        private static final int WORLD_HEIGHT = 384;

        public String name = "";
        public String dimension = "minecraft:overworld";
        public int[] from = new int[]{0, 0, 0};
        public int[] to = new int[]{0, 0, 0};
        public boolean fullHeight = true;

        public int minX() { return Math.min(from[0], to[0]); }
        public int minY() { return Math.min(from[1], to[1]); }
        public int minZ() { return Math.min(from[2], to[2]); }
        public int maxX() { return Math.max(from[0], to[0]); }
        public int maxY() { return Math.max(from[1], to[1]); }
        public int maxZ() { return Math.max(from[2], to[2]); }

        public long volume() {
            int h = fullHeight ? WORLD_HEIGHT : (maxY() - minY() + 1);
            return (long) (maxX() - minX() + 1) * h * (maxZ() - minZ() + 1);
        }

        public String sizeText() {
            int dx = maxX() - minX() + 1;
            int dz = maxZ() - minZ() + 1;
            if (fullHeight) {
                return dx + "x" + dz + " · 全高度";
            }
            return dx + "x" + (maxY() - minY() + 1) + "x" + dz;
        }

        public String cornerText() {
            if (fullHeight) {
                return minX() + ",*," + minZ() + "  →  " + maxX() + ",*," + maxZ();
            }
            return minX() + "," + minY() + "," + minZ() + "  →  " + maxX() + "," + maxY() + "," + maxZ();
        }

        public String dimensionText() {
            return Names.dimension(dimension);
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static String note = "尚未收到服务端数据";

    private RegionCache() {
    }

    public static List<Entry> list() {
        return ENTRIES;
    }

    public static String note() {
        return note;
    }

    /**
     * 从服务端快照重建列表。
     *
     * <p>force 参数保留是为了兼容原来的调用点：现在的数据来自网络包而不是文件，
     * 重建一份列表很便宜（仓库数量是几十个量级），所以每次调用都直接重建。
     */
    public static void refresh(boolean force) {
        List<ClientSnapshot.Region> src = ClientSnapshot.regions();
        note = ClientSnapshot.note();
        ENTRIES.clear();
        for (ClientSnapshot.Region r : src) {
            Entry e = new Entry();
            e.name = r.name == null ? "" : r.name;
            if (r.dimension != null && !r.dimension.isEmpty()) {
                e.dimension = r.dimension;
            }
            e.fullHeight = r.fullHeight;
            e.from = r.from == null ? new int[]{0, 0, 0} : r.from.clone();
            e.to = r.to == null ? new int[]{0, 0, 0} : r.to.clone();
            ENTRIES.add(e);
        }
    }
}
