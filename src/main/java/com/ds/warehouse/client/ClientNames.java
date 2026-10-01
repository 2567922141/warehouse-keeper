package com.ds.warehouse.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端本地名字（批次 4 打磨）：面板里的物品名 / 方块名一律按**客户端自己的语言**显示。
 *
 * <p>为什么要多这一层：服务端快照里带的 {@code name} 是服务端按**服务端语言**解析出来的
 * （{@code util.Names}）。单人存档的集成服务器跟着客户端走，一直是中文；但专用服务器
 * 通常是 en_us，于是「箱子总览」里会出现 {@code Dirt×1539}、{@code Gold Ingot} 这种中英混排。
 * 面板是客户端画的，拿到 id 就能在客户端注册表里重新取一次名字，两种环境下都是中文。
 *
 * <p>解析不出来（老服务端没给 id、id 未注册、注册表还没准备好）时返回 {@code null}，
 * 调用方退回快照里的服务端名字，绝不抛异常。
 *
 * <p>面板每帧每行都要用，所以结果进 {@link HashMap} 缓存；一个 id 的名字在一次游戏里不会变。
 */
public final class ClientNames {

    private static final Map<String, String> ITEM_CACHE = new HashMap<>();
    private static final Map<String, String> BLOCK_CACHE = new HashMap<>();
    private static final Map<String, String> BOX_CACHE = new HashMap<>();

    private ClientNames() {
    }

    /** 物品 id → 客户端语言的名字；解析不出来返回 null（调用方用快照里的名字兜底）。 */
    public static String item(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        if (ITEM_CACHE.containsKey(itemId)) {
            return ITEM_CACHE.get(itemId);
        }
        String name = null;
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
            // 注册表还没准备好 / 未知物品 —— 退回服务端名字
        }
        ITEM_CACHE.put(itemId, name);
        return name;
    }

    /** 方块 id → 客户端语言的名字；解析不出来返回 null。 */
    public static String block(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return null;
        }
        if (BLOCK_CACHE.containsKey(blockId)) {
            return BLOCK_CACHE.get(blockId);
        }
        String name = null;
        try {
            Identifier id = Identifier.tryParse(blockId);
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                Block b = BuiltInRegistries.BLOCK.getValue(id);
                if (b != null) {
                    String n = b.getName().getString();
                    if (n != null && !n.isBlank() && !isRawKey(n)) {
                        name = n;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 同 item
        }
        BLOCK_CACHE.put(blockId, name);
        return name;
    }

    /**
     * 箱子显示名：原版**双联箱**（两个箱子拼成的大箱子）的容器界面标题是「大型箱子」，
     * 但它挂的方块名只是「箱子」——面板里把 54 格的大箱子写成「箱子」，看起来就像把大箱子
     * 当成了小箱子，所以这里单独取容器标题那个名字。其它方块（含各种模组箱子）走
     * {@link #block}，是不是双联由调用方另外标注。
     */
    public static String box(String blockId, boolean dbl) {
        String key = (dbl ? "dbl|" : "") + (blockId == null ? "" : blockId);
        if (BOX_CACHE.containsKey(key)) {
            return BOX_CACHE.get(key);
        }
        String name = null;
        if (dbl && "minecraft:chest".equals(blockId)) {
            try {
                String n = Component.translatable("container.chestDouble").getString();
                if (n != null && !n.isBlank() && !isRawKey(n) && !n.startsWith("container.")) {
                    name = n;
                }
            } catch (Throwable ignored) {
                // 语言文件里没有这个键（或注册表没准备好）—— 退回方块名
            }
        }
        if (name == null) {
            name = block(blockId);
        }
        BOX_CACHE.put(key, name);
        return name;
    }

    /**
     * 没有语言文件时 {@code getName()} 会直接吐回翻译键（{@code block.xxx} / {@code item.xxx}），
     * 这种「名字」还不如 id 好读，当成解析失败。
     */
    private static boolean isRawKey(String text) {
        return text.startsWith("block.") || text.startsWith("item.")
                || text.startsWith("tile.") || text.startsWith("entity.");
    }
}
