package com.ds.warehouse.porter;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.WorldStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 假人名册：这个存档里请了几个搬运工、各自值守哪个仓库。
 *
 * <p>名字必须是 {@code [A-Za-z0-9_]}（Carpet 的 {@code /player} 不接受中文、空格），
 * 所以固定按 {@link #name(int)} 生成：第一个叫 {@code WarehouseBot}，之后
 * {@code WarehouseBot2}、{@code WarehouseBot3}……名字固定，UUID 由名字派生，
 * 关服重开还是同一个人，不会每次新建。
 *
 * <p>名册写在 {@code config/warehouse-keeper/bots.json}，不进存档目录。
 */
public final class Bots {

    /** 最多几个假人。每个假人都是一个真实玩家实体，太多会拖慢服务端。 */
    public static final int MAX = 8;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type TYPE = new TypeToken<Data>() {
    }.getType();

    /** JSON 根对象 */
    public static final class Data {
        public List<Entry> bots = new ArrayList<>();
    }

    /** 一个假人：名字 + 值守的仓库名（空串 = 不指定，跟着全局值守点走）+ 可选的自定义值守点 */
    public static final class Entry {
        public String name = "";
        public String region = "";

        /**
         * 显示名（昵称）——**只影响界面显示**，不动 {@link #name}：注册名还是
         * {@code WarehouseBot}，Carpet 的身份（按名字派生的离线 UUID）也不变，
         * 所以指令、按键、过滤一律照旧用 {@code name}，只有给人看的地方读这个字段。
         *
         * <p>{@code null} / 空串 = 没起昵称，界面回落显示注册名；读取请走
         * {@link #displayOf(String)}（它保证返回非 null 的可显示字符串）。
         * <p>老版本的 bots.json 里没有这个字段，Gson 读进来就是 {@code null}，
         * 所有读取路径都按 null 安全处理，所以升级不会坏档、也不需要迁移。
         */
        public String display;

        /**
         * 自定义值守点（{@code /warehouse bot spot <名字>} 设的那个）。
         *
         * <p>{@code homeDim} 为空串 = 没设过，值守点按 {@link com.ds.warehouse.porter.Body#standby}
         * 的自动顺序算（自己设的 → 仓库箱子旁 → 仓库中心 → 全局值守点 → 兜底）。
         * <p>老版本的 bots.json 里没有这几个字段，Gson 读进来就是这里的默认值（等于「没设过」），
         * 所以升级不会坏档、也不需要迁移。
         */
        public String homeDim = "";
        public double homeX;
        public double homeY;
        public double homeZ;

        /**
         * 玩家按过「收回」：这个假人记成「歇班」，模组不会再自动把它放出来
         * （进存档时的统一放出、空闲回值守点、接取货单前的放出、网页端一样全部跳过），
         * 直到显式 {@code /warehouse bot spawn <名字>} 或 {@code /warehouse porter spawn}。
         *
         * <p>老版本的 bots.json 里没有这个字段，Gson 读进来就是 false（＝照旧自动放出），
         * 升级不会坏档、也不需要迁移。
         */
        public boolean recalled;

        public Entry() {
        }

        public Entry(String name, String region) {
            this.name = name;
            this.region = region == null ? "" : region;
        }
    }

    /** 自定义值守点的只读快照 */
    public record Spot(String dim, double x, double y, double z) {
    }

    private static final List<Entry> LIST = new ArrayList<>();
    private static boolean dirty;

    private Bots() {
    }

    private static Path file() {
        // 每个存档一份名册：假人是不是在线、该守哪个仓库，换存档后都不该延续
        return WorldStore.file("bots.json");
    }

    /** 第 index 个假人的名字（0 → WarehouseBot，1 → WarehouseBot2 …） */
    public static String name(int index) {
        return index <= 0 ? "WarehouseBot" : "WarehouseBot" + (index + 1);
    }

    public static void load() {
        LIST.clear();
        dirty = false;
        Path f = file();
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                Data d = GSON.fromJson(r, TYPE);
                if (d != null && d.bots != null) {
                    for (Entry e : d.bots) {
                        if (e != null && e.name != null && !e.name.isEmpty()) {
                            e.region = e.region == null ? "" : e.region;
                            // 显示名归一化：老档缺字段 = null，手改档留下的空串/纯空白也一律变 null，
                            // 内存里就只有「null = 没昵称」一种表示，后面所有读取处都不必再判空串。
                            if (e.display != null) {
                                String text = e.display.trim();
                                e.display = text.isEmpty() ? null : text;
                            }
                            LIST.add(e);
                        }
                    }
                } else {
                    // JSON 是 null / 缺 bots 字段：名册读不出来，但**先留一份再继续**，
                    // 别让 ensureDefault + save 把它整体覆盖掉（审查发现 T1）
                    WorldStore.backupUnreadable(f);
                    WarehouseMod.LOGGER.warn("{} 里没有名册内容，已另存备份后按默认名册继续", f.getFileName());
                }
            } catch (Exception e) {
                WorldStore.backupUnreadable(f); // 读不动 ≠ 可以覆盖（T1）
                WarehouseMod.LOGGER.warn("读取 {} 失败（用默认名册）: {}", f, e.toString());
            }
        }
        ensureDefault();
    }

    public static void save() {
        if (!dirty) {
            return;
        }
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Data d = new Data();
            d.bots.addAll(LIST);
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(d, TYPE, w);
            }
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            WarehouseMod.LOGGER.info("已存 {} 个假人的名册: {}", LIST.size(), f);
        } catch (Exception e) {
            WarehouseMod.LOGGER.warn("写入 {} 失败: {}", f, e.toString());
        }
    }

    public static boolean isDirty() {
        return dirty;
    }

    /** 名册快照（网页/GUI 读，纯只读）。只含名字、值守仓库、自定义值守点坐标，没有任何隐私信息。 */
    public static List<java.util.Map<String, Object>> json() {
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (Entry e : LIST) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", e.name);
            // 显示名（昵称）：没设过就是注册名本身，所以这里永远可以直接拿去显示；
            // name 仍是注册名 —— 过滤 / 查找 / 跳转 / 指令都用它，语义不变。
            m.put("display", displayOf(e.name));
            m.put("region", e.region);
            // 空串 = 自动判定；有值 = 自定义值守点（形如 "15,-60,4"）
            m.put("spot", spotText(e.name));
            out.add(m);
        }
        return out;
    }

    /** 名册（只读副本） */
    public static List<Entry> list() {
        return Collections.unmodifiableList(LIST);
    }

    public static int size() {
        return LIST.size();
    }

    public static Entry of(String name) {
        for (Entry e : LIST) {
            if (e.name.equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }

    public static boolean has(String name) {
        return of(name) != null;
    }

    /** @return 这个假人值守的仓库名；没这个假人就是 null，不指定就是空串 */
    public static String regionOf(String name) {
        Entry e = of(name);
        return e == null ? null : e.region;
    }

    // ------------------------------------------------------------------
    // 显示名（昵称）：只影响「给人看」的文字，绝不参与身份

    /**
     * 这个假人界面上该显示的名字。
     *
     * <p>设过昵称就是昵称，没设过（或名册里根本没这个人）就是**注册名本身** ——
     * 所以调用方（指令反馈 / 面板 / JSON）拿到的永远是非 null 的可显示字符串，
     * 不需要自己判空。{@code name} 为 null 时返回空串。
     */
    public static String displayOf(String name) {
        if (name == null) {
            return "";
        }
        Entry e = of(name);
        if (e == null || e.display == null || e.display.isBlank()) {
            return name;
        }
        return e.display;
    }

    /**
     * 设这个假人的**显示名**（昵称）。
     *
     * <p>只写 {@link Entry#display}：注册名、Carpet 按名字派生的 UUID、指令里的名字全都不变，
     * 所以不存在身份迁移的问题。
     *
     * <p>{@code display} 传 null 或全空白 = 清除昵称（存 null），界面回落显示注册名。
     * 值不变时不标脏，避免白写一次 bots.json。
     *
     * @return false = 名册里没这个假人（什么都没做）
     */
    public static boolean setDisplay(String name, String display) {
        Entry e = of(name);
        if (e == null) {
            return false;
        }
        String trimmed = display == null ? "" : display.trim();
        String next = trimmed.isEmpty() ? null : trimmed;
        boolean changed = next == null ? e.display != null : !next.equals(e.display);
        if (changed) {
            e.display = next;
            dirty = true;
        }
        return true;
    }

    /** @return 这个假人自己设过的值守点；没设过（或没有这个假人）就是 null */
    public static Spot spotOf(String name) {
        Entry e = of(name);
        if (e == null || e.homeDim == null || e.homeDim.isEmpty()) {
            return null;
        }
        return new Spot(e.homeDim, e.homeX, e.homeY, e.homeZ);
    }

    /**
     * 设这个假人的自定义值守点；{@code dim} 传空串表示「取消，回到自动判定」。
     *
     * @return false = 名册里没这个假人
     */
    public static boolean setSpot(String name, String dim, double x, double y, double z) {
        Entry e = of(name);
        if (e == null) {
            return false;
        }
        e.homeDim = dim == null ? "" : dim;
        if (e.homeDim.isEmpty()) {
            e.homeX = 0;
            e.homeY = 0;
            e.homeZ = 0;
        } else {
            e.homeX = x;
            e.homeY = y;
            e.homeZ = z;
        }
        dirty = true;
        return true;
    }

    /** 一句话描述值守点，给名册/网页显示用；没设过就是空串 */
    public static String spotText(String name) {
        Spot s = spotOf(name);
        if (s == null) {
            return "";
        }
        return Math.round(s.x()) + "," + Math.round(s.y()) + "," + Math.round(s.z());
    }

    /** 一个假人都没有时补一个默认的（第一次装模组时用） */
    public static void ensureDefault() {
        if (LIST.isEmpty()) {
            LIST.add(new Entry(name(0), ""));
            dirty = true;
        }
    }

    /** 加一个假人；满员返回 null，否则返回新名字 */
    public static String add(String region) {
        if (LIST.size() >= MAX) {
            return null;
        }
        for (int i = 0; i < MAX; i++) {
            String n = name(i);
            boolean used = false;
            for (Entry e : LIST) {
                if (e.name.equalsIgnoreCase(n)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                LIST.add(new Entry(n, region));
                dirty = true;
                return n;
            }
        }
        return null;
    }

    public static boolean remove(String name) {
        Entry e = of(name);
        if (e == null) {
            return false;
        }
        LIST.remove(e);
        dirty = true;
        return true;
    }

    /** 改值守仓库；{@code region} 传空串表示不指定 */
    public static boolean assign(String name, String region) {
        Entry e = of(name);
        if (e == null) {
            return false;
        }
        String r = region == null ? "" : region;
        if (!r.equals(e.region)) {
            e.region = r;
            dirty = true;
        }
        return true;
    }

    /**
     * 记/清「收回」标记。
     *
     * <p>{@code true} = 这名搬运工被玩家收回了：{@link Body#ensure} 会拒绝把它放出来，
     * 空闲待命和接单前的自动放出也都不会碰它，直到玩家显式让它上岗。
     * 名册里没有这个人就什么都不做（返回 false）。
     */
    public static boolean setRecalled(String name, boolean recalled) {
        Entry e = of(name);
        if (e == null) {
            return false;
        }
        if (e.recalled != recalled) {
            e.recalled = recalled;
            dirty = true;
        }
        return true;
    }

    /** 这个人是不是被玩家「收回」了（收回后不再自动放出） */
    public static boolean isRecalled(String name) {
        Entry e = of(name);
        return e != null && e.recalled;
    }
}
