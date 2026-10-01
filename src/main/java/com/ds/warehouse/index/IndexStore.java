package com.ds.warehouse.index;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.AppConfig;
import com.ds.warehouse.config.CategoryRules;
import com.ds.warehouse.config.WorldStore;
import com.ds.warehouse.util.Categories;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 索引持久化。
 *
 * 存放位置：&lt;游戏实例&gt;/config/warehouse-keeper/worlds/&lt;存档目录名&gt;/index.json
 * （0.15.x 及以前放在 index/&lt;存档目录名&gt;.json，进存档时会自动迁移过来）
 * 刻意**不放进 world/** —— 卸载模组后存档零残留，这是本项目第一号硬性约束。
 *
 * 只保存索引真正用到的信息（物品 id + 数量），不序列化 ItemStack：
 *   - ItemStack 的组件 / NBT 用 Gson 直接序列化既脆弱又没必要；
 *   - 索引本身就是按「物品 id」聚合的，附魔之类的差异本来就不区分。
 *
 * 每个存档一份，而且文件所在目录就是按存档分的。开档时还会校验文件里记录的
 * worldPath 与当前存档一致，不一致就忽略（双保险，避免 A 存档的数据串到 B 存档里）。
 */
public final class IndexStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 存档格式版本；以后改结构时 +1，旧文件会被忽略并提示重扫 */
    private static final int FORMAT = 2;

    /** format 1：没有 categories 字段。仍然读得进来（分类现算），读之前先备份一份 index.v1.bak */
    private static final int FORMAT_LEGACY = 1;

    private static long lastSavedAt;
    private static String lastSavedPath = "-";
    private static String lastLoadNote = "本次启动尚未读取磁盘索引";

    private IndexStore() {
    }

    public static Path dir() {
        return WorldStore.dir();
    }

    /** 当前存档的目录（用来区分不同存档） */
    public static String worldPath(MinecraftServer server) {
        return WorldStore.rawWorldPath(server);
    }

    public static Path fileFor(MinecraftServer server) {
        return WorldStore.file("index.json");
    }

    // ------------------------------------------------------------------
    // 保存

    /** @return null 表示成功，否则是不能保存的原因 */
    public static String save(WarehouseIndex index, MinecraftServer server) {
        if (!AppConfig.get().saveIndexToDisk) {
            return "配置中已关闭「保存索引到磁盘」（可用 /warehouse settings 修改）";
        }
        Dto dto = new Dto();
        dto.format = FORMAT;
        dto.modVersion = WarehouseMod.VERSION;
        dto.worldPath = worldPath(server);
        dto.savedAt = System.currentTimeMillis();
        dto.lastScanRegion = index.lastScanRegion;
        dto.lastScanMillis = index.lastScanMillis;
        dto.scannedChunks = index.scannedChunks;
        dto.skippedChunks = index.skippedChunks;
        dto.totalStacks = index.totalStacks;
        dto.totalItems = index.totalItems;
        // 分类记忆化表：format 2 起随索引落盘，下次开档直接热启动（改覆盖表后 reload 会重算）
        for (String id : index.items.keySet()) {
            Categories.of(id); // 先把索引里每种物品都算一遍，落盘的才是完整的表
        }
        dto.categories = Categories.snapshot();
        dto.categoriesRules = CategoryRules.VERSION;
        dto.containers = new ArrayList<>(index.containers.size());
        for (ContainerRecord rec : index.containers.values()) {
            ContainerDto cd = new ContainerDto();
            cd.dimension = rec.dimension;
            cd.x = rec.pos.getX();
            cd.y = rec.pos.getY();
            cd.z = rec.pos.getZ();
            cd.blockId = rec.blockId;
            cd.size = rec.size;
            cd.doubleChest = rec.doubleChest;
            cd.items = new ArrayList<>(rec.contents.size());
            for (ContainerRecord.StoredStack ss : rec.contents) {
                ItemDto it = new ItemDto();
                it.slot = ss.slot();
                it.id = ItemIds.of(ss.stack());
                it.count = ss.stack().getCount();
                cd.items.add(it);
            }
            dto.containers.add(cd);
        }

        Path f = fileFor(server);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, w);
            }
            // 先写临时文件再原子替换：中途崩了也不会留下半个坏 JSON
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 保存索引失败: {}", e.toString());
            return "保存索引失败: " + e;
        }
        lastSavedAt = dto.savedAt;
        lastSavedPath = f.toString();
        WarehouseMod.LOGGER.info("[warehouse-keeper] 索引已保存到磁盘: {}  容器={} 物品种类={} 总数量={}",
                f, dto.containers.size(), index.items.size(), index.totalItems);
        return null;
    }

    // ------------------------------------------------------------------
    // 读取

    /** @return null 表示成功恢复，否则是「为什么没恢复」的说明 */
    public static String load(WarehouseIndex index, MinecraftServer server) {
        index.restoredFromDisk = false;
        index.restoredSavedAt = 0;

        if (!AppConfig.get().loadIndexFromDisk) {
            lastLoadNote = "配置中已关闭「开档读取磁盘索引」";
            return lastLoadNote;
        }
        Path f = fileFor(server);
        if (!Files.isRegularFile(f)) {
            lastLoadNote = "磁盘上尚无此存档的索引（" + f.getFileName() + " 不存在）";
            return lastLoadNote;
        }
        Dto dto;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            dto = GSON.fromJson(r, Dto.class);
        } catch (Exception e) {
            lastLoadNote = "索引文件读取失败: " + e;
            return lastLoadNote;
        }
        if (dto == null || dto.containers == null) {
            lastLoadNote = "索引文件为空或格式不符";
            return lastLoadNote;
        }
        if (dto.format != FORMAT && dto.format != FORMAT_LEGACY) {
            lastLoadNote = "索引文件为旧格式 (format=" + dto.format + ")，已忽略；请重新执行 /warehouse scan";
            return lastLoadNote;
        }
        String upgraded = "";
        if (dto.format == FORMAT_LEGACY) {
            // 格式升级前先留一份备份（红线：格式变更必须向后兼容 + 自动备份）
            Path bak = f.resolveSibling(f.getFileName() + ".v1.bak");
            try {
                if (!Files.exists(bak)) {
                    Files.copy(f, bak);
                }
                upgraded = "（旧格式 v1，已备份为 " + bak.getFileName() + "，分类现算）";
            } catch (Exception e) {
                upgraded = "（旧格式 v1，备份失败：" + e + "，分类现算）";
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 旧索引备份失败: {}", e.toString());
            }
        }
        String world = worldPath(server);
        if (dto.worldPath != null && !dto.worldPath.equals(world)) {
            lastLoadNote = "索引文件属于另一个存档，已忽略";
            return lastLoadNote;
        }

        index.clear();
        int ignoredItems = 0;
        for (ContainerDto cd : dto.containers) {
            if (cd == null) {
                continue;
            }
            ContainerRecord rec = new ContainerRecord(
                    cd.dimension == null || cd.dimension.isEmpty() ? "minecraft:overworld" : cd.dimension,
                    new BlockPos(cd.x, cd.y, cd.z),
                    cd.blockId == null ? "minecraft:air" : cd.blockId,
                    Math.max(0, cd.size));
            rec.doubleChest = cd.doubleChest;
            if (cd.items != null) {
                for (ItemDto it : cd.items) {
                    if (it == null || it.count <= 0) {
                        continue;
                    }
                    Item item = itemOf(it.id);
                    if (item == null) {
                        ignoredItems++;
                        continue;
                    }
                    ItemStack st = stackOf(item, it.count);
                    if (st.isEmpty()) {
                        continue;
                    }
                    rec.add(it.slot, st);
                }
            }
            index.accept(rec); // accept() 会重算 scannedContainers / totalStacks / totalItems
        }
        index.scannedChunks = dto.scannedChunks;
        index.skippedChunks = dto.skippedChunks;
        index.lastScanMillis = dto.lastScanMillis;
        index.lastScanRegion = dto.lastScanRegion == null ? "-" : dto.lastScanRegion;
        index.restoredFromDisk = true;
        index.restoredSavedAt = dto.savedAt;

        // 分类热启动：落过盘、且规则版本一致，才灌记忆化（旧格式 / 缺字段 / 规则改过 → 留空，用到时现算）
        boolean seeded = dto.categoriesRules == CategoryRules.VERSION;
        if (seeded) {
            Categories.seed(dto.categories);
        } else {
            Categories.clearMemo();
        }

        lastLoadNote = "已从磁盘恢复（保存于 " + fmt(dto.savedAt) + "，"
                + index.containers.size() + " 个容器 / " + index.items.size() + " 种物品）"
                + (seeded && dto.categories != null && !dto.categories.isEmpty()
                        ? "，分类表 " + Categories.memoSize() + " 条"
                        : "，分类表规则版本不符，将在用到时现算")
                + upgraded
                + (ignoredItems > 0 ? "，有 " + ignoredItems + " 个物品在当前整合包里不存在，已跳过" : "");
        WarehouseMod.LOGGER.info("[warehouse-keeper] {}", lastLoadNote);
        return null;
    }

    private static Item itemOf(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        Identifier key = Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(key);
        return item == null ? null : item;
    }

    /** 从 id + 数量还原一个 ItemStack（数量会被夹到该物品的最大堆叠数） */
    private static ItemStack stackOf(Item item, int count) {
        int max = new ItemStack(item).getMaxStackSize();
        if (max < 1) {
            max = 1;
        }
        if (count > max) {
            // 只警告，语义不变（下游一律按 ≤ max 处理）：跨堆叠上限变更后，存档里超过 max 的
            // 存量会被永久夹少，这里留一条能定位到具体物品的日志
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 物品 {} 在存档里记了 {} 个，超过当前堆叠上限 {}，存量可能被少算",
                    BuiltInRegistries.ITEM.getKey(item), count, max);
        }
        return new ItemStack(item, Math.min(count, max));
    }

    // ------------------------------------------------------------------
    // 给指令/网页看的说明文字

    public static String lastSavedText() {
        return lastSavedAt == 0
                ? "本次启动尚未保存"
                : fmt(lastSavedAt) + "  →  " + lastSavedPath;
    }

    public static String lastLoadNote() {
        return lastLoadNote;
    }

    public static long lastSavedAt() {
        return lastSavedAt;
    }

    public static String fmt(long ts) {
        if (ts <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ts));
    }

    // ------------------------------------------------------------------
    // 磁盘格式（只有这些字段进 JSON）

    private static final class Dto {
        int format;
        String modVersion;
        String worldPath;
        long savedAt;
        String lastScanRegion;
        long lastScanMillis;
        long scannedChunks;
        long skippedChunks;
        long totalStacks;
        long totalItems;
        List<ContainerDto> containers;
        /** format 2 起：物品 id → 分类（就是分类器的记忆化表，开档热启动用） */
        Map<String, String> categories;
        /** 落盘时分类规则表的版本；与当前 CategoryRules.VERSION 不一致时不敢用这张表（怕规则改过留陈旧值） */
        int categoriesRules;
    }

    private static final class ContainerDto {
        String dimension;
        int x;
        int y;
        int z;
        String blockId;
        int size;
        /** 原版双联箱（两个箱子拼的大箱子）。老索引文件里没有这个字段，Gson 会留 false。 */
        boolean doubleChest;
        List<ItemDto> items;
    }

    private static final class ItemDto {
        int slot;
        String id;
        int count;
    }
}
