package com.ds.warehouse.index;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.AppConfig;
import com.ds.warehouse.config.Region;
import com.ds.warehouse.config.RegionStore;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * 进入存档后自动重新扫描一次。
 *
 * 配合 IndexStore 的两段式体验：
 *   1. 开档瞬间把硬盘上的旧索引读进来 → 网页立刻有数据，不用等；
 *   2. 延迟几秒（默认 5 秒）自动重扫一遍 → 数据刷新成最新。
 *
 * 单人游戏和局域网联机走的是同一套逻辑（单人游戏内部也是 IntegratedServer，
 * SERVER_STARTED / END_SERVER_TICK 一样会触发），所以**不需要开局域网联机**。
 */
public final class AutoScan {

    /** 剩余 tick；< 0 表示没有待办的自动扫描 */
    private static int ticksLeft = -1;

    private AutoScan() {
    }

    /** 由 ServerLifecycleEvents.SERVER_STARTED 调用 */
    public static void onServerStarted() {
        ticksLeft = -1;
        AppConfig cfg = AppConfig.get();
        if (!cfg.autoScanOnWorldLoad) {
            return;
        }
        if (RegionStore.REGIONS.isEmpty()) {
            return;
        }
        ticksLeft = Math.max(1, cfg.autoScanDelaySeconds) * 20;
    }

    /** 由 ServerTickEvents.END_SERVER_TICK 调用 */
    public static void tick(MinecraftServer server) {
        if (ticksLeft < 0) {
            return;
        }
        if (Scanner.isRunning()) {
            // 玩家已经手动扫起来了，这次自动扫描就让位
            ticksLeft = -1;
            return;
        }
        if (--ticksLeft > 0) {
            return;
        }
        ticksLeft = -1;
        List<Region> regions = List.copyOf(RegionStore.REGIONS.values());
        String err = Scanner.start(server, regions);
        if (err != null) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 自动扫描没能启动: {}", err);
        } else {
            WarehouseMod.LOGGER.info("[warehouse-keeper] 进入存档，已自动开始扫描 {} 个区域", regions.size());
        }
    }

    public static boolean pending() {
        return ticksLeft >= 0;
    }

    public static int secondsLeft() {
        return ticksLeft < 0 ? 0 : (ticksLeft + 19) / 20;
    }

    public static void cancel() {
        ticksLeft = -1;
    }
}
