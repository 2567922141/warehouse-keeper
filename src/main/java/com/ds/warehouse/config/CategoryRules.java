package com.ds.warehouse.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分类规则的「纯字符串层」（D1 四层判定里的 L3）。
 *
 * <p>只认物品 id 本身，不碰注册表、不碰标签、不碰文件 —— 刻意保持零依赖，
 * 这样离线就能跑回归（见 {@code tools/categories/check.ps1}）。
 * 标签层（L1 原版标签 / L2 通用标签）在 {@link com.ds.warehouse.util.Categories}，那里必须读注册表。
 *
 * <h2>为什么不是「子串匹配」</h2>
 * 旧实现用 {@code path.contains("chest")}，于是 {@code chestplate} 变成容器、{@code bedrock} 变成床。
 * 这里改成**按 {@code _} 切成词元整词匹配**：{@code chestplate} 是一个词元，永远不等于 {@code chest}。
 *
 * <h2>优先级</h2>
 * 每个词元带一档优先级：{@code 0 强形制}（ore/ingot/sword/door…，几乎不会误判）＞
 * {@code 1 材料}（diamond/iron/redstone…，是"什么做的"）＞ {@code 2 弱形制}（block/slab/dye…，只有别的都没命中时才用）。
 * 同档里**相邻双词**（{@code sugar_cane}、{@code mushroom_stew}）比单词更具体，优先；
 * 再不行取 {@link #ORDER} 里靠前的类别（顺序本身就是优先级）。
 */
public final class CategoryRules {

    private CategoryRules() {
    }

    public static final String MINERAL = "矿物金属";
    public static final String BUILD = "建材方块";
    public static final String WOOD = "木材植物";
    public static final String TOOL = "工具装备";
    public static final String FOOD = "食物";
    public static final String FARM = "农业";
    public static final String REDSTONE = "红石机械";
    public static final String MAGIC = "酿造附魔";
    public static final String CONTAINER = "容器杂项";

    /**
     * 合成类目键：<b>不属于任何创造页签</b>的物品统一落这里。
     *
     * <p>0.21.0 起物品类目不再用中文常量，而是「物品所在创造页签的注册键」
     * （见 {@link com.ds.warehouse.util.Categories#of}）；带命名空间是为了它和
     * {@code minecraft:building_blocks}、{@code modid:xxx} 这些真页签键长得一样，
     * 存进 {@code container-tags.json} 与 {@code categories.json} 时不会跟旧中文名混淆。
     */
    public static final String OTHER = "warehouse-keeper:other";

    /**
     * 十个旧中文类目名 —— <b>现在它们是「父类」</b>，不再是判定结果。
     *
     * <p>旧存档的 {@code container-tags.json} 里写着这些名字，用户拍板不迁移、不改写：
     * 贴了旧名的箱子仍然收 {@link com.ds.warehouse.util.Categories#children} 算出来的新键物品。
     * 常量本身保留字面量，任何写盘/读盘路径都还能拿到它们。
     */
    public static final List<String> LEGACY = List.of(
            MINERAL, BUILD, WOOD, TOOL, FOOD, FARM, REDSTONE, MAGIC, CONTAINER, "其他");

    /**
     * 旧中文类目的显示顺序（也是同档平手时的优先级）。
     *
     * <p><b>遗留结构</b>：0.21.0 的类目键已经是创造页签键，展示顺序看
     * {@link com.ds.warehouse.util.Categories#order()}。这个 List 留着是因为三条链仍按它排序：
     * ① 判定表自检/离线回归（{@code tools/categories/check.ps1}）；② 覆盖表模板文案；
     * ③ 客户端标签环 {@code client/ContainerTagBar}。刻意不把它换成页签键 —— 本类必须保持零 Minecraft 依赖。
     */
    public static final List<String> ORDER = List.of(
            MINERAL, BUILD, WOOD, TOOL, FOOD, FARM, REDSTONE, MAGIC, CONTAINER, "其他");

    /**
     * 规则表版本。**改动下面任何一张表就要 +1** —— 索引文件里存着上次算好的分类表，
     * 版本对不上就丢弃重算，免得规则改过之后还拿旧结果当答案。
     *
     * <p>5：类目键从自研中文常量换成创造页签注册键（0.21.0）。
     */
    public static final int VERSION = 5;

    /** 强形制：这个词一出现，几乎就定了 */
    private static final int STRONG = 0;
    /** 材料：这个东西是用什么做的 */
    private static final int MATERIAL = 1;
    /** 弱形制：兜底形状词，只有前面都没命中才轮到 */
    private static final int WEAK = 2;

    /** 词元 → (优先级 << 8 | 类别序号) */
    private static final Map<String, Integer> TOKENS = new HashMap<>();
    /** 整条路径的例外表：少数「词元怎么排都不对」的 id 在这里钉死 */
    private static final Map<String, Integer> EXACT = new HashMap<>();
    /**
     * 被重复登记的词元。同一张表里写两遍是**后写覆盖先写**（静默改语义），
     * 所以这里留个账，自检要它永远是 0。
     */
    private static final List<String> DUPS = new ArrayList<>();

    private static int c(String name) {
        return ORDER.indexOf(name);
    }

    private static void put(int prio, String category, String... keys) {
        int v = (prio << 8) | c(category);
        for (String k : keys) {
            if (TOKENS.put(k, v) != null) {
                DUPS.add(k);
            }
        }
    }

    private static void exact(String category, String... paths) {
        int v = c(category);
        for (String p : paths) {
            EXACT.put(p, v);
        }
    }

    static {
        // ---- 强形制 ----
        put(STRONG, MINERAL, "ore", "ingot", "nugget", "raw", "gem", "gems", "shard", "debris", "scrap");
        put(STRONG, WOOD, "log", "logs", "wood", "planks", "plank", "sapling", "leaves", "stem", "hyphae",
                "fungus", "nylium", "vine", "vines", "roots", "seagrass", "propagule", "moss");
        put(STRONG, TOOL, "sword", "pickaxe", "axe", "shovel", "hoe", "helmet", "chestplate", "leggings",
                "boots", "bow", "crossbow", "arrow", "arrows", "shield", "trident", "mace", "spear", "brush",
                "armor", "elytra", "totem", "shears", "fishing_rod", "wolf_armor", "horse_armor", "spyglass",
                "flint_and_steel", "stonecutter", "grindstone", "smithing_table", "cartography_table",
                "fletching_table", "loom", "crafting_table");
        put(STRONG, FOOD, "apple", "bread", "cookie", "cake", "pie", "soup", "stew", "beef", "porkchop",
                "chicken", "mutton", "fish", "cod", "salmon", "tropical_fish", "egg", "milk", "honey", "berry",
                "berries", "carrot", "potato", "beetroot", "melon", "sugar", "cheese", "tomato",
                "rice", "noodles", "chocolate", "jam", "juice", "tea", "coffee", "fruit");
        put(STRONG, FARM, "seeds", "seed", "wheat", "hay", "crop", "crops", "bone_meal", "compost",
                "fertilizer", "manure", "sprout", "sprouts", "seedling", "beehive", "bee_nest", "grass",
                "fern", "kelp", "cactus", "mushroom", "mycelium", "lily_pad", "sugar_cane",
                "dripleaf", "bush", "lichen", "pickle", "frogspawn", "chorus_plant", "farmland");
        put(STRONG, REDSTONE, "piston", "observer", "repeater", "comparator", "dispenser", "dropper", "hopper",
                "lever", "button", "pressure_plate", "rail", "rails", "minecart", "redstone_block", "target",
                "daylight_detector", "tripwire_hook", "note_block", "sculk_sensor", "lightning_rod",
                "sculk", "tnt", "jukebox", "crafter");
        put(STRONG, MAGIC, "potion", "potions", "brewing", "enchant", "enchanted", "experience_bottle",
                "glistering_melon_slice");
        put(STRONG, CONTAINER, "chest", "barrel", "shulker", "box", "backpack", "drawer", "crate", "bundle",
                "bucket", "boat", "chest_boat", "sack", "pouch", "bag");
        put(STRONG, WOOD, "allium", "poppy", "dandelion", "tulip", "lilac", "peony", "rose_bush",
                "azure_bluet", "oxeye_daisy", "pink_petals", "wither_rose", "azalea", "torchflower",
                "sunflower", "cornflower", "orchid", "lily", "flower", "flowers", "petals", "bamboo");

        // ---- 材料 ----
        put(MATERIAL, MINERAL, "diamond", "iron", "gold", "golden", "copper", "emerald", "coal", "charcoal",
                "quartz", "amethyst", "netherite", "tin", "lead", "silver", "nickel", "uranium", "titanium",
                "zinc", "bronze", "steel", "platinum", "cobalt", "aluminum", "aluminium", "osmium", "tungsten",
                "lapis", "flint", "dust", "crystal", "crystals");
        put(MATERIAL, REDSTONE, "redstone");
        put(MATERIAL, MAGIC, "glowstone", "blaze", "ghast", "nether_wart", "wart", "dragon_breath",
                "ender_pearl", "ender_eye", "spider_eye", "book", "bottle");
        put(MATERIAL, FARM, "cocoa");

        // ---- 弱形制 ----
        put(WEAK, BUILD, "block", "slab", "stairs", "wall", "fence", "fence_gate", "gate", "door", "trapdoor",
                "glass", "pane", "brick", "bricks", "stone", "cobblestone", "deepslate", "dirt", "sand",
                "gravel", "terracotta", "concrete", "wool", "carpet", "clay", "mud", "basalt", "tuff", "calcite",
                "dripstone", "prismarine", "purpur", "end_stone", "netherrack", "blackstone", "bookshelf",
                "torch", "lantern", "bedrock", "obsidian", "soul_soil", "magma", "snow", "ice", "bone_block",
                "chain", "scaffolding", "ladder", "flower_pot", "sign", "banner", "bed", "candle", "composter",
                "cauldron", "beacon", "conduit", "lectern", "campfire", "respawn_anchor", "decorated_pot",
                "sponge", "cobweb", "andesite", "granite", "diorite", "sandstone", "rooted_dirt", "coarse_dirt",
                "lodestone", "coral", "frame", "painting", "honeycomb", "snowball",
                "shelf", "froglight", "shroomlight", "head", "skull");
        put(WEAK, CONTAINER, "dye", "shell", "scute", "tag", "saddle", "compass", "clock", "paper", "map",
                "firework_rocket", "firework_star", "goat_horn", "spawner", "vault", "trim",
                "template", "gunpowder", "string", "feather", "leather", "slime_ball", "disc", "discs",
                "resin", "trial_key", "ink_sac", "bone");
        put(WEAK, TOOL, "anvil");
        put(WEAK, REDSTONE, "furnace", "smoker");
        put(WEAK, WOOD, "stick", "litter");

        // ---- 钉死的例外（整条路径）----
        // 这张表在 Categories.decide() 里于**标签之前**生效：实测 L1/L2 标签也会打架 ——
        // #minecraft:nautilus_food 里躺着 pufferfish_bucket、#*_food 里躺着刷怪蛋，
        // #c:crops 里躺着 pumpkin、#minecraft:trim_materials 里躺着 resin_brick。
        // 名称规则比这些「顺手挂上去」的标签更可信，所以先判、先赢。
        exact(TOOL, "stonecutter", "grindstone", "smithing_table", "cartography_table", "fletching_table",
                "loom", "anvil", "saddle");
        exact(BUILD, "bookshelf", "chiseled_bookshelf", "bedrock", "obsidian", "dried_kelp_block",
                "quartz_block", "quartz_bricks", "quartz_pillar", "smooth_quartz", "chiseled_quartz_block",
                "quartz_stairs", "quartz_slab", "resin_brick", "flower_pot", "bamboo_block",
                "carved_pumpkin", "jack_o_lantern");
        exact(FOOD, "dried_kelp", "pumpkin_pie");
        exact(WOOD, "dead_bush", "creaking_heart", "leaf_litter");
        exact(FARM, "turtle_egg", "dragon_egg", "composter");
        exact(MAGIC, "nether_star", "magma_cream", "enchanting_table", "phantom_membrane", "rabbit_foot",
                "glistering_melon_slice", "glowstone_dust");
        exact(REDSTONE, "bell", "honey_block", "slime_block", "crafter");
        exact(MINERAL, "heavy_core", "cinnabar", "sulfur", "potent_sulfur");
        exact(BUILD, "polished_cinnabar", "chiseled_cinnabar", "polished_sulfur", "chiseled_sulfur",
                "sulfur_spike", "end_rod");
        exact(TOOL, "breeze_rod", "wind_charge", "fire_charge");
        exact(CONTAINER, "bowl", "rabbit_hide", "heart_of_the_sea");
    }

    /**
     * 后缀定死的规则，在词元之前判。
     *
     * <p>两条都是被实测咬出来的：
     * <ul>
     *   <li>{@code _spawn_egg} —— 词元 egg 是「食物」，于是 {@code blaze_spawn_egg} 变成食物。刷怪蛋不是吃的。</li>
     *   <li>{@code _bucket} —— 词元 bucket 属于容器，但 {@code pufferfish_bucket} 先撞上 pufferfish（食物），
     *       结果 6 个桶归容器、鱼类桶归食物。统一成容器。</li>
     * </ul>
     */
    private static final Map<String, Integer> SUFFIX = new HashMap<>();

    static {
        SUFFIX.put("_spawn_egg", c(OTHER));
        SUFFIX.put("_bucket", c(CONTAINER));
    }

    /**
     * 只按 id 猜分类。
     *
     * @return 命中就返回类别名，没命中返回 {@code null}（交给上层兜底成「其他」）
     */
    public static String classify(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        String path = pathOf(itemId);
        String fixed = exactOf(path);
        if (fixed != null) {
            return fixed;
        }
        String suf = suffixOf(path);
        if (suf != null) {
            return suf;
        }

        String[] tok = split(path);
        int bestPrio = 99;
        int bestSpec = 0;
        int bestCat = c(OTHER);

        for (String t : tok) {
            Integer v = TOKENS.get(t);
            if (v == null) {
                continue;
            }
            int pr = v >> 8;
            int cat = v & 0xff;
            if (pr < bestPrio || (pr == bestPrio && bestSpec < 1)) {
                bestPrio = pr;
                bestSpec = 1;
                bestCat = cat;
            } else if (pr == bestPrio && bestSpec == 1 && cat < bestCat) {
                bestCat = cat;
            }
        }
        for (int i = 0; i + 1 < tok.length; i++) {
            Integer v = TOKENS.get(tok[i] + "_" + tok[i + 1]);
            if (v == null) {
                continue;
            }
            int pr = v >> 8;
            int cat = v & 0xff;
            // 双词比单词更具体：同档也优先
            if (pr < bestPrio || (pr == bestPrio && (bestSpec < 2 || cat < bestCat))) {
                bestPrio = pr;
                bestSpec = 2;
                bestCat = cat;
            }
        }
        return bestPrio == 99 ? null : ORDER.get(bestCat);
    }

    /** 词元表里认识多少条（给指令/自检看的） */
    public static int tokenCount() {
        return TOKENS.size();
    }

    /** 钉死的例外有多少条 */
    public static int exactCount() {
        return EXACT.size();
    }

    /** 后缀定死的规则有多少条 */
    public static int suffixCount() {
        return SUFFIX.size();
    }

    /** 被重复登记的词元条数（自检要求永远是 0：重复登记＝后写静默覆盖先写） */
    public static int duplicateCount() {
        return DUPS.size();
    }

    /** 被重复登记的词元清单（给自检打印） */
    public static List<String> duplicates() {
        return List.copyOf(DUPS);
    }

    /** 名称钉死：只认去掉命名空间的 path，命中返回类别名，否则 null */
    public static String exactOf(String path) {
        if (path == null) {
            return null;
        }
        Integer v = EXACT.get(path);
        return v == null ? null : ORDER.get(v);
    }

    /** 后缀钉死：命中就返回**命中的那个后缀**（给「证据」显示用），否则 null */
    public static String suffixHit(String path) {
        if (path == null) {
            return null;
        }
        for (String k : SUFFIX.keySet()) {
            if (path.endsWith(k)) {
                return k;
            }
        }
        return null;
    }

    /** 后缀钉死：命中返回类别名，否则 null */
    public static String suffixOf(String path) {
        String k = suffixHit(path);
        return k == null ? null : ORDER.get(SUFFIX.get(k));
    }

    /**
     * 这个名字是不是「旧中文类目名/父类名」。
     *
     * <p>只用来把「旧名」与「新页签键」分开：旧名走父类语义（{@code ContainerTags} 原样落盘），
     * 新键直接就是类目。判定是大小写敏感的 —— 这十个名字都是中文，没有大小写问题。
     *
     * @param name 待判定字符串
     * @return true 表示它是 {@link #LEGACY} 里的那十个之一
     */
    public static boolean isLegacy(String name) {
        return name != null && LEGACY.contains(name);
    }

    /**
     * 取物品 id 的 path（去掉命名空间，转小写）。
     *
     * <p>这里刻意不调用 {@code ItemIds.path} —— 那个类引用了 Minecraft 的注册表，
     * 一旦依赖它，本类就没法离线编译做回归了。
     */
    private static String pathOf(String itemId) {
        int i = itemId.indexOf(':');
        String p = i < 0 ? itemId : itemId.substring(i + 1);
        return p.toLowerCase(Locale.ROOT);
    }

    private static String[] split(String path) {
        String[] buf = new String[12];
        int n = 0;
        int start = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == '_') {
                if (i > start && n < buf.length) {
                    buf[n++] = path.substring(start, i);
                }
                start = i + 1;
            }
        }
        String[] out = new String[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }
}
