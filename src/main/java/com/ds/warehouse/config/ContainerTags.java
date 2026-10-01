package com.ds.warehouse.config;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.index.ContainerRecord;
import com.ds.warehouse.index.ItemIds;
import com.ds.warehouse.index.Scanner;
import com.ds.warehouse.index.WarehouseIndex;
import com.ds.warehouse.util.Categories;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
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
        return be instanceof Container;
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
     *   <li>箱子还在、但归一化坐标变了（后来并成/拆成双联箱）⇒ 把标签挪到新键；
     *       新键已经有标签时保留新键那条，删掉这条历史残留</li>
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
            if (!(level.getBlockEntity(s.pos()) instanceof Container)) {
                MAP.remove(e.getKey());
                dirty = true;
                dead++;
                continue;
            }
            BlockPos canon = Scanner.canonical(level, s.pos());
            if (canon.equals(s.pos())) {
                continue;
            }
            String newKey = WarehouseIndex.key(s.dimension(), canon);
            MAP.remove(e.getKey());
            dirty = true;
            if (MAP.containsKey(newKey)) {
                dead++; // 新键已经有标签了，这条是历史残留
            } else {
                MAP.put(newKey, e.getValue());
                moved++;
            }
        }
        return new int[] { moved, dead };
    }

    // ------------------------------------------------------------------
    // 「按内容自动」：件数加权取最大项

    /**
     * 箱内件数最多的分类（同票时按 {@link Categories#ORDER} 的顺序定序，保证可复现）。
     *
     * @return 分类名；箱内没有可判定物品时返回 null
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
        for (String cat : Categories.ORDER) {
            Integer c = weight.get(cat);
            if (c != null && c > bestCount) {
                best = cat;
                bestCount = c;
            }
        }
        // 理论上不会走到这里（分类一定落在 ORDER 里），保底再扫一遍
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

    /** 分类名是否合法（手动选择用） */
    public static String normalizeCategory(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        for (String c : Categories.ORDER) {
            if (c.equalsIgnoreCase(raw.trim()) || c.toLowerCase(Locale.ROOT).equals(s)) {
                return c;
            }
        }
        return null;
    }

    /** 给指令用的分类清单（写成人话） */
    public static String categoryListText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Categories.ORDER.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(Categories.ORDER.get(i));
        }
        return sb.toString();
    }
}
