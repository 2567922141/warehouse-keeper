package com.ds.warehouse.porter;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.AppConfig;
import com.ds.warehouse.config.CategoryRules;
import com.ds.warehouse.config.ContainerTags;
import com.ds.warehouse.config.Region;
import com.ds.warehouse.config.RegionStore;
import com.ds.warehouse.index.ContainerRecord;
import com.ds.warehouse.index.Containers;
import com.ds.warehouse.index.ItemIds;
import com.ds.warehouse.index.IndexRefresh;
import com.ds.warehouse.index.Scanner;
import com.ds.warehouse.index.WarehouseIndex;
import com.ds.warehouse.util.Categories;
import com.ds.warehouse.util.CreativeOrder;
import com.ds.warehouse.util.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 假人能干的「活」——取货送货之外的批量任务。
 *
 * <p>目前只有一种：{@link #TIDY} 整理仓库 —— 把同一个箱子里同种物品的零碎堆叠合并到一格；
 * 把放错箱子的物品搬回它「家」（{@link Homes} 记住的那个箱子）。
 *
 * <p>（优化4）原先还有「清扫地面」任务，已随「删除自动拾取仓库范围内掉落物」一起下线。
 *
 * <p>每个假人各自一份任务（{@link #JOBS}），只在服务端 tick 线程上跑；
 * 网页/GUI 读的是 {@link #snapshot()} 这份只读快照。
 *
 * <p>物品搬运一律**直接读写 {@link Container}**，不模拟 GUI 交互；假人的瞬移只是表演，
 * 让玩家看得见「它在干活」。写入走 {@link Porter#putInto}（写后回读校验、认双联箱两半、
 * 防 Iron Chests 「消毒」），所以不会有物品凭空消失或翻倍。
 */
public final class Tasks {

    public static final int TIDY = 0;
    /**
     * 清扫地面任务（优化4 已下线）。
     *
     * <p>常量留着只是为了让还在引用它的命令层节点（{@code command/WarehouseCommand.java} 的
     * {@code porter sweep} / {@code bot sweep}）仍然能编译；{@link #start} 会直接拒绝这个类型并
     * 回一句说明，{@link #kindName} 也不再给它名字。等命令层的节点删掉后这个常量可以一起删。
     */
    public static final int SWEEP = 1;
    /** 整理：一个任务最多跑多久（批次 1 · B2） */
    private static final long JOB_NANOS = 10L * 60L * 1_000_000_000L;
    /** 整理：一个任务最多搬多少件（批次 1 · B2） */
    private static final int JOB_MOVES = 50_000;
    /** 整理预览一次最多看多少只箱子（预览是同步跑的，整仓一眼扫完会把主线程占住 · A1-b） */
    private static final int PREVIEW_LIMIT = 256;
    /**
     * 归仓一次最多考虑多少个候选箱子。
     *
     * <p>按现有的「标签优先 → 同维度 → 由近到远」顺序取前 N 个。这个上限只是兜底，
     * 取值刻意放宽（正常仓库远小于它），以免「明明后面还有空箱子却把物品放在地上」。
     */
    private static final int PARK_LIMIT = 512;

    /**
     * 箱内摆放顺序用的中文排序器：{@code Collator.getInstance(Locale.CHINA)} 就是**拼音序**
     * （先比第一个字的拼音，再比第二个字，以此类推 —— 玩家要的「钻石」排在「泥土」后面就是这个）。
     *
     * <p>{@link Collator} 不是线程安全的，而整理只在服务端线程跑；保险起见比较时加锁。
     */
    private static final Collator PINYIN = Collator.getInstance(Locale.CHINA);

    /**
     * 箱内排序的兜底比较：按物品显示名的拼音先后（首字 → 次字 → …）。
     *
     * <p>（优化2）箱内排序的主键已经换成「创造栏顺序」（见 {@link CreativeOrder}）；
     * 只有**不在任何创造栏页签里**的物品才落到这条规则上。名字取 {@link Names#item(String)}
     * （服务端能解析出中文就用中文，解析不出来会退回物品 id），所以最坏情况也不会乱：
     * 只是退回「按 id 排」的老行为。同音同名时再用物品 id 兜底，保证顺序稳定。
     */
    private static int byPinyin(String idA, String idB) {
        synchronized (PINYIN) {
            int c = PINYIN.compare(Names.item(idA), Names.item(idB));
            return c != 0 ? c : idA.compareTo(idB);
        }
    }

    /**
     * 有创造栏顺序的物品一律排在没顺序的前面，所以没顺序的那批从 2^32 起编号
     * （创造栏顺序号是 int，远小于它；见 {@link #sortKeysOf}）。
     */
    private static final long UNRANKED_BASE = 1L << 32;

    /**
     * （优化2）给这一箱用到的每个物品 id 算一个排序键，**一只箱子只算一次**。
     *
     * <p>三级键：
     * <ol>
     *   <li>创造栏里有位置的（{@link CreativeOrder#rank} ≥ 0）按顺序号升序排在最前面；</li>
     *   <li>不在任何 CATEGORY 页签里的（模组调试/占位物品之类）一律排在后面；</li>
     *   <li>第二批内部按原来的拼音（同音退化成物品 id）定序 —— 先给它们排一次序再编号，
     *       保证顺序确定，也避免选择排序每比一次都去查 map + 比拼音（原来一只箱子要
     *       O(n²) 次拼音比较，现在只有一次 O(m log m)）。</li>
     * </ol>
     */
    private static Map<String, Long> sortKeysOf(List<ItemStack> items) {
        List<String> ids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ItemStack s : items) {
            if (s.isEmpty()) {
                continue;
            }
            String id = ItemIds.of(s);
            if (id != null && seen.add(id)) {
                ids.add(id);
            }
        }
        Map<String, Long> keys = new HashMap<>(Math.max(4, ids.size() * 2));
        List<String> unranked = new ArrayList<>();
        for (String id : ids) {
            int r = CreativeOrder.rank(id);
            if (r >= 0) {
                keys.put(id, (long) r);
            } else {
                unranked.add(id);
            }
        }
        if (!unranked.isEmpty()) {
            unranked.sort(Tasks::byPinyin);
            for (int i = 0; i < unranked.size(); i++) {
                keys.put(unranked.get(i), UNRANKED_BASE + i);
            }
        }
        return keys;
    }

    /** 比两个 id 的排序键；键缺失（理论上不会）时退回拼音比较，保证任何情况下顺序都确定 */
    private static int compareKeys(Map<String, Long> keys, String a, String b) {
        Long ka = keys.get(a);
        Long kb = keys.get(b);
        if (ka == null || kb == null) {
            if (ka == null && kb == null) {
                return byPinyin(a, b);
            }
            return ka == null ? 1 : -1;
        }
        return Long.compare(ka, kb);
    }

    public static String kindName(int kind) {
        return switch (kind) {
            case TIDY -> "整理仓库";
            default -> "执行任务";
        };
    }

    /** 把中文/英文的活名解析成种类；认不出来返回 null */
    public static Integer parseKind(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "tidy", "sort", "整理", "整理仓库" -> TIDY;
            default -> null;
        };
    }

    // ------------------------------------------------------------------

    private static final class Job {
        final int kind;
        final String region;
        /** 开工时刻：整理用它算「已经跑了多久」（批次 1 · B2 的时间上限） */
        final long startedNanos = System.nanoTime();
        final List<Region> touched = new ArrayList<>();
        int phase;
        int wait;
        int steps;
        int moved;
        int merged;
        /** 假人身上带的东西里，成功放进箱子的件数 */
        int stored;
        /** 仓库里真没地方放、只能放在地上的件数 */
        int dropped;
        /** 因为「目标就是来源本身／同一个大箱子」而跳过的步数（防丢物品，A1-e） */
        int skipped;
        boolean dirty;
        /** 批次 1 · P4 按来源箱分组：现在盯着的来源箱子 key，先把这箱的活干完再换（null = 重新扫） */
        String focus;
        /** {@link #focus} 在整理顺序里是第几只（1 起）；0 = 还没盯上任何箱子。省掉每次进度都重扫一遍 */
        int focusIdx;
        /**
         * 多假人分工（1.1.1）：本假人负责第 {@link #part} 片（0 起），一共切成 {@link #parts} 片。
         *
         * <p>切分口径是「按来源箱哈希取模」：每名假人只碰自己那一片的箱子，天然不会两人盯同一只。
         * {@code parts == 1} 就是老行为（一个人干整仓）。
         */
        int part;
        int parts = 1;
        /** 批次 1 · P5：本次是因为撞上「件数/时间上限」而停的（不是把活干完了）⇒ 报告里要让玩家知道可以再跑一次接着干 */
        boolean limited;
        /** 批次 3（附录 J.6）：这一轮里有多少件被送进了「贴了标签的目标箱」 */
        int tagMoves;
        /**
         * 0.23.0：这一轮里有多少件是在<b>同一个箱子内部</b>为了排顺序而搬的（{@code planIn} 的 ② 箱内排序）。
         *
         * <p>它与 {@link #moved} 是「总数与其中一部分」的关系：{@code moved - sorted} 就是跨箱归类的件数。
         * 报告里要把两者分开写 —— 用户看不到「箱内排序到底动没动」，就会以为排序没生效。
         */
        int sorted;
        /** 表演节奏：下一件要到哪一刻才动手（服务端 tick 计数，间隔见 {@link AppConfig#tidyTicksPerMove}） */
        int nextMoveTick;
        /** 现在开着盖子的那只箱子（换箱子 / 收工时要把它盖上，免得箱盖一直敞着） */
        ServerLevel lidLevel;
        BlockPos lidPos;

        Job(int kind, String region) {
            this.kind = kind;
            this.region = region == null ? "" : region;
        }

        /**
         * 多假人分工（1.1.1）：同一个仓库里几个人一起干时，每人只负责自己那一片。
         *
         * @param part  本假人负责第几片（0 起，越界会自动折回）
         * @param parts 一共几片；1 = 一个人干整仓（老行为）
         */
        Job(int kind, String region, int part, int parts) {
            this(kind, region);
            this.parts = Math.max(1, parts);
            this.part = Math.floorMod(part, this.parts);
        }

        void touch(String dimension, BlockPos pos) {
            if (dimension == null || pos == null) {
                return;
            }
            for (Region r : RegionStore.REGIONS.values()) {
                if (Scanner.dimensionOf(r).equals(dimension) && r.contains(pos)) {
                    if (!touched.contains(r)) {
                        touched.add(r);
                    }
                    return;
                }
            }
        }
    }

    /**
     * 一步计划：把「来源」的第 slot 格搬到「目标」。
     *
     * <p>{@code sameContainer} = 目标就在同一个箱子里；此时 {@code toSlot} 的含义：
     * <ul>
     *   <li>{@code -1}：往前面某个没满的同类堆里叠（零碎堆叠合并）</li>
     *   <li>{@code >= 0}：整摞搬到这个<strong>空格</strong>上（把物品压实、按种类排好）</li>
     * </ul>
     *
     * <p>{@code tagged}：目标箱是「贴了标签的目标箱」（批次 3 · J.6）。只用于报告里报个数。
     */
    private record Move(String fromDim, BlockPos from, int slot, int toSlot,
                        String toDim, BlockPos to, String itemId, boolean sameContainer,
                        boolean tagged) {
    }

    private static final Map<String, Job> JOBS = new HashMap<>();
    /**
     * 批次 1 · R14「多假人抢同一批移动」：同一个来源箱子同一时刻只归一个假人。
     *
     * <p>两个假人同时在同一个仓库里整理时，如果都盯着同一口箱子，会各自规划一步、都去读同一份
     * 内容、再把同一摞东西搬两次 —— 轻则白搬，重则互相把东西搬回去（来回震荡）。这里按
     * <b>来源箱</b>认领：谁先把某个箱子设成 {@link Job#focus} 就归谁，别的假人扫到时跳过它。
     */
    private static final Map<String, String> CLAIMS = new HashMap<>();
    /** 网页/GUI 读的只读快照（tick 线程写） */
    private static volatile List<Map<String, Object>> SNAP = List.of();

    private Tasks() {
    }

    public static boolean busy(String botName) {
        return JOBS.containsKey(botName);
    }

    public static String kindOf(String botName) {
        Job j = JOBS.get(botName);
        return j == null ? "" : kindName(j.kind);
    }

    public static String regionOf(String botName) {
        Job j = JOBS.get(botName);
        return j == null ? "" : j.region;
    }

    public static List<Map<String, Object>> snapshot() {
        return SNAP;
    }

    // ------------------------------------------------------------------
    // 开工 / 收工

    /** @return null = 开工了；否则是要给玩家看的原因 */
    public static String start(MinecraftServer server, String botName, int kind, String region) {
        return start(server, botName, kind, region, 0, 1);
    }

    /**
     * 开工（多假人分工版）。
     *
     * <p>同一个仓库里派了 N 个假人时，命令层会给每人一个 {@code part}（0 起）和 {@code parts = N}，
     * 每人只碰「来源箱 key 哈希取模后落在自己这一片」的箱子（见 {@link #mine}）。口径是箱子而不是
     * 物品类别，所以归位/合并的判定完全不变，只是把整仓的箱子分给了几个人。
     *
     * @param part  本假人负责第几片（0 起）
     * @param parts 一共几片；1 = 一个人干整仓（老行为）
     * @return null = 开工了；否则是要给玩家看的原因
     */
    public static String start(MinecraftServer server, String botName, int kind, String region, int part, int parts) {
        if (server == null || botName == null || botName.isEmpty()) {
            return "名册中没有该搬运工。";
        }
        if (kind != TIDY) {
            // （优化4）清扫已下线：命令层旧的 porter sweep / bot sweep 节点还会传这个类型过来，
            // 这里安全地拒绝，绝不建出一个没人会执行的任务（也不抛异常）。
            return "清扫功能已移除：本模组不再自动拾取仓库范围内的掉落物。";
        }
        if (Porter.busyWithOrder(botName)) {
            return botName + " 正在配送订单，请等待配送完成后再派发任务。";
        }
        if (JOBS.containsKey(botName)) {
            return botName + " 已在执行「" + kindOf(botName) + "」，请先执行 /warehouse bot stop " + botName + " 停止。";
        }
        // 被「收回」的搬运工不接活：收回后它不会自动回到世界里，派了也只会当场取消
        if (Bots.isRecalled(botName)) {
            return botName + " 已被收回（歇班中），请先在面板上点「上岗」或执行 /warehouse bot spawn "
                    + botName + "。";
        }
        String r = region == null ? "" : region;
        if (!r.isEmpty() && RegionStore.REGIONS.get(r) == null) {
            return "不存在名为「" + r + "」的仓库。";
        }
        // 值守仓库是干活的先决条件：没分配就不干活，分配了就只在自己那一间里干活。
        // 这样两个假人永远不会去抢同一批活，也不会有人跑到别人的仓库里翻箱子。
        String assigned = Bots.regionOf(botName);
        if (assigned == null || assigned.isEmpty()) {
            return botName + " 尚未分配值守仓库，无法执行任务。请先使用 /warehouse bot assign "
                    + botName + " <仓库名> 指定。";
        }
        if (RegionStore.REGIONS.get(assigned) == null) {
            return botName + " 值守的仓库「" + assigned + "」已不存在，请重新使用 /warehouse bot assign 指定。";
        }
        if (!r.isEmpty() && !r.equals(assigned)) {
            return botName + " 值守的是仓库「" + assigned + "」，只能在「" + assigned + "」内执行任务。";
        }
        r = assigned;
        Job job = new Job(kind, r, part, parts);
        JOBS.put(botName, job);
        if (job.parts > 1) {
            WarehouseMod.LOGGER.info("[warehouse-keeper] 搬运工 {} 开始{}（值守仓库：{}，第 {}/{} 片）",
                    botName, kindName(kind), r, job.part + 1, job.parts);
        } else {
            WarehouseMod.LOGGER.info("[warehouse-keeper] 搬运工 {} 开始{}（值守仓库：{}）",
                    botName, kindName(kind), r);
        }
        return null;
    }

    public static boolean cancel(String botName) {
        Job j = JOBS.remove(botName);
        releaseClaims(botName);
        if (j != null) {
            // 取消也必须收尾（批次 1 · A1-c）：/warehouse bot stop 之后不能把已经捡到的物品留在假人身上，
            // 也不能让副手那叠「表演副本」留在手上，整理过的箱子同样要触发局部重扫。
            cleanup(WarehouseMod.server(), botName, j);
            // 半路停掉一个同伴后，剩下的人要把它的那一份接过来（否则那片箱子这一轮没人碰）
            rebalance(j.region);
        }
        return j != null;
    }

    public static void cancelAll() {
        MinecraftServer server = WarehouseMod.server();
        for (Map.Entry<String, Job> e : new ArrayList<>(JOBS.entrySet())) {
            // 关服路径同样逐个收尾，别让假人带着东西被一起卸载
            cleanup(server, e.getKey(), e.getValue());
        }
        JOBS.clear();
        CLAIMS.clear();
        SNAP = List.of();
    }

    /** 认领一个来源箱（返回值只是给调用方看是否成功，认领失败就是别的假人正在整理它） */
    private static boolean claim(String botName, String key) {
        if (key == null) {
            return false;
        }
        String owner = CLAIMS.get(key);
        if (owner == null || owner.equals(botName)) {
            CLAIMS.put(key, botName);
            return true;
        }
        return false;
    }

    /** 这个箱子是否正被**别的**假人整理（自己认领的不算） */
    private static boolean claimedByOther(String botName, String key) {
        String owner = CLAIMS.get(key);
        return owner != null && !owner.equals(botName);
    }

    private static void releaseClaims(String botName) {
        CLAIMS.values().removeIf(botName::equals);
    }

    /** 放掉某一个来源箱的认领（只有自己认领的才放），换箱子时立刻用 */
    private static void releaseClaim(String botName, String key) {
        if (key != null && botName.equals(CLAIMS.get(key))) {
            CLAIMS.remove(key);
        }
    }

    /**
     * 多假人分工的补救：派单时有同伴没能开工，参战人数比原计划少，得把分片重新摊一次。
     *
     * <p>不重摊的话，原本分给「没来的人」的那几片箱子这一轮没人会碰 —— 报告却会说整理完了。
     * 重摊会把手上那只箱子放开（认领也一起放），让它按新口径重新挑，所以不会漏也不会重复。
     *
     * @return true = 确实重摊了（口径变了）
     */
    public static boolean retuneParts(String botName, int part, int parts) {
        Job j = JOBS.get(botName);
        if (j == null) {
            return false;
        }
        int p = Math.max(1, parts);
        int q = Math.floorMod(part, p);
        if (j.parts == p && j.part == q) {
            return false;
        }
        releaseClaim(botName, j.focus);
        j.focus = null;
        j.focusIdx = 0;
        j.parts = p;
        j.part = q;
        return true;
    }

    /**
     * 多假人分工的再平衡：同一间仓库里有人收工之后，剩下的同伴按**实际还在干的人数**重摊分片。
     *
     * <p>触发点都在「一个任务从 {@link #JOBS} 里消失」的地方（{@link #finish} 与
     * {@link #cancel}）。因为一个假人可能是「被停掉 / 出错 / 不在世界」而半路收工的，
     * 它原来负责的那一片箱子如果不重摊，这一轮就彻底没人碰了（面板上同伴还会一路显示「本片 x/y」到头）。
     *
     * <p>只在该仓还有 {@code parts > 1} 的任务时才动手 —— 单人整仓（老行为）与别的仓库都不受影响。
     */
    private static void rebalance(String region) {
        if (region == null || region.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        boolean sharded = false;
        for (Map.Entry<String, Job> en : JOBS.entrySet()) {
            Job j = en.getValue();
            if (j.kind != TIDY || !region.equals(j.region)) {
                continue;
            }
            names.add(en.getKey());
            if (j.parts > 1) {
                sharded = true;
            }
        }
        if (!sharded || names.isEmpty()) {
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            retuneParts(names.get(i), i, names.size());
        }
    }

    // ------------------------------------------------------------------
    // 主循环

    /** 由 {@code Porter} 在每个假人空闲时每 tick 调用 */
    public static void tick(MinecraftServer server, String botName) {
        Job j = JOBS.get(botName);
        if (j == null) {
            return;
        }
        if (!Body.enabled() || Body.get(server, botName) == null) {
            // 没有真身就没法「表演」，任务直接取消（没有 Carpet 时就是这条路）
            finish(server, botName, j, "（搬运工不在世界中，任务已取消）");
            return;
        }
        try {
            if (j.kind != TIDY) {
                // 理论上到不了这里（start 已经拒绝非 TIDY）；防御性收尾，别让任务永远卡在 JOBS 里
                finish(server, botName, j, "（该任务类型已下线）");
                return;
            }
            tidyTick(server, botName, j);
        } catch (Throwable t) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 搬运工 {} 执行任务出错: {}", botName, t.toString());
            finish(server, botName, j, "（发生错误，任务已中止）");
        }
    }

    private static void finish(MinecraftServer server, String botName, Job j, String why) {
        JOBS.remove(botName);
        releaseClaims(botName);
        // 有人收工（干完 / 到上限 / 出错 / 不在世界）后，同仓的同伴按实际人数重摊分片：
        // 收工那人若没干完自己那一份，剩下的箱子不能就这么留着没人管。
        rebalance(j.region);
        cleanup(server, botName, j);
        String report = report(j);
        WarehouseMod.LOGGER.info("[warehouse-keeper] 搬运工 {} {}结束{} — {}",
                botName, kindName(j.kind), why, report);
        ServerPlayer p = Body.get(server, botName);
        if (p != null) {
            p.sendSystemMessage(Component.literal("[搬运工] " + report));
        }
        Body.Home home = Body.standby(server, botName);
        if (home != null) {
            Body.moveTo(server, botName, home.level(), home.x(), home.y(), home.z());
        }
    }

    /**
     * 任务收尾（批次 1 · A1-c）：盖上还开着的箱盖、收掉副手那叠表演道具、把假人身上捡到的东西归仓、
     * 给被动过的仓库排一次局部重扫。正常结束（{@link #finish}）和 {@link #cancel} 取消都走这里 ——
     * 取消以前只做 {@code JOBS.remove}，结果东西留在假人身上、副手副本留在手上、整理过的箱子也不重扫。
     * <p>这里只做资源收尾，**不发整理报告**：取消不该再报一次「入库 N 件」。
     */
    private static void cleanup(MinecraftServer server, String botName, Job j) {
        if (server == null) {
            return;
        }
        closeLid(j); // 收工前把还开着的箱盖盖上（玩家看得见的那只手要干净）
        Body.hold(server, botName, ItemStack.EMPTY); // 顺手把「表演道具」收掉
        // 任务收尾时把身上捡到的物品也归仓：整理仓库本身不扫地面，但假人是真玩家，会自己捡起地上的物品
        dumpCarried(server, botName, j);
        if (j.dirty && !j.touched.isEmpty()) {
            // 局部重扫：只刷新被动过的仓库，别处的索引记录原样保留。
            // 扫描进行中不再把请求丢掉 —— 排进 IndexRefresh 的队列，等 Scanner 空闲了自动补上。
            IndexRefresh.markRegions(j.touched);
        }
    }

    public static String report(Job j) {
        String carried = j.stored > 0 ? "入库 " + j.stored + " 件。" : "";
        String tail = j.dropped > 0 ? j.dropped + " 件放不下，暂放地面。" : "";
        String skip = j.skipped > 0 ? "（跳过 " + j.skipped + " 步）" : "";
        long ms = Math.max(0L, (System.nanoTime() - j.startedNanos) / 1_000_000L);
        String cost = "用时 " + (ms / 1000) + " 秒。";
        String more = j.limited ? "到上限了，再执行一次继续。" : "";
        if (j.kind == TIDY) {
            // 0.23.0：把「箱内排序」与「跨箱归类」分开报 —— 用户看不到箱内排序动没动，
            // 就会以为「箱内排序没生效」。moved 是两者之和，sorted 只是其中一部分。
            int cross = Math.max(0, j.moved - j.sorted);
            String detail = "";
            if (j.sorted > 0 && cross > 0) {
                detail = "（箱内排序 " + j.sorted + " 件、跨箱归类 " + cross + " 件）";
            } else if (j.sorted > 0) {
                detail = "（都是箱内排序 " + j.sorted + " 件）";
            } else if (cross > 0) {
                detail = "（都是跨箱归类 " + cross + " 件）";
            }
            String tagged = j.tagMoves > 0 ? "其中 " + j.tagMoves + " 件按标签投箱。" : "";
            // 一件没搬时不能只报 0/0 —— 玩家会以为「功能坏了」。说清楚为什么没活可干
            String idle = "";
            if (j.merged == 0 && j.moved == 0 && j.stored == 0) {
                int boxes = tidyOrder(j.region).size();
                if (boxes <= 0) {
                    idle = "（这个仓库里没有扫到箱子）";
                } else if (taggedBoxes(j.region) == 0) {
                    idle = "（" + boxes + " 只箱子都没贴标签，假人不知道东西该放哪）";
                } else if (j.parts > 1) {
                    // 1.1.4：多假人分工时「本片没活」不等于「整仓没活」，免得被读成「整仓都已就位」
                    idle = "（本片没活；别的片可能还在干）";
                } else {
                    idle = "（" + boxes + " 只箱子都已就位，没有要改投的物品）";
                }
            }
            // 1.1.4：多假人分工时把「哪一片」写进报告，玩家一眼就知道这个假人负责的是哪一份
            String head = j.parts > 1 ? "整理完成（本片第 " + (j.part + 1) + "/" + j.parts + " 片）：" : "整理完成：";
            return head + "合并 " + j.merged + " 组，归位 " + j.moved + " 件" + detail + "。" + tagged + idle
                    + cost + skip + more + carried + tail;
        }
        // （优化4）现在只剩 TIDY 一种任务；万一将来有别的类型，也给一句不含清扫字样的通用文案
        return "任务完成。" + cost + skip + more + carried + tail;
    }

    /** 这个仓库里贴了标签的箱子数（报告用：区分「都已就位」和「没标签可指路」） */
    private static int taggedBoxes(String region) {
        int n = 0;
        for (ContainerRecord rec : tidyOrder(region)) {
            if (rec != null && ContainerTags.isTarget(WarehouseIndex.key(rec.dimension, rec.pos))) {
                n++;
            }
        }
        return n;
    }

    /**
     * 批次 6 · D5：把「整理到哪了」写成一行，给面板看（{@link #refreshSnapshot()} 与
     * {@code SnapshotSync} 都读它，所以面板上的忙碌文字会变成「整理 · 已整理 7/42 箱 · 剩约 62 秒」）。
     *
     * <p>整理是一只箱子一只箱子过的（{@link #tidyOrder(String)} 的顺序），所以「已整理 x/y 箱」是真实进度；
     * 剩余时间按「已经花的时间 ÷ 已整理箱数 × 剩下的箱数」估。第一只箱子还没过完时不给估算，免得乱猜。
     *
     * @return 空串 = 这个假人现在不是在整理（或者仓库里没有箱子）
     */
    public static String progressOf(String botName) {
        Job j = JOBS.get(botName);
        if (j == null || j.kind != TIDY) {
            return "";
        }
        List<ContainerRecord> order = tidyOrder(j.region);
        int total = order.size();
        if (total <= 0) {
            return "";
        }
        // 1.1.1：盯上箱子时就把下标记进 Job（focusIdx），这里不再重扫一遍整理顺序
        int idx = j.focus == null ? 0 : j.focusIdx;
        if (idx > total) {
            idx = total;
        }
        long ms = Math.max(0L, (System.nanoTime() - j.startedNanos) / 1_000_000L);
        String eta;
        if (idx >= 1 && idx < total) {
            long remain = (long) ((double) ms / idx * (total - idx));
            eta = " · 剩约 " + Math.max(0L, remain / 1000L) + " 秒";
        } else if (idx >= total) {
            eta = " · 快完了";
        } else {
            eta = "";
        }
        // 现在手上这只箱子（玩家照着这个坐标就能看到它在干活）
        String where = j.lidPos == null ? ""
                : " · 正在整理 " + j.lidPos.getX() + "," + j.lidPos.getY() + "," + j.lidPos.getZ();
        if (j.parts > 1) {
            // 多假人分工：既要能看出「我这一片还剩多少」，也要能看出「整仓到哪了」
            int inPart = 0;
            int doneInPart = 0;
            for (int i = 0; i < total; i++) {
                ContainerRecord rec = order.get(i);
                if (rec == null || rec.pos == null || !mine(j, WarehouseIndex.key(rec.dimension, rec.pos))) {
                    continue;
                }
                inPart++;
                if (i + 1 <= idx) {
                    doneInPart++;
                }
            }
            // 1.1.4：抢活时可能正在替别人那一片干活，标出来，免得「本片 x/y」看着对不上
            String help = j.focus != null && !mine(j, j.focus) ? "（在帮别人的片）" : "";
            return "本片 " + doneInPart + "/" + inPart + " 箱 · 全仓 " + idx + "/" + total + " 箱" + help + where + eta;
        }
        return "已整理 " + idx + "/" + total + " 箱" + where + eta;
    }

    /**
     * 进度「粗档」：每 5 只箱子算一档，只给 {@code SnapshotSync} 的变化指纹用。
     *
     * <p>进度一点都不进指纹，别的客户端就会一直看着「已整理 1/N 箱」不动；每次都进指纹，
     * 又会每个冷却周期都推一份可能很大的快照。折中成粗档：跨过 5 只箱子才认为变了。
     *
     * @return 档位（越大越接近干完）；-1 = 这个假人现在没在整理
     */
    public static int progressStage(String botName) {
        Job j = JOBS.get(botName);
        if (j == null || j.kind != TIDY) {
            return -1;
        }
        if (j.focus == null) {
            return 0;
        }
        return j.focusIdx / 5;
    }

    /**
     * 批次 6 · D4：整理预览（干跑 · 只读）。
     *
     * <p>按整理的<b>真实顺序</b>（{@link #tidyOrder(String)}：暂存箱优先，其余按索引里的箱子）走一遍，
     * 对每只箱子问一次 {@link #planIn} —— 这就是「现在按下整理，第一步会做什么」。
     * 全程<b>不移动任何物品</b>：不写索引、不派任务、不占用假人、不碰 {@code Homes}。
     *
     * <p>口径：整理是「搬完一轮再看下一轮」，所以这里每只箱子只算它<b>最先</b>会做的那一个动作 ⇒
     * 报出来的件数是<b>下限</b>，不是整轮清空后的总量。
     *
     * <p>批次 1 · A1-b：这是同步跑的命令，一次最多处理 {@link #PREVIEW_LIMIT} 只箱子
     * （区块没加载的直接跳过），超了就在结果里写明「本次只预览了前 N 只箱子」。
     */
    public static String preview(MinecraftServer server, String region) {
        long t0 = System.nanoTime();
        List<ContainerRecord> order = tidyOrder(region);
        if (order.isEmpty()) {
            return "整理预览：仓库「" + region + "」里没有箱子。";
        }
        Job probe = new Job(TIDY, region);
        int busy = 0;
        int idle = 0;
        int blind = 0;
        int merges = 0;
        long mergeItems = 0;
        int compacts = 0;
        int crosses = 0;
        long crossItems = 0;
        // 预览是同步命令（WarehouseCommand 直接把返回的字符串逐行发聊天栏），只能在本 tick 里跑完，
        // 所以这里必须封顶：整仓几百只箱子逐只 Scanner.mapOf 会强制加载区块，把主线程卡住数秒到数分钟。
        // 先挑还没到上限、再要求区块已加载，两个条件都不满足的箱子一律跳过、也不算「检查过」。
        int seen = 0;
        int unloaded = 0;
        boolean capped = false;
        for (ContainerRecord rec : order) {
            if (rec == null || rec.pos == null) {
                continue;
            }
            if (seen >= PREVIEW_LIMIT) {
                capped = true;
                break;
            }
            ServerLevel level = Scanner.levelOf(server, rec.dimension);
            if (level == null) {
                blind++;
                continue;
            }
            if (!level.isLoaded(rec.pos)) {
                unloaded++;
                continue;
            }
            seen++;
            if (!fullyReadable(level, rec)) {
                blind++;
                continue;
            }
            Move m = planIn(server, probe, rec);
            if (m == null) {
                idle++;
                continue;
            }
            busy++;
            long n = countAt(level, m);
            if (!m.sameContainer()) {
                crosses++;
                crossItems += n;
            } else if (m.toSlot() >= 0) {
                compacts++;
            } else {
                merges++;
                mergeItems += n;
            }
        }
        long ms = Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);
        StringBuilder sb = new StringBuilder();
        sb.append("整理预览 · 仓库「").append(region).append("」· 共 ").append(order.size()).append(" 只箱子");
        if (blind > 0) {
            sb.append("（其中 ").append(blind).append(" 只整箱读不到，跳过）");
        }
        if (capped) {
            sb.append("（本次只预览了前 ").append(seen).append(" 只箱子）");
        }
        if (unloaded > 0) {
            sb.append("（另有 ").append(unloaded).append(" 只所在区块未加载，已跳过）");
        }
        sb.append('\n');
        sb.append("第一步能动 ").append(busy).append(" 只箱子：合并 ").append(merges)
                .append(" 组（约 ").append(mergeItems).append(" 件）· 压实 ").append(compacts)
                .append(" 格 · 跨箱归位 ").append(crosses).append(" 处（约 ").append(crossItems).append(" 件）");
        sb.append('\n');
        sb.append("没活要干 ").append(idle).append(" 只 · 用时 ").append(ms).append(" ms\n");
        sb.append("说明：预览只算每只箱子「现在最先会做」的那一个动作（真正的整理会一轮轮接着做），件数是下限；预览不会移动任何物品。");
        return sb.toString();
    }

    /**
     * 这口箱子现在能不能整箱读到（有一格够不着就算读不到）。
     * {@link #planIn} 返回 null 有「没活」和「读不到」两种原因，预览要靠它把两者分开报。
     */
    private static boolean fullyReadable(ServerLevel level, ContainerRecord rec) {
        Scanner.SlotMap map = Scanner.mapOf(level, rec.pos);
        if (map == null || map.size() <= 0) {
            return false;
        }
        for (int i = 0; i < map.size(); i++) {
            if (map.at(i) == null) {
                return false;
            }
        }
        return true;
    }

    /** 预览里数「这一步会搬走几件」：读来源格现在真实的数量（只读，不动它） */
    private static long countAt(ServerLevel level, Move m) {
        Scanner.SlotMap map = Scanner.mapOf(level, m.from());
        if (map == null) {
            return 0;
        }
        Scanner.Slot open = map.at(m.slot());
        if (open == null) {
            return 0;
        }
        ItemStack s = open.container().getItem(open.index());
        return s == null || s.isEmpty() ? 0 : s.getCount();
    }

    // ------------------------------------------------------------------
    // 整理仓库

    private static void tidyTick(MinecraftServer server, String botName, Job j) {
        // 表演节奏（玩家反馈「假人都不动就整理好了」）：搬一件之间隔 tidyTicksPerMove 刻，
        // 每一刻最多真的搬一件，于是玩家能看见它换箱子、掀盖、挥手、把货挪走。
        // 注意：**只是把时间摊开**，每一步照样是「先算好、再原子地搬」，件数与守恒口径完全没变。
        int tick = server.getTickCount();
        if (tick < j.nextMoveTick) {
            return;
        }
        if (j.steps >= JOB_MOVES) {
            j.limited = true;
            finish(server, botName, j, "（已达件数上限）");
            return;
        }
        if (System.nanoTime() - j.startedNanos > JOB_NANOS) {
            j.limited = true;
            finish(server, botName, j, "（已达时间上限）");
            return;
        }
        Move m = findTidyMove(server, botName, j);
        if (m == null) {
            finish(server, botName, j, "");
            return;
        }
        ServerLevel lvl = Scanner.levelOf(server, m.fromDim());
        if (lvl == null) {
            j.dirty = false;
            finish(server, botName, j, "（来源箱子所在维度不存在）");
            return;
        }
        int pace = Math.max(1, AppConfig.get().tidyTicksPerMove);
        boolean lidOpenHere = j.lidLevel == lvl && m.from().equals(j.lidPos);
        if (!lidOpenHere) {
            // 换箱子了：先把上一只的盖子放下，再走到这只箱子前面，扭头看着它、把盖子掀起来。
            // 掀盖单独占半拍 —— 客户端要收到方块事件才播动画，立刻搬的话玩家只看到盖子闪一下。
            closeLid(j);
            double[] spot = Body.chestSpot(lvl, m.from());
            Body.moveToLook(server, botName, lvl, spot[0], spot[1], spot[2],
                    m.from().getX() + 0.5, m.from().getY() + 0.75, m.from().getZ() + 0.5);
            Body.chestLid(lvl, m.from(), true);
            j.lidLevel = lvl;
            j.lidPos = m.from().immutable();
            j.nextMoveTick = tick + Math.max(2, pace / 2);
            return;
        }
        // 每刻最多真的搬一件（见上面的节奏说明），所以这里不需要「一刻搬 N 件」的预算闸门了
        double[] spot = Body.chestSpot(lvl, m.from());
        Body.moveToLook(server, botName, lvl, spot[0], spot[1], spot[2],
                m.from().getX() + 0.5, m.from().getY() + 0.75, m.from().getZ() + 0.5);
        // 表演：把这一搬要动的那叠举在副手上（玩家看见它手里拿着货），挥一下手，再把这一搬做完。
        // 副手那格原版捡东西永远不用，所以「道具」和它真捡到的东西不会抢格子，也绝不会弄丢货。
        Body.hold(server, botName, propOf(lvl, m));
        Body.swing(server, botName);
        doTidyMove(server, j, m);
        if (m.tagged()) {
            // 批次 3 · J.6：这一件是「按箱子标签」送进指定目标箱的
            j.tagMoves++;
        }
        j.steps++;
        j.nextMoveTick = tick + pace;
    }

    /** 把现在开着的那只箱子盖上（换箱子 / 任务收尾都用它） */
    private static void closeLid(Job j) {
        if (j.lidLevel != null && j.lidPos != null) {
            Body.chestLid(j.lidLevel, j.lidPos, false);
        }
        j.lidLevel = null;
        j.lidPos = null;
    }

    /** 表演道具：这一搬要从来源箱里拿走的那叠东西（副本，只为了举给人看） */
    private static ItemStack propOf(ServerLevel level, Move m) {
        Scanner.SlotMap map = Scanner.mapOf(level, m.from());
        if (map == null) {
            return ItemStack.EMPTY;
        }
        Scanner.Slot s = map.at(m.slot());
        return s == null ? ItemStack.EMPTY : s.container().getItem(s.index()).copy();
    }

    /**
     * 找下一步该干什么：
     * <ol>
     *   <li>同一个箱子里能合并的同种零碎堆叠</li>
     *   <li>把整摞搬进前面的空格（压实：物品从第 1 格开始一个个排好，同种类的挨在一起）</li>
     *   <li>放错箱子的东西搬回它的「家」</li>
     * </ol>
     * 每次都现读容器的真实内容，所以中途被玩家动过也不会算错。
     *
     * <p>批次 1 · P4「按来源箱分组」：先用 {@link Job#focus} 把手上这个箱子的活一口气干完，
     * 这个箱子真没活了才重新扫一遍区域里的箱子。以前每搬一件都要把整仓的箱子从头读一遍（R10）。
     *
     * <p>批次 1 · R14：换箱子时先看{@link #CLAIMS 认领表}，别的假人正在整理的箱子直接跳过。
     */
    private static Move findTidyMove(MinecraftServer server, String botName, Job j) {
        if (j.focus != null) {
            Move m = planIn(server, j, WarehouseMod.INDEX.containers.get(j.focus));
            if (m != null) {
                return m;
            }
            // 1.1.1：这口箱子真干完了，**立刻把认领放掉**。以前要等收工才释放，多假人同仓时
            // 别的假人只能干等（它明明已经没活了）。放掉之后别人下一 tick 就能接手。
            releaseClaim(botName, j.focus);
            j.focus = null;
            j.focusIdx = 0;
        }
        List<ContainerRecord> order = tidyOrder(j.region);
        // 1.1.4 抢活（work stealing）：第 0 趟只碰自己那一片；自己这片全干完了，第 1 趟再去接管
        // 别人**还没开工**的箱子（已开工的由 CLAIMS 挡着，claimedByOther 会跳过，不会两人搬同一摞）。
        // 目的：脏活集中在少数几只箱子时，不至于「派了 4 个假人、只有 1 个有活干」。
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < order.size(); i++) {
                ContainerRecord rec = order.get(i);
                // 判空与同文件 taggedBoxes / tagRank 一个口径：整理顺序里理论上不会有 null，
                // 但真混进来一条也不该把整轮任务用 NPE 打断（那时会被 catch 吞成「任务莫名中止」）。
                if (rec == null || rec.pos == null) {
                    continue;
                }
                String key = WarehouseIndex.key(rec.dimension, rec.pos);
                // 1.1.1 多假人分工：第 0 趟里不归自己这一片的箱子直接跳过（别人的活先不碰）
                if (pass == 0 && !mine(j, key)) {
                    continue;
                }
                if (claimedByOther(botName, key)) {
                    // 批次 1 · R14：这口箱子已经有别的假人在整理了，跳过（否则两人会把同一摞搬两次）
                    continue;
                }
                Move m = planIn(server, j, rec);
                if (m != null) {
                    j.focus = key;
                    j.focusIdx = i + 1;
                    claim(botName, key);
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * 这只箱子归不归这个假人管（1.1.1 多假人分工）。
     *
     * <p>按来源箱 key 的哈希取模分片：同一次派单里每个假人 {@code part} 不同、{@code parts} 相同，
     * 所以箱子既不会漏（每人只管一片、合起来是全集）也不会重（一片只属于一个人）。
     * {@code parts <= 1} 时全部归自己，就是 1.1.1 之前「一个人干整仓」的行为。
     */
    private static boolean mine(Job j, String key) {
        if (j.parts <= 1 || key == null) {
            return true;
        }
        return Math.floorMod(key.hashCode(), j.parts) == j.part;
    }

    /** 只看这一个箱子能干什么（{@code rec} 为 null 就直接返回 null） */
    private static Move planIn(MinecraftServer server, Job j, ContainerRecord rec) {
        if (rec == null || rec.pos == null) {
            return null;
        }
        {
            ServerLevel level = Scanner.levelOf(server, rec.dimension);
            if (level == null) {
                return null;
            }
            // P0 建模（批次 1）：这个箱子只取一到两次 getBlockEntity，之后每一格直接用映射翻译。
            // 以前是「每格一次 openSlot」——每格都要 getChunk + getBlockEntity，双联箱后半还要再问搭档，
            // 搬一件物品就要上万次区块读取（每一步还会把所有箱子从头读一遍）
            Scanner.SlotMap map = Scanner.mapOf(level, rec.pos);
            if (map == null || map.size() <= 0) {
                return null;
            }
            List<ItemStack> items = new ArrayList<>(map.size());
            boolean unreachable = false;
            for (int i = 0; i < map.size(); i++) {
                Scanner.Slot open = map.at(i);
                if (open == null) {
                    // 这一格现在够不着（方块被拆了 / 搭档读不到）。**绝不能当成空格** ——
                    // 那样会把「看不见的物品」当成不存在，整理计划就会往不存在的格子上搬（A1-c）
                    unreachable = true;
                    break;
                }
                items.add(open.container().getItem(open.index()).copy());
            }
            if (unreachable) {
                return null;
            }
            // 批次 3（附录 J · B14「暂存箱只出不进」）：暂存箱不用做箱内合并/压实 ——
            // 里面的东西马上就要按标签送出去，箱内整理只会白搬。直接走 ③。
            boolean staging = isStaging(rec);
            // ① 箱内合并：找「一个没满的格 + 后面一个同种的非空格」
            for (int i = 0; !staging && i < items.size(); i++) {
                ItemStack a = items.get(i);
                if (a.isEmpty() || a.getCount() >= a.getMaxStackSize()) {
                    continue;
                }
                for (int k = i + 1; k < items.size(); k++) {
                    ItemStack b = items.get(k);
                    if (b.isEmpty() || !ItemStack.isSameItemSameComponents(a, b)) {
                        continue;
                    }
                    return new Move(rec.dimension, rec.pos, k, -1,
                            rec.dimension, rec.pos, ItemIds.of(b), true, false);
                }
            }
            // ② 箱内真正排序：按**创造模式物品栏的顺序**（优化2，见 CreativeOrder / sortKeysOf）把整箱排好，
            //    不在创造栏里的物品排在后面、并保持原来的拼音序。
            //    每只箱子留一个空格子当「暂存位」，用选择排序的走法推着搬：
            //      - 当前格本来就该放这一格的东西 ⇒ 看下一格
            //      - 当前格是空的 ⇒ 把「剩下那些里顺序最靠前的」整摞搬进来
            //      - 当前格被别的占着 ⇒ 先把它寄放到暂存位（下一轮再把该来的搬进来）
            //    搬运只往空格子写（doTidyMove 也会再确认一次），所以不会覆盖、不会丢东西；
            //    每搬一次「错位的摞数」都严格减少 ⇒ 一定会收敛，不会来回搬。
            //    容器满（一个空格都没有）时排不了 —— 交给 ③ 把它放错的东西搬走腾出空位，下一轮再排。
            if (!staging) {
                // （优化2）按创造栏顺序排：先把这一箱用到的排序键取出来缓存（一遍 O(n) map 查 + 拼音排序），
                // 后面选择排序只比 long，不会再出现 O(n²) 次 map 查 + 拼音比较。
                // ensure 幂等，建表只在第一次真正跑到这里时发生一次，之后只是一次 boolean 判断。
                CreativeOrder.ensure(server);
                Map<String, Long> keys = sortKeysOf(items);
                int park = -1;
                for (int k = items.size() - 1; k >= 0; k--) {
                    if (items.get(k).isEmpty()) {
                        park = k;
                        break;
                    }
                }
                for (int i = 0; park >= 0 && i < items.size(); i++) {
                    int best = -1;
                    String bestId = null;
                    for (int k = i; k < items.size(); k++) {
                        ItemStack s = items.get(k);
                        if (s.isEmpty()) {
                            continue;
                        }
                        String id = ItemIds.of(s);
                        if (bestId == null || compareKeys(keys, id, bestId) < 0) {
                            bestId = id;
                            best = k;
                        }
                    }
                    if (best < 0) {
                        break; // 后面没货了 ⇒ 前面这些就是排好的
                    }
                    if (best == i) {
                        continue; // 这一格本来就对
                    }
                    if (items.get(i).isEmpty()) {
                        return new Move(rec.dimension, rec.pos, best, i,
                                rec.dimension, rec.pos, bestId, true, false);
                    }
                    // 挡路的先寄放：暂存位永远在 i 后面（i 之前不可能留下空格子），所以不会把
                    // 已经排好的前缀搅乱，也就不会出现「搬过去又搬回来」的死循环
                    if (park > i) {
                        return new Move(rec.dimension, rec.pos, i, park,
                                rec.dimension, rec.pos, ItemIds.of(items.get(i)), true, false);
                    }
                    break;
                }
            }
            // ③ 放错箱子的搬回它的「家」
            for (int i = 0; i < items.size(); i++) {
                ItemStack s = items.get(i);
                if (s.isEmpty()) {
                    continue;
                }
                String id = ItemIds.of(s);
                // P2 主箱决策：先看记忆里的家（有位置就用），家满了或压根没记忆时，
                // 认索引里「存量最多的那个箱子」当新家（同类聚一起；第一次搬成功 Homes 就记住了）
                Spot home = pickHome(server, rec, id, s, j.region);
                if (home == null || home.pos() == null) {
                    continue;
                }
                if (home.dimension().equals(rec.dimension) && Scanner.sameChest(level, home.pos(), rec.pos)) {
                    continue; // 已经在家里了（含「家就是这箱子的另一半」）
                }
                // 批次 3 · J.6：目标箱贴了标签就记一笔（报告里报数用）
                boolean tagged = ContainerTags.isTarget(WarehouseIndex.key(home.dimension(), home.pos()));
                return new Move(rec.dimension, rec.pos, i, -1,
                        home.dimension(), home.pos, id, false, tagged);
            }
        }
        return null;
    }

    /**
     * P2 主箱决策：这种东西该放哪个箱子。
     *
     * <p>批次 3（附录 J.3/J.6）标签落地后的优先级：
     * <ol>
     *   <li>贴了标签的目标箱 —— 手动档箱的手动分类 &gt; 自动档箱的自动分类（见 {@link #taggedHome}）；
     *       一个标签都没贴时这一步恒为空，行为与改动前完全一致</li>
     *   <li>东西已经在「存量最多的那个箱子」里 —— 不动（那就是事实上的家，避免大搬迁）</li>
     *   <li>{@code Homes} 记着的家 —— 前提是真有空位</li>
     *   <li>家满了或没有记忆：存量最多、还装得下的那个箱子当新家（P2 的「该物品最多 &gt; 够装」）</li>
     * </ol>
     *
     * <p>候选只从索引里拿（不再把整仓箱子逐个打开问一遍，R12）；**绝不返回装不下的目标** ——
     * 那只会规划出「搬不进去」的空步，让任务在原地磨到上限。除非那个目标就是来源箱子本身
     * （上层会用 {@code sameChest} 判成「已经在家」而跳过，不会白搬）。
     *
     * <p><b>D5-b</b>：{@code region} 非空时，「家」的候选只认该值守仓库里的箱子 —— 假人绝不允许把
     * 自己区域里的东西搬到别人的仓库去（实机实测发现：roof 的假人整理时把 3 个圆石送进了 bench）。
     */
    private static Spot pickHome(MinecraftServer server, ContainerRecord from, String id, ItemStack stack, String region) {
        // 批次 3（附录 J.3/J.6）：先问「有没有贴了标签的目标箱」——
        // 手动档箱的手动分类 > 自动档箱的自动分类 > 下面这套老启发式。
        // 一个标签都没贴时 taggedHome 恒返回 null，行为与改动前完全一致（J.9 第 5 条回归）。
        Spot tagged = taggedHome(server, region, id, stack, from);
        if (tagged != null) {
            return tagged;
        }
        // 先把索引里「这种东西都在哪些箱子、各有多少」算出来（同一个箱子会出现在好几个槽位里）
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Spot> spots = new LinkedHashMap<>();
        WarehouseIndex.ItemEntry e = WarehouseMod.INDEX.items.get(id);
        if (e != null) {
            for (WarehouseIndex.SlotRef r : e.refs) {
                if (r.pos() == null) {
                    continue;
                }
                if (!inRegion(region, r.dimension(), r.pos())) {
                    continue; // D5-b：别人的仓库不配当「家」，连候选都不算
                }
                if (Containers.noTidy(r.blockId())) {
                    continue; // 0.23.0 · 优化5：熔炉 / 漏斗 / 雕纹书架等只可查看，绝不当目标箱
                }
                String k = WarehouseIndex.key(r.dimension(), r.pos());
                if (isStagingKey(k)) {
                    continue; // 批次 3 · B14：暂存箱「只出不进」，连候选都不算
                }
                counts.merge(k, (long) r.count(), Long::sum);
                spots.putIfAbsent(k, new Spot(r.dimension(), r.pos()));
            }
        }
        String fromKey = from == null ? null : WarehouseIndex.key(from.dimension, from.pos);
        // 存量最多的那个箱子就是「事实上的家」：东西已经在它里面就绝不往外搬。
        // 少了这条守卫，「家（另一个箱子）满了 + 这里只是一点零头」会退化成
        // 「把整箱 1664 件搬到那个零头箱子去」这种荒唐的大搬迁。
        String primary = null;
        long primaryCount = -1;
        for (Map.Entry<String, Long> en : counts.entrySet()) {
            if (en.getValue() > primaryCount) {
                primaryCount = en.getValue();
                primary = en.getKey();
            }
        }
        if (primary != null && primary.equals(fromKey)) {
            return new Spot(from.dimension, from.pos);
        }
        // ① 记忆里的家：有位置就搬回去
        Spot mem = homeOf(id);
        Spot memFixed = normalizeHome(server, id, mem);
        if (memFixed != null && inRegion(region, memFixed.dimension(), memFixed.pos())
                && !isStagingKey(WarehouseIndex.key(memFixed.dimension(), memFixed.pos()))
                && !noTidySpot(memFixed) // 0.23.0 · 优化5：记忆里的家落在熔炉 / 漏斗里也不认
                && hasRoom(server, memFixed, stack)) {
            return memFixed;
        }
        // ② 家满了 / 压根没有记忆：搬进「存量最多、还装得下」的那个箱子（P2 的「该物品最多 > 够装」）
        Spot best = null;
        long bestCount = -1;
        for (Map.Entry<String, Long> en : counts.entrySet()) {
            if (en.getValue() <= bestCount) {
                continue;
            }
            Spot cand = normalizeHome(server, id, spots.get(en.getKey()));
            if (cand == null || !hasRoom(server, cand, stack)) {
                continue; // 装不下的不配当新家
            }
            bestCount = en.getValue();
            best = cand;
        }
        if (best != null) {
            return best;
        }
        // ③ 实在没地方：只有「家就是来源箱子」时才还回去（上层判成「已经在家」而跳过），否则不动
        return homeIfSource(server, from, id, mem);
    }

    /**
     * 批次 3（附录 J.3/J.6/J.7）：这种东西有没有「贴了标签的目标箱」。
     *
     * <p><b>0.23.0 · BUG1</b>：候选箱先按「标签精确度」{@link Categories#matchRank} 排 ——
     * 与物品新页签键同键（0）&gt; 与物品旧中文类目名同名（1）&gt; 旧父类兜底（2）&gt; 暂存箱（3）。
     * 少了这一维，旧中文标签「酿造附魔」「矿物金属」都匹配附魔书，谁近谁赢，指定类目的空箱反而没人收。
     *
     * <p>同一精确度里：手动档箱的手动分类（2）&gt; 自动档箱的自动分类（1）&gt; 暂存箱兜底（0，只在分类是
     * 「其他」且确实没有别的目标箱时才允许 —— 暂存箱「只出不进」是 B14）。再往里按 B15
     * 「最集中 → 最近」排：先挑已经装着这种物品最多的箱子，再挑离来源最近的，最后按坐标 key
     * 定序（保证每一轮的结果一样，不会来回晃）。
     *
     * <p>已经待在本分类的目标箱里就原地返回（上层判成「已经在家」而跳过）—— 少了这条，
     * 「目标箱满了」会退化成「从这个满箱搬出去 → 它又有空位 → 再搬回来」的来回震荡。
     *
     * @return 目标坐标；没有贴标签的目标箱、或者都装不下时返回 {@code null}（上层继续用老启发式）
     */
    private static Spot taggedHome(MinecraftServer server, String region, String id, ItemStack stack, ContainerRecord from) {
        String category = Categories.of(id);
        if (category == null || category.isEmpty()) {
            return null;
        }
        String fromKey = from == null || from.pos == null ? null : WarehouseIndex.key(from.dimension, from.pos);
        if (fromKey != null) {
            ContainerTags.Tag own = ContainerTags.get(fromKey);
            if (own != null && !own.staging && Categories.matches(own.effectiveCategory(), category)) {
                return new Spot(from.dimension, from.pos);
            }
        }
        boolean allowStaging = CategoryRules.OTHER.equals(category); // J.7：只有「其他」才允许兜底进暂存箱
        Map<String, Long> counts = countsOf(id, region);
        List<TagCand> cands = new ArrayList<>();
        for (ContainerRecord rec : regionContainers(region)) {
            if (rec == null || rec.pos == null) {
                continue;
            }
            String key = WarehouseIndex.key(rec.dimension, rec.pos);
            ContainerTags.Tag tag = ContainerTags.get(key);
            if (tag == null) {
                continue;
            }
            int rank;
            if (tag.staging) {
                if (!allowStaging) {
                    continue; // B14：暂存箱只出不进
                }
                rank = STAGING_RANK; // 暂存箱排在所有真实分类之后
            } else {
                // 0.23.0 · BUG1：候选箱不只是「匹不匹配」，还要看「贴不贴切」——
                // 与物品新页签键同键(0) > 与物品旧中文类目名同名(1) > 旧父类兜底(2)
                rank = Categories.matchRank(tag.effectiveCategory(), id);
                if (rank < 0) {
                    continue;
                }
            }
            int prio = tag.staging ? 0 : (tag.isAutoMode() ? 1 : 2);
            cands.add(new TagCand(rank, prio, counts.getOrDefault(key, 0L), distanceSq(from, rec), key,
                    new Spot(rec.dimension, rec.pos)));
        }
        if (cands.isEmpty()) {
            return null;
        }
        cands.sort((a, b) -> {
            // 0.23.0 · BUG1：标签精确度最高优先（0 最精确），同精确度才比档位 / 集中度 / 距离
            int c = Integer.compare(a.rank(), b.rank());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(b.prio(), a.prio());
            if (c != 0) {
                return c;
            }
            c = Long.compare(b.count(), a.count());
            if (c != 0) {
                return c;
            }
            c = Long.compare(a.dist(), b.dist());
            if (c != 0) {
                return c;
            }
            return a.key().compareTo(b.key());
        });
        for (TagCand c : cands) {
            if (hasRoom(server, c.spot(), stack)) {
                return c.spot();
            }
        }
        return null;
    }

    /** 暂存箱的「标签精确度」：排在 0/1/2 之后，只有分类是「其他」时才轮得到（B14 / J.7） */
    private static final int STAGING_RANK = 3;

    /**
     * 标签候选箱（批次 3 · B15 排序用；0.23.0 · BUG1 起加「标签精确度」）：
     * {@link Categories#matchRank} 越小越对口（0 同键 / 1 与旧中文名同名 / 2 旧父类兜底 / 3 暂存箱），
     * 精确度相同再看档位（暂存 0 &lt; 自动 1 &lt; 手动 2），然后「最集中」，最后「最近」。
     */
    private record TagCand(int rank, int prio, long count, long dist, String key, Spot spot) {
    }

    /** 索引里这种东西在各箱子各有多少（只算本区域，D5-b），供 B15「最集中」用 */
    private static Map<String, Long> countsOf(String id, String region) {
        Map<String, Long> counts = new LinkedHashMap<>();
        WarehouseIndex.ItemEntry e = WarehouseMod.INDEX.items.get(id);
        if (e != null) {
            for (WarehouseIndex.SlotRef r : e.refs) {
                if (r.pos() == null || !inRegion(region, r.dimension(), r.pos())) {
                    continue;
                }
                counts.merge(WarehouseIndex.key(r.dimension(), r.pos()), (long) r.count(), Long::sum);
            }
        }
        return counts;
    }

    /** 两个箱子之间的距离平方（不同维度算「无穷远」） */
    private static long distanceSq(ContainerRecord a, ContainerRecord b) {
        if (a == null || b == null || a.pos == null || b.pos == null || !a.dimension.equals(b.dimension)) {
            return Long.MAX_VALUE;
        }
        long dx = (long) a.pos.getX() - b.pos.getX();
        long dy = (long) a.pos.getY() - b.pos.getY();
        long dz = (long) a.pos.getZ() - b.pos.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** 这口箱子贴的是「暂存」标签吗（批次 3 · B14） */
    private static boolean isStaging(ContainerRecord rec) {
        return rec != null && rec.pos != null && isStagingKey(WarehouseIndex.key(rec.dimension, rec.pos));
    }

    private static boolean isStagingKey(String key) {
        return key != null && ContainerTags.isStaging(key);
    }

    /**
     * 整理时先照顾暂存箱（批次 3 · B14「只出不进」）：先把暂存箱里的东西按标签分出去，
     * 再按索引顺序照顾别的箱子。一个暂存箱都没有时原样返回（与改动前完全一样）。
     */
    private static List<ContainerRecord> tidyOrder(String region) {
        List<ContainerRecord> all = regionContainers(region);
        List<ContainerRecord> staging = null;
        for (ContainerRecord rec : all) {
            if (isStaging(rec)) {
                if (staging == null) {
                    staging = new ArrayList<>();
                }
                staging.add(rec);
            }
        }
        if (staging == null || staging.size() == all.size()) {
            return all;
        }
        for (ContainerRecord rec : all) {
            if (!isStaging(rec)) {
                staging.add(rec);
            }
        }
        return staging;
    }

    /**
     * 归仓（{@link #parkInWarehouse}）排序用：贴了本分类标签的箱子最优先，没标签的次之，
     * 暂存箱垫底（B14：只出不进，实在没地方才往里放）。
     */
    private static int tagRank(ContainerRecord rec, String category) {
        if (rec == null || rec.pos == null) {
            return 1;
        }
        ContainerTags.Tag tag = ContainerTags.get(WarehouseIndex.key(rec.dimension, rec.pos));
        if (tag == null) {
            return 1;
        }
        if (tag.staging) {
            return 2;
        }
        return category != null && Categories.matches(tag.effectiveCategory(), category) ? 0 : 1;
    }

    /** 把「家」的坐标归一化（双联箱的另一半也算同一个箱子），顺手纠正老档案里记的另一半坐标 */
    private static Spot normalizeHome(MinecraftServer server, String id, Spot home) {
        if (home == null || home.pos() == null) {
            return null;
        }
        ServerLevel level = Scanner.levelOf(server, home.dimension());
        if (level == null) {
            return null;
        }
        // chunk 没加载时直接返回记忆里的坐标：canonical() 内部走 partnerLoaded，会强载区块，
        // 而归一化只是「顺手纠正老档案」的锦上添花，不值得为它把区块拉起来。
        if (!level.isLoaded(home.pos())) {
            return new Spot(home.dimension(), home.pos());
        }
        BlockPos pos = Scanner.canonical(level, home.pos());
        if (!pos.equals(home.pos())) {
            Homes.remember(id, WarehouseIndex.key(home.dimension(), pos));
        }
        return new Spot(home.dimension(), pos);
    }

    /** 记忆里的家就是来源这个箱子时还回去（上层判成「已经在家」而跳过）；否则 null，不产生空步 */
    private static Spot homeIfSource(MinecraftServer server, ContainerRecord from, String id, Spot mem) {
        Spot fixed = normalizeHome(server, id, mem);
        if (fixed == null || from == null) {
            return null;
        }
        if (fixed.dimension().equals(from.dimension) && fixed.pos().equals(from.pos)) {
            return fixed;
        }
        return null;
    }

    /**
     * 这个坐标上的箱子是不是「可查看、但不整理」的容器（0.23.0 · 优化5）。
     *
     * <p>直接查索引里的 {@link ContainerRecord#blockId}，不去读世界方块：整理路径每 tick 都会问，
     * 不能为它碰区块。索引里没有这条记录（还没扫到）就当作可整理。
     */
    private static boolean noTidySpot(Spot spot) {
        if (spot == null || spot.pos() == null) {
            return false;
        }
        ContainerRecord rec = WarehouseMod.INDEX.containers.get(
                WarehouseIndex.key(spot.dimension(), spot.pos()));
        return rec != null && Containers.noTidy(rec.blockId);
    }

    /** 目标箱子还有没有位置放这件东西 */
    private static boolean hasRoom(MinecraftServer server, Spot spot, ItemStack stack) {
        if (spot == null || spot.pos() == null) {
            return false;
        }
        ServerLevel level = Scanner.levelOf(server, spot.dimension());
        return level != null && hasRoom(level, spot.pos(), stack);
    }

    /** 执行一步计划 */
    private static void doTidyMove(MinecraftServer server, Job j, Move m) {
        ServerLevel level = Scanner.levelOf(server, m.fromDim());
        if (level == null) {
            return;
        }
        Scanner.SlotMap from = Scanner.mapOf(level, m.from());
        if (from == null) {
            return;
        }
        Scanner.Slot src = from.at(m.slot());
        if (src == null) {
            return;
        }
        Container c = src.container();
        ItemStack stack = c.getItem(src.index());
        if (stack.isEmpty()) {
            return;
        }
        if (m.sameContainer() && m.toSlot() >= 0) {
            // 压实：整摞搬到同一个箱子里的空格上（只往空格搬，所以不会覆盖东西，也一定会收敛）
            Scanner.Slot dst = from.at(m.toSlot());
            if (dst == null) {
                return;
            }
            if (!dst.container().getItem(dst.index()).isEmpty()) {
                return;
            }
            int n = stack.getCount();
            dst.container().setItem(dst.index(), stack.copy());
            dst.container().setChanged();
            ItemStack after = dst.container().getItem(dst.index());
            if (after.isEmpty() || after.getCount() != n || !ItemIds.of(after).equals(ItemIds.of(stack))) {
                dst.container().setItem(dst.index(), ItemStack.EMPTY);
                dst.container().setChanged();
                return;
            }
            c.setItem(src.index(), ItemStack.EMPTY);
            c.setChanged();
            j.moved += n;
            j.sorted += n; // 0.23.0：同箱内把这一摞搬到该在的格子，是「箱内排序」，与跨箱归类分开报数
            j.dirty = true;
            j.touch(m.fromDim(), m.from());
            return;
        }
        if (m.sameContainer()) {
            // 箱内合并：把这一格往前面那个没满的同种格里叠
            int size = from.size();
            for (int i = 0; i < size; i++) {
                if (i == m.slot()) {
                    continue;
                }
                Scanner.Slot dst = from.at(i);
                if (dst == null) {
                    continue;
                }
                ItemStack d = dst.container().getItem(dst.index());
                if (d.isEmpty() || !ItemStack.isSameItemSameComponents(d, stack)) {
                    continue;
                }
                int room = d.getMaxStackSize() - d.getCount();
                if (room <= 0) {
                    continue;
                }
                int move = Math.min(room, stack.getCount());
                ItemStack next = d.copy();
                next.grow(move);
                dst.container().setItem(dst.index(), next);
                dst.container().setChanged();
                // 回读校验（Iron Chests 会「消毒」写入的 ItemStack）
                ItemStack after = dst.container().getItem(dst.index());
                if (after.isEmpty() || after.getCount() != d.getCount() + move) {
                    dst.container().setItem(dst.index(), d);
                    dst.container().setChanged();
                    continue;
                }
                stack.shrink(move);
                if (stack.isEmpty()) {
                    c.setItem(src.index(), ItemStack.EMPTY);
                } else {
                    c.setItem(src.index(), stack);
                }
                c.setChanged();
                j.merged++;
                j.dirty = true;
                j.touch(m.fromDim(), m.from());
                return;
            }
            return;
        }
        // 跨箱子搬：先写进目标（putInto 会回读校验），再把来源清掉
        ServerLevel tl = Scanner.levelOf(server, m.toDim());
        if (tl == null) {
            return;
        }
        ItemStack copy = stack.copy();
        int before = copy.getCount();
        // A1-e 同格保护：目标和来源如果是**同一个物理箱子**（双联箱的另一半也算同一个），
        // 就绝不能走「先写目标、再清来源」—— 槽号是镜像的，物品会被写回来源那一格，
        // 之后来源被清空，那一摞物品就凭空消失了。这里直接跳过，并把「家」纠正到归一化坐标。
        if (m.fromDim().equals(m.toDim()) && Scanner.sameChest(tl, m.from(), m.to())) {
            j.skipped++;
            Homes.remember(m.itemId(), WarehouseIndex.key(m.toDim(), Scanner.canonical(tl, m.to())));
            return;
        }
        if (!Porter.putInto(tl, m.to(), copy)) {
            return;
        }
        int left = copy.getCount();
        int moved = before - left;
        if (moved <= 0) {
            return;
        }
        if (left <= 0) {
            c.setItem(src.index(), ItemStack.EMPTY);
        } else {
            ItemStack rest = stack.copy();
            rest.setCount(left);
            c.setItem(src.index(), rest);
        }
        c.setChanged();
        j.moved += moved;
        j.dirty = true;
        j.touch(m.fromDim(), m.from());
        j.touch(m.toDim(), m.to());
        Homes.remember(m.itemId(), WarehouseIndex.key(m.toDim(), Scanner.canonical(tl, m.to())));
    }

    // ------------------------------------------------------------------

    /**
     * 把假人身上捡到的东西送回仓库。
     *
     * <p>顺序：①先送各自的「家」（{@link Homes} 记住的那个箱子）；②家里塞不下，或者这种物品
     * 压根没记过「家」（比如地上捡到的、从没被索引过的东西）→ 在本次任务涉及的区域里找<b>最近的、
     * 还有空位</b>的箱子放进去，并顺手记住这个新「家」；③真的一格空位都没有，才掉在它脚下，
     * 同时记进 {@code j.dropped} 并在报告里说清楚。绝不凭空消失，也绝不「明明有空箱子却丢地上」。
     */
    private static void dumpCarried(MinecraftServer server, String botName, Job j) {
        ServerPlayer bot = Body.get(server, botName);
        if (bot == null) {
            return;
        }
        var inv = bot.getInventory();
        int held = Body.heldSlot(server, botName);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == held) {
                continue;
            }
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            int had = s.getCount();
            String id = ItemIds.of(s);
            Spot home = homeOf(id);
            if (home != null && home.pos() != null) {
                ServerLevel level = Scanner.levelOf(server, home.dimension());
                // 「家」必须用归一化坐标：双联箱的另一半是同一个箱子，用镜像坐标会写错格子（A1-d）。
                // 但 chunk 没加载时不能调 canonical —— 它会强载区块（hasRoom 那边也会直接判 false）。
                BlockPos homePos = level == null || !level.isLoaded(home.pos())
                        ? home.pos()
                        : Scanner.canonical(level, home.pos());
                if (level != null && hasRoom(level, homePos, s)) {
                    Body.moveTo(server, botName, level,
                            homePos.getX() + 0.5, homePos.getY() + 1, homePos.getZ() + 0.5);
                    Porter.putInto(level, homePos, s);
                    if (!s.isEmpty()) {
                        j.touch(home.dimension(), homePos);
                        j.dirty = true;
                    }
                }
            }
            if (!s.isEmpty()) {
                ContainerRecord rec = parkInWarehouse(server, bot, botName, s, j.region);
                if (rec != null) {
                    ServerLevel rl = Scanner.levelOf(server, rec.dimension);
                    BlockPos rp = rl == null || !rl.isLoaded(rec.pos) ? rec.pos : Scanner.canonical(rl, rec.pos);
                    Homes.remember(id, WarehouseIndex.key(rec.dimension, rp));
                    j.touch(rec.dimension, rp);
                    j.dirty = true;
                }
            }
            // 先记账「放进箱子多少件」，再处理真的放不下的（否则会把丢地上的也算成归仓）
            j.stored += had - s.getCount();
            if (!s.isEmpty()) {
                j.dropped += s.getCount();
                net.minecraft.world.Containers.dropItemStack(bot.level(),
                        bot.getX(), bot.getY() + 0.5, bot.getZ(), s.copy());
                s.setCount(0);
            }
            inv.setItem(i, ItemStack.EMPTY);
        }
        inv.setChanged();
    }

    /**
     * 在仓库区域里给这一叠东西找个还有空位的箱子：优先假人当前维度，同维度按距离由近到远。
     *
     * <p>找到了就把假人挪过去、把东西放进去，返回用到的那个箱子；一个空位都没有返回 {@code null}。
     * 给 {@link Porter#flushBody} 之类的「假人要把身上东西交出来」的场景复用。
     */
    public static ContainerRecord parkInWarehouse(MinecraftServer server, ServerPlayer bot,
                                                 String botName, ItemStack s, String region) {
        if (bot == null || s.isEmpty()) {
            return null;
        }
        String here = bot.level().dimension().identifier().toString();
        // 批次 3 · J.6：归仓也认标签 —— 贴了本分类标签的箱子最优先，暂存箱垫底（B14 只出不进）
        String category = Categories.of(ItemIds.of(s));
        List<ContainerRecord> list = new ArrayList<>(regionContainers(region));
        list.sort(Comparator
                .comparingInt((ContainerRecord rec) -> tagRank(rec, category))
                .thenComparingInt(rec -> rec.dimension.equals(here) ? 0 : 1)
                .thenComparingDouble(rec -> bot.distanceToSqr(
                        rec.pos.getX() + 0.5, rec.pos.getY() + 0.5, rec.pos.getZ() + 0.5)));
        if (list.size() > PARK_LIMIT) {
            // 限量归仓（批次 1 · A1-b）：整仓可能有几百只箱子，下面每只都要 Scanner.canonical / hasRoom，
            // 两者第一件事都是强制加载区块，在服务端 tick 线程里循环整仓会把主线程卡住。上面的排序已经把
            // 「贴了本分类标签的、同维度的、离假人近的」排在前面，这里只留前 N 只当候选。
            list = list.subList(0, PARK_LIMIT);
        }
        for (ContainerRecord rec : list) {
            ServerLevel level = Scanner.levelOf(server, rec.dimension);
            if (level == null) {
                continue;
            }
            if (!level.isLoaded(rec.pos)) {
                // 区块没加载就跳过（不在这里强载）：能看见的空位才算空位
                continue;
            }
            // 双联箱还要看搭档那一半所在区块加载了没：下面 canonical 走的是 partnerLoaded（会强载区块），
            // 这里先用不加载的 partnerOf 看一眼，把「顺手把区块拉起来」挡在门外
            BlockPos other = Scanner.partnerOf(level, rec.pos, level.getBlockState(rec.pos));
            if (other != null && !level.isLoaded(other)) {
                continue;
            }
            // 一律用归一化坐标寻址：双联箱只认「坐标靠前的那一半」，否则槽号会镜像错位（A1-a）
            BlockPos pos = Scanner.canonical(level, rec.pos);
            if (!hasRoom(level, pos, s)) {
                continue;
            }
            Body.moveTo(server, botName, level,
                    pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            Porter.putInto(level, pos, s);
            if (s.isEmpty()) {
                return rec;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 快照 / 小工具

    /** 假的（物品该放哪个箱子） */
    private record Spot(String dimension, BlockPos pos) {
    }

    private static Spot homeOf(String id) {
        Homes.Spot spot = Homes.spot(id);
        if (spot != null && spot.pos() != null) {
            return new Spot(spot.dimension(), spot.pos());
        }
        WarehouseIndex.ItemEntry e = WarehouseMod.INDEX.items.get(id);
        if (e != null && !e.refs.isEmpty()) {
            WarehouseIndex.SlotRef r = e.refs.get(0);
            return new Spot(r.dimension(), r.pos());
        }
        return null;
    }

    private static List<Region> regionsOf(String name) {
        List<Region> out = new ArrayList<>();
        if (name == null || name.isEmpty()) {
            out.addAll(RegionStore.REGIONS.values());
        } else {
            Region r = RegionStore.REGIONS.get(name);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * 这个坐标是不是落在给定仓库区域里。
     *
     * <p>{@code region} 为空 = 「不限区域」（整仓任务），一律通过；非空时名字对应的区域不存在也当不通过。
     * D5-b 用它把「整理/归位」的目的地锁在假人自己值守的仓库内。
     */
    private static boolean inRegion(String region, String dimension, BlockPos pos) {
        if (region == null || region.isEmpty()) {
            return true;
        }
        if (pos == null) {
            return false;
        }
        for (Region r : regionsOf(region)) {
            if (Scanner.dimensionOf(r).equals(dimension) && r.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private static List<ContainerRecord> regionContainers(String name) {
        List<Region> regions = regionsOf(name);
        List<ContainerRecord> out = new ArrayList<>();
        for (ContainerRecord rec : WarehouseMod.INDEX.containers.values()) {
            if (rec == null || rec.pos == null) {
                continue;
            }
            if (Containers.noTidy(rec.blockId)) {
                continue; // 0.23.0 · 优化5：熔炉 / 漏斗 / 雕纹书架等「可查看但不整理」
            }
            for (Region r : regions) {
                if (Scanner.dimensionOf(r).equals(rec.dimension) && r.contains(rec.pos)) {
                    out.add(rec);
                    break;
                }
            }
        }
        // 防呆：双联箱的搭档坐标上如果还留着一条旧记录（拼箱、区域边界、老索引从磁盘恢复），
        // 它就是同一个物理箱子的第二条记录。留着会被整理当成「另一个小箱子」，
        // 于是规划出「从大箱子搬到大箱子另一半」。这里只保留合并的那一条。
        Set<String> mergedPartnerKeys = new HashSet<>();
        for (ContainerRecord rec : out) {
            if (rec.partner != null) {
                mergedPartnerKeys.add(WarehouseIndex.key(rec.dimension, rec.partner));
            }
        }
        if (!mergedPartnerKeys.isEmpty()) {
            out.removeIf(rec -> mergedPartnerKeys.contains(WarehouseIndex.key(rec.dimension, rec.pos)));
        }
        // 固定顺序：每次从同一个箱子开始挑，行为可预期（也免得来回打转）
        out.sort((a, b) -> {
            int c = a.dimension.compareTo(b.dimension);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.pos.getX(), b.pos.getX());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.pos.getY(), b.pos.getY());
            return c != 0 ? c : Integer.compare(a.pos.getZ(), b.pos.getZ());
        });
        return out;
    }

    private static boolean hasRoom(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            // 没加载的箱子当「放不下」（批次 1 · A1-b）：Scanner.mapOf 第一行会强制加载区块，
            // 归仓/找家这些路径都是成批试箱子的，不能靠它顺手把区块拉起来。
            return false;
        }
        Scanner.SlotMap map = Scanner.mapOf(level, pos);
        if (map == null) {
            return false;
        }
        int size = map.size();
        for (int i = 0; i < size; i++) {
            Scanner.Slot t = map.at(i);
            if (t == null) {
                continue;
            }
            ItemStack dst = t.container().getItem(t.index());
            if (dst.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(dst, stack) && dst.getCount() < dst.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /** 假人身上拿着的东西（给指令/网页看） */
    public static List<String> carrying(MinecraftServer server, String botName) {
        ServerPlayer bot = Body.get(server, botName);
        if (bot == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        var inv = bot.getInventory();
        int held = Body.heldSlot(server, botName);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == held) {
                continue;
            }
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                out.add(Names.item(ItemIds.of(s)) + " x" + s.getCount());
            }
        }
        return out;
    }

    /** tick 线程刷新网页/GUI 看的任务快照 */
    public static void refreshSnapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Job> en : JOBS.entrySet()) {
            Job j = en.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bot", en.getKey());
            m.put("kind", kindName(j.kind));
            m.put("region", j.region);
            m.put("steps", j.steps);
            // 1.1.1 多假人分工：面板要能显示「第 2/3 片」，也让客户端自己算出这个假人管哪一片
            m.put("part", j.part);
            m.put("parts", j.parts);
            m.put("progress", report(j));
            out.add(m);
        }
        SNAP = List.copyOf(out);
    }
}
