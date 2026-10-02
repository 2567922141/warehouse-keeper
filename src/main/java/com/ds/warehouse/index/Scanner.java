package com.ds.warehouse.index;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.AppConfig;
import com.ds.warehouse.config.ContainerTags;
import com.ds.warehouse.config.Region;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 容器扫描器。
 *
 * 关键设计（照抄 Carpet-Org-Addition 的思路，不要逐方块暴力遍历）：
 *   1. 按 ChunkPos 遍历区块，而不是按方块遍历。
 *   2. 默认用 level.getChunk(cx, cz, ChunkStatus.FULL, false) —— 最后一个 false 表示
 *      **不强制加载区块**。没加载的区块直接计入 skippedChunks，绝不因为扫描而
 *      把整个仓库区域从磁盘拉起来。
 *   3. 取 LevelChunk.getBlockEntities().values()，区域内 + {@link Containers#isWarehouseContainer}
 *      过滤（方块实体是 Container、且方块 id 不在排除表 —— 默认排除雕纹书架与 26.2 的架子）。
 *   4. 分时执行：每 tick 最多占用 BUDGET_NANOS，剩下的留给服务器 tick。
 *
 * 「扫描时临时强制加载区块」开关（settings.json 的 scanForceLoadChunks，默认开）：
 *   仓库离玩家很远时，区块没加载就扫不到箱子。打开后为区域内的每个区块挂一个
 *   TicketType.UNKNOWN 票据再 getChunk(..., true)：
 *     - UNKNOWN 的 flags = LOADING | CAN_EXPIRE_IF_UNLOADED，**不含 PERSIST**，
 *       所以永远不会被写进存档的 chunks.dat（FORCED 含 PERSIST，绝对不能用）。
 *     - 扫描结束 / 中止 / 切换维度时，会逐个 removeTicketWithRadius 释放；
 *       就算失败，票据也只在内存里，服务器重启即彻底消失。
 *     - 上限 MAX_FORCE_LOAD 个区块，超过后退化成「只扫已加载的区块」并打日志。
 *
 * 兼容性：唯一的「模组适配层」就是 {@code instanceof Container} + {@code getContainerSize()}
 * （由 {@link Containers} 统一判定「算不算仓库容器」）。
 * 原版箱子、Iron Chests 的 126 格大箱子、其它模组的容器全都自动覆盖，无需特判。
 */
public final class Scanner {

    /** 每 tick 扫描预算：20ms */
    private static final long BUDGET_NANOS = 20_000_000L;
    /** 安全上限：一次扫描最长 2 分钟，防止超大区域把服务器拖住 */
    private static final long MAX_TOTAL_NANOS = 120_000_000_000L;
    /** 安全上限：一次扫描最多强制加载 4096 个区块（约 256x256 方块） */
    private static final int MAX_FORCE_LOAD = 4096;

    /**
     * 扫描期间用来「按住」区块的票据。
     *
     * 必须是 UNKNOWN：它的 flags = FLAG_LOADING | FLAG_CAN_EXPIRE_IF_UNLOADED，
     * 不含 FLAG_PERSIST，所以不会写进存档。FORCED 含 FLAG_PERSIST，会污染存档，
     * 绝对不能在这里用。
     */
    private static final TicketType SCAN_TICKET = TicketType.UNKNOWN;

    private static Job current;

    private Scanner() {
    }

    public static boolean isRunning() {
        return current != null;
    }

    public static String progressText() {
        Job j = current;
        return j == null ? "空闲（没有正在进行的扫描）" : j.progressText();
    }

    /**
     * 开始一次扫描。会先清空索引。
     *
     * @return null 表示启动成功；否则返回不能启动的原因
     */
    public static String start(MinecraftServer server, List<Region> regions) {
        return start(server, regions, false);
    }

    /**
     * 开始一次「局部重扫」：只重扫这几个区域，**索引里其他区域的记录原样保留**。
     *
     * <p>搬运工动过箱子之后就走这条 —— 用 {@link #start} 会把整份索引清空重建，
     * 落在本次区域之外的仓库会从索引里凭空消失。
     *
     * @return null 表示启动成功；否则返回不能启动的原因
     */
    public static String startPartial(MinecraftServer server, List<Region> regions) {
        return start(server, regions, true);
    }

    private static String start(MinecraftServer server, List<Region> regions, boolean partial) {
        if (current != null) {
            return "已有扫描任务正在进行，请先用 /warehouse status 查看进度";
        }
        if (regions.isEmpty()) {
            return "没有可扫描的区域，请先用 /warehouse pos1 + pos2 + region save <名字> 创建";
        }
        for (Region r : regions) {
            ServerLevel lvl = levelOf(server, dimensionOf(r));
            if (lvl == null) {
                return "未找到维度 " + dimensionOf(r) + "（区域 " + r.name + "）";
            }
        }
        if (partial) {
            // 只删掉本次要重扫的那些区域，别的地方的记录留着
            WarehouseMod.INDEX.removeContainersIn(regions);
        } else {
            WarehouseMod.INDEX.clear();
        }
        current = new Job(server, List.copyOf(regions), partial);
        return null;
    }

    public static void tick(MinecraftServer server) {
        Job j = current;
        if (j == null) {
            return;
        }
        try {
            j.step();
        } catch (Throwable t) {
            WarehouseMod.LOGGER.error("[warehouse-keeper] 扫描出错", t);
            j.abort("扫描出错: " + t);
            current = null;
        }
        if (j.finished) {
            current = null;
        }
    }

    public static String dimensionOf(Region r) {
        return (r.dimension == null || r.dimension.isEmpty()) ? "minecraft:overworld" : r.dimension;
    }

    public static ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel lvl : server.getAllLevels()) {
            if (lvl.dimension().identifier().toString().equals(dimension)) {
                return lvl;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 双联箱的「槽位寻址」
    //
    // 扫描时把双联箱两半拼成**一个** ContainerRecord，槽位号是连续的：
    // 第一半 0..n1-1，第二半 n1..n1+n2-1。但两个半其实是**两个方块、两个 BlockEntity**，
    // 各自只有 27 格。搬运工拿到的就是这种合并后的槽位号，如果直接把它当成
    // 「第一个方块的第几格」，第二半的东西永远取不出来（slot 越界 → 被跳过），
    // 表现就是「仓库里明明还有 32 个，搬运工却只说剩这么多」。
    //
    // 所以这里统一入口：给一个合并槽位号，返回真正要操作的那个 Container + 它自己的格号。
    // 刻意**不查索引** —— 用方块状态现算搭档，索引正在重扫时也不会失灵。

    /** 真正要操作的目标：某个容器 + 它自己的格号 */
    public record Slot(Container container, int index) {
    }

    /** @return 双联箱的另一半；不是双联箱、没加载、或对方对不上就是 null */
    public static BlockPos partnerOf(ServerLevel level, BlockPos p, BlockState state) {
        return partner(level, p, state, false);
    }

    /**
     * 和 {@link #partnerOf} 一样，但搭档所在的区块没加载时会先把它加载起来。
     *
     * <p>整理/写入路径必须看全整个箱子：只认本半会让 27..53 被当成空格，
     * 表现就是「双联箱被看成两个小箱子」。扫描路径刻意不用这个 —— 扫描不该顺手加载区块。
     */
    public static BlockPos partnerLoaded(ServerLevel level, BlockPos p) {
        level.getChunk(p.getX() >> 4, p.getZ() >> 4, ChunkStatus.FULL, true);
        return partner(level, p, level.getBlockState(p), true);
    }

    /**
     * 把坐标归一到「这个箱子的正式记录坐标」：双联箱返回坐标靠前的那一半，其它情况原样返回。
     *
     * <p>只用来给箱子做<b>身份</b>（索引 key、{@code Homes} 记忆、标签、审计里的坐标必须是同一个）。
     * 槽号顺序不再依赖它：{@link #mapOf} / {@link #openSlot} / 扫描建记录都按界面顺序取
     * （TYPE=RIGHT 的那半在前，见 {@link #isGuiFirst}），所以拿哪一半的坐标去问都得到同一套槽号。
     */
    public static BlockPos canonical(ServerLevel level, BlockPos p) {
        BlockPos other = partnerLoaded(level, p);
        return (other != null && other.compareTo(p) < 0) ? other : p;
    }

    /** a 和 b 是不是同一个物理箱子（双联箱的两半算同一个）*/
    public static boolean sameChest(ServerLevel level, BlockPos a, BlockPos b) {
        return canonical(level, a).equals(canonical(level, b));
    }

    /** 真正干活的：{@code load=true} 时会先把搭档所在区块加载起来再问 */
    private static BlockPos partner(ServerLevel level, BlockPos p, BlockState state, boolean load) {
        if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE)) {
            return null;
        }
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) {
            return null;
        }
        BlockPos other = ChestBlock.getConnectedBlockPos(p, state);
        if (other.equals(p)) {
            return null;
        }
        if (load) {
            level.getChunk(other.getX() >> 4, other.getZ() >> 4, ChunkStatus.FULL, true);
        } else if (!level.isLoaded(other)) {
            // isLoaded 不会加载区块；不先问一句的话，下面两句会把区块顺手加载起来
            return null;
        }
        BlockState os = level.getBlockState(other);
        if (!(os.getBlock() instanceof ChestBlock) || !os.hasProperty(ChestBlock.TYPE)) {
            return null;
        }
        if (os.getValue(ChestBlock.TYPE) != type.getOpposite()) {
            return null;
        }
        return ChestBlock.getConnectedBlockPos(other, os).equals(p) ? other : null;
    }

    /**
     * 双联箱在<b>界面上</b>谁排前面：原版 {@code DoubleBlockCombiner} 永远把
     * {@code TYPE == RIGHT} 的那一半当「前半」——
     * {@code ChestBlock.getBlockType()} 把 RIGHT 映射成 {@code BlockType.FIRST}、LEFT 映射成 SECOND，
     * {@code ChestBlock$1.acceptDouble()} 再原样 {@code new CompoundContainer(前半, 后半)}。
     *
     * <p>⇒ <b>合并槽号 {@code 0..n1-1} 是 TYPE=RIGHT 的那一半、{@code n1..} 是 LEFT 那一半，
     * 与「哪个坐标靠前」无关。</b>（A2-b：以前按坐标靠前的一半当前半，于是整理时算出来的「第 0 格」
     * 落在界面另一侧，玩家看到的就是「东西没顶格、被塞到箱子中间」。）
     *
     * @return true = 传入的这半在界面上排前面
     */
    public static boolean isGuiFirst(BlockState state) {
        return state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) == ChestType.RIGHT;
    }

    /**
     * 把一个「合并后的槽位号」翻译成真正要操作的容器与格号。
     *
     * @return null 表示这个槽位现在够不着（方块被拆了、区块没加载、对方不是容器…）
     */
    public static Slot openSlot(ServerLevel level, BlockPos p, int slot) {
        SlotMap map = mapOf(level, p);
        return map == null ? null : map.at(slot);
    }

    /** 这个位置合并后一共有多少格（双联箱算两半），够不着就是 0 */
    public static int mergedSize(ServerLevel level, BlockPos p) {
        SlotMap map = mapOf(level, p);
        return map == null ? 0 : map.size();
    }

    // ------------------------------------------------------------------

    /**
     * 一个箱子（双联箱算<b>一个</b>）的「合并槽位 → (Container, 自己那一半的格号)」映射。
     *
     * <p><b>批次 1「P0 建模」用</b>：一箱只取一到两次 {@code getBlockEntity}，之后每一格的翻译
     * 都不再碰区块和方块实体。合并语义与 {@link #mergedSize} / {@link #openSlot} <b>完全一致</b>
     * （前半 {@code 0..n1-1}、后半 {@code n1..n1+n2-1}；搭档读不到就只有前半），
     * 而「前半」= 界面上先渲染的那一半（{@code TYPE == RIGHT}，见 {@link #isGuiFirst}），
     * 所以把它当成「把 {@code openSlot} 装在口袋里」用即可，行为不变、只是不再逐格查表。
     *
     * <p>为什么值得：整理一步要读整仓每一格，逐格走 {@link #openSlot} 时每一格都会
     * {@code getChunk} + {@code getBlockEntity}，双联箱后半还要再问一次搭档
     * ⇒ 每搬一件物品做上万次区块读取。用这个映射以后每次只剩 {@code getItem}。
     *
     * <p>内容刻意<b>不缓存</b>：{@link #at} 每次都读 {@code getItem}，所以中途被玩家动过也不会算错。
     */
    public static final class SlotMap {
        private final Container first;
        private final Container second;
        private final int firstSize;
        private final int secondSize;

        private SlotMap(Container first, int firstSize, Container second, int secondSize) {
            this.first = first;
            this.firstSize = firstSize;
            this.second = second;
            this.secondSize = secondSize;
        }

        /** 合并后的总格数（和 {@link #mergedSize} 一致） */
        public int size() {
            return firstSize + secondSize;
        }

        /** @return null = 这个槽位现在够不着（和 {@link #openSlot} 一致） */
        public Slot at(int slot) {
            if (slot < 0) {
                return null;
            }
            if (slot < firstSize) {
                return new Slot(first, slot);
            }
            if (second != null && slot < firstSize + secondSize) {
                return new Slot(second, slot - firstSize);
            }
            return null;
        }
    }

    /**
     * 建一份 {@link SlotMap}。
     *
     * @return null 表示够不着（那个坐标现在不是容器，或者区块读不到）—— 调用方必须<b>跳过</b>，
     *         绝不能当成空格（A1-c）
     */
    public static SlotMap mapOf(ServerLevel level, BlockPos p) {
        level.getChunk(p.getX() >> 4, p.getZ() >> 4, ChunkStatus.FULL, true);
        BlockEntity self = level.getBlockEntity(p);
        // 「算不算仓库容器」只有 Containers 一个判据：被排除的方块（雕纹书架 / 架子）在这里就当作够不着。
        // 该判据成立时方块实体必然是 Container，直接转即可（不必再问一次 instanceof —— 那会多一次
        // getBlockEntity 读取）。
        if (!Containers.isWarehouseContainer(level, p, self)) {
            return null;
        }
        Container c = (Container) self;
        int n1 = c.getContainerSize();
        BlockPos other = partnerLoaded(level, p);
        if (other != null && level.getBlockEntity(other) instanceof Container c2) {
            // 界面顺序：TYPE=RIGHT 的那半排前面（原版 CompoundContainer 就是按这个顺序拼的，见 #isGuiFirst）
            if (isGuiFirst(level.getBlockState(p))) {
                return new SlotMap(c, n1, c2, c2.getContainerSize());
            }
            return new SlotMap(c2, c2.getContainerSize(), c, n1);
        }
        return new SlotMap(c, n1, null, 0);
    }

    /**
     * 把「这一个容器现在的样子」做成一条索引记录，供局部刷新使用（{@link IndexRefresh} 的轮询）。
     *
     * <p>调用方必须自己保证坐标所在区块<b>已经加载</b>：{@link #mapOf} 第一行就会强制加载区块，
     * 这个方法不会替调用方兜底。
     *
     * @return null 表示这个坐标现在不是容器（或者读不到）
     */
    public static ContainerRecord recordOf(ServerLevel level, BlockPos p) {
        SlotMap map = mapOf(level, p);
        if (map == null) {
            return null;
        }
        BlockState state = level.getBlockState(p);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String dimension = level.dimension().identifier().toString();
        ContainerRecord rec = new ContainerRecord(dimension, p.immutable(), blockId, map.size());
        BlockPos other = partnerLoaded(level, p);
        if (other != null && level.getBlockEntity(other) instanceof Container) {
            rec.doubleChest = true;
            rec.partner = other.immutable();
        }
        for (int i = 0; i < map.size(); i++) {
            Slot slot = map.at(i);
            if (slot == null) {
                continue;
            }
            ItemStack st = slot.container().getItem(slot.index());
            if (!st.isEmpty()) {
                rec.add(i, st.copy());
            }
        }
        return rec;
    }

    /**
     * 读一格物品的附魔与自定义名，并进调用方给的「容器级」集合里（扫描时一个容器调一次，别每格一次跨对象提交）。
     *
     * <p>附魔同时读 {@code DataComponents.ENCHANTMENTS}（物品自己的附魔）与
     * {@code DataComponents.STORED_ENCHANTMENTS}（附魔书把附魔存在这里），只读前者会漏掉整箱附魔书。
     * 26.2 用的是 Mojang 官方映射：附魔是数据包动态注册表，{@code BuiltInRegistries} 里<b>没有</b>
     * {@code ENCHANTMENT}，所以注册名只能走 {@code Holder.unwrapKey()} → {@code ResourceKey.identifier()}。
     *
     * @param enchantLevelsOut    附魔注册名（含命名空间）→ 等级，按「取最大」合并
     * @param customNamesLowerOut 物品自定义名（已转小写）
     */
    public static void collectMeta(ItemStack stack, Map<String, Integer> enchantLevelsOut, Set<String> customNamesLowerOut) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (enchantLevelsOut != null) {
            readEnchantments(stack.get(DataComponents.ENCHANTMENTS), enchantLevelsOut);
            readEnchantments(stack.get(DataComponents.STORED_ENCHANTMENTS), enchantLevelsOut);
        }
        if (customNamesLowerOut != null) {
            String name = customNameText(stack);
            if (!name.isEmpty()) {
                customNamesLowerOut.add(name.toLowerCase(Locale.ROOT));
            }
        }
    }

    private static void readEnchantments(ItemEnchantments enchants, Map<String, Integer> out) {
        if (enchants == null || enchants.isEmpty()) {
            return;
        }
        for (Object2IntMap.Entry<Holder<Enchantment>> e : enchants.entrySet()) {
            String id = enchantId(e.getKey());
            int level = e.getIntValue();
            if (id == null || level <= 0) {
                continue;
            }
            out.merge(id, level, Math::max);
        }
    }

    /** 附魔的注册名（{@code minecraft:sharpness} 这种）；拿不到注册键就返回 null */
    private static String enchantId(Holder<Enchantment> holder) {
        if (holder == null) {
            return null;
        }
        return holder.unwrapKey().map(k -> k.identifier().toString()).orElse(null);
    }

    /** 物品自定义名的原文（没改过名就是空串） */
    public static String customNameText(ItemStack stack) {
        Component name = stack == null ? null : stack.get(DataComponents.CUSTOM_NAME);
        return name == null ? "" : name.getString();
    }

    /**
     * 一格物品的附魔文字：{@code 附魔注册名@等级} 用 {@code ,} 连接
     * （例如 {@code minecraft:sharpness@5,minecraft:unbreaking@3}），无附魔是空串。
     *
     * <p>给网页 {@code /api/container} 与面板「容器详情」逐格用；顺序按附魔 id 排序，同一格每次看到的都一样。
     */
    public static String enchantText(ItemStack stack) {
        Map<String, Integer> levels = new TreeMap<>();
        collectMeta(stack, levels, null);
        if (levels.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : levels.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('@').append(e.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static final class Job {
        private final MinecraftServer server;
        private final List<Region> regions;
        private final long startNanos = System.nanoTime();
        /** 本次扫描是否临时强制加载区块（照抄开始扫描那一刻的设置） */
        private final boolean forceLoad;
        /**
         * 局部重扫：只扫这几个区域，索引里其他区域的记录原样保留。
         *
         * <p>这时候统计数字不能照抄本次扫描的本地计数（那只是被扫到的那部分），
         * 必须用 {@link WarehouseIndex#reaggregate()} 按整份记录重算。
         */
        private final boolean partial;
        /** 本次扫描为「按住」区块而挂上的票据，扫完必须逐个释放 */
        private final List<ChunkPos> held = new ArrayList<>();

        private int regionIndex = 0;
        private Region region;
        private ServerLevel level;

        private int cx;
        private int cz;
        private int cx0;
        private int cz0;
        private int cx1;
        private int cz1;
        private long scannedChunks;
        private long skippedChunks;
        private long scannedContainers;
        private long totalStacks;
        private long totalItems;

        private boolean forceLoadLimitHit;
        private boolean finished;
        private String abortReason;

        Job(MinecraftServer server, List<Region> regions, boolean partial) {
            this.server = server;
            this.regions = regions;
            this.partial = partial;
            this.forceLoad = AppConfig.get().scanForceLoadChunks;
            beginRegion(0);
            WarehouseMod.INDEX.lastScanRegion = describeAllRegions(regions);
            WarehouseMod.INDEX.lastScanMillis = 0;
        }

        private static String describeAllRegions(List<Region> regions) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < regions.size(); i++) {
                if (i > 0) {
                    sb.append(" + ");
                }
                sb.append(regions.get(i).name);
            }
            return sb.toString();
        }

        private void beginRegion(int index) {
            // 换维度前先把上一个维度按住的区块票据全部释放
            releaseHeldChunks();
            regionIndex = index;
            region = regions.get(index);
            level = levelOf(server, dimensionOf(region));
            BlockPos lo = region.min();
            BlockPos hi = region.max();
            cx0 = lo.getX() >> 4;
            cz0 = lo.getZ() >> 4;
            cx1 = hi.getX() >> 4;
            cz1 = hi.getZ() >> 4;
            cx = cx0;
            cz = cz0;
        }

        void step() {
            long tickStart = System.nanoTime();
            while (true) {
                if (finished) {
                    return;
                }
                if (System.nanoTime() - startNanos > MAX_TOTAL_NANOS) {
                    abort("扫描超时（超过 2 分钟）");
                    return;
                }
                if (cx > cx1) {
                    if (regionIndex + 1 < regions.size()) {
                        beginRegion(regionIndex + 1);
                        continue;
                    }
                    finish();
                    return;
                }
                scanChunk(cx, cz);
                cz++;
                if (cz > cz1) {
                    cz = cz0;
                    cx++;
                }
                if (System.nanoTime() - tickStart > BUDGET_NANOS) {
                    return;
                }
            }
        }

        /**
         * 释放本次扫描挂上的所有区块票据。
         *
         * 必须用 level.getChunkSource().removeTicketWithRadius(TicketType, ChunkPos, int)，
         * 参数要和 addTicketWithRadius 时完全一致（同类型、同坐标、同半径 = 0）。
         */
        private void releaseHeldChunks() {
            if (held.isEmpty()) {
                return;
            }
            ServerLevel lvl = level;
            if (lvl != null) {
                for (ChunkPos cp : held) {
                    try {
                        lvl.getChunkSource().removeTicketWithRadius(SCAN_TICKET, cp, 0);
                    } catch (Throwable ignore) {
                        // 释放失败不致命：票据只在内存里，服务器重启后彻底消失
                    }
                }
            }
            held.clear();
        }

        private void scanChunk(int chunkX, int chunkZ) {
            boolean force = forceLoad;
            if (force) {
                if (held.size() >= MAX_FORCE_LOAD) {
                    if (!forceLoadLimitHit) {
                        forceLoadLimitHit = true;
                        WarehouseMod.LOGGER.warn(
                                "[warehouse-keeper] 本次扫描最多临时加载 {} 个区块，其余区块仅扫描已加载部分。"
                                        + "如需完整扫描，请缩小区域范围。", MAX_FORCE_LOAD);
                    }
                    force = false;
                } else {
                    ChunkPos cp = new ChunkPos(chunkX, chunkZ);
                    level.getChunkSource().addTicketWithRadius(SCAN_TICKET, cp, 0);
                    held.add(cp);
                }
            }
            // force=true 时会同步等待区块加载完成；false 时未加载的区块返回 null
            ChunkAccess ca = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, force);
            if (!(ca instanceof LevelChunk lc)) {
                skippedChunks++;
                return;
            }
            scannedChunks++;
            for (BlockEntity be : lc.getBlockEntities().values()) {
                BlockPos p = be.getBlockPos();
                if (!region.contains(p)) {
                    continue;
                }
                // 「算不算仓库容器」只有 Containers 一个判据：被排除的方块（雕纹书架 / 架子）不建记录
                if (!Containers.isWarehouseContainer(level, p, be) || !(be instanceof Container container)) {
                    continue;
                }
                // 原版「两个箱子拼成一个大箱子」，在方块实体层面是两个各 27 格的箱子；
                // 游戏里打开 GUI 看到的 54 格是运行时才拼出来的 CompoundContainer。原版
                // ChestBlock.getContainer() 确实能拿到它，但它会顺着坐标去读搭档那一格
                // （Level.getBlockState/getBlockEntity 对未加载区块会顺手加载）——
                // 扫描期刻意不加载区块，所以这里自己拼，并且<b>严格照原版的顺序</b>：
                // 界面上先渲染的是 TYPE=RIGHT 的那一半（见 #isGuiFirst），合并槽号 0..26 是它，
                // 27..53 才是 LEFT 那一半——跟「哪个坐标靠前」无关。
                // 只用原版自己的方块状态判断（ChestBlock.TYPE = LEFT/RIGHT），并且要求对方类型
                // 正好相反、对方也指回我 —— 绝不会把两个独立的箱子错并成一个。
                // 坐标靠前的那一半负责在索引里记录整个大箱子（只出现一次）。
                // Iron Chests 的箱子 TYPE 恒为 SINGLE（GenericChestBlock 构造里写死），永远不会走进来。
                BlockState state = be.getBlockState();
                Container first = container;
                Container second = null;
                BlockPos partner = doubleChestPartner(p, state);
                if (partner != null && region.contains(partner)) {
                    BlockEntity pbe = level.getBlockEntity(partner);
                    if (pbe instanceof Container partnerContainer) {
                        if (p.compareTo(partner) > 0) {
                            continue; // 另一半会负责记录
                        }
                        if (isGuiFirst(state)) {
                            second = partnerContainer; // 我这一半在界面上排前面
                        } else {
                            second = container;        // 搭档那一半在界面上排前面
                            first = partnerContainer;
                        }
                    }
                }
                int firstSize = first.getContainerSize();
                int secondSize = second == null ? 0 : second.getContainerSize();
                int size = firstSize + secondSize;
                String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                ContainerRecord rec = new ContainerRecord(dimensionOf(region), p.immutable(), blockId, size);
                rec.doubleChest = second != null;
                rec.partner = second == null ? null : partner.immutable();
                if (rec.partner != null) {
                    // 两个箱子刚拼成一只大箱子：它俩各自的标签键要并到这一对的正式键上（BUG3）。
                    // 放在扫描里做，是因为「拼箱」本身没有事件，只能等下一轮扫到这一对时收口。
                    ContainerTags.mergePair(rec.dimension, rec.pos, rec.partner);
                } else if (partner != null) {
                    // 搭档在区域外（双联箱跨区域边界）：索引记录仍按原样各记一半，但标签是全局的，
                    // 两半的键照样要并到这一对的正式键上，否则两半会各显示一种分类（审查发现 4）。
                    ContainerTags.mergePair(rec.dimension, rec.pos, partner);
                }
                // 附魔 / 自定义名：按容器先收一份，循环走完再一次性提交给索引（别每格跨对象调一次）
                Map<String, Integer> metaEnchants = new TreeMap<>();
                Set<String> metaNames = new LinkedHashSet<>();
                for (int i = 0; i < firstSize; i++) {
                    ItemStack st = first.getItem(i);
                    if (st.isEmpty()) {
                        continue;
                    }
                    rec.add(i, st.copy());
                    totalStacks++;
                    totalItems += st.getCount();
                    collectMeta(st, metaEnchants, metaNames);
                }
                if (second != null) {
                    // 大箱子的槽位沿用游戏界面里的顺序：先界面前半的 0..n1-1，再后半的 n1..
                    for (int i = 0; i < secondSize; i++) {
                        ItemStack st = second.getItem(i);
                        if (st.isEmpty()) {
                            continue;
                        }
                        rec.add(firstSize + i, st.copy());
                        totalStacks++;
                        totalItems += st.getCount();
                        collectMeta(st, metaEnchants, metaNames);
                    }
                }
                WarehouseMod.INDEX.accept(rec);
                // accept() 自己也会按记录内容重算一遍（IndexRefresh 轮询那条路径只会调 accept），
                // 这里按契约再提交一次「扫描时收集」的结果；两边内容一致、按最大值/并集合并，重复提交无副作用。
                WarehouseMod.INDEX.addItemMeta(WarehouseIndex.key(rec.dimension, rec.pos), metaEnchants, metaNames);
                scannedContainers++;
            }
        }

        /**
         * 如果这一格是「双联箱」的一半，返回另一半的坐标；否则返回 null。
         *
         * 判断完全基于原版自己的方块状态，并且要求对方也「指回来」：
         *   - 自己必须是带 TYPE 的 ChestBlock，且 TYPE 不是 SINGLE
         *   - 对方的方块类型必须正好相反（LEFT ↔ RIGHT）
         *   - 对方按自己的朝向算出来的邻居必须还是我
         * 三条同时成立才认，所以两个恰好在隔壁的独立箱子绝不会被错并成一个大箱子。
         * Iron Chests 的箱子 TYPE 恒为 SINGLE，永远走不到这里。
         */
        private BlockPos doubleChestPartner(BlockPos p, BlockState state) {
            return partnerOf(level, p, state);
        }

        private void finish() {
            releaseHeldChunks();
            long ms = (System.nanoTime() - startNanos) / 1_000_000L;
            WarehouseIndex idx = WarehouseMod.INDEX;
            // 同一个物理箱子只许留一条记录：拼箱 / 区域边界 / 局部重扫都可能让搭档坐标上
            // 留下一份旧记录，整理时会被当成「另一个小箱子」（A1-b）
            int ghosts = idx.dropGhostRecords();
            if (ghosts > 0) {
                WarehouseMod.LOGGER.info("[warehouse-keeper] 清掉 {} 条双联箱重复记录（同一物理箱子只留一条）", ghosts);
            }
            // 收尾一律按索引里的记录重算汇总，全量扫描也不例外：
            // 同一个箱子可能同时落在两个重叠区域里（实测 2,101,2 既在 roof 又在 t1），
            // 它会被扫到两次 —— 本任务里的本地计数（scannedContainers/totalItems）会重复累加，
            // 全量扫描后 dirt 1664 → 3328、容器 21 → 22。重算一遍两个口径就都对了。
            idx.reaggregate();
            idx.scannedChunks = scannedChunks;
            idx.skippedChunks = skippedChunks;
            idx.lastScanMillis = ms;
            finished = true;
            WarehouseMod.LOGGER.info(
                    "[warehouse-keeper] 扫描完成: 区域={} 容器={} 物品种类={} 总数量={} 区块={}/{} 临时加载={} 耗时={}ms{}",
                    idx.lastScanRegion, idx.scannedContainers, idx.items.size(), idx.totalItems, scannedChunks, skippedChunks,
                    forceLoad ? "开" : "关", ms, partial ? "（局部重扫）" : "");
            // 扫描完成即存盘 —— 这样下次开档直接读硬盘，不用重扫
            String saveErr = IndexStore.save(idx, server);
            if (saveErr != null) {
                WarehouseMod.LOGGER.info("[warehouse-keeper] 索引未保存到硬盘: {}", saveErr);
            }
        }

        void abort(String reason) {
            releaseHeldChunks();
            abortReason = reason;
            WarehouseIndex idx = WarehouseMod.INDEX;
            // 中止也要和 finish() 一样收尾：start() 里的 removeContainersIn 只删记录不扣聚合，
            // 而本任务的局部计数在容器同时落在两个区域时会重复累加 —— 拿它直写全局会让
            // totalStacks/totalItems 与 items 表对不上、幽灵槽位残留、同一个箱子被重复聚合。
            // reaggregate() 本来就按 containers 重算总数与 scannedContainers，所以改成重算而不是直写。
            int ghosts = idx.dropGhostRecords();
            if (ghosts > 0) {
                WarehouseMod.LOGGER.info("[warehouse-keeper] 中止收尾：清掉 {} 条双联箱重复记录（同一物理箱子只留一条）", ghosts);
            }
            idx.reaggregate();
            idx.scannedChunks = scannedChunks;
            idx.skippedChunks = skippedChunks;
            idx.lastScanMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            finished = true;
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 扫描中止: {}", reason);
        }

        String progressText() {
            if (finished) {
                return abortReason != null ? ("已中止: " + abortReason) : "已完成";
            }
            int done = (cx - cx0) * (cz1 - cz0 + 1) + (cz - cz0);
            int total = Math.max(1, (cx1 - cx0 + 1) * (cz1 - cz0 + 1));
            long pct = Math.min(100, done * 100L / total);
            return String.format("进行中 %d%%  区域 %s (%d/%d)  已扫区块 %d  跳过(未加载) %d  临时加载 %s  容器 %d  物品 %d",
                    pct, region == null ? "?" : region.name, regionIndex + 1, regions.size(),
                    scannedChunks, skippedChunks, forceLoad ? "开" : "关", scannedContainers, totalItems);
        }
    }
}
