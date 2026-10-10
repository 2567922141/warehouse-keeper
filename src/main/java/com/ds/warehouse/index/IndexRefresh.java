package com.ds.warehouse.index;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.ContainerTags;
import com.ds.warehouse.config.Region;
import com.ds.warehouse.config.RegionStore;
import com.ds.warehouse.web.WebSnapshot;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 索引新鲜度看门人。
 *
 * <p>模组原本没有任何「世界变了」的监听：玩家自己往箱子里放/拿东西、放一只新箱子、拆掉一只箱子，
 * 索引和面板快照都不会跟着变 —— 这就是「物品、箱子消息不及时」的根因。
 *
 * <p>这里补三件事，全部只在服务端 tick 线程上做，既不加载区块也不做全量扫描：
 * <ol>
 *   <li><b>事件</b>：拆掉容器、往仓库区域里放置方块 —— 这两类能改变「有哪些箱子」的操作立刻处理
 *       （拆掉的容器直接从索引里删；放方块的区域排进待重扫队列）。</li>
 *   <li><b>轮询</b>：每 {@link #POLL_TICKS} tick 抽查一批索引里记录过的容器（只查已经加载的区块），
 *       内容变了就把那一条记录就地换掉并重算汇总。</li>
 *   <li><b>局部重扫排队</b>：待重扫的仓库等 {@link Scanner} 空闲时逐个 {@code startPartial}，
 *       而不是像以前那样在扫描进行中把请求直接丢掉。</li>
 * </ol>
 */
public final class IndexRefresh {
    private IndexRefresh() {
    }

    /** 每这么多 tick 抽查一次「索引里的箱子内容变了没有」（20 tick = 1 秒） */
    private static final int POLL_TICKS = 20;
    /** 一轮最多抽查这么多只箱子，剩下的下一轮接着查，避免单个 tick 里读太多方块实体 */
    private static final int MAX_POLL_PER_ROUND = 512;
    /** {@link #sigOf} 的「这个坐标现在不是容器」返回值（真指纹恰好等于它的概率可以忽略） */
    private static final long MISSING = -1L;

    /**
     * 这口箱子自己 + 东西南北四个邻居所在的区块都加载了吗。
     *
     * <p>{@code isLoaded} 只查已加载表、不会加载任何东西。双联箱判定与找搭档都可能碰到
     * 隔壁区块，所以读这口箱子之前先问一句（审查发现 S6）。
     */
    private static boolean sidesLoaded(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        for (Direction d : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            if (!level.isLoaded(pos.relative(d))) {
                return false;
            }
        }
        return true;
    }
    /** 增量更新跑这么多轮（约 1 分钟）就整份重算一次兜底，防止长期累积出小偏差 */
    private static final int FULL_REAGG_ROUNDS = 60;

    /** 待局部重扫的仓库名（保持插入顺序，先进先出） */
    private static final Set<String> STALE = new LinkedHashSet<>();
    /** 容器 key -> 上一次看到的内容签名 */
    private static final Map<String, Long> SIG = new HashMap<>();

    private static int pollCooldown = POLL_TICKS;
    /** 抽查游标：每轮从上次停下的地方接着走，避免总盯着前几只箱子 */
    private static int pollCursor;
    /** 距上次整份重算过了多少轮（见 {@link #FULL_REAGG_ROUNDS}） */
    private static int roundsSinceFullReagg;
    private static int replacedTotal;
    private static int rescanTotal;

    /** 注册两块世界事件。只在模组初始化时调一次。 */
    public static void register() {
        // 拆掉容器：索引里那条记录必须立刻消失，否则面板和指令会一直显示已经不存在的箱子；
        // 贴在它上面的标签也要一起处理 —— 否则原地再放一个新箱子会直接继承旧标签（BUG5）
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, entity) -> {
            // 破坏事件给进来的是 Level（客户端也会走到这里），索引和标签只在服务端维护
            if (!(level instanceof ServerLevel server)) {
                return;
            }
            String dim = server.dimension().identifier().toString();
            String key = WarehouseIndex.key(dim, pos);
            // 「拆之前这一对箱子」的正式键。必须在事件里用 state 自己算：
            //   1) AFTER 事件触发时方块已经没了，Scanner.canonical() 在这一半上看不到搭档；
            //   2) Scanner.canonical() 走的是会强载搭档区块的路径，破坏方块时不能这么干。
            BlockPos partner = Scanner.partnerOf(server, pos, state);
            String pairKey = WarehouseIndex.key(dim,
                    partner != null && partner.compareTo(pos) < 0 ? partner : pos);
            // 搭档还活着（拆的是双联箱的一半）⇒ 标签挪到搭档自己的键；两半都没了 ⇒ 删标签
            boolean partnerAlive = partner != null && server.getBlockEntity(partner) instanceof Container;
            String keepKey = partnerAlive ? WarehouseIndex.key(dim, partner) : null;
            boolean known = WarehouseMod.INDEX.containers.containsKey(key)
                    || WarehouseMod.INDEX.containers.containsKey(pairKey);
            if (entity instanceof Container || known) {
                containerGone(key, pairKey, keepKey);
                markRegionOf(server, pos);
                if (partnerAlive) {
                    // 双联箱拆一半：活着的那半通常在隔壁方块（可能跨区块/跨区域边界），一起排队重扫
                    markRegionOf(server, partner);
                }
            }
        });

        // 放置方块：可能是一只新箱子（也可能只是垫了一块石头），先让那片区域重扫一遍
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() && player.getItemInHand(hand).getItem() instanceof BlockItem) {
                markRegionOf(level, hit.getBlockPos().relative(hit.getDirection()));
            }
            return InteractionResult.PASS;
        });
    }

    /**
     * 一只容器消失后的收尾：删掉索引记录，并把标签删掉、或挪给活下来的那一半。
     *
     * <p>「消失」有两种触发：玩家拆掉（{@link PlayerBlockBreakEvents#AFTER}）与抽查时发现
     * 坐标上已经不是容器（爆炸 / {@code setblock} 换掉，见 {@link #pollContainers}）。
     * 以前这里只删索引记录，标签会变成「死标签」：原地再放一个新箱子会自动继承旧分类。
     *
     * @param rawKey  消失的那个坐标的键（双联箱里可能是坐标靠后的那一半）
     * @param pairKey 消失之前「这一对」的正式键（标签实际用的就是它）
     * @param keepKey 搭档还活着时要挪过去的键；搭档也没了就传 null（直接删标签）
     */
    private static void containerGone(String rawKey, String pairKey, String keepKey) {
        boolean known = WarehouseMod.INDEX.containers.containsKey(pairKey)
                || (!rawKey.equals(pairKey) && WarehouseMod.INDEX.containers.containsKey(rawKey));
        if (known) {
            // 增量减掉这条记录的贡献（removeContainer 内部会 deaggregate），不必整份重算
            WarehouseMod.INDEX.removeContainer(pairKey);
            WarehouseMod.INDEX.removeContainer(rawKey);
            WebSnapshot.markDirty();
        }
        SIG.remove(pairKey);
        SIG.remove(rawKey);
        if (keepKey == null) {
            ContainerTags.clear(pairKey);
            if (!rawKey.equals(pairKey)) {
                ContainerTags.clear(rawKey);
            }
        } else {
            ContainerTags.rekey(pairKey, keepKey);
            if (!rawKey.equals(pairKey)) {
                ContainerTags.rekey(rawKey, keepKey);
            }
        }
    }

    /** 挂在服务端 tick 上。 */
    public static void tick(MinecraftServer server) {
        // 「自动」路径的标签改动（拆箱挪键 / 拼箱合并 / 抽查清理）只把 ContainerTags 标成 dirty，
        // 命令路径各自会存盘、SERVER_STOPPING 也会存 —— 但强杀进程时这些自动改动会回滚，
        // 重启后 BUG3/BUG5 的现象又会复现（审查发现 1）。这里补一个周期落盘点，dirty 才写盘。
        if (ContainerTags.isDirty()) {
            ContainerTags.save();
        }

        if (!STALE.isEmpty() && !Scanner.isRunning() && !RegionStore.REGIONS.isEmpty()) {
            // 一次只排一个仓库：局部重扫本身就占 tick 预算，攒着慢慢来比一次全排上去好
            Iterator<String> it = STALE.iterator();
            while (it.hasNext()) {
                String name = it.next();
                it.remove();
                Region r = RegionStore.REGIONS.get(name);
                if (r != null) {
                    Scanner.startPartial(server, List.of(r));
                    rescanTotal++;
                    break;
                }
            }
        }

        if (--pollCooldown > 0) {
            return;
        }
        pollCooldown = POLL_TICKS;
        pollContainers(server);
    }

    /** 有仓库在排队等重扫（面板查询可以据此稍等一下再答）。 */
    public static boolean stale() {
        return !STALE.isEmpty();
    }

    /** 已经就地换掉的容器条数（自检/状态用）。 */
    public static int replacedCount() {
        return replacedTotal;
    }

    /** 排过队的局部重扫次数（自检/状态用）。 */
    public static int rescanCount() {
        return rescanTotal;
    }

    /** 把一个仓库名排进待重扫队列（搬运工写过箱子、整理收工都用它）。 */
    public static void markRegion(String name) {
        if (name != null && !name.isEmpty()) {
            STALE.add(name);
        }
    }

    /** 批量排入待重扫队列（按仓库名）。 */
    public static void markNames(Iterable<String> names) {
        if (names == null) {
            return;
        }
        for (String n : names) {
            markRegion(n);
        }
    }

    /** 批量排入待重扫队列（按区域对象，搬运工 / 整理收工用这个）。 */
    public static void markRegions(Iterable<Region> regions) {
        if (regions == null) {
            return;
        }
        for (Region r : regions) {
            if (r != null) {
                markRegion(r.name);
            }
        }
    }

    /** 坐标所在的仓库排进待重扫队列（同一坐标可能同时落在多个区域里）。 */
    public static void markRegionOf(Level level, BlockPos pos) {
        String dim = level.dimension().identifier().toString();
        String shortDim = dim.contains(":") ? dim.substring(dim.indexOf(':') + 1) : dim;
        for (Region r : RegionStore.REGIONS.values()) {
            if (r.dimension == null
                    || r.dimension.equals(dim)
                    || r.dimension.equals(shortDim)) {
                if (r.contains(pos)) {
                    markRegion(r.name);
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * 抽查一批容器：内容签名变了就地把记录换掉，箱子已经没了的就把那条记录清掉。
     *
     * <p>只碰「已经加载」的区块，而且 <b>tick 线程绝不使用会加载区块的读取</b>：
     * {@link Scanner#mapOf} 第一行就是 {@code getChunk(..., ChunkStatus.FULL, true)}，
     * {@link Scanner#partnerLoaded} 还会连搭档那半的区块一起强载 —— 双联箱的另一半没加载时
     * 就跳过它，宁可下一轮再查，也绝不在 tick 里把区块拉起来。
     */
    private static void pollContainers(MinecraftServer server) {
        List<String> keys = new ArrayList<>(WarehouseMod.INDEX.containers.keySet());
        if (keys.isEmpty()) {
            return;
        }
        int round = Math.min(keys.size(), MAX_POLL_PER_ROUND);
        List<ContainerRecord> fresh = new ArrayList<>();
        // 记录还在、箱子已经没了的 key
        List<String> dead = new ArrayList<>();
        for (int i = 0; i < round; i++) {
            int idx = Math.floorMod(pollCursor + i, keys.size());
            String key = keys.get(idx);
            ContainerRecord old = WarehouseMod.INDEX.containers.get(key);
            if (old == null) {
                continue;
            }
            ServerLevel level = Scanner.levelOf(server, old.dimension);
            if (level == null || !level.isLoaded(old.pos)) {
                continue;
            }
            // 双联箱的另一半可能落在隔壁区块里：那边没加载时，读方块状态会把它顺手强载起来。
            // 以前只在「当前这半还是双联箱」时才查搭档，可箱子被拆成单箱以后这一半照样要读
            // 东西南北的邻居（判定是不是双联、找拆剩下的另一半），邻居没加载一样强载
            // ⇒ 只要是箱子类容器，就把自己和四个水平邻居都问一遍（审查发现 S6）。
            BlockState state = level.getBlockState(old.pos);
            if (state.getBlock() instanceof ChestBlock && !sidesLoaded(level, old.pos)) {
                continue;
            }
            long sig = sigOf(level, old.pos);
            if (sig == MISSING) {
                // 这个坐标现在不是容器了（箱子被炸掉 / 被 setblock 换掉）：删记录 + 让所在区域重扫。
                // 不能像以前那样只写个 -1 了事 —— -1 恰好等于 sigOf 对空的返回值，这条记录
                // 下一轮算出来还是 -1，于是永远不再被复查，幽灵槽位一直留在索引里。
                dead.add(key);
                continue;
            }
            Long prev = SIG.get(key);
            if (prev == null) {
                SIG.put(key, sig);
                continue;
            }
            if (prev != sig) {
                // 上面的守卫已经保证两半所在区块都已加载，所以这里的读取不会强载任何区块
                ContainerRecord now = Scanner.recordOf(level, old.pos);
                if (now == null) {
                    dead.add(key);
                    continue;
                }
                SIG.put(key, sigOf(level, old.pos));
                fresh.add(now);
            }
        }
        pollCursor = Math.floorMod(pollCursor + round, keys.size());
        // 幽灵容器：删掉以后这个 key 就不会出现在下一轮的遍历里，不会每 tick 反复删
        for (String key : dead) {
            ContainerRecord rec = WarehouseMod.INDEX.containers.get(key);
            SIG.remove(key);
            if (rec == null) {
                // 记录已经被别的路径删掉了，只剩标签还指着这个坐标：一并清掉（BUG5）
                ContainerTags.clear(key);
                continue;
            }
            ServerLevel level = Scanner.levelOf(server, rec.dimension);
            // 双联箱只没了一半（被炸掉 / 被 setblock 换掉）时不能直接清标签：标签要跟着活下来的
            // 那半走，跟玩家亲手拆箱那条路（PlayerBlockBreakEvents）保持一致。
            // 只在这份记录的搭档坐标上问一句 —— isLoaded 不会加载区块，tick 线程可以放心调。
            String keepKey = null;
            if (rec.partner != null && level != null && level.isLoaded(rec.partner)
                    && level.getBlockEntity(rec.partner) instanceof Container) {
                keepKey = WarehouseIndex.key(rec.dimension, rec.partner);
            }
            if (keepKey == null) {
                // 确认「这个坐标上已经不是容器」了：标签也要一起清掉，否则原地再放一个新箱子
                // 会自动继承旧分类（BUG5）。只清这一个键，别的坐标上的标签一概不动。
                ContainerTags.clear(key);
            } else {
                ContainerTags.rekey(key, keepKey);
            }
            if (level != null) {
                markRegionOf(level, rec.pos); // 让那片区域重扫一遍，把换了/没了的箱子找回来
            }
            // 增量减少这条记录的贡献（removeContainer 内部会 deaggregate），不必整份重算
            WarehouseMod.INDEX.removeContainer(key);
        }
        if (fresh.isEmpty() && dead.isEmpty()) {
            return;
        }
        // accept() 自己会把同坐标（以及双联箱搭档坐标）的旧记录先减掉再加新的，所以这里不必先删；
        // 也不必像以前那样每次变化都整份 reaggregate() —— 那是大仓库每秒一次的尖峰来源。
        for (ContainerRecord rec : fresh) {
            WarehouseMod.INDEX.accept(rec);
        }
        // 兜底：增量更新跑满 FULL_REAGG_ROUNDS 轮就整份重算一次，长期累积的小偏差会被抹平
        if (++roundsSinceFullReagg >= FULL_REAGG_ROUNDS) {
            roundsSinceFullReagg = 0;
            WarehouseMod.INDEX.reaggregate();
        }
        WebSnapshot.markDirty();
        replacedTotal += fresh.size();
    }

    /**
     * 一个容器的内容指纹：只看「第几格是什么、多少个」。
     *
     * <p><b>tick 线程绝不使用会加载区块的读取</b>：这里刻意不走 {@link Scanner#mapOf}，
     * 改用<b>不加载区块</b>的 {@link Scanner#partnerOf} —— 搭档那半的区块没加载、或已经不是箱子时
     * 就按「只剩本半」处理，绝不会为了算一次指纹把搭档区块拉起来。
     *
     * @return {@link #MISSING} 表示这个坐标现在不是容器
     */
    private static long sigOf(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        // 「算不算仓库容器」只有 Containers 一个判据：被排除的方块（雕纹书架 / 架子）当作 MISSING，
        // 于是轮询会把旧索引里残留的那两条记录当成幽灵删掉，并让所在区域重扫一次
        if (!Containers.isWarehouseContainer(level, pos, be)) {
            return MISSING;
        }
        Container own = (Container) be;
        BlockState state = level.getBlockState(pos);
        BlockPos partner = Scanner.partnerOf(level, pos, state);
        Container other = partner != null && level.getBlockEntity(partner) instanceof Container c2 ? c2 : null;
        // 槽号顺序与 Scanner.mapOf 一致：界面上排前面的那半（TYPE=RIGHT）在前
        Container first = own;
        Container second = other;
        if (other != null && !Scanner.isGuiFirst(state)) {
            first = other;
            second = own;
        }
        long h = 1125899906842597L;
        h = mixInto(h, first);
        int size = first.getContainerSize();
        if (second != null) {
            size += second.getContainerSize();
            h = mixInto(h, second);
        }
        return h * 31 + size;
    }

    /** 把一个容器的每一格混进指纹（空槽位也要占一格，否则「少了一格」看不出来） */
    private static long mixInto(long h, Container c) {
        for (int i = 0, n = c.getContainerSize(); i < n; i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty()) {
                h = h * 31 + 7;
            } else {
                h = h * 31 + st.getItem().hashCode() * 31L + st.getCount();
            }
        }
        return h;
    }
}
