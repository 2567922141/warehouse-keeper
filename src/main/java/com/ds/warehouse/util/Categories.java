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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品分类（D1 v3：创造页签类目 + 旧名父类 + 记忆化）。
 *
 * <p><b>类目键 = 物品所在创造页签的注册键</b>（{@link #of}）：{@code minecraft:building_blocks}、
 * {@code minecraft:ingredients}、模组自己的页签 {@code modid:xxx}；不在任何 CATEGORY 页签的物品一律
 * {@link CategoryRules#OTHER}（{@code warehouse-keeper:other}）。页签由另一位写者的
 * {@link CreativeOrder} 建表，类目键与语言无关，且天然覆盖所有模组（Fabric 把模组物品注入原版页签）。
 *
 * <p><b>旧中文类目名不迁移、不改写</b>（用户拍板）：{@code container-tags.json} 里的「建材方块」这类值
 * 原样留着，被当成<b>父类</b> —— {@link #matches} 展开 {@link #children} 之后，贴了旧名的箱子仍然能收
 * 新键物品。映射不是手写表，而是<b>运行时推导</b>：遍历注册表，对每个物品同时算「旧判定链给的旧类目」
 * 与「页签给的新键」，两边一撮合就有了 {@code 旧名 → {新键…}}。
 *
 * <p><b>诊断链 {@link #decide} 原样保留</b>（{@code /warehouse categories check} 还在用）：
 * 旧判定链是 {@link #children} 与 {@link #decide} 共同的地基（{@link #legacyOf}），
 * 所以下面这四层判定本身仍然是「旧类目」的定义，只是不再是玩家看到的类目：
 * <pre>
 *   L0  用户覆盖表   config/warehouse-keeper/categories.json（键=完整 id，可热重载）
 *   L0.5 名称钉死   少数 id 与 _spawn_egg/_bucket 后缀（见 CategoryRules），**先于标签**
 *   L1  原版物品标签  #minecraft:logs / #minecraft:swords …
 *   L2  通用物品标签  #c:ingots / #c:foods …模组物品的救星
 *   L3  词元规则     按 _ 切词整词匹配（见 CategoryRules）；"chestplate" 永远不等于 "chest"
 *   L4  兜底         其他
 * </pre>
 *
 * <p>判定结果进 {@link #MEMO} 记忆化（{@code ConcurrentHashMap}：网页快照在 HTTP 线程也会调
 * {@link #of}），所以热路径 10 万槽位也只算一次；索引落盘时把这张表一起存进 format 2，
 * 下次开档 {@link #seed} 灌回来，连第一次扫描都不用现算。
 *
 * <p>覆盖表是唯一需要用户手写的文件，只写模组自己的配置目录，绝不碰存档。
 */
public final class Categories {

    private Categories() {
    }

    /**
     * <b>旧</b>中文类目的显示顺序（{@link CategoryRules#ORDER}）。
     *
     * <p><b>遗留结构，新代码别再用</b>：0.21.0 的类目键是创造页签键，展示顺序看 {@link #order()}。
     * 保留它是因为 {@code command/}、{@code client/}、{@code web/} 仍在按它遍历（那几位写者的活），
     * 而且 {@code categoryListText} 这类老调用点还没有全部换过来。字段本身不会删。
     *
     * @deprecated 用 {@link #order()}（新类目键的展示顺序）或 {@link #displayName(String)}
     */
    @Deprecated
    public static final List<String> ORDER = CategoryRules.ORDER;

    /** 合成类目键：不属于任何创造页签的物品都归它 */
    public static final String OTHER = CategoryRules.OTHER;

    private static final Logger LOGGER = LoggerFactory.getLogger("warehouse-keeper/categories");

    /** 记忆化：物品 id → 类目键（新键） */
    private static final Map<String, String> MEMO = new ConcurrentHashMap<>();

    /** 「旧名（父类）→ 它原来管辖的物品现在的新键」的反向表，惰性算一次 */
    private static volatile Map<String, Set<String>> CHILDREN = null;

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
    // 类目键（新体系）

    /**
     * 物品的类目键（带记忆化）。
     *
     * <p>{@link CreativeOrder#tabOf} 认得它（落在某个 CATEGORY 页签里）就用页签注册键，
     * 否则一律 {@link CategoryRules#OTHER}。任何情况下都返回一个非空类目键。
     *
     * @param itemId 完整物品 id，例如 {@code minecraft:diamond}；null / 空返回 {@code OTHER}
     */
    public static String of(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return OTHER;
        }
        String hit = MEMO.get(itemId);
        if (hit != null) {
            return hit;
        }
        String cat = newKeyOf(itemId);
        MEMO.put(itemId, cat);
        return cat;
    }

    /**
     * 新类目键的判定：<b>L0 覆盖表优先</b>，其次物品所在创造页签，没有页签就是「其他」。
     * 不走记忆化（记忆化在 {@link #of}）。
     *
     * <p>覆盖表在 0.21.0 仍然有效、且优先于页签：玩家可以写页签键（{@code minecraft:building_blocks}
     * 之类，可省 {@code minecraft:} 前缀），也可以继续写旧中文名 —— 后者把物品钉进旧父类，
     * 只被贴着同名旧标签的箱子收，语义与 0.20.x 一致。
     */
    private static String newKeyOf(String itemId) {
        ensureLoaded();
        String ov = overrides.get(itemId);
        if (ov != null && !ov.isEmpty()) {
            return ov;
        }
        String tab = CreativeOrder.tabOf(itemId);
        return tab == null ? OTHER : tab;
    }

    /**
     * 这个「箱子标签分类」收不收这个物品类目。
     *
     * @param tagCategory  箱子标签上写的分类：新页签键，或旧中文名（父类）
     * @param itemCategory 物品类目（{@link #of} 的返回值）
     * @return true 表示同键，或 {@code tagCategory} 是旧名且它原来管辖的物品里有这个新键
     */
    public static boolean matches(String tagCategory, String itemCategory) {
        if (tagCategory == null || itemCategory == null) {
            return false;
        }
        return tagCategory.equals(itemCategory) || children(tagCategory).contains(itemCategory);
    }

    /**
     * 旧中文名 → 它「原来管辖」的物品现在的新键集合。
     *
     * <p>不是手写映射表，是<b>运行时推导</b>的（{@link #childrenMap}）：遍历注册表，
     * 每个物品同时算旧判定链给的旧类目（{@link #legacyOf}）与页签给的新键（{@link #of}），
     * 旧名 → 新键即为所需。所以模组物品自动在内，也不会因为「页签里有什么」判断失误而错。
     *
     * @param tagCategory 分类名
     * @return 不可变集合；不是旧中文名时返回空集合（新键的物品请用 {@link #matches} 判等）
     */
    public static Set<String> children(String tagCategory) {
        if (tagCategory == null) {
            return Set.of();
        }
        Set<String> kids = childrenMap().get(tagCategory);
        return kids == null ? Set.of() : kids;
    }

    /**
     * 把用户输入规范化成类目键。
     *
     * <ul>
     *   <li>新键（含命名空间）：{@code minecraft:ingredients} → 原样</li>
     *   <li>省略命名空间的页签名：{@code ingredients} → {@code minecraft:ingredients}</li>
     *   <li>旧中文名（「建材方块」…）：<b>返回该旧名本身</b> —— 它是父类，不能折叠成某一个新键</li>
     *   <li>合成类目「其他」：{@code warehouse-keeper:other} / {@code other}</li>
     *   <li>都认不出：{@code null}</li>
     * </ul>
     */
    public static String resolve(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        if (CategoryRules.isLegacy(s)) {
            return s;
        }
        if (s.equals(OTHER)) {
            return OTHER;
        }
        if (tabKeys().contains(s)) {
            return s;
        }
        // 省略命名空间：按 minecraft 补全（模组页签必须写全名，否则无歧义可言）
        String full = "minecraft:" + s;
        if (tabKeys().contains(full)) {
            return full;
        }
        return null;
    }

    /**
     * 展示 / 统计顺序：{@link CreativeOrder#categoryTabs()} 的页签顺序，
     * 末尾追加合成类目 {@link #OTHER}。
     *
     * @return 不可变列表；{@link CreativeOrder} 建表失败（返回空表）时至少是 {@code [OTHER]}
     */
    public static List<String> order() {
        List<String> tabs = CreativeOrder.categoryTabs();
        if (tabs == null || tabs.isEmpty()) {
            return List.of(OTHER);
        }
        List<String> out = new ArrayList<>(tabs.size() + 1);
        out.addAll(tabs);
        out.remove(OTHER); // 模组若真注册了一个叫 warehouse-keeper:other 的页签，只留一次
        out.add(OTHER);
        return List.copyOf(out);
    }

    /**
     * 类目键的人话名字。
     *
     * <ul>
     *   <li>旧中文名：字面量（玩家自己写的就是中文，不需要翻译）</li>
     *   <li>合成类目：lang 键 {@code warehouse.category.other} 的当前语言值，没有资源包时回落「其他」</li>
     *   <li>页签键：页签自己的 {@code getDisplayName()}（模组页签自带本地化）</li>
     *   <li>认不出的键：回退字面量</li>
     * </ul>
     */
    public static String displayName(String categoryKey) {
        if (categoryKey == null || categoryKey.isEmpty()) {
            return OTHER;
        }
        if (CategoryRules.isLegacy(categoryKey)) {
            return categoryKey;
        }
        if (OTHER.equals(categoryKey)) {
            // 服务端没有语言文件（专服）时 translatable 取不到值、会原样返回 lang 键，
            // 那条字符串会直接进指令回显 —— 落回旧字面量「其他」比露出 lang 键体面。
            String translated = Component.translatable("warehouse.category.other").getString();
            return translated == null || translated.isEmpty() || translated.equals("warehouse.category.other")
                    ? LEGACY_OTHER : translated;
        }
        CreativeModeTab tab = tabOf(categoryKey);
        return tab == null ? categoryKey : tab.getDisplayName().getString();
    }

    /** {@link #displayName} 的组件版（UI 层要排版时用） */
    public static Component label(String categoryKey) {
        if (categoryKey == null || categoryKey.isEmpty()) {
            return Component.translatable("warehouse.category.other");
        }
        if (CategoryRules.isLegacy(categoryKey)) {
            return Component.literal(categoryKey);
        }
        if (OTHER.equals(categoryKey)) {
            return Component.translatable("warehouse.category.other");
        }
        CreativeModeTab tab = tabOf(categoryKey);
        return tab == null ? Component.literal(categoryKey) : tab.getDisplayName();
    }

    /** 把类目键翻回页签对象；不是已知页签返回 null */
    private static CreativeModeTab tabOf(String tabKey) {
        Identifier id = Identifier.tryParse(tabKey);
        if (id == null || !BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(id)) {
            return null;
        }
        CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(id);
        return tab != null && tab.getType() == CreativeModeTab.Type.CATEGORY ? tab : null;
    }

    /** 全部合法页签键：{@link CreativeOrder} 建的表 + 注册表兜底（建表失败时 {@link #resolve} 仍可用） */
    private static Set<String> tabKeys() {
        Set<String> keys = new LinkedHashSet<>(CreativeOrder.categoryTabs());
        for (Identifier id : BuiltInRegistries.CREATIVE_MODE_TAB.keySet()) {
            CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(id);
            if (tab != null && tab.getType() == CreativeModeTab.Type.CATEGORY) {
                keys.add(id.toString());
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // 旧名（父类）→ 新键：运行时推导 + 保护性缓存

    /** 旧名 → {新键} 的不可变快照 */
    private static Map<String, Set<String>> childrenMap() {
        Map<String, Set<String>> cache = CHILDREN;
        if (cache != null) {
            return cache;
        }
        synchronized (Categories.class) {
            if (CHILDREN == null) {
                CHILDREN = buildChildren();
            }
            return CHILDREN;
        }
    }

    /**
     * 遍历注册表把 {@code 旧名 → {新键}} 导出来。
     *
     * <p>两个保护：① 跳过没有注册键的物品（{@code getKey} 返回 null，跨整合包恢复索引时常见）；
     * ② 旧名与新键任一为空就不收，绝不让 map 里出现 null。
     */
    private static Map<String, Set<String>> buildChildren() {
        ensureLoaded();
        Map<String, Set<String>> built = new LinkedHashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) {
                continue;
            }
            String legacy = legacyOf(id.toString());
            if (legacy == null || legacy.isEmpty()) {
                continue;
            }
            String now = of(id.toString());
            if (now == null || now.isEmpty()) {
                continue;
            }
            built.computeIfAbsent(legacy, k -> new LinkedHashSet<>()).add(now);
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>(built.size());
        for (Map.Entry<String, Set<String>> e : built.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
        }
        return Collections.unmodifiableMap(frozen);
    }

    /**
     * 现有那条旧判定链，只取类目名（一层一层与 {@link #decide} 严格同序，行为逐字不变）。
     *
     * <p>刻意<b>不</b>返回 {@link CategoryRules#OTHER}（合成键）：这里要的是「旧中文名」，
     * 兜底必须是字面量 {@code "其他"}，否则 {@link #children} 会把 {@code minecraft:air} 之流
     * 当成贴了新键标签的物品来收。旧判定链的锚点恒定是 {@code CategoryRules.ORDER}（第 9 位就是「其他」）。
     */
    private static String legacyOf(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return LEGACY_OTHER;
        }
        String id = normalize(itemId);
        ensureLoaded();

        String ov = overrides.get(id);
        if (ov != null) {
            return ov;
        }
        String path = ItemIds.path(id).toLowerCase(Locale.ROOT);
        String fixed = CategoryRules.exactOf(path);
        if (fixed != null) {
            return fixed;
        }
        String suffixKey = CategoryRules.suffixHit(path);
        if (suffixKey != null) {
            return CategoryRules.suffixOf(path);
        }

        Holder.Reference<Item> holder = holderOf(id);
        if (holder != null) {
            for (TagRule r : VANILLA) {
                if (hit(holder, r)) {
                    return r.category();
                }
            }
            for (TagRule r : COMMON) {
                if (hit(holder, r)) {
                    return r.category();
                }
            }
        }

        String rule = CategoryRules.classify(id);
        if (rule != null) {
            return rule;
        }
        String suffix = bySuffix(id);
        if (suffix != null) {
            return suffix;
        }
        return LEGACY_OTHER;
    }

    /** 旧判定链的兜底名（字面量「其他」，不是合成键 {@link #OTHER}） */
    private static final String LEGACY_OTHER = "其他";

    // ------------------------------------------------------------------
    // 热路径（旧判定链，诊断用）

    /** 判定明细（分类 / 判定层 / 证据），给 /warehouse categories 用 */
    public record Decision(String category, String layer, String detail, String key) {
    }

    /**
     * 完整判定，不走记忆化。
     *
     * <p><b>注意</b>：这里返回的「分类」是<b>旧中文类目</b>（旧判定链的原始定义），
     * 供 {@code /warehouse categories check|dumpall} 展示与 {@link #children} 推导使用；
     * 玩家看到的类目键请用 {@link #of}。
     */
    public static Decision decide(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return new Decision(LEGACY_OTHER, "L4 兜底", "空 id", "");
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
        return new Decision(LEGACY_OTHER, "L4 兜底", "没有任何证据", id);
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

    /**
     * 清掉全部运行时缓存：物品 → 类目（{@link #clearMemo}）与「旧名 → 新键」那张推导表。
     *
     * <p>服务器启动时调一次（{@link com.ds.warehouse.WarehouseMod}）：{@link CreativeOrder} 的页签表
     * 那时才建好，之前算出来的类目与 children 可能基于空表，必须整批丢掉。
     */
    public static synchronized void clearCaches() {
        MEMO.clear();
        CHILDREN = null;
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
        CHILDREN = null;
        overrides = Map.of();
        ensureLoaded();
    }

    /**
     * 第一次用到时读一次；文件不存在就写一份模板（只写模组自己的配置目录）。
     *
     * <p>覆盖表的值按 {@link #resolve} 规范化后校验，页签键与旧中文名都收：页签键把物品钉进该页签，
     * 旧中文名把它钉进旧父类（只被贴着同名旧标签的箱子收，即 0.20.x 的语义）。
     * 覆盖表优先于页签判定（见 {@link #newKeyOf}）。
     */
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
                // 0.21.0：值既可以是页签键（minecraft:building_blocks，可省前缀），也可以是旧中文名（父类）
                String canon = resolve(v);
                if (canon == null) {
                    bad++;
                    LOGGER.warn("[warehouse-keeper] 覆盖表条目「{}」的分类「{}」不是已知分类，已忽略", k, v);
                    continue;
                }
                map.put(k.trim().toLowerCase(Locale.ROOT), canon);
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
                + "  \"_说明\": \"物品分类覆盖表：键 = 完整物品 id（必须带命名空间），值 = 分类名（页签键或旧中文名）。改完执行 /warehouse categories reload\",\n"
                + "  \"_分类（页签键）\": \"" + String.join(" / ", order()) + "\",\n"
                + "  \"_旧分类（仍可用，作父类）\": \"" + String.join(" / ", CategoryRules.ORDER) + "\",\n"
                + "  \"_示例\": \"modid:example_item → minecraft:building_blocks；写 建材方块 也行（旧父类，只被贴着同名旧标签的箱子收）\"\n"
                + "}\n";
        Files.writeString(f, t, StandardCharsets.UTF_8);
    }
}
