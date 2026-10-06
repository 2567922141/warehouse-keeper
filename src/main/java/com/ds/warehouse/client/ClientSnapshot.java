package com.ds.warehouse.client;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端这一侧的「服务端仓库快照」。
 *
 * <p>内容是服务端通过 {@code warehouse-keeper:snapshot} 包推过来的（见
 * {@link com.ds.warehouse.net.SnapshotSync}）：区域定义 + 每个仓库的物品清单。
 * 单机和联机走的是同一条路 —— 界面不再去读本机的 regions.json，
 * 因此「连别人的服务器看不见仓库」的问题从根上没有了。
 */
public final class ClientSnapshot {

    /** 一个仓库里的某种物品（字段名与 JSON 键名一致） */
    public static final class Item {
        /** 物品 id，例如 minecraft:dirt；客户端用它按自己的语言重新取名字（老服务端可能给空串） */
        public String id = "";
        public String name = "";
        public long count;
        public int refs;
        /** 悬停提示用：位置 + 第几格（服务端拼好） */
        public String loc = "";
        /**
         * 0.23.0：这一行的附魔组合（{@code 注册名@等级} 用 {@code ,} 连接）；空 = 无附魔。
         *
         * <p>服务端把附魔书/附魔装备按附魔拆成了多行，所以同一件物品会出现多行，靠这个字段区分。
         * 老服务端不发这个字段 ⇒ 空串 ⇒ 与老版本行为一致。
         */
        public String ench = "";
        /** 这一行里出现过的自定义名（{@code ; } 连接）；空 = 没改名 */
        public String customName = "";
        /**
         * 1.1.3：这一行的类目键（如 {@code minecraft:building_blocks}），服务端算好发过来的；
         * 空串 = 老服务端不发。
         *
         * <p>客户端进程里没有 MinecraftServer，建不起「物品→创造页签」表，自己算会把每件物品
         * 都算成「其他」（取货页的类目下拉曾经只剩「全部分类 / 其他」）。有值就优先用它。
         */
        public String category = "";
    }

    /** 箱子总览的一行（批次 4）。双联箱在服务端索引里就是一条记录，所以一只箱子一行 */
    public static final class Box {
        public int x;
        public int y;
        public int z;
        /** 方块 id，例如 minecraft:chest；老服务端可能给空串 */
        public String block = "";
        /** 方块的中文名，例如 箱子 / 木桶 */
        public String name = "";
        /** 总格数（双联箱是 54） */
        public int size;
        /** 已占格数 */
        public int used;
        /** 里面物品的总个数 */
        public long items;
        /** 是不是双联箱 */
        public boolean dbl;
        /** 主要物品（服务端拼好的「铁锭×320、圆石×128」，最多 3 种） */
        public String top = "";
        /** 主要物品的 id 与个数（`id=count;id=count`，最多 3 种）：客户端按自己的语言翻译后再画 */
        public String topIds = "";
        /** 批次 6 · D3 主导分类：这一箱主要装哪一类；空箱或老服务端是空串 */
        public String dominant = "";
        /** 主导分类占的非空槽数 */
        public int domSlots;
        /** 主导分类的总件数 */
        public long domCount;
    }

    /** 一个仓库的区域定义 + 物品清单 */
    public static final class Region {
        public String name = "";
        public String dimension = "minecraft:overworld";
        public boolean fullHeight = true;
        public int[] from = new int[]{0, 0, 0};
        public int[] to = new int[]{0, 0, 0};
        public long totalItems;
        public long usedSlots;
        public long capacity;
        public List<Item> items = new ArrayList<>();
        /** 箱子总览（批次 4）；老服务端不发这一段，解析后是空表 */
        public List<Box> boxes = new ArrayList<>();
    }

    /** 名册里的一个搬运工（面板「搬运工」页用） */
    public static final class Bot {
        public String name = "";
        /**
         * 自定义显示名（0.22.0 · 优化7）。空 = 没设置，界面一律退回 {@link #name}。
         *
         * <p>老服务端不发这个字段，解析后是空串，所以每次读都要判空 —— 用 {@link #shown()}。
         */
        public String display = "";
        /** 值守的仓库名；空 = 没指定 */
        public String region = "";
        /** 自定义值守点 "x,y,z"；空 = 自动 */
        public String spot = "";
        /** 正在干什么；空 = 闲着 */
        public String busy = "";
        /** 现在在世​界里（在岗）。面板的按钮据此显示「上岗」还是「收回」 */
        public boolean present;

        /**
         * 画给人看的名字：设了自定义显示名就用它，否则用注册名。
         *
         * <p><b>只用于显示</b>：指令、任务、按钮回调、以名字为键的查表一律继续用
         * {@link #name}（注册名）—— 那是身份，不能换。
         */
        public String shown() {
            return display == null || display.isEmpty() ? name : display;
        }
    }

    /**
     * 一个网页账号的三项权限。
     *
     * <p>服务端只把这一小段发给管理员客户端（见 {@code SnapshotSync.accountsDto()}），
     * 里面刻意没有密码相关的任何字段。
     */
    public static final class Account {
        public String name = "";
        public boolean take = true;
        public boolean bot = true;
        public boolean tidy = true;
    }

    /**
     * 一只箱子的标签（批次 3）。键是 "维度@x,y,z"，坐标已归一化到双联箱的主半；
     * 打开箱子时客户端按射线打到的坐标查，横着相邻的格子也认（双联箱两半各存各的坐标）。
     */
    public static final class Tag {
        public String key = "";
        /** 给人看的一行：暂存 / 农业（手动档） / 未打标签 */
        public String label = "";
        /** 生效的分类；暂存箱或没打标签时是空串 */
        public String category = "";
        /** MANUAL / AUTO */
        public String mode = "";
        public boolean staging;
    }

    private static final class Payload {
        String version;
        long at;
        boolean truncated;
        List<Region> regions = new ArrayList<>();
        List<Bot> bots = new ArrayList<>();
        List<Tag> tags = new ArrayList<>();
        List<Account> accounts;
        /**
         * 类目键清单（1.1.1）：服务端 {@code Categories.order()} 的顺序。
         *
         * <p>老服务端不发这一段 ⇒ 解析后是空表 ⇒ {@code ContainerTagBar} 自动退回本机注册表兜底。
         */
        List<String> categories = new ArrayList<>();
    }

    private static final Gson GSON = new Gson();

    private static volatile List<Region> regions = Collections.emptyList();
    private static volatile List<Bot> bots = Collections.emptyList();
    private static volatile List<Tag> tags = Collections.emptyList();
    private static volatile Map<String, Tag> tagMap = Collections.emptyMap();
    private static volatile List<Account> accounts = Collections.emptyList();
    /** 服务端给的类目键清单（展示顺序）；老服务端是空表 */
    private static volatile List<String> categories = Collections.emptyList();
    private static volatile long receivedAt;
    private static volatile String version = "";
    private static volatile String note = "等待服务端同步…";

    private ClientSnapshot() {
    }

    /** 收到包：解析并存下来（坏包只记一条备注，不影响游戏） */
    public static void apply(byte[] json) {
        try {
            Payload p = GSON.fromJson(new String(json, StandardCharsets.UTF_8), Payload.class);
            if (p == null || p.regions == null) {
                note = "服务端同步数据不可读";
                return;
            }
            List<Region> clean = new ArrayList<>(p.regions.size());
            for (Region r : p.regions) {
                if (r == null) {
                    continue;
                }
                if (r.from == null || r.from.length < 3) {
                    r.from = new int[]{0, 0, 0};
                }
                if (r.to == null || r.to.length < 3) {
                    r.to = new int[]{0, 0, 0};
                }
                if (r.items == null) {
                    r.items = new ArrayList<>();
                } else {
                    for (Item it : r.items) {
                        if (it == null) {
                            continue;
                        }
                        if (it.id == null) {
                            it.id = "";
                        }
                        if (it.name == null) {
                            it.name = "";
                        }
                        if (it.ench == null) {
                            it.ench = "";
                        }
                        if (it.customName == null) {
                            it.customName = "";
                        }
                        if (it.category == null) {
                            it.category = "";
                        }
                    }
                }
                // 箱子总览（批次 4）：老服务端不发这一段 ⇒ 空表；字段防空指针
                if (r.boxes == null) {
                    r.boxes = new ArrayList<>();
                } else {
                    for (Box b : r.boxes) {
                        if (b == null) {
                            continue;
                        }
                        if (b.block == null) {
                            b.block = "";
                        }
                        if (b.name == null) {
                            b.name = "";
                        }
                        if (b.top == null) {
                            b.top = "";
                        }
                        if (b.topIds == null) {
                            b.topIds = "";
                        }
                        if (b.dominant == null) {
                            b.dominant = "";
                        }
                    }
                }
                clean.add(r);
            }
            regions = List.copyOf(clean);
            bots = p.bots == null ? Collections.emptyList() : List.copyOf(p.bots);
            List<Tag> cleanTags = new ArrayList<>();
            Map<String, Tag> map = new LinkedHashMap<>();
            if (p.tags != null) {
                for (Tag t : p.tags) {
                    if (t == null || t.key == null || t.key.isEmpty()) {
                        continue;
                    }
                    if (t.label == null) {
                        t.label = "";
                    }
                    if (t.category == null) {
                        t.category = "";
                    }
                    if (t.mode == null) {
                        t.mode = "";
                    }
                    cleanTags.add(t);
                    map.put(t.key, t);
                }
            }
            tags = List.copyOf(cleanTags);
            tagMap = Map.copyOf(map);
            accounts = p.accounts == null ? Collections.emptyList() : List.copyOf(p.accounts);
            // 类目清单（1.1.1）：老服务端没有这个字段 ⇒ 空表 ⇒ 标签栏退回本机注册表兜底
            List<String> cats = new ArrayList<>();
            if (p.categories != null) {
                for (String c : p.categories) {
                    if (c != null && !c.isEmpty() && !cats.contains(c)) {
                        cats.add(c);
                    }
                }
            }
            categories = List.copyOf(cats);
            receivedAt = System.currentTimeMillis();
            version = p.version == null ? "" : p.version;
            note = regions.isEmpty()
                    ? "服务端尚无仓库"
                    : ("服务端同步 · 共 " + regions.size() + " 个仓库"
                    + (p.truncated ? "（物品过多，仅同步部分）" : ""));
        } catch (Exception e) {
            // 解析失败绝不把上一份数据丢掉（玩家至少还能看旧内容），但要把话说清楚：
            // 服务端只在「内容变化」时才补发，所以要么等下一次变化，要么重新进服。
            String tail = hasData()
                    ? "（界面仍显示上一次的数据；等服务端下次变化或重新进服即可恢复）" : "";
            note = "服务端同步数据解析失败: " + e.getClass().getSimpleName() + tail;
        }
    }

    /** 断开连接：立刻忘掉上一台服务器的数据，免得串到下一个世界 */
    public static void clear() {
        regions = Collections.emptyList();
        bots = Collections.emptyList();
        tags = Collections.emptyList();
        tagMap = Collections.emptyMap();
        accounts = Collections.emptyList();
        categories = Collections.emptyList();
        receivedAt = 0L;
        version = "";
        note = "等待服务端同步…";
    }

    public static List<Region> regions() {
        return regions;
    }

    /** 名册：服务端那边现在有哪些搬运工、各自值守哪、在忙什么 */
    public static List<Bot> bots() {
        return bots;
    }

    /**
     * 按**注册名**找这个搬运工设的显示名；没有装显示名（或快照里没有这个人）时返回 {@code null}。
     *
     * <p>1.1.2 · 优化2：拿它去替换游戏里画出来的玩家名（见 {@code mixin.PlayerDisplayNameMixin}）——
     * 原版玩家名牌 = 记分板队伍装饰 + 档案名，{@code setCustomName} 对它无效，
     * 所以服务端改不了名字，只能在客户端显示这一层换。
     *
     * <p>返回 null（而不是空串）是为了让调用方一眼分辨「没得换」和「换成了空」。
     */
    public static String displayNameOf(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Bot b : bots) {
            if (b == null || !name.equals(b.name)) {
                continue;
            }
            return b.display == null || b.display.isEmpty() ? null : b.display;
        }
        return null;
    }

    /** 箱子标签（批次 3）：游戏内标签栏按坐标查，网页面板按 key 查 */
    public static List<Tag> tags() {
        return tags;
    }

    /**
     * 按坐标精确找标签。
     *
     * <p>这里<b>只做精确匹配</b>：以前这里会横着往邻格找一圈（因为服务端把标签记在双联箱归一化的
     * 那一半上），但那只对原版双联箱成立 —— 结果隔壁一只桶/箱子的标签会串到当前箱子上，看起来
     * 就是「这只箱子显示的是别人的标签」。归一化兜底挪到了 {@code ContainerTagBar#tagNear}，
     * 由它先确认当前方块真的是会拼箱的原版箱子，再去看邻格。
     */
    public static Tag tagOf(String dimension, int x, int y, int z) {
        Map<String, Tag> m = tagMap;
        if (m.isEmpty() || dimension == null || dimension.isEmpty()) {
            return null;
        }
        return m.get(dimension + "@" + x + "," + y + "," + z);
    }

    /** 网页账号的三项权限；非管理员客户端拿到的是空表 */
    public static List<Account> accounts() {
        return accounts;
    }

    /**
     * 服务端下发的类目键清单（展示顺序，含末尾的「其他」）。
     *
     * <p>{@code ContainerTagBar} 优先用它画标签栏下拉 —— 这样访客看到的类目与房主完全一致。
     * 老服务端不发这一段（空表）时由调用方退回本机注册表兜底。
     */
    public static List<String> categories() {
        return categories;
    }

    public static Region find(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Region r : regions) {
            if (name.equals(r.name)) {
                return r;
            }
        }
        return null;
    }

    public static boolean hasData() {
        return receivedAt > 0L;
    }

    public static long receivedAt() {
        return receivedAt;
    }

    public static String version() {
        return version;
    }

    public static String note() {
        return note;
    }
}
