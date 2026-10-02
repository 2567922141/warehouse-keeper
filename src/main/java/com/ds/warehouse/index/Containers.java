package com.ds.warehouse.index;

import com.ds.warehouse.config.AppConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Set;

/**
 * 「这个方块算不算仓库容器」的<b>唯一</b>判据。
 *
 * <p>模组原本只问一句 {@code be instanceof Container} —— 于是雕纹书架、26.2 新增的架子、
 * 熔炉、漏斗……所有实现 {@link Container} 的方块实体都被当成箱子统计与整理。用户拍板把范围收窄为
 * 「<b>除雕纹书架与架子外，所有容器照常统计与整理</b>」：那两类是陈列/装饰性质，被整理会打乱玩家的摆法。
 *
 * <p>被排除的方块<b>在索引入口就不建 {@code ContainerRecord}</b>，于是查找、面板统计、标签、整理
 * 四处行为天然一致 —— 不需要在每个下游各自过滤一遍。
 *
 * <p>排除表分两层：本类的 {@link #ALWAYS_EXCLUDED}（原版硬编码的两项）与用户可改的
 * {@link AppConfig#excludedContainers()}（{@code settings.json} 的 {@code containerExclude}）。
 * 后者是为了让整合包里「只能放特定物品的展示架」也能被玩家自己挡掉，不必改代码。
 */
public final class Containers {

    /**
     * 始终排除的原版方块（不可用配置删除）。
     *
     * <ul>
     *   <li>{@code minecraft:chiseled_bookshelf} 雕纹书架 —— 只能放书，整理会打乱摆法</li>
     *   <li>{@code minecraft:shelf} 架子（26.2 新增的 {@code ShelfBlockEntity}）—— 用于摆放展示</li>
     * </ul>
     */
    private static final Set<String> ALWAYS_EXCLUDED = Set.of(
            "minecraft:chiseled_bookshelf",
            "minecraft:shelf");

    private Containers() {
    }

    /**
     * 这个方块实体是不是「参与仓库管理的容器」。
     *
     * <p>三条同时成立才算：① 方块实体实现 {@link Container}；② 方块 id 不在硬编码排除表里；
     * ③ 方块 id 不在 {@link AppConfig#excludedContainers()} 里。
     *
     * @param level 该方块所在的维度（用来读方块状态）
     * @param pos   该方块的坐标
     * @param be    该坐标上的方块实体
     * @return true 表示应当建索引、参与统计与整理
     */
    public static boolean isWarehouseContainer(ServerLevel level, BlockPos pos, BlockEntity be) {
        if (!(be instanceof Container)) {
            return false;
        }
        String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        return !excluded(id);
    }

    /**
     * 这个方块 id 是不是被排除在仓库管理之外（硬编码排除表 + 用户配置）。
     *
     * <p>大小写不敏感：配置里写成 {@code Minecraft:Chiseled_Bookshelf} 一样挡得住。
     *
     * @param blockId 方块 id，例如 {@code minecraft:chest}；null / 空 一律当作不排除
     */
    public static boolean excluded(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return false;
        }
        String id = blockId.toLowerCase(java.util.Locale.ROOT);
        return ALWAYS_EXCLUDED.contains(id) || AppConfig.excludedContainers().contains(id);
    }
}
