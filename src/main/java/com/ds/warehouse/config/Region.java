package com.ds.warehouse.config;

import net.minecraft.core.BlockPos;

/**
 * 一个矩形仓库区域（轴对齐包围盒）。
 */
public class Region {
    /** 全高度时，体积按「世界最高 384 格」估算（-64 ~ 320），只是用来提示大不大 */
    public static final int WORLD_HEIGHT = 384;

    public String name;
    public String dimension;
    public int[] from = new int[3];
    public int[] to = new int[3];

    /**
     * 是否「所有高度都算」。
     *
     * <p>默认 true：圈的时候只看 X/Z 划出的长方形，里面任何高度的箱子都会被算进来，
     * Y 只作为记录保留，不参与判定。老配置文件里没有这个字段 → Gson 保留默认值 true。
     */
    public boolean fullHeight = true;

    /** 给 Gson 用的空构造 */
    public Region() {
    }

    public Region(String name, String dimension, BlockPos a, BlockPos b) {
        this.name = name;
        this.dimension = dimension;
        this.from = new int[]{a.getX(), a.getY(), a.getZ()};
        this.to = new int[]{b.getX(), b.getY(), b.getZ()};
    }

    public BlockPos min() {
        return new BlockPos(
                Math.min(from[0], to[0]),
                Math.min(from[1], to[1]),
                Math.min(from[2], to[2]));
    }

    public BlockPos max() {
        return new BlockPos(
                Math.max(from[0], to[0]),
                Math.max(from[1], to[1]),
                Math.max(from[2], to[2]));
    }

    /**
     * 点是否落在区域内。
     *
     * <p>fullHeight = true 时**只看 X/Z**，任何高度都算在内；
     * fullHeight = false 时三个轴都要满足。
     */
    public boolean contains(BlockPos p) {
        BlockPos lo = min();
        BlockPos hi = max();
        if (p.getX() < lo.getX() || p.getX() > hi.getX()) {
            return false;
        }
        if (p.getZ() < lo.getZ() || p.getZ() > hi.getZ()) {
            return false;
        }
        if (fullHeight) {
            return true;
        }
        return p.getY() >= lo.getY() && p.getY() <= hi.getY();
    }

    /** 扩建：把区域撑大到能包住这个点。原本的范围只会变大，不会缩小。 */
    public void include(BlockPos p) {
        BlockPos lo = min();
        BlockPos hi = max();
        from = new int[]{
                Math.min(lo.getX(), p.getX()),
                Math.min(lo.getY(), p.getY()),
                Math.min(lo.getZ(), p.getZ())};
        to = new int[]{
                Math.max(hi.getX(), p.getX()),
                Math.max(hi.getY(), p.getY()),
                Math.max(hi.getZ(), p.getZ())};
    }

    /** 扩建：并集 —— 把另一个区域的范围整个并进来（适合「仓库旁边又盖了一间」）。 */
    public void merge(Region other) {
        include(other.min());
        include(other.max());
    }

    /**
     * 扩建：朝一个方向扩大 amount 格。
     *
     * <p>方向遵循 Minecraft 惯例：north = -Z，south = +Z，west = -X，east = +X，
     * down = -Y，up = +Y。也接受中文（北/南/西/东/下/上）和单字母缩写。
     *
     * @return null 表示成功，否则是「方向名不认识」的说明
     */
    public String grow(String direction, int amount) {
        String d = direction == null ? "" : direction.trim().toLowerCase(java.util.Locale.ROOT);
        int n = Math.max(1, amount);
        BlockPos lo = min();
        BlockPos hi = max();
        switch (d) {
            case "north", "n", "北" -> lo = lo.offset(0, 0, -n);
            case "south", "s", "南" -> hi = hi.offset(0, 0, n);
            case "west", "w", "西" -> lo = lo.offset(-n, 0, 0);
            case "east", "e", "东" -> hi = hi.offset(n, 0, 0);
            case "down", "d", "下" -> lo = lo.offset(0, -n, 0);
            case "up", "u", "上" -> hi = hi.offset(0, n, 0);
            default -> {
                return "无法识别的方向「" + direction + "」（可用 north/south/east/west/up/down，或 北/南/东/西/上/下）";
            }
        }
        from = new int[]{lo.getX(), lo.getY(), lo.getZ()};
        to = new int[]{hi.getX(), hi.getY(), hi.getZ()};
        return null;
    }

    /**
     * 缩小：朝一个方向缩掉 amount 格（{@link #grow} 的反向操作）。
     *
     * <p>方向遵循 Minecraft 惯例：north = -Z，south = +Z，west = -X，east = +X，
     * down = -Y，up = +Y。也接受中文（北/南/西/东/下/上）和单字母缩写。
     *
     * <p>闭区间语义：每轴至少要留 1 格（下界 == 上界仍合法）。amount 不是正数、
     * 方向不认识、或任何一轴缩成「上界 &lt; 下界」，都会整个失败并**不改动任何字段**。
     * fullHeight = true 时 Y 轴不参与判定（见 {@link #contains}），但按 up/down 缩小
     * 仍会照常改 min/max。
     *
     * @return null 表示成功，否则是失败原因的说明
     */
    public String shrink(String direction, int amount) {
        String d = direction == null ? "" : direction.trim().toLowerCase(java.util.Locale.ROOT);
        if (amount <= 0) {
            return "缩小的格数必须是正整数（收到 " + amount + "）";
        }
        BlockPos lo = min();
        BlockPos hi = max();
        switch (d) {
            case "north", "n", "北" -> lo = lo.offset(0, 0, amount);
            case "south", "s", "南" -> hi = hi.offset(0, 0, -amount);
            case "west", "w", "西" -> lo = lo.offset(amount, 0, 0);
            case "east", "e", "东" -> hi = hi.offset(-amount, 0, 0);
            case "down", "d", "下" -> lo = lo.offset(0, amount, 0);
            case "up", "u", "上" -> hi = hi.offset(0, -amount, 0);
            default -> {
                return "无法识别的方向「" + direction + "」（可用 north/south/east/west/up/down，或 北/南/东/西/上/下）";
            }
        }
        if (lo.getX() > hi.getX() || lo.getZ() > hi.getZ() || (!fullHeight && lo.getY() > hi.getY())) {
            return "朝 " + d + " 缩小 " + amount + " 格后会剩不下空间（当前 " + describeSize() + "，每轴至少要留 1 格）";
        }
        from = new int[]{lo.getX(), lo.getY(), lo.getZ()};
        to = new int[]{hi.getX(), hi.getY(), hi.getZ()};
        return null;
    }

    public long blockVolume() {
        BlockPos lo = min();
        BlockPos hi = max();
        int height = fullHeight ? WORLD_HEIGHT : (hi.getY() - lo.getY() + 1);
        return (long) (hi.getX() - lo.getX() + 1)
                * height
                * (hi.getZ() - lo.getZ() + 1);
    }

    /** 区域的方块边长，用于提示体积过大 */
    public String describeSize() {
        BlockPos lo = min();
        BlockPos hi = max();
        int dx = hi.getX() - lo.getX() + 1;
        int dz = hi.getZ() - lo.getZ() + 1;
        if (fullHeight) {
            return dx + "x" + dz + "（全高度）";
        }
        return dx + "x" + (hi.getY() - lo.getY() + 1) + "x" + dz;
    }

    public String describeCorners() {
        BlockPos lo = min();
        BlockPos hi = max();
        if (fullHeight) {
            return lo.getX() + ",*," + lo.getZ() + "  ->  " + hi.getX() + ",*," + hi.getZ();
        }
        return lo.getX() + "," + lo.getY() + "," + lo.getZ() + "  ->  " + hi.getX() + "," + hi.getY() + "," + hi.getZ();
    }

    /** 高度限制的文字说明（给 /warehouse region info 用） */
    public String heightText() {
        if (fullHeight) {
            return "全高度（不限 Y，区域内任意高度的容器均计入）";
        }
        BlockPos lo = min();
        BlockPos hi = max();
        return "只算 Y " + lo.getY() + " ~ " + hi.getY();
    }
}
