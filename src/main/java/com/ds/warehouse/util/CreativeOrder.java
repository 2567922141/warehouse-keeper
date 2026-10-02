package com.ds.warehouse.util;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.index.ItemIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「物品 → 它在创造模式物品栏里排第几」的对照表（优化2）。
 *
 * <p>原版物品身上<b>没有</b>「我在创造栏第几格」这种字段：这个顺序是打开创造界面时由
 * {@link CreativeModeTabs#tryRebuildTabContents} 临时算出来的。扫描/整理跑在专用服务端上，
 * 服务端从没打开过创造界面 ⇒ {@code getDisplayItems()} 默认是空的，必须由我们主动触发一次构建。
 *
 * <p>建表规则：拍平 {@link CreativeModeTabs#tabs()} 里所有 {@link CreativeModeTab.Type#CATEGORY}
 * 页签（按注册顺序），页签内的槽位顺序就是创造栏顺序；谁先出现谁的数字小。模组物品天然在内 ——
 * Fabric 会把它们注入原版页签，模组自建页签也在这个注册表里。
 *
 * <p>只建一次（{@link #ensure} 幂等），之后再查只是查 map；**绝不放进 tick 热路径反复构建**。
 * 任何异常都就地吞掉、记一次日志、留空表 —— 建不出表只会让箱内排序回退到拼音序，绝不能崩游戏。
 */
public final class CreativeOrder {

    private static final Object LOCK = new Object();

    /** itemId → 创造栏顺序号（0 起，越小越靠前）；空表 = 没建起来 */
    private static volatile Map<String, Integer> RANK = Map.of();
    /** itemId → 它所在 CATEGORY 页签的注册键 */
    private static volatile Map<String, String> TAB_OF = Map.of();
    /** CATEGORY 页签的注册键，按创造栏顺序 */
    private static volatile List<String> TABS = List.of();
    private static volatile boolean ready;

    private CreativeOrder() {
    }

    /**
     * 建表（幂等）。在真正需要按创造栏顺序排序时调一次即可；已经建好时只是一次 boolean 判断。
     * {@code server} 为 null 时什么都不做（也<b>不</b>标记为已建，等下次拿到服务端再建）。
     */
    public static void ensure(net.minecraft.server.MinecraftServer server) {
        if (ready || server == null) {
            return;
        }
        synchronized (LOCK) {
            if (ready) {
                return;
            }
            Map<String, Integer> rank = new HashMap<>();
            Map<String, String> tabOf = new HashMap<>();
            List<String> tabs = new ArrayList<>();
            try {
                // 服务端从没开过创造界面 ⇒ 必须先主动构建一次，否则 getDisplayItems() 全是空的。
                // 第二参数（hasPermissions）取 false：true 会把「有 OP 权限」的那套参数写进原版全局静态
                // CACHED_PARAMETERS，单人存档里服务端与客户端同 JVM，可能让非 OP 玩家在创造背包里看到
                // OP 专属物品；false 只是少收录被权限挡住的物品（它们回退拼音序，不影响排序正确性）。
                CreativeModeTabs.tryRebuildTabContents(
                        server.getWorldData().enabledFeatures(), false, server.registryAccess());
                int n = 0;
                for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
                    if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
                        continue;
                    }
                    var key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
                    String tabId = key == null ? "" : key.toString();
                    tabs.add(tabId);
                    for (ItemStack st : tab.getDisplayItems()) {
                        if (st.isEmpty()) {
                            continue;
                        }
                        String id = ItemIds.of(st);
                        if (id == null || id.isEmpty()) {
                            continue;
                        }
                        // 同一个物品可能出现在多个页签（或页签里重复出现）：第一个位置说了算
                        if (!rank.containsKey(id)) {
                            rank.put(id, n++);
                        }
                        tabOf.putIfAbsent(id, tabId);
                    }
                }
            } catch (Throwable t) {
                // 建不出表不是致命问题：排序回退到拼音序即可，绝不能让游戏崩
                rank.clear();
                tabOf.clear();
                tabs.clear();
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 创造栏顺序构建失败，箱内排序回退到拼音序: {}",
                        t.toString());
            }
            RANK = Map.copyOf(rank);
            TAB_OF = Map.copyOf(tabOf);
            TABS = List.copyOf(tabs);
            ready = true;
        }
    }

    /** 创造栏顺序号（越小越靠前）；不在任何 CATEGORY 页签里返回 {@code -1} */
    public static int rank(String itemId) {
        if (itemId == null) {
            return -1;
        }
        Integer r = RANK.get(itemId);
        return r == null ? -1 : r;
    }

    /** 物品所在的创造栏页签注册键；未知返回 {@code null} */
    public static String tabOf(String itemId) {
        return itemId == null ? null : TAB_OF.get(itemId);
    }

    /** CATEGORY 页签的注册键，按创造栏顺序（不可变） */
    public static java.util.List<String> categoryTabs() {
        return TABS;
    }

    /** 丢掉缓存，下次 {@link #ensure} 重新建表（资源重载 / registry sync 后用） */
    public static void invalidate() {
        synchronized (LOCK) {
            RANK = Map.of();
            TAB_OF = Map.of();
            TABS = List.of();
            ready = false;
        }
    }
}
