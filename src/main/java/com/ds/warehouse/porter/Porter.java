package com.ds.warehouse.porter;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.Region;
import com.ds.warehouse.config.RegionStore;
import com.ds.warehouse.index.ItemIds;
import com.ds.warehouse.index.IndexRefresh;
import com.ds.warehouse.index.Scanner;
import com.ds.warehouse.index.WarehouseIndex;
import com.ds.warehouse.util.Audit;
import com.ds.warehouse.util.Names;
import com.ds.warehouse.web.WebSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 搬运工 —— 功能三的服务端实现。
 *
 * <p>刻意**不做成真实体**：没有假玩家、没有寻路、没有自定义数据包。
 * 它就是一个跑在服务端 tick 线程上的状态机，做三件事：
 * <ol>
 *   <li>接到订单 → 去仓库对应箱子把那叠东西取出来（直接操作 {@link Container} 接口，不模拟开箱 GUI）</li>
 *   <li>「走」到下单的人身边（纯表演：粒子 + 十几 tick 延迟）</li>
 *   <li>把东西塞进他的背包；背包满就掉在他脚下并如实告诉他</li>
 * </ol>
 *
 * <p><b>线程约定</b>：除了 {@link #submit} 与几个读状态的访问器，所有字段只在服务端 tick
 * 线程上改。网页线程看不到索引（索引是可变 HashMap），所以网页下单只把「要什么」原样排队，
 * 由 tick 线程去解析。
 *
 * <p><b>存档安全</b>：全程不新增任何注册项、不写 world/。从箱子里取出来的东西，
 * 只要还没交到玩家手上，就一定记在 {@link #carried} 里；出错/超时/关服都会放回原箱子。
 */
public final class Porter {

    // ---- 常量 ----
    /** 一次下单最多要多少 */
    public static final int MAX_ORDER = 4096;
    /** 最多积压多少订单 */
    public static final int MAX_PENDING = 20;
    /** 最近完成的订单保留多少条 */
    public static final int MAX_HISTORY = 30;
    /** 「走过去 / 走回来」各花多少 tick（纯表演，12 tick = 0.6 秒） */
    private static final int TRAVEL_TICKS = 12;
    /** 下单的人中途下线，最多等他多久（tick），超时就把东西放回原箱子 */
    private static final int WAIT_TICKS = 20 * 120;

    private static final int STEP_OUT = 1;
    private static final int STEP_TAKE = 2;
    private static final int STEP_BACK = 3;
    private static final int STEP_GIVE = 4;
    /** 交完货在玩家面前多站一会儿（tick），让他看清是谁送来的 */
    private static final int STEP_SHOW = 5;
    private static final int SHOW_TICKS = 30;

    /** 一件正在搬运的东西。记住它从哪来，好在出意外时原样放回去。 */
    private record Carry(ItemStack stack, String dimension, BlockPos pos) {
    }

    /** 网页线程递进来的原始请求（还没解析物品名） */
    private record Request(String playerName, String query, int count) {
    }

    /** 一条取货订单 */
    public static final class Order {
        public final String playerName;
        public final UUID playerId;
        public final String itemId;
        public final String displayName;
        public final int count;
        public final long createdAt = System.currentTimeMillis();

        public String state = "排队中";
        public int taken;
        public String note = "";
        public String sourceText = "";
        /**
         * 0.23.0：附魔筛选（取货页「按附魔分行」下单时才有货）。
         *
         * <p>格式与 {@code ViewQueryService} 的 {@code ench} 字段一致：{@code 注册名@等级} 用 {@code ,}
         * 连接；空串 = 不限附魔（老行为）。取货时逐格比对这一格的附魔组合，对不上就跳过。
         */
        public String ench = "";
        /** 接这单的假人名字（还没派出去就是空串） */
        public String botName = "";
        public long finishedAt;
        public boolean failed;
        public boolean done;

        int step = STEP_OUT;

        Order(String playerName, UUID playerId, String itemId, String displayName, int count) {
            this.playerName = playerName;
            this.playerId = playerId;
            this.itemId = itemId;
            this.displayName = displayName;
            this.count = count;
        }

        public String summary() {
            return playerName + " 请求 " + count + " 个 " + displayName;
        }

        public int elapsedMs() {
            return (int) ((finishedAt > 0 ? finishedAt : System.currentTimeMillis()) - createdAt);
        }
    }

    // ---- 状态 ----
    /** 网页线程 → tick 线程的入口 */
    private static final ConcurrentLinkedQueue<Request> INCOMING = new ConcurrentLinkedQueue<>();
    /** 等待派送的订单（只在 tick 线程上动） */
    private static final ArrayDeque<Order> PENDING = new ArrayDeque<>();
    /** 历史记录（网页线程会读，所以用写时复制表） */
    private static final List<Order> HISTORY = new CopyOnWriteArrayList<>();

    /** 在线玩家名快照：tick 线程写、网页线程读（网页没有玩家身份，下单必须选「送给谁」）。 */
    private static volatile List<String> ONLINE = List.of();

    /** 在线玩家里的管理员名字（快照，网页线程读） */
    private static volatile Set<String> OPS = Set.of();
    /** 人形状态的只读快照（网页线程读，tick 线程每 10 tick 刷一次） */
    private static volatile List<Map<String, Object>> BODY_SNAPS = List.of();
    private static int bodySnapTicks;
    /** 「家」记忆落盘的节流计时（tick） */
    private static int homesSaveTicks;

    /** 上次把索引学进 {@link Homes} 时的索引版本号（只在 tick 线程读写） */
    private static long homesRevision = -1;

    private static volatile Order active;
    /** 正在执行这一单的假人名字（空 = 还没派出去） */
    private static String activeBotName = "";
    private static final List<Carry> carried = new ArrayList<>();
    private static final List<Region> touchedRegions = new ArrayList<>();
    private static List<WarehouseIndex.SlotRef> refs = List.of();
    private static int refIndex;
    private static int need;
    private static int phase;
    private static int waited;

    private Porter() {
    }

    // ------------------------------------------------------------------
    // 下单

    /**
     * 游戏内下单（tick 线程调用，能立刻回错误文字）。
     *
     * @return null = 收下了；否则是要给玩家看的原因
     */
    public static String request(ServerPlayer player, String query, int count) {
        // 0.23.0：`<物品>#<附魔组合>` = 只取这个附魔变体（取货页按附魔分行时点哪行就填这个）
        String ench = "";
        if (query != null) {
            int hash = query.lastIndexOf('#');
            if (hash > 0 && hash < query.length() - 1) {
                ench = query.substring(hash + 1).trim();
                query = query.substring(0, hash).trim();
            }
        }
        WarehouseIndex.ItemEntry entry = resolve(query);
        if (entry == null) {
            return WarehouseMod.INDEX.items.isEmpty()
                    ? "仓库索引为空，请先执行 /warehouse scan。"
                    : "仓库中没有「" + query + "」这件物品。";
        }
        return enqueue(player.getName().getString(), player.getUUID(), entry, count, ench);
    }

    /** 网页下单：物品名由 tick 线程解析（HTTP 线程不碰可变的索引） */
    public static void submit(String playerName, String query, int count) {
        INCOMING.add(new Request(playerName, query, count));
    }

    private static String enqueue(String playerName, UUID playerId, WarehouseIndex.ItemEntry entry, int count,
                                  String ench) {
        if (PENDING.size() >= MAX_PENDING) {
            return "还有 " + PENDING.size() + " 个订单未完成，请稍后再下单。";
        }
        if (entry.total <= 0) {
            return "仓库中已没有「" + entry.displayName + "」。";
        }
        // F-D5-1：这单货所在仓库没有任何假人值守时**当场**说清楚，
        // 不要先回「已下单」再在 tick 里异步失败（玩家会在 /warehouse porter 里才发现）
        List<String> where = itemRegions(entry);
        if (where.isEmpty()) {
            return "「" + entry.displayName + "」不在任何仓库范围内，无法配送。";
        }
        if (!anyBotOn(where)) {
            return "仓库中「" + entry.displayName + "」位于 " + String.join("、", where)
                    + "，但没有假人值守该仓库。请先使用 /warehouse bot assign <假人> " + where.get(0)
                    + " 指定后再下单。";
        }
        int want = count <= 0 ? 1 : Math.min(count, MAX_ORDER);
        String clamped = "";
        if (want > entry.total) {
            want = (int) Math.min(entry.total, MAX_ORDER);
            clamped = "（仓库中仅剩这些）";
        }
        Order o = new Order(playerName, playerId, entry.itemId, entry.displayName, want);
        o.ench = ench == null ? "" : ench;
        PENDING.addLast(o);
        WarehouseMod.LOGGER.info("[warehouse-keeper] 取货订单: {} 请求 {} 个 {} {}{}", playerName, want,
                entry.displayName, clamped, ench == null || ench.isEmpty() ? "" : "（附魔 " + ench + "）");
        return null;
    }

    /** 把一个玩家输入解析成索引里的某个物品（完全照搬 /warehouse find 的规则） */
    public static WarehouseIndex.ItemEntry resolve(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String id = ItemIds.resolve(query);
        if (id != null) {
            WarehouseIndex.ItemEntry e = WarehouseMod.INDEX.items.get(id);
            if (e != null) {
                return e;
            }
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        WarehouseIndex.ItemEntry best = null;
        for (WarehouseIndex.ItemEntry e : WarehouseMod.INDEX.items.values()) {
            // displayName 是建索引时算好的：专用服务端的 Language 多半是 en_us，那时还没有客户端推来的名字表，
            // 所以缓存里是英文名 —— 中文搜索必须再用 Names.item() 实时取一次（它会优先查客户端推的名字）。
            String live = Names.item(e.itemId);
            if (e.itemId.toLowerCase(Locale.ROOT).contains(q)
                    || e.displayName.toLowerCase(Locale.ROOT).contains(q)
                    || live.toLowerCase(Locale.ROOT).contains(q)) {
                if (best == null || e.total > best.total) {
                    best = e;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 主循环

    /** 进存档时把名册里的假人都放出来（各自待在值守点） */
    public static void ensureBodies(MinecraftServer server) {
        if (!Body.enabled()) {
            return;
        }
        for (Bots.Entry e : Bots.list()) {
            Body.Home home = Body.standby(server, e.name);
            if (home != null) {
                Body.ensure(server, e.name, home.level(), home.x(), home.y(), home.z());
            }
        }
    }

    public static void tick(MinecraftServer server) {
        refreshOnline(server);
        // 索引一变就顺手学一遍「哪种物品放在哪个箱子」（很便宜，且只增不减）
        if (WarehouseMod.INDEX.revision() != homesRevision) {
            homesRevision = WarehouseMod.INDEX.revision();
            Homes.learn(WarehouseMod.INDEX);
        }
        // 记忆改动后最多 5 秒就落一次盘。只在关服时写是不够的：
        // 一旦崩了或被强杀，刚记住的「家」就白记了。
        if (Homes.isDirty() && ++homesSaveTicks >= 100) {
            homesSaveTicks = 0;
            Homes.save();
        }
        drainIncoming();

        if (active == null) {
            startNext(server);
        }
        // 送货的那个假人去做订单，其他假人各自待命 / 干活
        for (Bots.Entry e : Bots.list()) {
            if (active != null && e.name.equalsIgnoreCase(activeBotName)) {
                continue;
            }
            if (Tasks.busy(e.name)) {
                Tasks.tick(server, e.name);
            } else {
                idleTick(server, e.name);
            }
        }
        Tasks.refreshSnapshot();
        if (active == null) {
            return;
        }
        Order o = active;
        switch (o.step) {
            case STEP_OUT -> {
                if (phase == TRAVEL_TICKS) {
                    puffChest(server, o);
                    moveBodyToChest(server);
                }
                if (--phase <= 0) {
                    o.step = STEP_TAKE;
                }
            }
            case STEP_TAKE -> doTake(server);
            case STEP_BACK -> doBack(server, o);
            case STEP_GIVE -> doGive(server, o);
            case STEP_SHOW -> {
                // 站在玩家面前挥挥手，时间到了再回值守点
                if (phase % 10 == 0) {
                    Body.swing(server, activeBotName);
                }
                if (--phase <= 0) {
                    finish(server, o, "", false);
                }
            }
            default -> {
                // 不该发生；兜底别卡死
                finish(server, o, "内部状态异常，已中止。", true);
            }
        }
    }

    // ------------------------------------------------------------------
    // 空闲：每个假人各自站在值守点上待命；玩家丢给它的东西它捡起来送回仓库；
    // 没活干的时候还能执行「整理仓库」任务（见 Tasks）

    /** 一个假人的空闲状态（每个假人一份） */
    private static final class Idle {
        boolean collecting;
        int phase;
        int wait;
        String dim;
        BlockPos pos;
        int slot = -1;
        boolean dirty;
        /** 暂时放不进去的东西（不知道放哪 / 箱子满了），10 秒内先不再重试 */
        final java.util.Set<String> stuck = new java.util.HashSet<>();
        int stuckTicks;
    }

    private static final Map<String, Idle> IDLES = new HashMap<>();

    private static Idle idle(String botName) {
        return IDLES.computeIfAbsent(botName, k -> new Idle());
    }

    private static void idleTick(MinecraftServer server, String botName) {
        if (!Body.enabled()) {
            return;
        }
        Idle s = idle(botName);
        // 每 10 秒给「放不进去的东西」一次重试机会
        if (++s.stuckTicks >= 200) {
            s.stuckTicks = 0;
            s.stuck.clear();
        }
        if (s.collecting) {
            collectTick(server, botName, s);
            return;
        }
        ServerPlayer bot = Body.get(server, botName);
        if (bot != null && pickCollectTarget(server, botName, bot, s)) {
            s.collecting = true;
            return;
        }
        // 有活干就交给 Tasks（它会自己管假人的位置）
        if (Tasks.busy(botName)) {
            Tasks.tick(server, botName);
            return;
        }
        // 没事干就回值守点站着
        Body.Home home = Body.standby(server, botName);
        if (home == null) {
            return;
        }
        if (Body.ensure(server, botName, home.level(), home.x(), home.y(), home.z())) {
            Body.moveTo(server, botName, home.level(), home.x(), home.y(), home.z());
        }
    }

    private static boolean hasAnything(MinecraftServer server, String botName, ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        int held = Body.heldSlot(server, botName);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == held) {
                continue;
            }
            if (!inv.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 挑一件它身上「知道该放哪」的东西；挑不到就返回 false */
    private static boolean pickCollectTarget(MinecraftServer server, String botName, ServerPlayer bot, Idle s) {
        Inventory inv = bot.getInventory();
        int held = Body.heldSlot(server, botName);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            // 副手那格是送货表演用的副本，不算捡到的东西
            if (i == held) {
                continue;
            }
            ItemStack s2 = inv.getItem(i);
            if (s2.isEmpty()) {
                continue;
            }
            String id = ItemIds.of(s2);
            if (s.stuck.contains(id)) {
                continue;
            }
            // 归仓也只归自己值守的那间仓库；没分配值守仓库的假人身上拿着东西就先拿着（退场时统一入库）
            String mineRegion = Bots.regionOf(botName) == null ? "" : Bots.regionOf(botName);
            if (mineRegion.isEmpty()) {
                s.stuck.add(id);
                continue;
            }
            Homes.Spot spot = Homes.spot(id);
            if (spot != null && !mineRegion.equals(regionOfPos(spot.dimension(), spot.pos()))) {
                spot = null;
            }
            if (spot == null) {
                WarehouseIndex.ItemEntry e = WarehouseMod.INDEX.items.get(id);
                if (e != null) {
                    for (WarehouseIndex.SlotRef r : e.refs) {
                        if (mineRegion.equals(regionOfPos(r.dimension(), r.pos()))) {
                            spot = new Homes.Spot(r.dimension(), r.pos());
                            break;
                        }
                    }
                }
            }
            if (spot == null || spot.pos() == null) {
                s.stuck.add(id);
                continue;
            }
            s.slot = i;
            s.dim = spot.dimension();
            s.pos = spot.pos();
            s.phase = 0;
            s.wait = 0;
            return true;
        }
        return false;
    }

    private static void collectTick(MinecraftServer server, String botName, Idle s) {
        ServerPlayer bot = Body.get(server, botName);
        if (bot == null || s.pos == null) {
            stopCollecting(server, botName, s);
            return;
        }
        ServerLevel level = Scanner.levelOf(server, s.dim);
        if (level == null) {
            stopCollecting(server, botName, s);
            return;
        }
        switch (s.phase) {
            case 0 -> {
                double[] spot = Body.chestSpot(level, s.pos);
                Body.moveTo(server, botName, level, spot[0], spot[1], spot[2]);
                if (++s.wait >= TRAVEL_TICKS) {
                    s.phase = 1;
                    s.wait = 0;
                }
            }
            case 1 -> {
                Inventory inv = bot.getInventory();
                ItemStack stack = s.slot >= 0 ? inv.getItem(s.slot) : ItemStack.EMPTY;
                if (stack.isEmpty()) {
                    s.slot = -1;
                    s.phase = 0;
                    s.wait = 0;
                    return;
                }
                String id = ItemIds.of(stack);
                int before = stack.getCount();
                boolean ok = putInto(level, s.pos, stack);
                if (stack.isEmpty()) {
                    inv.setItem(s.slot, ItemStack.EMPTY);
                }
                bot.inventoryMenu.broadcastChanges();
                if (ok) {
                    s.dirty = true;
                    Homes.remember(id, WarehouseIndex.key(s.dim, s.pos));
                    noteTouched(s.dim, s.pos);
                    WarehouseMod.LOGGER.info("[warehouse-keeper] 搬运工 {} 已将拾取的 {} x{} 存入 {}",
                            botName, id, before - stack.getCount(), WarehouseIndex.key(s.dim, s.pos));
                } else {
                    // 放不进去就继续拿在手上，绝不悄悄丢地上
                    s.stuck.add(id);
                }
                s.slot = -1;
                s.phase = 2;
                s.wait = 0;
            }
            case 2 -> {
                if (++s.wait >= 4) {
                    s.phase = 0;
                    s.wait = 0;
                    s.slot = -1;
                    if (!hasAnything(server, botName, bot) || !pickCollectTarget(server, botName, bot, s)) {
                        stopCollecting(server, botName, s);
                    } else {
                        s.collecting = true;
                    }
                }
            }
            default -> stopCollecting(server, botName, s);
        }
    }

    private static void stopCollecting(MinecraftServer server, String botName, Idle s) {
        s.collecting = false;
        s.phase = 0;
        s.wait = 0;
        s.slot = -1;
        s.dim = null;
        s.pos = null;
        if (s.dirty) {
            s.dirty = false;
            if (active == null) {
                rescanTouched(server);
            }
        } else if (active == null) {
            touchedRegions.clear();
        }
        Body.Home home = Body.standby(server, botName);
        if (home != null) {
            Body.moveTo(server, botName, home.level(), home.x(), home.y(), home.z());
        }
    }

    /** 假人身上还拿着、但仓库里没位置放的东西（给指令/网页报告用） */
    public static List<String> carryingStuck(MinecraftServer server, String botName) {
        return Tasks.carrying(server, botName);
    }

    /** 所有假人身上都拿着什么（只列非空的） */
    public static Map<String, List<String>> carryingAll(MinecraftServer server) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (Bots.Entry e : Bots.list()) {
            List<String> c = Tasks.carrying(server, e.name);
            if (!c.isEmpty()) {
                out.put(e.name, c);
            }
        }
        return out;
    }

    /**
     * 关服前把所有假人身上捡来的东西尽量塞回仓库，塞不下的丢在它脚下。
     * 捡来的东西绝不能因为假人退场而凭空消失。
     */
    public static void flushBody(MinecraftServer server) {
        for (Bots.Entry e : Bots.list()) {
            flushBody(server, e.name);
        }
    }

    /**
     * 假人退场（关服 / 收回 / 移除）前把身上捡来的东西尽量塞回仓库。
     *
     * <p>顺序和 {@link Tasks#dumpCarried} 一致：①先送各自的「家」；②家里塞不下、或这种物品没记过
     * 「家」→ 在仓库里找最近的还有空位的箱子；③真没地方才丢在它脚下。捡来的东西绝不能凭空消失，
     * 也绝不该「明明有空箱子却全丢地上」。
     */
    public static void flushBody(MinecraftServer server, String botName) {
        ServerPlayer bot = Body.get(server, botName);
        if (bot == null) {
            return;
        }
        // 先清掉手里的表演道具，免得把它当成真货存进仓库
        Body.hold(server, botName, null);
        Inventory inv = bot.getInventory();
        int held = Body.heldSlot(server, botName);
        ServerLevel botLevel = bot.level();
        int dropped = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (i == held || s.isEmpty()) {
                continue;
            }
            String id = ItemIds.of(s);
            Homes.Spot spot = Homes.spot(id);
            if (spot == null) {
                WarehouseIndex.ItemEntry e = WarehouseMod.INDEX.items.get(id);
                if (e != null && !e.refs.isEmpty()) {
                    WarehouseIndex.SlotRef r = e.refs.get(0);
                    spot = new Homes.Spot(r.dimension(), r.pos());
                }
            }
            String own = Bots.regionOf(botName) == null ? "" : Bots.regionOf(botName);
            ServerLevel level = (spot == null || spot.pos() == null) ? null : Scanner.levelOf(server, spot.dimension());
            if (level != null && !own.isEmpty()
                    && own.equals(regionOfPos(spot.dimension(), spot.pos()))) {
                // 家就在它自己值守的仓库里：放回家。「家」用归一化坐标：双联箱的另一半是
                // 同一个箱子，镜像坐标会写错格子（A1-a/d）
                putInto(level, Scanner.canonical(level, spot.pos()), s);
            }
            if (!s.isEmpty() && !own.isEmpty()) {
                // 退场/关服时优先塞进它自己值守的仓库，绝不丢地上
                Tasks.parkInWarehouse(server, bot, botName, s, own);
            }
            if (!s.isEmpty()) {
                // 自己值守的仓库也满了（或它没分配值守仓库）：全仓库兜底，还是别丢地上
                Tasks.parkInWarehouse(server, bot, botName, s, "");
            }
            if (!s.isEmpty()) {
                // 真没地方了 —— 只能丢在脚下。丢地上的每一件都必须计数并告诉玩家（A2）
                Containers.dropItemStack(botLevel, bot.getX(), bot.getY() + 0.5, bot.getZ(), s.copy());
                dropped += s.getCount();
            }
            inv.setItem(i, ItemStack.EMPTY);
        }
        bot.inventoryMenu.broadcastChanges();
        if (dropped > 0) {
            announceGroundDrop(server, botName, dropped, botLevel, bot.blockPosition());
        }
        // F-D5-2：退场 / 关服入库后立刻把被动过的仓库重扫一遍，
        // 别让玩家还得自己 /warehouse scan 才看得到「东西已经进箱了」
        rescanTouched(server);
    }

    /**
     * 归仓兜底把物品放在地上时，必须让玩家看得见：日志 + 审计 + 在线提示（A2）。
     *
     * <p>「放不下就丢地上」是设计内的最后兜底（比凭空消失强），但**静默**丢是不行的：
     * 玩家既不知道掉了多少，也不知道掉在哪，就再也找不回来了。
     */
    private static void announceGroundDrop(MinecraftServer server, String botName, int count,
                                           ServerLevel level, BlockPos pos) {
        String where = level.dimension().identifier() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        String msg = "「" + botName + "」放不下 " + count + " 件，掉在 " + where + "，请尽快捡走。";
        WarehouseMod.LOGGER.warn("[warehouse-keeper] {}", msg);
        Audit.add(botName, "归仓丢地面", msg);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal("[搬运工] " + msg));
        }
    }

    /** tick 线程刷新在线名单快照（网页的「送给谁」下拉框靠它） */
    private static void refreshOnline(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        Set<String> ops = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String n = p.getName().getString();
            if (com.ds.warehouse.util.Admin.isAdmin(server, p)) {
                ops.add(n);
            }
            // 别把搬运工自己列进「送给谁」
            if (Bots.has(n)) {
                continue;
            }
            names.add(n);
        }
        ONLINE = List.copyOf(names);
        OPS = Set.copyOf(ops);
        // 假人状态也做成快照：HTTP 线程绝不能直接摸实体
        if (++bodySnapTicks >= 10) {
            bodySnapTicks = 0;
            List<Map<String, Object>> out = new ArrayList<>();
            for (Bots.Entry e : Bots.list()) {
                out.add(Body.json(server, e.name));
            }
            BODY_SNAPS = List.copyOf(out);
        }
    }

    /** 所有假人状态的只读快照（网页/GUI 线程读） */
    public static List<Map<String, Object>> bodiesJson() {
        return BODY_SNAPS;
    }

    /** 在线玩家名（快照，任何线程都能安全读） */
    public static List<String> onlinePlayers() {
        return ONLINE;
    }

    /**
     * 在线玩家名里谁是管理员（OP / 单人存档房主）。
     *
     * <p>和 {@link #onlinePlayers()} 一样是 tick 线程刷新的快照 —— 网页线程据此判断
     * 「这个登录的人有没有管理权限」，绝不直接去摸服务端玩家列表。
     */
    public static boolean isOpName(String name) {
        return name != null && !name.isEmpty() && OPS.contains(name);
    }

    private static void drainIncoming() {
        Request r;
        while ((r = INCOMING.poll()) != null) {
            WarehouseIndex.ItemEntry entry = resolve(r.query());
            if (entry == null) {
                reject(r, WarehouseMod.INDEX.items.isEmpty()
                        ? "仓库索引为空，请先在游戏内执行 /warehouse scan。"
                        : "仓库中没有「" + r.query() + "」这件物品。");
                continue;
            }
            String err = enqueue(r.playerName(), null, entry, r.count(), "");
            if (err != null) {
                reject(r, err);
            }
        }
    }

    private static void reject(Request r, String why) {
        Order ghost = new Order(r.playerName(), null, "", r.query(), Math.max(1, r.count()));
        ghost.failed = true;
        ghost.done = true;
        ghost.state = "被拒绝";
        ghost.note = why;
        ghost.finishedAt = System.currentTimeMillis();
        HISTORY.add(0, ghost);
        trimHistory();
        WarehouseMod.LOGGER.info("[warehouse-keeper] 取货订单被拒绝: {} — {}", r.playerName(), why);
    }

    private static void startNext(MinecraftServer server) {
        while (!PENDING.isEmpty()) {
            Order o = PENDING.peekFirst();
            ServerPlayer target = find(server, o);
            if (target == null) {
                // 人不在线：留着排队，他上线就送
                o.state = "等下单人上线";
                return;
            }
            WarehouseIndex.ItemEntry entry = WarehouseMod.INDEX.items.get(o.itemId);
            if (entry == null || entry.refs.isEmpty()) {
                PENDING.pollFirst();
                finish(server, o, "仓库中当前找不到「" + o.displayName + "」。", true);
                continue;
            }
            // 挑一个闲着的假人：只能是「值守仓库里真有这单货」的那一位
            String pick = pickBotFor(entry);
            if (pick == null) {
                List<String> where = itemRegions(entry);
                if (where.isEmpty()) {
                    PENDING.pollFirst();
                    finish(server, o, "「" + o.displayName + "」不在任何仓库范围内，无法配送。", true);
                    continue;
                }
                if (!anyBotOn(where)) {
                    PENDING.pollFirst();
                    finish(server, o, "仓库中「" + o.displayName + "」位于 " + String.join("、", where)
                            + "，但没有假人值守该仓库。请先使用 /warehouse bot assign <假人> " + where.get(0)
                            + " 指定后再下单。", true);
                    continue;
                }
                // 有人值守但都忙着：单子留在队里，等有人空出来
                o.state = "排队等搬运工";
                return;
            }
            // 只从「这个假人值守的仓库」里取货，别的仓库的东西一概不动
            String botRegion = Bots.regionOf(pick) == null ? "" : Bots.regionOf(pick);
            List<WarehouseIndex.SlotRef> mine = new ArrayList<>();
            for (WarehouseIndex.SlotRef ref : entry.refs) {
                if (botRegion.equals(regionOfPos(ref.dimension(), ref.pos()))) {
                    mine.add(ref);
                }
            }
            if (mine.isEmpty()) {
                PENDING.pollFirst();
                finish(server, o, "「" + o.displayName + "」不在「" + botRegion + "」仓库里，无法派单。", true);
                continue;
            }
            PENDING.pollFirst();
            activeBotName = pick;
            refs = mine;
            refIndex = 0;
            need = o.count;
            carried.clear();
            touchedRegions.clear();
            o.botName = pick;
            o.taken = 0;
            o.sourceText = refs.get(0).coordText();
            o.state = "去仓库取货";
            o.step = STEP_OUT;
            phase = TRAVEL_TICKS;
            waited = 0;
            active = o;
            // 订单来了就把人形放出来（Carpet 的生成是异步的，晚一两拍也没关系：
            // 取货/交货那几步会不断 moveTo，人一到位就自动跟上）
            Body.Home home = Body.standby(server, pick);
            if (home != null) {
                Body.ensure(server, pick, home.level(), home.x(), home.y(), home.z());
            }
            return;
        }
    }

    /** 这个假人是不是正在送单 */
    public static boolean busyWithOrder(String botName) {
        return active != null && activeBotName != null && !activeBotName.isEmpty()
                && activeBotName.equalsIgnoreCase(botName);
    }

    /** 正在送货的假人名字（没有就是空串） */
    public static String activeBot() {
        return active == null ? "" : activeBotName;
    }

    /**
     * 挑一个闲着的假人来接这单：**只认「值守仓库里真有这单货」的假人**。
     *
     * <p>没分配值守仓库的假人一律不派活，也绝不退而求其次让别人值守的假人跨仓库取货 ——
     * 这就是「假人只在自己那一间仓库里干活，互不干扰」这条硬规则。
     * 都忙着（或没人值守有货的仓库）就返回 null，由调用方决定是排队还是拒绝。
     */
    private static String pickBotFor(WarehouseIndex.ItemEntry entry) {
        if (entry == null) {
            return null;
        }
        List<String> free = new ArrayList<>();
        for (Bots.Entry e : Bots.list()) {
            if (busyWithOrder(e.name) || Tasks.busy(e.name) || idle(e.name).collecting) {
                continue;
            }
            // 被玩家「收回」的搬运工不派活，也不因为接单被自动放出来
            if (e.recalled) {
                continue;
            }
            String r = e.region == null ? "" : e.region;
            if (r.isEmpty() || RegionStore.REGIONS.get(r) == null) {
                continue;
            }
            free.add(e.name);
        }
        if (free.isEmpty()) {
            return null;
        }
        for (WarehouseIndex.SlotRef ref : entry.refs) {
            String region = regionOfPos(ref.dimension(), ref.pos());
            if (region == null) {
                continue;
            }
            for (String n : free) {
                if (region.equals(Bots.regionOf(n))) {
                    return n;
                }
            }
        }
        return null;
    }

    /** 这单货在哪些仓库里（按 refs 算，去重，顺序稳定） */
    private static List<String> itemRegions(WarehouseIndex.ItemEntry entry) {
        List<String> out = new ArrayList<>();
        if (entry == null) {
            return out;
        }
        for (WarehouseIndex.SlotRef ref : entry.refs) {
            String r = regionOfPos(ref.dimension(), ref.pos());
            if (r != null && !out.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }

    /** 名册里有没有假人值守这几个仓库（不管他忙不忙） */
    private static boolean anyBotOn(List<String> regions) {
        for (Bots.Entry e : Bots.list()) {
            String r = e.region == null ? "" : e.region;
            if (!r.isEmpty() && regions.contains(r) && RegionStore.REGIONS.get(r) != null) {
                return true;
            }
        }
        return false;
    }

    private static String regionOfPos(String dimension, BlockPos pos) {
        for (Region r : RegionStore.REGIONS.values()) {
            if (Scanner.dimensionOf(r).equals(dimension) && r.contains(pos)) {
                return r.name;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 取货

    private static void doTake(MinecraftServer server) {
        Order o = active;
        if (find(server, o) == null) {
            // 还没动手就拿，先等人回来；人不在就不取，避免东西悬在半路。
            // 必须和 doBack / doGive 走同一条等待 + 超时路径（A2）：以前这里只改 state 不 waited++，
            // 下单人一掉线这单就永远停在「取货」，流水线停摆、已经取出来的货一直压在内存里。
            waitOrGiveUp(server, o);
            return;
        }
        int guard = 0;
        while (need > 0 && refIndex < refs.size() && guard++ < 512) {
            WarehouseIndex.SlotRef ref = refs.get(refIndex++);
            String err = takeOne(server, ref, o);
            if (err != null) {
                returnCarried(server);
                finish(server, o, err, true);
                return;
            }
        }
        if (o.taken <= 0) {
            finish(server, o, "未能从箱子中取到物品（箱子可能已被移动，或其中已无该物品）。", true);
            return;
        }
        if (o.taken < o.count) {
            o.note = "仓库中仅剩 " + o.taken + " 个。";
        }
        // 让假人真的把货拿在手上（玩家能看见它举着东西）
        Body.hold(server, activeBotName, carried.isEmpty() ? null : carried.get(0).stack());
        Body.swing(server, activeBotName);
        o.state = "送回下单的人";
        o.step = STEP_BACK;
        phase = TRAVEL_TICKS;
        waited = 0;
    }

    /** @return null = 成功（或这一格没东西，跳过）；否则是中止原因 */
    private static String takeOne(MinecraftServer server, WarehouseIndex.SlotRef ref, Order o) {
        ServerLevel level = Scanner.levelOf(server, ref.dimension());
        if (level == null) {
            return null;
        }
        BlockPos pos = ref.pos();
        // 槽位号是「双联箱合并后」的号：第二半会落到隔壁那个方块上，
        // 必须走 openSlot 翻译，不能直接当成这个方块的格号（否则第二半永远取不出来）
        Scanner.Slot open = Scanner.openSlot(level, pos, ref.slot());
        if (open == null) {
            return null;
        }
        Container c = open.container();
        int slot = open.index();
        ItemStack cur = c.getItem(slot);
        if (cur.isEmpty()) {
            return null;
        }
        if (!ItemIds.of(cur).equals(o.itemId)) {
            // 箱子里的东西变了（索引过期），这一格跳过
            return null;
        }
        if (!o.ench.isEmpty() && !enchMatches(o.ench, cur)) {
            // 这单指定了附魔（取货页按附魔分行下单）：这一格的附魔组合对不上就跳过，
            // 绝不能拿一本「别的附魔书」糊弄过去。
            return null;
        }
        int take = Math.min(need, cur.getCount());
        if (take <= 0) {
            return null;
        }

        ItemStack moved = cur.copyWithCount(take);
        ItemStack left = cur.copy();
        left.shrink(take);
        if (left.getCount() <= 0) {
            left = ItemStack.EMPTY;
        }
        c.setItem(slot, left);
        c.setChanged();

        // 回读校验：Iron Chests 的 setItem 会「消毒」传入的 ItemStack，
        // 万一它把这一格变成了别的东西，我们必须当场发现并放弃，绝不能凭空发货。
        ItemStack after = c.getItem(slot);
        int afterCount = after.isEmpty() ? 0 : after.getCount();
        if (afterCount != cur.getCount() - take) {
            c.setItem(slot, cur);
            c.setChanged();
            return "从 " + Names.block(ref.blockId()) + "（" + ref.coordText() + "）取物失败，任务已中止，箱内物品已尽量还原。";
        }

        carried.add(new Carry(moved, ref.dimension(), pos));
        noteTouched(ref.dimension(), pos);
        // 记住「这种物品是从这个箱子拿的」：万一以后全取光了，索引里就没有它了，
        // 玩家想把东西交回来时就靠这条记忆找到箱子。
        Homes.remember(o.itemId, WarehouseIndex.key(ref.dimension(), pos));
        o.taken += take;
        need -= take;
        return null;
    }

    /**
     * 这一格的附魔组合是不是正好等于订单要的那一组。
     *
     * <p>两边都是 {@code 注册名@等级} 的 {@code ,} 连接串，但索引与 {@code Scanner} 的拼接顺序不保证，
     * 所以按**集合**比（顺序无关）；数量也不同时比不相等。
     */
    private static boolean enchMatches(String want, ItemStack stack) {
        Set<String> w = enchSet(want);
        return !w.isEmpty() && w.equals(enchSet(Scanner.enchantText(stack)));
    }

    private static Set<String> enchSet(String raw) {
        Set<String> out = new HashSet<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String one : raw.split(",")) {
            String t = one.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    static void noteTouched(String dimension, BlockPos pos) {
        for (Region r : RegionStore.REGIONS.values()) {
            if (Scanner.dimensionOf(r).equals(dimension) && r.contains(pos)) {
                if (!touchedRegions.contains(r)) {
                    touchedRegions.add(r);
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // 送货

    private static void doBack(MinecraftServer server, Order o) {
        ServerPlayer target = find(server, o);
        if (target == null) {
            waitOrGiveUp(server, o);
            return;
        }
        if (phase == TRAVEL_TICKS) {
            ServerLevel lvl = target.level();
            lvl.sendParticles(ParticleTypes.END_ROD, target.getX(), target.getY() + 1.0, target.getZ(),
                    8, 0.4, 0.5, 0.4, 0.02);
            // 立刻出现在下单的人面前
            double[] front = Body.frontOf(target);
            Body.moveTo(server, activeBotName, lvl, front[0], front[1], front[2]);
        }
        if (--phase <= 0) {
            o.step = STEP_GIVE;
        }
    }

    private static void doGive(MinecraftServer server, Order o) {
        ServerPlayer target = find(server, o);
        if (target == null) {
            waitOrGiveUp(server, o);
            return;
        }
        Inventory inv = target.getInventory();
        int total = 0;
        int free = 0;
        for (Carry c : carried) {
            total += c.stack().getCount();
            free += freeSpaceFor(inv, c.stack());
        }
        int delivered = 0;
        for (Carry c : carried) {
            ItemStack s = c.stack();
            if (s.isEmpty()) {
                continue;
            }
            delivered += s.getCount();
            // 原版方法：装得下就进背包，装不下的会掉在玩家脚下（不会凭空消失）
            inv.placeItemBackInInventory(s);
        }
        target.inventoryMenu.broadcastChanges();
        Body.hold(server, activeBotName, null);
        Body.swing(server, activeBotName);

        String extra = total > free ? " 背包空间不足，" + (total - free) + " 个已掉落在你脚下。" : "";
        o.note = "已将 " + delivered + " 个 " + o.displayName + " 送达 " + o.playerName + "。" + extra;
        o.state = "交货中";
        o.step = STEP_SHOW;
        // 在玩家面前多站 1.5 秒再走 —— 不然人一闪就没，玩家根本看不清是谁送的
        phase = SHOW_TICKS;
    }

    private static void waitOrGiveUp(MinecraftServer server, Order o) {
        o.state = "等下单人上线";
        waited++;
        if (waited > WAIT_TICKS) {
            // 空手时别说「物品已放回仓库」——doTake 一开始就在等下单人上线，那会儿还什么都没取
            boolean had = !carried.isEmpty();
            returnCarried(server);
            finish(server, o, had
                    ? "下单玩家始终未上线，本单已放弃；已取出的物品已放回仓库。"
                    : "下单玩家始终未上线，本单已放弃。", true);
        }
    }

    private static int freeSpaceFor(Inventory inv, ItemStack s) {
        int free = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty()) {
                free += s.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(slot, s)) {
                free += Math.max(0, slot.getMaxStackSize() - slot.getCount());
            }
        }
        return free;
    }

    /**
     * 把还没送出去的东西放回原来的箱子。
     *
     * 触发场景：取货出错、下单的人太久不上线、服务器要关了。**绝不能凭空吞掉。**
     */
    public static void returnCarried(MinecraftServer server) {
        int dropped = 0;
        BlockPos firstDrop = null;
        ServerLevel firstLevel = null;
        for (Carry c : carried) {
            ServerLevel level = Scanner.levelOf(server, c.dimension());
            if (level == null) {
                continue;
            }
            BlockPos pos = c.pos();
            // 用 putInto：它认双联箱的两半，而且写入后会回读校验；
            // 换成只认第一个方块的写法，东西会全塞进前半 27 格。
            putInto(level, pos, c.stack());
            if (!c.stack().isEmpty()) {
                // 箱子满了/没了：丢在箱子旁边，总比凭空消失强。掉地上必须计数 + 留痕（A2）
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, c.stack());
                dropped += c.stack().getCount();
                if (firstDrop == null) {
                    firstDrop = pos;
                    firstLevel = level;
                }
                c.stack().setCount(0);
            }
        }
        carried.clear();
        if (dropped > 0 && firstDrop != null && firstLevel != null) {
            announceGroundDrop(server, "取货跑腿", dropped, firstLevel, firstDrop);
        }
    }

    // ------------------------------------------------------------------
    // 归仓（流程二）：玩家把东西交给仓库，搬运工按索引放回它该在的箱子

    /**
     * 把玩家手里的一叠东西按索引归回仓库。
     *
     * <p>规则：东西该放哪由索引决定（{@code INDEX.items} 里那个物品的 refs）。
     * 索引里**根本没有**这个物品时不动手、也不乱塞 —— 直接告诉他没地方放。
     * 箱子满了就如实报告还剩多少，绝不悄悄丢地上。
     *
     * @return 给玩家看的一句话
     */
    public static String deposit(MinecraftServer server, ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return "手中没有物品。";
        }
        String id = ItemIds.of(stack);
        String label = stack.getHoverName().getString();

        // 该放哪儿：优先信索引（箱子里还有这个物品）；索引里没有它，就信记忆（Homes）。
        // 为什么需要记忆：搬运工把某种物品**全部**取走之后索引里就没它了，
        // 这时玩家想把东西交回来，索引答不上来 —— 可东西明明就是从那个箱子拿的。
        List<WarehouseIndex.SlotRef> targets = new ArrayList<>();
        WarehouseIndex.ItemEntry entry = WarehouseMod.INDEX.items.get(id);
        if (entry != null) {
            targets.addAll(entry.refs);
        } else {
            Homes.Spot home = Homes.spot(id);
            if (home != null) {
                targets.add(new WarehouseIndex.SlotRef(home.dimension(), home.pos(), -1, 0, ""));
            }
        }
        if (targets.isEmpty()) {
            return "仓库中没有为「" + label + "」预留位置（索引中没有该物品），无法确定应放入哪个箱子。"
                    + "请先在仓库中为其腾出一格并重新扫描，然后再交付。";
        }
        int before = stack.getCount();
        for (WarehouseIndex.SlotRef ref : targets) {
            if (stack.isEmpty()) {
                break;
            }
            ServerLevel level = Scanner.levelOf(server, ref.dimension());
            if (level == null || ref.pos() == null) {
                continue;
            }
            // 归一化寻址：双联箱只认坐标靠前的那一半，用镜像坐标会写错格子（A1-a）
            BlockPos pos = Scanner.canonical(level, ref.pos());
            if (putInto(level, pos, stack)) {
                noteTouched(ref.dimension(), pos);
                Homes.remember(id, WarehouseIndex.key(ref.dimension(), pos));
            }
        }
        int moved = before - (stack.isEmpty() ? 0 : stack.getCount());
        rescanTouched(server);
        if (moved <= 0) {
            return "「" + label + "」未能入库：目标箱子已满。";
        }
        if (stack.isEmpty()) {
            return "已将 " + moved + " 个 " + label + " 入库。";
        }
        return "已入库 " + moved + " 个 " + label + "，剩余 " + stack.getCount() + " 个 —— 箱子已满。";
    }

    /**
     * 往一个位置里塞东西。位置可能是双联箱，所以按「合并后的槽位」遍历，
     * 每一格都走 {@link Scanner#openSlot} 落到真正那个方块上。
     *
     * @return true 表示确实往这个箱子里写进了东西
     */
    static boolean putInto(ServerLevel level, BlockPos pos, ItemStack stack) {
        int size = Scanner.mergedSize(level, pos);
        if (size <= 0) {
            return false;
        }
        boolean wrote = false;
        // 1) 先往已有的同类堆里叠
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            Scanner.Slot open = Scanner.openSlot(level, pos, i);
            if (open == null) {
                continue;
            }
            Container c = open.container();
            int idx = open.index();
            ItemStack slot = c.getItem(idx);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) {
                continue;
            }
            int room = slot.getMaxStackSize() - slot.getCount();
            if (room <= 0) {
                continue;
            }
            int move = Math.min(room, stack.getCount());
            ItemStack next = slot.copy();
            next.grow(move);
            c.setItem(idx, next);
            c.setChanged();
            // 回读校验：Iron Chests 会「消毒」写入的 ItemStack，被吞了就还原并换一格
            ItemStack after = c.getItem(idx);
            if ((after.isEmpty() ? 0 : after.getCount()) != slot.getCount() + move) {
                c.setItem(idx, slot);
                c.setChanged();
                continue;
            }
            stack.shrink(move);
            wrote = true;
        }
        // 2) 再找空格子
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            Scanner.Slot open = Scanner.openSlot(level, pos, i);
            if (open == null) {
                continue;
            }
            Container c = open.container();
            int idx = open.index();
            if (!c.getItem(idx).isEmpty()) {
                continue;
            }
            int move = Math.min(stack.getMaxStackSize(), stack.getCount());
            ItemStack next = stack.copyWithCount(move);
            c.setItem(idx, next);
            c.setChanged();
            ItemStack after = c.getItem(idx);
            if ((after.isEmpty() ? 0 : after.getCount()) != move || !ItemIds.of(after).equals(ItemIds.of(stack))) {
                c.setItem(idx, ItemStack.EMPTY);
                c.setChanged();
                continue;
            }
            stack.shrink(move);
            wrote = true;
        }
        if (wrote) {
            // F-D5-2：假人往箱子里放了东西 = 这个仓库的内容变了，
            // 标记一下让「局部重扫」把索引刷新（否则 stats / 网页要等下一次 scan 才准）
            noteTouched(level.dimension().identifier().toString(), pos);
        }
        return wrote;
    }

    // ------------------------------------------------------------------
    // 收尾

    private static void finish(MinecraftServer server, Order o, String note, boolean failed) {
        if (note != null && !note.isEmpty()) {
            o.note = (o.note == null || o.note.isEmpty() ? "" : o.note + " ") + note;
        }
        o.failed = failed;
        o.done = true;
        o.state = failed ? "失败" : "已送达";
        o.finishedAt = System.currentTimeMillis();
        HISTORY.add(0, o);
        trimHistory();

        active = null;
        carried.clear();
        refs = List.of();
        need = 0;
        refIndex = 0;
        phase = 0;
        waited = 0;
        String who = activeBotName;
        activeBotName = "";

        ServerPlayer p = find(server, o);
        if (p != null) {
            p.sendSystemMessage(Component.literal("[搬运工] " + o.note));
        }
        WarehouseMod.LOGGER.info("[warehouse-keeper] 搬运工 {} — {}", o.summary(), o.note);

        goHome(server, who);
        rescanTouched(server);
    }

    private static void trimHistory() {
        while (HISTORY.size() > MAX_HISTORY) {
            HISTORY.remove(HISTORY.size() - 1);
        }
    }

    /** 箱子里少了东西 → 重扫一次相关区域，让面板和指令的数字跟得上 */
    static void rescanTouched(MinecraftServer server) {
        List<Region> targets;
        if (touchedRegions.isEmpty()) {
            if (RegionStore.REGIONS.isEmpty()) {
                return;
            }
            targets = new ArrayList<>(RegionStore.REGIONS.values());
        } else {
            targets = new ArrayList<>(touchedRegions);
        }
        touchedRegions.clear();
        // 局部重扫：只刷新被搬运工动过的那些区域，别的仓库的索引记录必须原样保留
        // （用 Scanner.start 会把整份索引清空，没被扫到的仓库就消失了）
        // 扫描进行中不再直接丢掉请求：排进 IndexRefresh 的队列，等 Scanner 空闲了自动补上
        IndexRefresh.markRegions(targets);
    }

    private static ServerPlayer find(MinecraftServer server, Order o) {
        if (o.playerId != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(o.playerId);
            if (p != null) {
                return p;
            }
        }
        return server.getPlayerList().getPlayerByName(o.playerName);
    }

    private static void puffChest(MinecraftServer server, Order o) {
        if (o.sourceText.isEmpty()) {
            return;
        }
        WarehouseIndex.SlotRef ref = refs.isEmpty() ? null : refs.get(0);
        if (ref == null) {
            return;
        }
        ServerLevel lvl = Scanner.levelOf(server, ref.dimension());
        if (lvl == null) {
            return;
        }
        BlockPos pos = ref.pos();
        lvl.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                6, 0.35, 0.35, 0.35, 0.01);
    }

    /** 把假人挪到箱子前面（取货表演的第一步） */
    private static void moveBodyToChest(MinecraftServer server) {
        if (!Body.enabled()) {
            return;
        }
        WarehouseIndex.SlotRef ref = refs.isEmpty() ? null : refs.get(0);
        if (ref == null) {
            return;
        }
        ServerLevel lvl = Scanner.levelOf(server, ref.dimension());
        if (lvl == null) {
            return;
        }
        double[] spot = Body.chestSpot(lvl, ref.pos());
        Body.moveTo(server, activeBotName, lvl, spot[0], spot[1], spot[2]);
    }

    /** 让它回值守点待命 */
    private static void goHome(MinecraftServer server, String botName) {
        if (!Body.enabled() || botName == null || botName.isEmpty()) {
            return;
        }
        Body.hold(server, botName, null);
        Body.Home home = Body.standby(server, botName);
        if (home != null) {
            Body.moveTo(server, botName, home.level(), home.x(), home.y(), home.z());
        }
    }

    // ------------------------------------------------------------------
    // 状态查询（网页线程也会调，所以只返回快照）

    public static int pendingCount() {
        return PENDING.size();
    }

    public static Order currentOrder() {
        return active;
    }

    /** 当前这一单的 JSON（没有就是 null） */
    public static Map<String, Object> activeJson() {
        Order o = active;
        return o == null ? null : json(o);
    }

    public static List<Order> history() {
        return Collections.unmodifiableList(HISTORY);
    }

    /** 排队中的订单快照（给网页看谁在等什么） */
    public static List<Map<String, Object>> pendingJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        Order cur = active;
        if (cur != null) {
            out.add(json(cur));
        }
        for (Order o : PENDING) {
            out.add(json(o));
        }
        return out;
    }

    public static List<Map<String, Object>> historyJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Order o : HISTORY) {
            out.add(json(o));
        }
        return out;
    }

    private static Map<String, Object> json(Order o) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("player", o.playerName);
        m.put("item", o.displayName);
        m.put("itemId", o.itemId);
        m.put("count", o.count);
        m.put("taken", o.taken);
        m.put("state", o.state);
        m.put("note", o.note);
        m.put("failed", o.failed);
        m.put("done", o.done);
        m.put("createdAt", o.createdAt);
        m.put("elapsedMs", o.elapsedMs());
        return m;
    }

    public static String statusText() {
        StringBuilder sb = new StringBuilder();
        Order cur = active;
        if (cur == null) {
            sb.append("搬运工：空闲");
        } else {
            sb.append("搬运工：").append(cur.state)
                    .append("（").append(cur.playerName).append(" 请求 ")
                    .append(cur.count).append(" 个 ").append(cur.displayName).append("）");
        }
        sb.append("　排队 ").append(PENDING.size()).append(" 单");
        if (!HISTORY.isEmpty()) {
            Order last = HISTORY.get(0);
            sb.append("\n最近一单：").append(last.summary()).append(" — ").append(last.state);
            if (!last.note.isEmpty()) {
                sb.append("（").append(last.note).append("）");
            }
        }
        return sb.toString();
    }

    /** 带假人信息的版本（指令与网页用） */
    public static String statusText(MinecraftServer server) {
        StringBuilder sb = new StringBuilder(statusText());
        if (server == null) {
            return sb.toString();
        }
        for (Bots.Entry e : Bots.list()) {
            sb.append("\n").append(Body.describe(server, e.name));
            if (Tasks.busy(e.name)) {
                sb.append("　正在").append(Tasks.kindOf(e.name));
            }
            List<String> carrying = Tasks.carrying(server, e.name);
            if (!carrying.isEmpty()) {
                sb.append("\n　随身携带：").append(String.join("、", carrying));
            }
        }
        return sb.toString();
    }
}
