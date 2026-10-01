package com.ds.warehouse.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把内部 id（minecraft:chest / ironchest:crystal_chest / minecraft:the_nether…）
 * 翻译成玩家看得懂的名字（箱子 / 水晶箱 / 下界…）。
 *
 * 名字来自方块自身的语言文件（Block.getName()），所以：
 *   - 原版方块 → 原版中文名
 *   - Iron Chests 等模组方块 → 模组自带的中文名（ironchest 有 zh_cn.json）
 *   - 没装语言文件时 → 退回英文名，再不行才退回 id
 *
 * 只做「读」，不碰任何存档；结果缓存，重复查询不重复解析。
 */
public final class Names {

    private static final Map<String, String> BLOCK_CACHE = new HashMap<>();
    private static final Map<String, String> ITEM_CACHE = new HashMap<>();
    private static final Map<String, String> DIM_CACHE = new HashMap<>();

    /**
     * 客户端推上来的名字（批次 5 阶段 1，见 {@code net.ItemNamesPayload}）。
     *
     * <p>专用服务端的语言是 en_us，{@link #item} 在服务端只会解析出英文名；客户端把自己
     * 语言下的名字推上来后，这里优先用它 —— 于是「按拼音排序」在联机下也是玩家看到的名字。
     * 单人存档不推或推的是同一套中文名，行为不变。
     */
    private static volatile Map<String, String> CLIENT = Map.of();

    private Names() {
    }

    /** 服务端收下客户端推来的名字表：整表替换，空表忽略。任何一条不合法就跳过这一条。 */
    public static void acceptClientNames(List<String> ids, List<String> names) {
        if (ids == null || names == null || ids.isEmpty() || names.isEmpty()) {
            return;
        }
        Map<String, String> m = new HashMap<>();
        int n = Math.min(ids.size(), names.size());
        for (int i = 0; i < n; i++) {
            String id = ids.get(i);
            String name = names.get(i);
            if (id == null || name == null || id.isBlank() || name.isBlank() || isRawKey(name)) {
                continue;
            }
            m.put(id, name);
        }
        if (m.isEmpty()) {
            return;
        }
        CLIENT = Map.copyOf(m);
    }

    /** 客户端推来的名字条数（诊断 / 日志用） */
    public static int clientNameCount() {
        return CLIENT.size();
    }

    /** 方块 id → 显示名。任何异常都退回原 id，绝不抛。 */
    public static String block(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return "未知容器";
        }
        String pushed = CLIENT.get(blockId);
        if (pushed != null) {
            return pushed;
        }
        String cached = BLOCK_CACHE.get(blockId);
        if (cached != null) {
            return cached;
        }
        String name = blockId;
        try {
            Identifier id = Identifier.tryParse(blockId);
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                Block b = BuiltInRegistries.BLOCK.getValue(id);
                if (b != null) {
                    String n = b.getName().getString();
                    // 没有语言文件时 getName() 会直接吐回翻译键（block.xxx），这种就当没有
                    if (n != null && !n.isBlank() && !isRawKey(n)) {
                        name = n;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 注册表还没准备好 / 未知方块 —— 保持原 id
        }
        BLOCK_CACHE.put(blockId, name);
        return name;
    }

    /** 物品 id → 显示名。和 {@link #block} 同一套规则。 */
    public static String item(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return "未知物品";
        }
        String pushed = CLIENT.get(itemId);
        if (pushed != null) {
            return pushed;
        }
        String cached = ITEM_CACHE.get(itemId);
        if (cached != null) {
            return cached;
        }
        String name = itemId;
        try {
            Identifier id = Identifier.tryParse(itemId);
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                Item it = BuiltInRegistries.ITEM.getValue(id);
                if (it != null) {
                    String n = new ItemStack(it).getHoverName().getString();
                    if (n != null && !n.isBlank() && !isRawKey(n)) {
                        name = n;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 注册表还没准备好 / 未知物品 —— 保持原 id
        }
        ITEM_CACHE.put(itemId, name);
        return name;
    }

    /** 维度 id → 显示名 */
    public static String dimension(String dimId) {
        if (dimId == null || dimId.isEmpty()) {
            return "未知维度";
        }
        String cached = DIM_CACHE.get(dimId);
        if (cached != null) {
            return cached;
        }
        String name;
        String key = dimId.toLowerCase(Locale.ROOT);
        switch (key) {
            case "minecraft:overworld" -> name = "主世界";
            case "minecraft:the_nether" -> name = "下界";
            case "minecraft:the_end" -> name = "末地";
            default -> {
                // 模组维度：取 id 的路径部分当名字（mymod:sky_island → sky_island）
                int colon = dimId.indexOf(':');
                String p = (colon >= 0 && colon + 1 < dimId.length()) ? dimId.substring(colon + 1) : dimId;
                name = p.isBlank() ? dimId : p;
            }
        }
        DIM_CACHE.put(dimId, name);
        return name;
    }

    private static boolean isRawKey(String s) {
        return s.startsWith("block.") || s.startsWith("tile.") || s.startsWith("item.");
    }
}
