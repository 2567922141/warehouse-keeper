package com.ds.warehouse.client;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.api.AddonSettings;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读附属模组声明的 {@code warehouse-keeper:addon} entrypoint（见 {@link AddonSettings}）。
 *
 * <p>只在客户端屏幕（{@link WarehouseScreen}）里用。任何异常都就地吞掉并只 warn 一次：
 * <b>某个附属模组坏了，只是它的设置菜单不出现，主体面板必须照常可用</b>。
 */
final class AddonSettingsRegistry {

    private AddonSettingsRegistry() {
    }

    /** 自定义 entrypoint key：附属模组在 fabric.mod.json 的 entrypoints 里声明。 */
    static final String KEY = "warehouse-keeper:addon";

    /** 已知的附属模组 id：装了、但没有 addon entrypoint ⇒ 附属包太老，设置页给一行灰字提示。 */
    private static final List<String> KNOWN_ADDONS = List.of("warehouse-keeper-voice");

    private static List<AddonSettings> cache;
    private static List<String> legacy;
    private static boolean warned;

    /** 已安装且实现了接口的附属模组（按 id 去重，保持 entrypoint 顺序）；出错时返回空表。 */
    static List<AddonSettings> all() {
        if (cache != null) {
            return cache;
        }
        List<AddonSettings> out = new ArrayList<>();
        try {
            Map<String, AddonSettings> byId = new LinkedHashMap<>();
            for (AddonSettings a : FabricLoader.getInstance().getEntrypoints(KEY, AddonSettings.class)) {
                if (a == null) {
                    continue;
                }
                String id = a.id() == null ? "" : a.id().trim();
                if (id.isEmpty()) {
                    warnOnce("附属模组设置项缺少 id()，已跳过：" + a.getClass().getName());
                    continue;
                }
                if (byId.putIfAbsent(id, a) != null) {
                    warnOnce("附属模组设置项 id 重复，已跳过后来的那个：" + id);
                }
            }
            out.addAll(byId.values());
        } catch (Throwable t) {
            warnOnce("读取 " + KEY + " 失败，附属模组设置菜单不可用：" + t);
        }
        cache = out;
        return cache;
    }

    /** 装了、但没实现接口的老版本附属包 id（设置页给一行灰字，指向它自己的界面）。 */
    static List<String> legacyIds() {
        if (legacy != null) {
            return legacy;
        }
        List<String> out = new ArrayList<>();
        List<AddonSettings> addons = all();
        for (String id : KNOWN_ADDONS) {
            boolean loaded;
            try {
                loaded = FabricLoader.getInstance().isModLoaded(id);
            } catch (Throwable t) {
                loaded = false;
            }
            if (!loaded) {
                continue;
            }
            boolean hasApi = false;
            for (AddonSettings a : addons) {
                if (id.equals(a.id())) {
                    hasApi = true;
                    break;
                }
            }
            if (!hasApi) {
                out.add(id);
            }
        }
        legacy = out;
        return legacy;
    }

    /** 有附属模组（或老附属包）时，「设置」页才需要二级页；否则保持 1.2.0 的样子。 */
    static boolean hasAny() {
        return !all().isEmpty() || !legacyIds().isEmpty();
    }

    private static void warnOnce(String msg) {
        if (warned) {
            return;
        }
        warned = true;
        WarehouseMod.LOGGER.warn("[warehouse-keeper] {}", msg);
    }
}
