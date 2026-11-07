package com.ds.warehouse.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「哪些仓库的边界开着」这份纯客户端状态。
 *
 * <p>只存状态、不负责画：实际绘制在 {@link RegionGizmos}（半透明彩色长方体 + 仓库名）。
 * 以前这里是每 8 tick 撒一次 {@code END_ROD} 粒子，issue #8 优化②要求换成别的效果，
 * 于是渲染搬到了 gizmo；开关语义、按仓库分别控制的语义都保持不变。
 *
 * <p>状态是<b>纯客户端内存态</b>：不落盘、不走网络，退出游戏就没了。
 */
public final class RegionBorder {

    /**
     * 开着边界的仓库：{@code 区域名 → 区域}。
     *
     * <p>用 {@link LinkedHashMap} 是为了让绘制顺序稳定。值必须每次都换成 {@link RegionCache}
     * 最新的那个 {@code Entry}：{@code RegionCache.refresh()} 每次都会重建 Entry 对象，
     * 旧对象里的坐标可能已经过期（仓库被扩建/缩小过）。
     */
    private static final Map<String, RegionCache.Entry> ENABLED = new LinkedHashMap<>();

    private RegionBorder() {
    }

    /** 这个仓库的边界现在开着吗（{@code name} 为空 = 没有具体仓库 ⇒ false）。 */
    public static boolean isOn(String name) {
        return name != null && !name.isEmpty() && ENABLED.containsKey(name);
    }

    /** 当前开着边界的仓库名（按开启顺序，只读快照）。 */
    public static List<String> enabledNames() {
        return List.copyOf(ENABLED.keySet());
    }

    /** 当前开着边界的仓库（绘制用；顺序与开启顺序一致）。 */
    public static List<RegionCache.Entry> enabledEntries() {
        return List.copyOf(ENABLED.values());
    }

    /** 打开 / 关闭某个仓库的边界；重复设置同一个值是无害的。 */
    public static void toggle(String name, boolean value) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (value) {
            RegionCache.Entry entry = find(name);
            if (entry == null) {
                // 列表里已经没有这个仓库了（刚被删）：直接清掉，别保留一个空壳
                ENABLED.remove(name);
            } else {
                ENABLED.put(name, entry);
            }
        } else {
            ENABLED.remove(name);
        }
    }

    /** 某个仓库的数据更新了（选中 / 扩建 / 缩小）：开着的话把值换成最新的，下次绘制用新坐标。 */
    public static RegionCache.Entry touch(RegionCache.Entry entry) {
        if (entry != null && ENABLED.containsKey(entry.name)) {
            ENABLED.put(entry.name, entry);
        }
        return entry;
    }

    /** 兼容旧调用点：把某个仓库设为「当前仓库」（不改变开关状态）。 */
    public static void select(RegionCache.Entry entry) {
        touch(entry);
    }

    /** 当前仓库（compat：旧代码用它拿选中区域），没有时返回 null。 */
    public static RegionCache.Entry region() {
        return ENABLED.isEmpty() ? null : ENABLED.values().iterator().next();
    }

    /** 仓库列表刷新后调用：删掉的仓库要一并关掉，坐标变了的换成新对象。 */
    public static void validate() {
        ENABLED.keySet().removeIf(name -> find(name) == null);
        for (Map.Entry<String, RegionCache.Entry> e : ENABLED.entrySet()) {
            RegionCache.Entry latest = find(e.getKey());
            if (latest != null) {
                e.setValue(latest);
            }
        }
    }

    /** 每个客户端 tick 调一次（现在只做清理，绘制交给 gizmo）。 */
    public static void clientTick() {
        validate();
    }

    private static RegionCache.Entry find(String name) {
        for (RegionCache.Entry e : RegionCache.list()) {
            if (e.name.equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }
}
