package com.ds.warehouse.index;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个容器的索引快照。
 *
 * 注意：这里是「扫描那一刻」的拷贝，不是实时视图。
 * 任何写入操作之后都必须重新扫描或回读校验（Iron Chests 会消毒写入的 ItemStack）。
 */
public final class ContainerRecord {
    public final String dimension;
    public final BlockPos pos;
    /** 方块 id，例如 minecraft:chest / ironchest:diamond_chest */
    public final String blockId;
    /** 由 container.getContainerSize() 动态取得，绝不硬编码 27/54 */
    public final int size;
    /**
     * 这是不是一个「双联箱」（原版两个箱子拼成的大箱子）。
     * 只影响界面显示，不影响容量/统计 —— size 已经是合并后的 54。
     */
    public boolean doubleChest;
    /**
     * 双联箱另一半的坐标（不是双联箱就是 null）。
     *
     * <p>用来清掉「搭档坐标上可能残留的旧记录」：拼箱、区域边界、局部重扫都可能让
     * 同一个物理箱子在索引里留下两条记录，整理时就会被当成两个小箱子。
     */
    public BlockPos partner;
    /** 只保存非空槽位 */
    public final List<StoredStack> contents = new ArrayList<>();

    public ContainerRecord(String dimension, BlockPos pos, String blockId, int size) {
        this.dimension = dimension;
        this.pos = pos;
        this.blockId = blockId;
        this.size = size;
    }

    public void add(int slot, ItemStack stack) {
        contents.add(new StoredStack(slot, stack));
    }

    public boolean isEmpty() {
        return contents.isEmpty();
    }

    public String shortBlockId() {
        int i = blockId.indexOf(':');
        return i < 0 ? blockId : blockId.substring(i + 1);
    }

    public String coordText() {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public record StoredStack(int slot, ItemStack stack) {
    }
}
