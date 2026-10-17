package com.ds.warehouse.index;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 组件感知的「变体身份」：把 {@code minecraft:potion} 这种**同一个 id、靠组件区分**的物品
 * 拆成不同的条目，让面板、搜索、取货都能指名到具体那一种。
 *
 * <p>键的算法（用户 m35362 拍板「全部组件」，只把两处按计划排除）：
 * <ol>
 *   <li>{@code stack.getComponentsPatch().size() == 0} ⇒ 就是最普通的物品，键 = 物品 id（绝大多数物品走这条，零开销）</li>
 *   <li>否则取「非默认值」的组件（{@link PatchedDataComponentMap#hasNonDefault}），按组件注册名排序，
 *       排除 {@link #KEY_IGNORED}（一装东西/一磨损就换身份会让搜索碎片化的那些），
 *       每项用游戏自己的 {@link DataComponentType#codec()} 编成 NBT，再写成一串规范文本</li>
 *   <li>对这串文本取 sha256，取前 10 位十六进制，拼成 {@code <物品id>@<10位>}</li>
 * </ol>
 *
 * <p>分隔符用 {@code @} 而不是 {@code #}：{@code #} 已经被「{@code <物品>#<附魔组合>}」占用了
 * （取货页按附魔下行下单、{@link com.ds.warehouse.porter.Porter#request} 解析它），两者要能叠着用：
 * {@code minecraft:enchanted_book@1a2b3c4d5e#minecraft:sharpness@5}。
 *
 * <p>不用 {@code ItemStack.hashItemAndComponents}：它是 32 位、跨版本与跨会话都不保证稳定，
 * 只适合运行期快比较，不适合当落盘的身份键。
 */
public final class Variants {

    /**
     * 不参与身份、只参与显示的组件。
     *
     * <p>排掉它们的理由：{@code container}/{@code bundle_contents} 一装东西就换身份，
     * 搜索会碎成一堆「潜影盒#a1b2」；{@code damage}/{@code repair_cost} 一磨损就换身份，
     * 同名同类会散开；{@code custom_model_data} 是资源包改写外观用的，不改变「是什么东西」；
     * 荧光覆盖只是视觉效果。
     */
    private static final Set<String> KEY_IGNORED = Set.of(
            "minecraft:container",
            "minecraft:bundle_contents",
            "minecraft:damage",
            "minecraft:repair_cost",
            "minecraft:custom_model_data",
            "minecraft:enchantment_glint_override"
    );

    /** 组件注册名 → 给玩家看的中文简称（没有就用组件名最后一段） */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("minecraft:potion_contents", "药水"),
            Map.entry("minecraft:dyed_color", "染色"),
            Map.entry("minecraft:map_id", "地图编号"),
            Map.entry("minecraft:map_decorations", "地图标记"),
            Map.entry("minecraft:fireworks", "烟花"),
            Map.entry("minecraft:banner_patterns", "旗帜图案"),
            Map.entry("minecraft:base_color", "底色"),
            Map.entry("minecraft:trim", "纹饰"),
            Map.entry("minecraft:enchantments", "附魔"),
            Map.entry("minecraft:stored_enchantments", "附魔书"),
            Map.entry("minecraft:written_book_content", "成书"),
            Map.entry("minecraft:writable_book_content", "书与笔"),
            Map.entry("minecraft:instrument", "乐器"),
            Map.entry("minecraft:ominous_bottle_amplifier", "凶兆等级"),
            Map.entry("minecraft:profile", "头颅主人"),
            Map.entry("minecraft:suspicious_stew_effects", "炖菜效果"),
            Map.entry("minecraft:charged_projectiles", "装填弹药"),
            Map.entry("minecraft:lodestone_tracker", "磁石指针"),
            Map.entry("minecraft:bucket_entity_data", "桶内生物"),
            Map.entry("minecraft:recipes", "知识之书配方"),
            Map.entry("minecraft:custom_name", "名字"),
            Map.entry("minecraft:entity_data", "实体数据"),
            Map.entry("minecraft:block_entity_data", "方块实体数据"),
            Map.entry("minecraft:bees", "蜂巢")
    );

    /** 身份文本 → 短哈希。同一份身份反复出现（大仓库里同种药水成百上千）时省掉重复的 sha256 */
    private static final Map<String, String> HASH_CACHE = new ConcurrentHashMap<>();
    private static final int HASH_CACHE_MAX = 4096;

    /**
     * 组件编解码用的注册表（药水、附魔、画、音效…都在数据包动态注册表里）。
     * <p>
     * 实测（离线探针 {@code experiments/variant-probe}）：<b>附魔书</b>用裸 {@link NbtOps} 解析会直接失败 --
     * {@code Can't access registry minecraft:enchantment}，附魔书因此存不下、读不回；
     * 换成同一个 NBT 树跑 {@link RegistryOps}（服务器注册表里有 43 个附魔）就 {@code OK}。
     * 所以只要拿得到服务器注册表就一定要用 RegistryOps，拿不到（客户端、离线工具）才退回 NbtOps。
     */
    private static volatile HolderLookup.Provider registryAccess;

    private Variants() {
    }

    /** 变体键：{@code minecraft:potion@1a2b3c4d5e}；没有「非默认组件」时就是物品 id 本身 */
    public static String keyOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String id = ItemIds.of(stack);
        String identity = identityText(stack);
        if (identity.isEmpty()) {
            return id;
        }
        return id + SEP + shortHash(identity);
    }

    /** 变体键与物品 id 之间的分隔符（见类注释：{@code #} 被附魔筛选占了） */
    public static final char SEP = '@';

    /** 这个键是不是「带组件」的变体（还是就一个物品 id） */
    public static boolean isVariantKey(String key) {
        return key != null && key.indexOf(SEP) > 0;
    }

    /** 键里的物品 id 部分（{@code minecraft:potion@abc} ⇒ {@code minecraft:potion}） */
    public static String itemIdOf(String key) {
        if (key == null) {
            return "";
        }
        int at = key.indexOf(SEP);
        return at > 0 ? key.substring(0, at) : key;
    }

    /** 键的哈希部分（{@code minecraft:potion@abc} ⇒ {@code abc}；普通物品返回空串） */
    public static String hashOf(String key) {
        if (key == null) {
            return "";
        }
        int at = key.indexOf(SEP);
        return at > 0 && at + 1 < key.length() ? key.substring(at + 1) : "";
    }

    /** 注入注册表（世界加载时调一次，之后所有组件编解码都带上它；见 {@link #registryAccess}） */
    public static void useRegistries(HolderLookup.Provider access) {
        registryAccess = access;
    }

    /**
     * 组件编解码用的 ops：有注册表就用 {@link RegistryOps}（附魔这类数据包动态注册表的条目才解析得出来），
     * 没有（客户端、离线工具）才退回裸 {@link NbtOps}。
     */
    private static DynamicOps<Tag> ops() {
        HolderLookup.Provider access = registryAccess;
        return access == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, access);
    }

    /**
     * 一行的显示名（真实栈）。
     *
     * <p>绝大部分物品就是 hover 名（药水会得到「迟缓药水」而不是「药水」）。唯一的例外是
     * **只有自定义效果、没有 registered potion** 的药水：原版 hover 名会退回占位符
     * 「不可合成的药水」（用户 m36915 点名过），这时改用物品基础名（药水 / 喷溅药水 / 药箭），
     * 具体效果交给 {@link #note(ItemStack)} 列。
     */
    public static String displayName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        if (stack.get(DataComponents.CUSTOM_NAME) == null) {
            PotionContents pc = stack.get(DataComponents.POTION_CONTENTS);
            if (pc != null && pc.potion().isEmpty()) {
                // 物品原型名（药水 / 喷溅药水 / 药箭），同 util.Names.item 的做法
                return Component.translatable(stack.getItem().getDescriptionId()).getString();
            }
        }
        return stack.getHoverName().getString();
    }

    /**
     * 给玩家看的短注解，例如「药水：迅捷 II」「附魔书：锋利 V、耐久 III」「烟花：飞行 2 · 大型球状」。
     *
     * <p>用户 m36902 / m36931：<b>不许出现 id 与 SNBT</b>，凡是带组件的物品都要把属性用认得的人话列全。
     * 认得出来的组件逐项翻译（见 {@link #friendly}），真认不出来（模组自定义组件）才退回
     * 「简称：值」，值里的 {@code minecraft:} 前缀也剥掉。没有可说的组件时返回空串。
     */
    public static String note(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        // 行名（hover 名）与容器类型都从真实栈拿：喷溅 / 滞留 / 药箭是**不同的物品**，
        // 光看组件分不出来，只能看它是什么药水容器（用户 m37577）。
        return note(identityComponents(stack), stack.getHoverName().getString(),
                potionKind(stack), potionPrefix(stack));
    }

    /**
     * {@link #note(ItemStack)} 的拼接部分，单独拆出来是为了能在没有真实 {@code ItemStack}
     * 的离线环境（探针 / 单测）里直接验证译文——headless 下 {@code new ItemStack(...)} 需要数据包
     * 绑好组件，普通脚本绑不起来。
     */
    static String note(List<TypedDataComponent<?>> interesting) {
        return note(interesting, "", "", null);
    }

    /**
     * @param rowName      这一行最终显示的名字，用来去重（行名已经写了「迟缓药水」就不再重复）
     * @param kind         药水容器类型（喷溅 / 滞留 / 药箭；普通药水是空串）
     * @param potionPrefix 药水名的原版翻译前缀，普通物品传 {@code null}
     */
    static String note(List<TypedDataComponent<?>> interesting, String rowName, String kind,
            String potionPrefix) {
        String name = rowName == null ? "" : rowName;
        List<String> parts = new ArrayList<>();
        for (TypedDataComponent<?> c : interesting) {
            String text = friendly(c, name, potionPrefix);
            if (text == null || text.isEmpty()) {
                continue;
            }
            parts.add(text);
            if (parts.size() >= 6) {
                break;
            }
        }
        String joined = String.join("；", parts);
        if (kind != null && !kind.isEmpty() && !name.contains(kind)) {
            joined = joined.isEmpty() ? kind : kind + " · " + joined;
        }
        if (joined.isEmpty()) {
            return "";
        }
        return joined.length() <= 110 ? joined : joined.substring(0, 110) + "…";
    }

    /** 药水容器类型：只有会和普通药水撞名的三种（喷溅 / 滞留 / 药箭）才返回，普通药水返回空串。 */
    private static String potionKind(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.SPLASH_POTION) {
            return "喷溅";
        }
        if (item == Items.LINGERING_POTION) {
            return "滞留";
        }
        if (item == Items.TIPPED_ARROW) {
            return "药箭";
        }
        return "";
    }

    /** 药水名的原版翻译前缀（`item.minecraft.potion.effect.` 等），非药水物品返回 {@code null}。 */
    private static String potionPrefix(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.POTION) {
            return "item.minecraft.potion.effect.";
        }
        if (item == Items.SPLASH_POTION) {
            return "item.minecraft.splash_potion.effect.";
        }
        if (item == Items.LINGERING_POTION) {
            return "item.minecraft.lingering_potion.effect.";
        }
        if (item == Items.TIPPED_ARROW) {
            return "item.minecraft.tipped_arrow.effect.";
        }
        return null;
    }

    /**
     * 单个「非默认组件」→ 人话。名字 / 等级 / 颜色全部走原版的本地化文本（{@code Component.getString()}），
     * 这样中文环境下就是中文，不依赖我们自己做映射表。
     */
    private static String friendly(TypedDataComponent<?> c, String rowName, String potionPrefix) {
        DataComponentType<?> type = c.type();
        Object v = c.value();
        if (type == DataComponents.POTION_CONTENTS && v instanceof PotionContents pc) {
            // 行名往往已经是「迟缓药水」，这里补的是「哪一瓶 + 效果/等级/时长 + 自定义颜色」。
            // 时长必须列：同名药水有普通 / 延长 / 强化（以及自定义时长）多种（用户 m37577）。
            List<String> parts = new ArrayList<>();
            if (potionPrefix != null && pc.potion().isPresent()) {
                String potion = pc.getName(potionPrefix).getString();
                // 没有翻译（自定义效果才有 potion=empty）时会拿到原始 key，绝不往界面上吐
                if (isTranslated(potion) && !rowName.contains(potion)) {
                    parts.add(potion);
                }
            }
            List<String> fx = new ArrayList<>();
            for (MobEffectInstance mi : pc.getAllEffects()) {
                StringBuilder one = new StringBuilder(mi.getEffect().value().getDisplayName().getString());
                if (mi.getAmplifier() > 0) {
                    one.append(' ').append(roman(mi.getAmplifier() + 1));
                }
                one.append(' ').append(clock(mi.getDuration()));
                fx.add(one.toString());
                if (fx.size() >= 3) {
                    break;
                }
            }
            if (!fx.isEmpty()) {
                parts.add(String.join("、", fx));
            }
            int color = pc.getColor();
            int intrinsic = PotionContents.getColorOptional(pc.getAllEffects())
                    .orElse(PotionContents.BASE_POTION_COLOR);
            if (color != intrinsic) {
                parts.add("颜色 " + hexColor(color));
            }
            return parts.isEmpty() ? null : String.join(" · ", parts);
        }
        if (type == DataComponents.STORED_ENCHANTMENTS && v instanceof ItemEnchantments ie) {
            return enchantText(ie);
        }
        if (type == DataComponents.ENCHANTMENTS && v instanceof ItemEnchantments ie) {
            return enchantText(ie);
        }
        if (type == DataComponents.FIREWORKS && v instanceof Fireworks fw) {
            return fireworkText(fw);
        }
        if (type == DataComponents.BANNER_PATTERNS && v instanceof BannerPatternLayers bpl) {
            return bannerText(bpl);
        }
        if (type == DataComponents.BASE_COLOR && v instanceof DyeColor dc) {
            return "底色 " + colorName(dc);
        }
        if (type == DataComponents.DYED_COLOR && v instanceof DyedItemColor dc) {
            return "染色 " + hexColor(dc.rgb());
        }
        if (type == DataComponents.CUSTOM_NAME) {
            return null; // 行名就是自定义名本身，再列一遍是纯噪音
        }
        if (type == DataComponents.CHARGED_PROJECTILES && v instanceof ChargedProjectiles cp) {
            return "装填 " + projectileText(cp);
        }
        if (type == DataComponents.WRITTEN_BOOK_CONTENT && v instanceof WrittenBookContent b) {
            String by = b.author().isBlank() ? "" : " 作者 " + b.author();
            return "《" + b.title().raw() + "》" + by;
        }
        if (type == DataComponents.MAP_ID && v instanceof MapId m) {
            return "地图 #" + m.id();
        }
        if (type == DataComponents.OMINOUS_BOTTLE_AMPLIFIER && v instanceof OminousBottleAmplifier a) {
            return "凶兆等级 " + roman(a.value() + 1);
        }
        if (type == DataComponents.PROFILE && v instanceof ResolvableProfile p) {
            return "头颅主人 " + p.name().orElse("未指定");
        }
        if (type == DataComponents.SUSPICIOUS_STEW_EFFECTS && v instanceof SuspiciousStewEffects s) {
            return "炖菜效果 " + s.effects().size() + " 条";
        }
        if (type == DataComponents.ENTITY_DATA || type == DataComponents.BLOCK_ENTITY_DATA
                || type == DataComponents.BUCKET_ENTITY_DATA) {
            return null; // 整坨实体 NBT，列出来只会刷屏
        }
        // 兜底：认不出来的组件（多半是模组加的）也用简称，并把值里的命名空间剥掉，别露出一串 id
        String id = componentId(type);
        String label = LABELS.getOrDefault(id, tail(id));
        String raw = stripNamespaces(shorten(canonicalValue(c)));
        return raw.isEmpty() ? "" : label + "：" + raw;
    }

    /** 本地化文本是否真的翻出来了：没翻译的 translatable 会原样返回 key，绝不能显示给玩家。 */
    private static boolean isTranslated(String text) {
        return text != null && !text.isEmpty() && text.indexOf(':') < 0 && text.indexOf('.') < 0;
    }

    private static String enchantText(ItemEnchantments ie) {
        List<String> out = new ArrayList<>();
        for (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<Holder<Enchantment>> e : ie.entrySet()) {
            Holder<Enchantment> h = e.getKey();
            int level = e.getIntValue();
            String one;
            try {
                // getFullname 会查 #minecraft:curse 标签决定要不要加「诅咒」前缀，标签没绑好时会抛
                one = Enchantment.getFullname(h, level).getString();
            } catch (Throwable t) {
                // 拿不到标签时退化成「附魔名 + 罗马等级」，至少不吐 id、也不把整行弄没
                String name;
                try {
                    name = h.value().description().getString();
                } catch (Throwable t2) {
                    name = "附魔";
                }
                one = name + " " + roman(level);
            }
            out.add(one);
            if (out.size() >= 4) {
                out.add("…");
                break;
            }
        }
        return out.isEmpty() ? "无" : String.join("、", out);
    }

    private static String fireworkText(Fireworks fw) {
        StringBuilder sb = new StringBuilder("飞行 ").append(fw.flightDuration());
        List<FireworkExplosion> explosions = fw.explosions();
        if (!explosions.isEmpty()) {
            FireworkExplosion e = explosions.get(0);
            sb.append(" · ").append(e.shape().getName().getString());
            if (!e.colors().isEmpty()) {
                sb.append(' ').append(hexColor(e.colors().getInt(0)));
            }
            if (e.hasTrail()) {
                sb.append(" 拖尾");
            }
            if (e.hasTwinkle()) {
                sb.append(" 闪烁");
            }
            if (explosions.size() > 1) {
                sb.append(" 等 ").append(explosions.size()).append(" 组");
            }
        }
        return sb.toString();
    }

    private static String bannerText(BannerPatternLayers bpl) {
        List<String> out = new ArrayList<>();
        for (BannerPatternLayers.Layer layer : bpl.layers()) {
            out.add(layer.description().getString());
            if (out.size() >= 4) {
                out.add("…");
                break;
            }
        }
        return out.isEmpty() ? "无图案" : String.join("、", out);
    }

    private static String projectileText(ChargedProjectiles cp) {
        List<ItemStack> items = cp.itemCopies().toList();
        if (items.isEmpty()) {
            return "空";
        }
        String name = Component.translatable(items.get(0).getItem().getDescriptionId()).getString();
        return name + " ×" + items.size();
    }

    private static String colorName(DyeColor color) {
        return switch (color) {
            case WHITE -> "白色";
            case ORANGE -> "橙色";
            case MAGENTA -> "品红色";
            case LIGHT_BLUE -> "淡蓝色";
            case YELLOW -> "黄色";
            case LIME -> "黄绿色";
            case PINK -> "粉红色";
            case GRAY -> "灰色";
            case LIGHT_GRAY -> "淡灰色";
            case CYAN -> "青色";
            case PURPLE -> "紫色";
            case BLUE -> "蓝色";
            case BROWN -> "棕色";
            case GREEN -> "绿色";
            case RED -> "红色";
            case BLACK -> "黑色";
        };
    }

    private static String hexColor(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /** 药水效果剩余时间：{@code 20 tick = 1 秒}，写成 {@code m:ss}（10 分钟以上写 {@code 12m30s}） */
    private static String clock(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        if (seconds >= 600) {
            return (seconds / 60) + "m" + (seconds % 60) + "s";
        }
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(n);
        };
    }

    /** 兜底文案里把 {@code minecraft:} 这类命名空间剥掉，别让玩家看到 id 观感 */
    private static String stripNamespaces(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("[a-z0-9_.\\-]+:", "");
    }

    // ------------------------------------------------------------------
    // 落盘 / 读回（IndexStore 的逐格 comp 字段）

    /**
     * 把这个栈的**非默认组件**写成 SNBT 串（没有就返回空串）。
     *
     * <p>存这个而不是只存变体键：读回来的时候能连「抗火药水」的效力、地图编号、染色值一起还原，
     * 顺带补上报告 §10.1 那条「重启后药水/烟花/旗帜的信息全丢」。
     */
    public static String snbtOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return encodePatch(stack.getComponentsPatch());
    }

    /** {@link #snbtOf(ItemStack)} 的编码部分；离线验证用同一个入口，保证测的就是跑的那条路。 */
    public static String encodePatch(DataComponentPatch patch) {
        if (patch == null || patch.isEmpty()) {
            return "";
        }
        try {
            DataResult<Tag> encoded = DataComponentPatch.CODEC.encodeStart(ops(), patch);
            Tag tag = encoded.result().orElse(null);
            return tag == null ? "" : tag.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * SNBT 串 → 组件补丁。解析<b>必须</b>走带注册表的 ops：裸 {@link NbtOps} 解不开
     * {@code minecraft:enchantment} 这类数据包动态注册表引用的组件（实测报
     * {@code Can't access registry minecraft:enchantment}），结果就是附魔书读回来变成空书。
     *
     * @return 解析不出来返回 {@code null}（老版本写的串、被手改坏的串都算）
     */
    public static DataComponentPatch decodePatch(String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return null;
        }
        try {
            CompoundTag tag = TagParser.parseCompoundFully(snbt);
            return DataComponentPatch.CODEC.parse(ops(), tag).result().orElse(null);
        } catch (Throwable t) {
            // 老版本写的、或被手改坏的串：当没有组件处理，不影响整份索引恢复
            return null;
        }
    }

    /** 把 {@link #snbtOf} 写下的组件灌回一个（本来只有 id + count 的）栈；@return 是否成功 */
    public static boolean applyComponents(ItemStack stack, String snbt) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        DataComponentPatch patch = decodePatch(snbt);
        if (patch == null || patch.isEmpty()) {
            return false;
        }
        stack.applyComponents(patch);
        return true;
    }

    // ------------------------------------------------------------------
    // 内部

    /**
     * 身份的规范文本：{@code 组件名=值的NBT;} 按组件名排序连接。
     *
     * <p>空串 = 这个物品没有任何参与身份的组件（键就是物品 id）。
     */
    private static String identityText(ItemStack stack) {
        List<TypedDataComponent<?>> components = identityComponents(stack);
        if (components.isEmpty()) {
            return "";
        }
        List<String> pieces = new ArrayList<>(components.size());
        for (TypedDataComponent<?> c : components) {
            pieces.add(componentId(c.type()) + "=" + canonicalValue(c));
        }
        pieces.sort(Comparator.naturalOrder());
        StringBuilder sb = new StringBuilder();
        for (String p : pieces) {
            sb.append(p).append(';');
        }
        return sb.toString();
    }

    /** 非默认值、且不在 {@link #KEY_IGNORED} 里的组件，按组件注册名排好序 */
    private static List<TypedDataComponent<?>> identityComponents(ItemStack stack) {
        DataComponentPatch patch = stack.getComponentsPatch();
        if (patch.isEmpty()) {
            return List.of();
        }
        DataComponentMap all = stack.getComponents();
        PatchedDataComponentMap patched = all instanceof PatchedDataComponentMap p ? p : null;
        List<TypedDataComponent<?>> out = new ArrayList<>(4);
        for (TypedDataComponent<?> c : all) {
            if (KEY_IGNORED.contains(componentId(c.type()))) {
                continue;
            }
            // 默认值组件不算差异：没显式设过的（hasNonDefault == false）直接跳过。
            // 拿不到 PatchedDataComponentMap 时退化成「patch 非空就算差异」，宁可多算也不漏。
            if (patched != null && !patched.hasNonDefault(c.type())) {
                continue;
            }
            if (!patched.has(c.type())) {
                continue;
            }
            out.add(c);
        }
        out.sort(Comparator.comparing(c -> componentId(c.type())));
        return out;
    }

    private static String componentId(DataComponentType<?> type) {
        Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
        return id == null ? "?" : id.toString();
    }

    private static String tail(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** 组件值 → 规范 NBT 文本（CompoundTag 的键递归排序，保证同一份数据每次都得到同一串） */
    private static String canonicalValue(TypedDataComponent<?> component) {
        @SuppressWarnings("unchecked")
        DataComponentType<Object> type = (DataComponentType<Object>) component.type();
        Object value = component.value();
        try {
            DataResult<Tag> encoded = type.codec().encodeStart(ops(), value);
            Tag tag = encoded.result().orElse(null);
            if (tag != null) {
                return canonical(tag);
            }
        } catch (Throwable ignored) {
            // 个别组件（引用服务端对象）编不出来，退回 toString
        }
        try {
            return String.valueOf(value);
        } catch (Throwable t) {
            return value == null ? "null" : value.getClass().getSimpleName();
        }
    }

    /** Tag → 稳定文本：CompoundTag 的键排序，ListTag 保序（列表顺序本身是语义） */
    private static String canonical(Tag tag) {
        StringBuilder sb = new StringBuilder();
        write(tag, sb);
        return sb.toString();
    }

    private static void write(Tag tag, StringBuilder sb) {
        if (tag instanceof CompoundTag compound) {
            List<String> keys = new ArrayList<>(compound.keySet());
            keys.sort(Comparator.naturalOrder());
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(keys.get(i)).append(':');
                Tag child = compound.get(keys.get(i));
                if (child == null) {
                    sb.append("null");
                } else {
                    write(child, sb);
                }
            }
            sb.append('}');
            return;
        }
        if (tag instanceof ListTag list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(list.get(i), sb);
            }
            sb.append(']');
            return;
        }
        sb.append(tag == null ? "null" : tag.toString());
    }

    private static String shorten(String text) {
        String s = text.replace('\n', ' ').trim();
        if (s.length() <= 24) {
            return s;
        }
        return s.substring(0, 24) + "…";
    }

    private static String shortHash(String text) {
        String cached = HASH_CACHE.get(text);
        if (cached != null) {
            return cached;
        }
        String hex = sha256Hex(text);
        String short10 = hex.length() >= 10 ? hex.substring(0, 10) : hex;
        if (HASH_CACHE.size() < HASH_CACHE_MAX) {
            HASH_CACHE.put(text, short10);
        }
        return short10;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            // 理论上不会发生（SHA-256 是 JDK 必备）；退化成内容自带的信息，保证不崩
            return Integer.toHexString(text.hashCode()).toUpperCase(Locale.ROOT);
        }
    }
}
