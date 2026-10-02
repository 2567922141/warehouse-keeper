package com.ds.warehouse.config;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.index.ContainerRecord;
import com.ds.warehouse.index.Containers;
import com.ds.warehouse.index.ItemIds;
import com.ds.warehouse.index.Scanner;
import com.ds.warehouse.index.WarehouseIndex;
import com.ds.warehouse.util.Audit;
import com.ds.warehouse.util.Categories;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 箱子标签（方案报告附录 J）：给每个物理容器挂一条「身份标签」。
 *
 * <p>标签决定整理时这只箱子是<b>收件箱</b>（目标箱：某分类的东西放这里）还是
 * <b>取件箱</b>（暂存箱：只出不进，整理先从它里面清）。
 *
 * <p>三个概念要分清（拍板见 §J.10 / §J.11）：
 * <ul>
 *   <li><b>暂存</b>（{@link Tag#staging}）：只出不进。任何档位下都能按（B18）。</li>
 *   <li><b>档位</b>（{@link Tag#mode}）：{@code MANUAL}（默认，手动选的分类生效）/
 *       {@code AUTO}（按内容算的那条生效）。<b>只有 OP 能改</b>。</li>
 *   <li><b>两条分类都留着</b>：手动档失效时 {@code manualCategory} 仍留在文件里（休眠），
 *       切回手动档立刻恢复，不需要重选（B18 的 A 方案）。</li>
 * </ul>
 *
 * <p><b>有效标签</b>＝{@code staging ? null : (mode==AUTO ? autoCategory : manualCategory)}。
 * 自动档用的是<b>落盘定案</b>那条（B13），不是每次打开实时重算；想跟着内容变就再按一次
 * 「按内容自动」。
 *
 * <p>键＝归一化坐标（{@link Scanner#canonical}）＋维度，与索引同一套 key 规则 ⇒
 * 双联箱两半天然共享一条标签（开哪一半都是同一个标签）。
 *
 * <p>文件：{@code config/warehouse-keeper/worlds/<存档名>/container-tags.json}
 * （和 regions.json / homes.json 同目录同规则；标签只是 JSON，<b>绝不碰容器内容</b>）。
 */
public final class ContainerTags {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type TYPE = new TypeToken<LinkedHashMap<String, Tag>>() {
    }.getType();

    public static final String MANUAL = "MANUAL";
    public static final String AUTO = "AUTO";
    public static final String KIND_AUTO = "AUTO";
    public static final String KIND_MANUAL = "MANUAL";
    public static final String KIND_STAGING = "STAGING";

    /** key = {@link WarehouseIndex#key(String, BlockPos)}（已归一化） */
    private static final Map<String, Tag> MAP = new LinkedHashMap<>();
    private static boolean dirty;

    private ContainerTags() {
    }

    /** 一条箱子标签 */
    public static final class Tag {
        /** 暂存箱：只出不进，永远不作为目标箱 */
        public boolean staging;
        /** 档位：MANUAL（默认）/ AUTO */
        public String mode = MANUAL;
        /** 手动选的分类（休眠保留） */
        public String manualCategory;
        /** 按内容算并落盘定案的分类（B13） */
        public String autoCategory;
        /** 最后一次贴标签的种类：AUTO / MANUAL / STAGING（显示与审计用） */
        public String kind;
        public String setBy = "";
        public long setAt;

        public boolean isAutoMode() {
            return AUTO.equalsIgnoreCase(mode);
        }

        /** 有效分类：暂存箱没有分类；否则由档位决定用哪条 */
        public String effectiveCategory() {
            if (staging) {
                return null;
            }
            return isAutoMode() ? autoCategory : manualCategory;
        }

        /** 给玩家看的一行：暂存 / 某分类（手动·自动） / 未打标签 */
        public String label() {
            if (staging) {
                return "暂存";
            }
            String cat = effectiveCategory();
            if (cat == null || cat.isEmpty()) {
                return "未打标签";
            }
            return cat + (isAutoMode() ? "（自动档）" : "（手动档）");
        }

        public Tag copy() {
            Tag t = new Tag();
            t.staging = staging;
            t.mode = mode;
            t.manualCategory = manualCategory;
            t.autoCategory = autoCategory;
            t.kind = kind;
            t.setBy = setBy;
            t.setAt = setAt;
            return t;
        }
    }

    // ------------------------------------------------------------------
    // 读写

    private static Path file() {
        return WorldStore.file("container-tags.json");
    }

    public static void load() {
        MAP.clear();
        dirty = false;
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Map<String, Tag> read = GSON.fromJson(r, TYPE);
            if (read != null) {
                for (Map.Entry<String, Tag> e : read.entrySet()) {
                    if (e.getValue() != null) {
                        MAP.put(e.getKey(), e.getValue());
                    }
                }
            }
            WarehouseMod.LOGGER.info("已载入 {} 条箱子标签: {}", MAP.size(), f);
        } catch (Exception e) {
            WarehouseMod.LOGGER.warn("读取 {} 失败（标签丢失不影响物品安全）: {}", f, e.toString());
        }
    }

    public static void save() {
        if (!dirty) {
            return;
        }
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(MAP, TYPE, w);
            }
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            WarehouseMod.LOGGER.info("已存 {} 条箱子标签: {}", MAP.size(), f);
        } catch (Exception e) {
            WarehouseMod.LOGGER.warn("写入 {} 失败: {}", f, e.toString());
        }
    }

    public static boolean isDirty() {
        return dirty;
    }

    // ------------------------------------------------------------------
    // 查询

    /** 归一化后的标签键：双联箱两半 → 同一个键 */
    public static String keyFor(ServerLevel level, BlockPos pos) {
        String dim = level.dimension().identifier().toString();
        return WarehouseIndex.key(dim, Scanner.canonical(level, pos));
    }

    public static Tag get(String key) {
        return key == null ? null : MAP.get(key);
    }

    public static Tag get(String dimension, BlockPos pos) {
        return MAP.get(WarehouseIndex.key(dimension, pos));
    }

    /** 有效分类（暂存箱与未打标签都返回 null） */
    public static String effectiveCategory(String key) {
        Tag t = get(key);
        return t == null ? null : t.effectiveCategory();
    }

    /** 是不是暂存箱（只出不进） */
    public static boolean isStaging(String key) {
        Tag t = get(key);
        return t != null && t.staging;
    }

    /** 是不是某分类的目标箱（排除暂存与未打标签） */
    public static boolean isTarget(String key) {
        String cat = effectiveCategory(key);
        return cat != null && !cat.isEmpty();
    }

    public static Map<String, Tag> all() {
        return MAP;
    }

    public static int size() {
        return MAP.size();
    }

    /** 键 → 维度 + 坐标（标签可能指向已被拆掉的箱子，所以刻意不查索引） */
    public static Spot spot(String key) {
        if (key == null) {
            return null;
        }
        int at = key.lastIndexOf('@');
        if (at <= 0) {
            return null;
        }
        String[] xyz = key.substring(at + 1).split(",");
        if (xyz.length != 3) {
            return null;
        }
        try {
            return new Spot(key.substring(0, at), new BlockPos(Integer.parseInt(xyz[0].trim()),
                    Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim())));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record Spot(String dimension, BlockPos pos) {
    }

    /** 这只箱子还在不在（只看已加载的区块，绝不为了列标签去加载世界） */
    public static boolean loadedAndPresent(MinecraftServer server, String key) {
        Spot s = spot(key);
        if (s == null || server == null) {
            return false;
        }
        ServerLevel level = Scanner.levelOf(server, s.dimension());
        if (level == null || !level.isLoaded(s.pos())) {
            return false;
        }
        BlockEntity be = level.getBlockEntity(s.pos());
        // 判据统一走 Containers：雕纹书架/架子这类被排除的方块在这里也必须算「不在」
        return Containers.isWarehouseContainer(level, s.pos(), be);
    }

    // ------------------------------------------------------------------
    // 写标签（每条都会落盘；标签不改动物品）

    private static Tag ensure(String key) {
        return MAP.computeIfAbsent(key, k -> new Tag());
    }

    private static void stamp(Tag t, String by) {
        t.setBy = by == null ? "" : by;
        t.setAt = System.currentTimeMillis();
        dirty = true;
    }

    /** 手动贴一个分类（需要先处于手动档，自动档下由调用方拒绝） */
    public static Tag setManual(String key, String category, String by) {
        Tag t = ensure(key);
        t.manualCategory = category;
        t.kind = KIND_MANUAL;
        stamp(t, by);
        return t;
    }

    /** 按内容算一次并落盘定案（B13：之后箱内物品变了也不动，除非重按） */
    public static Tag setAuto(String key, String category, String by) {
        Tag t = ensure(key);
        t.autoCategory = category;
        t.kind = KIND_AUTO;
        stamp(t, by);
        return t;
    }

    /** 暂存：只出不进（与档位互相独立） */
    public static Tag setStaging(String key, boolean staging, String by) {
        Tag t = ensure(key);
        t.staging = staging;
        t.kind = staging ? KIND_STAGING : t.kind;
        stamp(t, by);
        return t;
    }

    /** 改档位（只有 OP 能调）；切到自动档而还没有 autoCategory 时，调用方负责先算一次 */
    public static Tag setMode(String key, String mode, String by) {
        Tag t = ensure(key);
        t.mode = AUTO.equalsIgnoreCase(mode) ? AUTO : MANUAL;
        stamp(t, by);
        return t;
    }

    /**
     * 把一个键上的标签挪到另一个键。
     *
     * <p>用于「双联箱被拆掉一半」：标签本来按这一对的正式坐标存，另一半没了以后
     * 活下来的那半坐标变了，标签要跟着走，否则玩家再点它会显示「未设置」。
     *
     * <p>原键没有标签时什么都不做；新键已经有标签时不再像以前那样把原键这条就地丢掉，
     * 而是按与拼箱合并同一套优先级（暂存 &gt; 手动 &gt; 自动，同档比时间）并成一条并写审计 ——
     * 见审查发现 7。
     *
     * @return true = 真的搬走/并掉了一条
     */
    public static boolean rekey(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        Tag t = MAP.get(from);
        if (t == null) {
            return false;
        }
        if (!MAP.containsKey(to)) {
            MAP.remove(from);
            MAP.put(to, t);
            dirty = true;
            return true;
        }
        mergeTwo(to, from, "拆箱挪键：把 " + from + " 上的标签并进 " + to);
        return true;
    }

    /**
     * 把「刚拼成 / 刚拆开的双联箱」两半上的标签并到同一个归一化键上（BUG3）。
     *
     * <p>标签键是<b>写入那一刻</b>按 {@link Scanner#canonical} 算出来的：两个独立箱子各贴过标签，
     * 拼成大箱子以后正式坐标变成坐标较小的那一半，另一条就成了孤儿键 —— 而客户端查标签是
     * 「本坐标精确匹配 + 四邻格兜底」，于是两半各显示一种分类。
     *
     * <p>两边都有标签时的优先级（按计划定案）：<b>暂存 &gt; 手动档 &gt; 自动档</b>；
     * 同档比 {@code setAt}（后来贴的胜）；档位也一样时以坐标较小的那一半为主。
     * 输的那条不是静默丢掉：赢家缺的另一个档位分类会补进来，并写一行审计。
     * （这是「绝不自动删标签」的唯一例外：消失的只是同一只大箱子上的重复标签，
     * 信息已经并进留下的那一条。）
     *
     * @param dim 维度 id
     * @param a   双联箱的一半
     * @param b   双联箱的另一半
     * @return 被并掉的那个键；没有可并的返回 null
     */
    public static String mergePair(String dim, BlockPos a, BlockPos b) {
        return mergePair(dim, a, b, null);
    }

    /**
     * @param staleKey 直接指定「孤儿键」的原始字符串，给键格式不规范的历史数据用；null = 按坐标算
     */
    private static String mergePair(String dim, BlockPos a, BlockPos b, String staleKey) {
        if (dim == null || a == null || b == null) {
            return null;
        }
        BlockPos canon = a.compareTo(b) <= 0 ? a : b;
        BlockPos other = canon.equals(a) ? b : a;
        String key = WarehouseIndex.key(dim, canon);
        String stale = staleKey != null ? staleKey : WarehouseIndex.key(dim, other);
        if (key.equals(stale)) {
            return null;
        }
        if (MAP.get(stale) == null) {
            // 另一半本来就没标签：这是最常见的情况，什么都不用做
            return null;
        }
        mergeTwo(key, stale, "双联箱合并：把 " + coord(other) + " 上的标签并进 " + coord(canon));
        return stale;
    }

    /**
     * 把 {@code stale} 这条标签并进 {@code key}：只有 stale 有标签就搬键；两边都有就按
     * 「暂存 &gt; 手动 &gt; 自动，同档比 {@code setAt}」定胜负，输家那个档位的分类补进赢家
     * （玩家贴过的分类不因为合并而丢），并写一行审计。
     *
     * <p>拼箱合并（{@link #mergePair}）与拆箱挪键（{@link #rekey}）共用这一段，免得两条路的优先级不一致。
     */
    private static void mergeTwo(String key, String stale, String what) {
        Tag drop = MAP.get(stale);
        if (drop == null) {
            return;
        }
        Tag keep = MAP.get(key);
        if (keep == null) {
            MAP.remove(stale);
            MAP.put(key, drop);
            dirty = true;
            return;
        }
        boolean dropWins = wins(drop, keep);
        Tag win = dropWins ? drop : keep;
        Tag lose = dropWins ? keep : drop;
        // 胜负只决定「哪一条生效」；另一条那个档位的分类还留着，别把玩家贴过的分类直接扔掉
        if (win.manualCategory == null || win.manualCategory.isEmpty()) {
            win.manualCategory = lose.manualCategory;
        }
        if (win.autoCategory == null || win.autoCategory.isEmpty()) {
            win.autoCategory = lose.autoCategory;
        }
        MAP.remove(stale);
        MAP.put(key, win);
        dirty = true;
        Audit.add("系统", "改标签", what + "（暂存 > 手动 > 自动，同档比时间）");
    }

    /** 合并优先级：暂存 > 手动档 > 自动档；同档比 setAt（后来贴的胜） */
    private static boolean wins(Tag candidate, Tag current) {
        int c = rank(candidate);
        int k = rank(current);
        return c != k ? c > k : candidate.setAt > current.setAt;
    }

    private static int rank(Tag t) {
        if (t.staging) {
            return 3;
        }
        return t.isAutoMode() ? 1 : 2;
    }

    private static String coord(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** 清除标签；清完两条分类都没有、也不暂存 ⇒ 直接把记录删掉（保持文件干净） */
    public static boolean clear(String key) {
        Tag t = MAP.get(key);
        if (t == null) {
            return false;
        }
        MAP.remove(key);
        dirty = true;
        return true;
    }

    /** 清掉指向「已经不在的箱子」的标签（只在玩家明确要求时调用，绝不自动删） */
    public static int pruneMissing(MinecraftServer server) {
        return repair(server)[1];
    }

    /**
     * 修一遍标签键：
     * <ul>
     *   <li>箱子已经没了 ⇒ 删掉这条标签（只在玩家明确要求时调用，绝不自动删）</li>
     *   <li>箱子还在、但归一化坐标变了（后来并成/拆成双联箱）⇒ 走 {@link #mergePair} 把标签并到新键：
     *       暂存 &gt; 手动档 &gt; 自动档，同档比贴标签时间，两边都不会被静默丢掉（并写一行审计）</li>
     *   <li>区块没加载 ⇒ 什么都不断言，原样留着</li>
     * </ul>
     *
     * @return {@code [挪动的条数, 删掉的条数]}
     */
    public static int[] repair(MinecraftServer server) {
        int moved = 0;
        int dead = 0;
        for (Map.Entry<String, Tag> e : new ArrayList<>(MAP.entrySet())) {
            Spot s = spot(e.getKey());
            if (s == null) {
                MAP.remove(e.getKey());
                dirty = true;
                dead++;
                continue;
            }
            ServerLevel level = Scanner.levelOf(server, s.dimension());
            if (level == null || !level.isLoaded(s.pos())) {
                continue; // 区块没加载：无从判断，留着
            }
            if (!Containers.isWarehouseContainer(level, s.pos(), level.getBlockEntity(s.pos()))) {
                MAP.remove(e.getKey());
                dirty = true;
                dead++;
                continue;
            }
            BlockPos canon = Scanner.canonical(level, s.pos());
            if (canon.equals(s.pos())) {
                continue;
            }
            // 归一化坐标变了（后来并成/拆成双联箱）：走 mergePair，优先级与审计都跟扫描那条路一致。
            // 这里把条目自己的键原样交给它（历史数据里的键格式可能跟 WarehouseIndex.key 不一致）
            String newKey = WarehouseIndex.key(s.dimension(), canon);
            boolean occupied = MAP.containsKey(newKey) && !newKey.equals(e.getKey());
            if (mergePair(s.dimension(), s.pos(), canon, e.getKey()) != null) {
                if (occupied) {
                    dead++; // 新键本来就有标签，这条是历史残留（并进去时按优先级定了胜负）
                } else {
                    moved++;
                }
            }
        }
        return new int[] { moved, dead };
    }

    // ------------------------------------------------------------------
    // 「按内容自动」：件数加权取最大项

    /**
     * 箱内件数最多的类目（同票时按 {@link Categories#order()} 的位置定序，保证可复现）。
     *
     * <p>0.21.0 起统计的是<b>新类目键</b>（创造页签键，见 {@link Categories#of}），
     * 落盘进 {@code autoCategory} 的自然也是新键。手动档里可能还留着旧中文名（父类），
     * 那是玩家数据，本方法不负责改写。
     *
     * @return 类目键；箱内没有可判定物品时返回 null
     */
    public static String dominantCategory(ContainerRecord rec) {
        if (rec == null || rec.contents.isEmpty()) {
            return null;
        }
        Map<String, Integer> weight = new LinkedHashMap<>();
        for (ContainerRecord.StoredStack ss : rec.contents) {
            if (ss.stack().isEmpty()) {
                continue;
            }
            int count = ss.stack().getCount();
            if (count <= 0) {
                continue;
            }
            String cat = Categories.of(ItemIds.of(ss.stack()));
            weight.merge(cat, count, Integer::sum);
        }
        if (weight.isEmpty()) {
            return null;
        }
        String best = null;
        int bestCount = -1;
        for (String cat : Categories.order()) {
            Integer c = weight.get(cat);
            if (c != null && c > bestCount) {
                best = cat;
                bestCount = c;
            }
        }
        // 保底：覆盖表可能把物品钉到某个不在 order() 里的历史类目上，那样它就不在上面那一轮里
        if (best == null) {
            for (Map.Entry<String, Integer> e : weight.entrySet()) {
                if (e.getValue() > bestCount) {
                    best = e.getKey();
                    bestCount = e.getValue();
                }
            }
        }
        return best;
    }

    /**
     * 把用户输入规范化成「箱子标签分类」（手动选择用）。
     *
     * <p>两类都收：新页签键（{@code minecraft:ingredients} / 省略命名空间的 {@code ingredients}）
     * 与旧中文名（「建材方块」…）。旧名<b>原样返回</b> —— 它是父类，贴了它的箱子按
     * {@link Categories#matches} 继续收该旧类目原来管辖的那些新键物品（旧存档不迁移）。
     *
     * @return 规范化后的分类串；认不出返回 null
     */
    public static String normalizeCategory(String raw) {
        return Categories.resolve(raw);
    }

    /** 给指令用的分类清单（写成人话） */
    public static String categoryListText() {
        StringBuilder sb = new StringBuilder();
        List<String> cats = Categories.order();
        for (int i = 0; i < cats.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(Categories.displayName(cats.get(i)));
        }
        return sb.toString();
    }
}
