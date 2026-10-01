package com.ds.warehouse.index;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 物品 id 工具。
 *
 * 26.x（Mojang 官方映射）里 Identifier 就是老版本的 ResourceLocation。
 */
public final class ItemIds {
    private ItemIds() {
    }

    public static String of(ItemStack stack) {
        return of(stack.getItem());
    }

    public static String of(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "minecraft:air" : id.toString();
    }

    /** 去掉命名空间，例如 minecraft:diamond_sword -> diamond_sword */
    public static String path(String itemId) {
        int i = itemId.indexOf(':');
        return i < 0 ? itemId : itemId.substring(i + 1);
    }

    /** 取命名空间，默认 minecraft */
    public static String namespace(String itemId) {
        int i = itemId.indexOf(':');
        return i <= 0 ? "minecraft" : itemId.substring(0, i);
    }

    /**
     * 把玩家输入解析成物品 id。
     * 支持 "diamond"、"minecraft:diamond"、"DIAMOND"（大小写不敏感）。
     * 找不到时返回 null。
     */
    @SuppressWarnings("deprecation")
    public static String resolve(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        String s = input.trim().toLowerCase();
        if (!s.contains(":")) {
            s = "minecraft:" + s;
        }
        Identifier id = Identifier.tryParse(s);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.containsKey(id) ? id.toString() : null;
    }
}
