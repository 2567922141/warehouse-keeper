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
 * 熔炉、漏斗……所有实现 {@link Container} 的方块实体都被当成箱子统计与整理。
 *
 * <p><b>0.23.0 起判据拆成两问</b>（用户拍板）：
 * <ul>
 *   <li>{@link #isWarehouseContainer} / {@link #excluded}：<b>要不要进索引</b>（能不能被查看）。
 *       默认全都进（{@link #ALWAYS_EXCLUDED} 空集，{@link AppConfig#excludedContainers()} 默认空），
 *       所以雕纹书架、架子、熔炉、漏斗里的东西在面板 / 网页里都看得见；</li>
 *   <li>{@link #noTidy}：<b>整理时要不要动它</b>。熔炉 / 漏斗 / 雕纹书架等只查看、不整理，
 *       既不当整理的目标箱，也不会被当成需要整理的来源箱。</li>
 * </ul>
 *
 * <p>被 {@link #excluded} 排除的方块在索引入口就不建 {@code ContainerRecord}，查找、面板统计、
 * 标签、整理四处行为天然一致；被 {@link #noTidy} 排除的<b>照常进索引</b>，只在整理路径上过滤。
 */
public final class Containers {

    /**
     * 始终排除（连索引都不进）的原版方块：<b>0.23.0 起为空集</b>。
     *
     * <p>0.22.0 及以前这里钉着雕纹书架与 26.2 的架子。用户拍板：这两类也<b>要能查看</b>
     * （面板 / 网页里看得到它们装了什么），只是整理时不许动。于是职责拆成两半：
     * 「是否进索引（可查看）」交给用户配置 {@link AppConfig#excludedContainers()}（默认空 = 全都进），
     * 「不整理」交给 {@link #NO_TIDY}。
     *
     * <p>常量与注释保留（不删），需要时还能往这里硬钉「连看都不该看」的方块。
     */
    private static final Set<String> ALWAYS_EXCLUDED = Set.of();

    /**
     * 「可以查看、但整理时绝不作为目标箱，也不去整理它」的容器（0.23.0 · 优化5）。
     *
     * <p>这些都是功能 / 陈列性质的方块：熔炉里的原料是按配方摆好的、漏斗是传输管道、
     * 雕纹书架与架子是陈列，整理会把玩家的摆法搅乱，还会把熔炉烧到一半的料搬走。
     *
     * <p>它们<b>照常进索引</b>（{@link #isWarehouseContainer} 不挡它们），所以查找、面板、网页
     * 都能看到里面的东西；只有 {@code porter/Tasks} 的整理路径用 {@link #noTidy} 把它们排除。
     */
    private static final Set<String> NO_TIDY = Set.of(
            "minecraft:chiseled_bookshelf",
            // 26.2 的「展示架」（架子）是按木头分 id 的：oak_shelf / spruce_shelf / birch_shelf …
            // 根本没有单个 "shelf"（0.23.0 早先只写了 minecraft:shelf，等于没挡住，用户报「展示架里的物品也被整理」）。
            // 这里列全当前这几种；以后新增木头由 noTidy() 的后缀规则兜住，不必再改代码。
            "minecraft:oak_shelf",
            "minecraft:spruce_shelf",
            "minecraft:birch_shelf",
            "minecraft:jungle_shelf",
            "minecraft:acacia_shelf",
            "minecraft:cherry_shelf",
            "minecraft:dark_oak_shelf",
            "minecraft:mangrove_shelf",
            "minecraft:pale_oak_shelf",
            "minecraft:bamboo_shelf",
            "minecraft:crimson_shelf",
            "minecraft:warped_shelf",
            "minecraft:furnace",
            "minecraft:blast_furnace",
            "minecraft:smoker",
            "minecraft:hopper",
            "minecraft:dropper",
            "minecraft:dispenser",
            "minecraft:brewing_stand",
            "minecraft:crafter",
            "minecraft:decorated_pot");

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

    /**
     * 这个方块 id 是不是「可查看、但不整理」（{@link #NO_TIDY}）。
     *
     * <p>风格与 {@link #excluded(String)} 一致：大小写不敏感，null / 空一律当作可整理。
     * 整理路径（{@code porter/Tasks} 的候选目标箱与区域容器清单）用它把熔炉 / 漏斗 / 雕纹书架
     * 这些挡在门外，取货、查找、面板展示一概不受影响。
     *
     * @param blockId 方块 id，例如 {@code minecraft:furnace}；null / 空返回 false
     */
    public static boolean noTidy(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return false;
        }
        String id = blockId.toLowerCase(java.util.Locale.ROOT);
        if (NO_TIDY.contains(id)) {
            return true;
        }
        // 展示架兜底：26.2 起每种木头一个 id（oak_shelf / spruce_shelf / …），以后还会加木头。
        // 按后缀认「架子」，新增木头不用改代码（模组自己加的 *_shelf 同样当陈列，不整理）。
        String path = id.indexOf(':') >= 0 ? id.substring(id.indexOf(':') + 1) : id;
        return path.equals("shelf") || path.endsWith("_shelf");
    }
}
