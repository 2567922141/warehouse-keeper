package com.ds.warehouse.util;

import com.ds.warehouse.config.CategoryRules;
import com.ds.warehouse.index.ItemIds;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品分类（D1 v2：四层判定 + 记忆化）。
 *
 * <pre>
 *   L0  用户覆盖表   config/warehouse-keeper/categories.json（键=完整 id，可热重载）
 *   L0.5 名称钉死   少数 id 与 _spawn_egg/_bucket 后缀（见 CategoryRules），**先于标签**：
 *                   因为标签里也会躺着不该在这个筐里的东西（nautilus_food 里有鱼桶、crops 里有南瓜）
 *   L1  原版物品标签  #minecraft:logs / #minecraft:swords …「官方挂的牌子」
 *   L2  通用物品标签  #c:ingots / #c:foods …模组物品的救星
 *   L3  词元规则     按 _ 切词整词匹配（见 CategoryRules）；"chestplate" 永远不等于 "chest"
 *   L4  兜底         其他
 * </pre>
 *
 * 先命中先返回。判定结果进 {@link #MEMO} 记忆化（{@code ConcurrentHashMap}：网页快照在 HTTP 线程也会调
 * {@link #of}），所以热路径 10 万槽位也只算一次；索引落盘时把这张表一起存进 format 2，
 * 下次开档 {@link #seed} 灌回来，连第一次扫描都不用现算。
 *
 * <p>覆盖表是唯一需要用户手写的文件，只写模组自己的配置目录，绝不碰存档。
 */
public final class Categories {

    private Categories() {
    }

    /** 显示顺序（也就是分类优先级） */
    public static final List<String> ORDER = CategoryRules.ORDER;

    private static final String OTHER = CategoryRules.OTHER;

    private static final Logger LOGGER = LoggerFactory.getLogger("warehouse-keeper/categories");

    /** 记忆化：物品 id → 分类 */
    private static final Map<String, String> MEMO = new ConcurrentHashMap<>();

    private static volatile Map<String, String> overrides = Map.of();
    private static volatile boolean loaded;
    private static volatile String loadNote = "尚未读取覆盖表";

    /** 标签规则：id 的 path 命中即归该分类；prefix=true 时按前缀命中（c:foods/* 这种） */
    private record TagRule(String tag, boolean prefix, String category) {
    }

    private static final Map<String, TagKey<Item>> TAG_CACHE = new ConcurrentHashMap<>();

    /** L1：原版标签。只挂「官方明确定义」的牌子，且刻意避开 *_food 那类会把花/种子当食物的通配标签。 */
    private static final List<TagRule> VANILLA = new ArrayList<>();

    /** L2：Fabric / NeoForge 通用标签（模组物品的救星） */
    private static final List<TagRule> COMMON = new ArrayList<>();

    static {
        // ---- L1 原版标签 ----
        vanilla("coal_ores", "copper_ores", "diamond_ores", "emerald_ores", "gold_ores", "iron_ores",
                "lapis_ores", "redstone_ores", "coals", "metal_nuggets", "beacon_payment_items",
                "trim_materials");
        // 木材植物
        vanilla("logs", "logs_that_burn", "crimson_stems", "warped_stems", "planks", "saplings", "leaves",
                "small_flowers", "flowers", "moss_blocks", "wart_blocks");
        // 刻意不要 minecraft:non_flammable_wood —— 它同时包含门/栅栏/按钮/船等「木制成品」，
        // 挂上去会把 crimson_door 归成木材植物，而 oak_door 走 doors 标签归建材方块，同一族被劈成两半。
        // 竹子方块同理：bamboo_blocks 标签是「竹块/竹马赛克」，属建材，所以下面建材组里挂着它。
        // 工具装备
        vanilla("swords", "axes", "pickaxes", "shovels", "hoes", "spears", "head_armor", "chest_armor",
                "leg_armor", "foot_armor", "trimmable_armor", "harnesses", "arrows", "enchantable/durability");
        // 食物：只挑真食物的标签（bee_food / chicken_food / panda_food 之类是花和种子，不能要）
        vanilla("meat", "fishes", "cat_food", "ocelot_food", "wolf_food", "fox_food", "axolotl_food",
                "nautilus_food", "piglin_food");
        // 农业：只留「真农产品」——草方块那一族是地形方块，归建材（见下），否则 dirt 建材、grass_block 农业，同一族被劈开
        // 红石机械
        vanilla("buttons", "stone_buttons", "rails", "lightning_rods");
        // 酿造附魔
        vanilla("brewing_fuel", "bookshelf_books", "lectern_books", "book_cloning_target");
        // 建材方块
        vanilla("stone_bricks", "stone_crafting_materials", "stone_tool_materials", "walls", "slabs",
                "stairs", "fences", "fence_gates", "doors", "trapdoors", "terracotta", "glazed_terracotta",
                "concrete", "concrete_powders", "wool", "wool_carpets", "sand", "dirt", "mud", "bars",
                "chains", "lanterns", "candles", "soul_fire_base_blocks", "smelts_to_glass",
                "copper_golem_statues", "grass_blocks", "beds", "banners", "signs", "hanging_signs",
                "bamboo_blocks", "wooden_shelves");
        // 容器杂项
        vanilla("shulker_boxes", "bundles", "boats",
                "chest_boats", "copper_chests", "decorated_pot_sherds", "dyes");

        // ---- L2 通用标签 ----
        // 刻意不要 c:storage_blocks —— 它连干海带块/干草块/骨块/黏液块一起打包，会把它们全归成矿物金属。
        // 金属/宝石块本来就带材料词元（iron_block、tin_block…），不需要这条。
        common("c:ingots", "c:nuggets", "c:raw_materials", "c:ores", "c:gems", "c:dusts");
        common("c:logs", "c:saplings", "c:leaves");
        commonPrefix("c:wooden_");
        common("c:tools", "c:armors", "c:swords");
        commonPrefix("c:tools/");
        common("c:foods");
        commonPrefix("c:foods/");
        common("c:seeds", "c:crops", "c:fertilizers", "c:mushrooms");
        common("c:chests", "c:barrels", "c:buckets", "c:dyes");
        common("c:stones", "c:cobblestones", "c:glass_blocks", "c:concrete", "c:terracotta");
    }

    private static void vanilla(String... paths) {
        String category = vanillaCategory(paths[0]);
        for (String p : paths) {
            VANILLA.add(new TagRule("minecraft:" + p, false, category));
        }
    }

    private static void common(String... tags) {
        String category = commonCategory(tags[0]);
        for (String t : tags) {
            COMMON.add(new TagRule(t, false, category));
        }
    }

    private static void commonPrefix(String prefix) {
        COMMON.add(new TagRule(prefix, true, commonCategory(prefix)));
    }

    /**
     * 上面的 {@code vanilla(...)} 是「一组同名标签一起归类」的写法，但每组分类不同，
     * 所以这里靠标签名反查分类 —— 免得每行都要写一遍分类名还容易写错。
     */
    private static String vanillaCategory(String firstTag) {
        return switch (firstTag) {
            case "coal_ores" -> CategoryRules.MINERAL;
            case "logs" -> CategoryRules.WOOD;
            case "swords" -> CategoryRules.TOOL;
            case "meat" -> CategoryRules.FOOD;
            case "grass_blocks" -> CategoryRules.FARM;
            case "buttons" -> CategoryRules.REDSTONE;
            case "brewing_fuel" -> CategoryRules.MAGIC;
            case "stone_bricks" -> CategoryRules.BUILD;
            case "shulker_boxes" -> CategoryRules.CONTAINER;
            default -> CategoryRules.OTHER;
        };
    }

    private static String commonCategory(String firstTag) {
        return switch (firstTag) {
            case "c:ingots" -> CategoryRules.MINERAL;
            case "c:logs" -> CategoryRules.WOOD;
            case "c:tools" -> CategoryRules.TOOL;
            case "c:foods" -> CategoryRules.FOOD;
            case "c:seeds" -> CategoryRules.FARM;
            case "c:chests" -> CategoryRules.CONTAINER;
            case "c:stones" -> CategoryRules.BUILD;
            case "c:wooden_" -> CategoryRules.WOOD;
            case "c:tools/" -> CategoryRules.TOOL;
            default -> CategoryRules.OTHER;
        };
    }

    // ------------------------------------------------------------------
    // 热路径

    /** 判定一个物品的分类（带记忆化）。任何情况下都返回一个分类名。 */
    public static String of(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return OTHER;
        }
        String hit = MEMO.get(itemId);
        if (hit != null) {
            return hit;
        }
        String cat = decide(itemId).category();
        MEMO.put(itemId, cat);
        return cat;
    }

    /** 判定明细（分类 / 判定层 / 证据），给 /warehouse categories 用 */
    public record Decision(String category, String layer, String detail, String key) {
    }

    /** 完整判定，不走记忆化 */
    public static Decision decide(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return new Decision(OTHER, "L4 兜底", "空 id", "");
        }
        String id = normalize(itemId);
        ensureLoaded();

        String ov = overrides.get(id);
        if (ov != null) {
            return new Decision(ov, "L0 覆盖表", "categories.json", id);
        }

        // ---- L0.5 名称钉死：钉死例外 / 后缀，**先于标签** ----
        // 实测教训：L1/L2 标签里也会躺着「不属于这个筐」的东西 ——
        // #minecraft:nautilus_food 里有 pufferfish_bucket、#minecraft:axolotl_food 里有 tropical_fish_bucket，
        // #c:crops 里有 pumpkin，#minecraft:trim_materials 里有 resin_brick。
        // 名字本身的钉死规则比这些「顺手挂上去」的标签更可信，所以先判、先赢。
        String path = ItemIds.path(id).toLowerCase(Locale.ROOT);
        String fixed = CategoryRules.exactOf(path);
        if (fixed != null) {
            return new Decision(fixed, "L0.5 名称钉死", "钉死 " + path, id);
        }
        String suffixKey = CategoryRules.suffixHit(path);
        if (suffixKey != null) {
            return new Decision(CategoryRules.suffixOf(path), "L0.5 名称钉死", "后缀 " + suffixKey, id);
        }

        Holder.Reference<Item> holder = holderOf(id);
        if (holder != null) {
            for (TagRule r : VANILLA) {
                if (hit(holder, r)) {
                    return new Decision(r.category(), "L1 原版标签", "#" + r.tag(), id);
                }
            }
            for (TagRule r : COMMON) {
                if (hit(holder, r)) {
                    return new Decision(r.category(), "L2 通用标签", "#" + r.tag(), id);
                }
            }
        }

        String rule = CategoryRules.classify(id);
        if (rule != null) {
            return new Decision(rule, "L3 词元规则", "整词匹配", id);
        }
        String suffix = bySuffix(id);
        if (suffix != null) {
            return new Decision(suffix, "L3 词元规则", "后缀", id);
        }
        return new Decision(OTHER, "L4 兜底", "没有任何证据", id);
    }

    private static String bySuffix(String id) {
        String p = ItemIds.path(id).toLowerCase(Locale.ROOT);
        if (p.endsWith("_seeds") || p.endsWith("_seed")) {
            return CategoryRules.FARM;
        }
        if (p.endsWith("_slab") || p.endsWith("_stairs") || p.endsWith("_wall")
                || p.endsWith("_trapdoor") || p.endsWith("_door")) {
            return CategoryRules.BUILD;
        }
        if (p.endsWith("_ore") || p.endsWith("_ingot") || p.endsWith("_nugget")) {
            return CategoryRules.MINERAL;
        }
        return null;
    }

    private static boolean hit(Holder.Reference<Item> holder, TagRule r) {
        TagKey<Item> key = TAG_CACHE.computeIfAbsent(r.tag(), t -> {
            Identifier id = Identifier.tryParse(r.prefix() ? t.substring(0, t.length() - 1) : t);
            return id == null ? null : TagKey.create(Registries.ITEM, id);
        });
        if (key == null) {
            return false;
        }
        if (!r.prefix()) {
            return holder.is(key);
        }
        // 前缀规则：标签自己不带通配，逐个判断 holder 身上的标签
        return holder.tags().anyMatch(tk -> tk.location().toString().startsWith(r.tag()));
    }

    private static Holder.Reference<Item> holderOf(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(key);
        return item == null ? null : item.builtInRegistryHolder();
    }

    private static String normalize(String itemId) {
        String s = itemId.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    // ------------------------------------------------------------------
    // 记忆化落盘 / 热启动

    /** 把落盘的分类表灌进记忆化（索引 format 2 里的 categories） */
    public static void seed(Map<String, String> saved) {
        MEMO.clear();
        if (saved != null && !saved.isEmpty()) {
            MEMO.putAll(saved);
        }
    }

    /** 给落盘用的快照 */
    public static Map<String, String> snapshot() {
        return new LinkedHashMap<>(MEMO);
    }

    public static int memoSize() {
        return MEMO.size();
    }

    public static void clearMemo() {
        MEMO.clear();
    }

    // ------------------------------------------------------------------
    // L0 覆盖表

    public static Path overrideFile() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("warehouse-keeper").resolve("categories.json");
    }

    public static int overrideCount() {
        return overrides.size();
    }

    public static String loadNote() {
        return loadNote;
    }

    /** 重新读覆盖表并清空记忆化（改完覆盖表执行 /warehouse categories reload） */
    public static synchronized void reload() {
        loaded = false;
        MEMO.clear();
        overrides = Map.of();
        ensureLoaded();
    }

    /** 第一次用到时读一次；文件不存在就写一份模板（只写模组自己的配置目录） */
    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path f = overrideFile();
        try {
            if (!Files.isRegularFile(f)) {
                writeTemplate(f);
                overrides = Map.of();
                loadNote = "覆盖表不存在，已生成模板：" + f;
                return;
            }
            Map<String, String> map = new LinkedHashMap<>();
            String json = Files.readString(f, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                overrides = Map.of();
                loadNote = "覆盖表不是 JSON 对象，已忽略：" + f;
                LOGGER.warn("[warehouse-keeper] 覆盖表不是 JSON 对象：{}", f);
                return;
            }
            int bad = 0;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                String k = e.getKey();
                if (k.startsWith("_")) {
                    continue; // 模板里的说明行
                }
                if (!k.contains(":")) {
                    bad++;
                    LOGGER.warn("[warehouse-keeper] 覆盖表条目「{}」缺少命名空间，已忽略", k);
                    continue;
                }
                String v = e.getValue().isJsonPrimitive() ? e.getValue().getAsString().trim() : "";
                if (!ORDER.contains(v)) {
                    bad++;
                    LOGGER.warn("[warehouse-keeper] 覆盖表条目「{}」的分类「{}」不是已知分类，已忽略", k, v);
                    continue;
                }
                map.put(k.trim().toLowerCase(Locale.ROOT), v);
            }
            overrides = Map.copyOf(map);
            loadNote = "已载入覆盖表 " + map.size() + " 条"
                    + (bad > 0 ? "（忽略 " + bad + " 条无效）" : "") + "：" + f;
        } catch (Exception ex) {
            overrides = Map.of();
            loadNote = "覆盖表读取失败：" + ex;
            LOGGER.warn("[warehouse-keeper] 覆盖表读取失败: {}", ex.toString());
        }
    }

    private static void writeTemplate(Path f) throws Exception {
        Files.createDirectories(f.getParent());
        String t = "{\n"
                + "  \"_说明\": \"物品分类覆盖表：键 = 完整物品 id（必须带命名空间），值 = 分类名。改完执行 /warehouse categories reload\",\n"
                + "  \"_分类\": \"" + String.join(" / ", ORDER) + "\",\n"
                + "  \"_示例\": \"modid:example_item → 矿物金属（照这个格式另起一行即可）\"\n"
                + "}\n";
        Files.writeString(f, t, StandardCharsets.UTF_8);
    }
}
