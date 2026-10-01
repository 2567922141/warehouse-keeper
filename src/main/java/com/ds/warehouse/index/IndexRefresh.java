package com.ds.warehouse.index;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.Region;
import com.ds.warehouse.config.RegionStore;
import com.ds.warehouse.web.WebSnapshot;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
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

    /** 待局部重扫的仓库名（保持插入顺序，先进先出） */
    private static final Set<String> STALE = new LinkedHashSet<>();
    /** 容器 key -> 上一次看到的内容签名 */
    private static final Map<String, Long> SIG = new HashMap<>();

    private static int pollCooldown = POLL_TICKS;
    /** 抽查游标：每轮从上次停下的地方接着走，避免总盯着前几只箱子 */
    private static int pollCursor;
    private static int replacedTotal;
    private static int rescanTotal;

    /** 注册两块世界事件。只在模组初始化时调一次。 */
    public static void register() {
        // 拆掉容器：索引里那条记录必须立刻消失，否则面板和指令会一直显示已经不存在的箱子
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, entity) -> {
            if (level.isClientSide()) {
                return;
            }
            String dim = level.dimension().identifier().toString();
            String key = WarehouseIndex.key(dim, pos);
            boolean known = WarehouseMod.INDEX.containers.containsKey(key);
            if (entity instanceof Container || known) {
                if (known && WarehouseMod.INDEX.containers.remove(key) != null) {
                    WarehouseMod.INDEX.reaggregate();
                    WebSnapshot.markDirty();
                }
                SIG.remove(key);
                markRegionOf(level, pos);
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

    /** 挂在服务端 tick 上。 */
    public static void tick(MinecraftServer server) {
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
            // 双联箱的另一半可能落在隔壁区块里：那边没加载时，读整箱（mapOf / partnerLoaded）
            // 会把它顺手强载起来。tick 线程绝不使用会加载区块的读取 ⇒ 这一轮跳过，下一轮再试。
            BlockState state = level.getBlockState(old.pos);
            if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                    && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = ChestBlock.getConnectedBlockPos(old.pos, state);
                if (!other.equals(old.pos) && !level.isLoaded(other)) {
                    continue;
                }
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
            ContainerRecord rec = WarehouseMod.INDEX.containers.remove(key);
            SIG.remove(key);
            if (rec != null) {
                ServerLevel level = Scanner.levelOf(server, rec.dimension);
                if (level != null) {
                    markRegionOf(level, rec.pos); // 让那片区域重扫一遍，把换了/没了的箱子找回来
                }
            }
        }
        if (fresh.isEmpty() && dead.isEmpty()) {
            return;
        }
        // accept() 只能加不能减，所以先把旧的删掉，最后整份重算一次汇总
        for (ContainerRecord rec : fresh) {
            WarehouseMod.INDEX.containers.remove(WarehouseIndex.key(rec.dimension, rec.pos));
            WarehouseMod.INDEX.accept(rec);
        }
        WarehouseMod.INDEX.reaggregate();
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
        if (!(level.getBlockEntity(pos) instanceof Container own)) {
            return MISSING;
        }
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
