package com.ds.warehouse.porter;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.AppConfig;
import com.ds.warehouse.config.Region;
import com.ds.warehouse.config.RegionStore;
import com.ds.warehouse.index.ContainerRecord;
import com.ds.warehouse.index.Scanner;
import com.ds.warehouse.util.Names;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 搬运工的「身体」——用 Carpet 的 {@code /player} 指令生成真正的假人玩家。
 *
 * <p>可以同时存在好几个（见 {@link Bots}）：每个假人是一个真实的 {@code ServerPlayer}，
 * 有名字、能被看见，各自守着自己被分配的仓库。取货时站在箱子旁，送货时瞬移到玩家面前，
 * 手上还举着那叠东西。
 *
 * <p>刻意**只通过指令**跟 Carpet 打交道：不引用 Carpet 的任何类、不加 Mixin、
 * 不加编译期依赖（{@code fabric.mod.json} 里也只写 suggests 不写 depends）。
 * 所以整合包没装 Carpet 时本模组照常加载，只是退回「隐形搬运工」。
 *
 * <p>存档安全：假人只是服务端内存里的普通玩家实体，退场时
 * {@code /player <名字> kill}，不写 {@code world/playerdata}；
 * 它的名字固定，UUID 由名字派生（每次启动都一样），不会每次新建。
 */
public final class Body {

    /** 第一个假人的名字（Carpet 不接受中文名，所以只能用 ASCII） */
    public static final String NAME = "WarehouseBot";
    /** 头顶显示的名字。注意：玩家的名牌由客户端玩家列表决定，这里改了也不一定看得见 */
    public static final Component DISPLAY = Component.literal("仓库搬运工");

    /** 每个假人各自一套：生成冷却、这一条命是否已经准备过 */
    private static final Map<String, Integer> COOLDOWN = new HashMap<>();
    private static final Map<String, UUID> PREPARED = new HashMap<>();

    private Body() {
    }

    // ------------------------------------------------------------------
    // 可用性

    /** 整合包里有没有 Carpet（没有就退回隐形搬运工） */
    public static boolean available() {
        try {
            return FabricLoader.getInstance().isModLoaded("carpet");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 有 Carpet 并且用户没关掉人形 */
    public static boolean enabled() {
        return available() && AppConfig.get().porterBody;
    }

    /** 这个假人现在在世界里吗（Carpet 生成的假人就是普通 ServerPlayer） */
    public static ServerPlayer get(MinecraftServer server, String name) {
        if (server == null || name == null || name.isEmpty()) {
            return null;
        }
        return server.getPlayerList().getPlayerByName(name);
    }

    public static boolean present(MinecraftServer server, String name) {
        return get(server, name) != null;
    }

    // ------------------------------------------------------------------
    // 生成 / 退场 / 移动

    /**
     * 让这个假人待在指定位置。
     *
     * <p>已经在世界里就只做「准备」（模式、名字），不会重复生成。
     * 生成是异步的（下一 tick 才真的出现在世界里），所以返回值可能先是 false。
     *
     * @return 假人现在是否已经存在
     */
    public static boolean ensure(MinecraftServer server, String name, ServerLevel level,
                                 double x, double y, double z) {
        if (!enabled() || server == null || level == null || name == null || name.isEmpty()) {
            return false;
        }
        // 玩家按过「收回」的搬运工不再自动放出 —— 这是「收回」按钮的意义所在：
        // 否则下一次空闲待命（Porter.idleTick 每 tick 都会叫 ensure）就会把它当场放回来。
        // 想让它回来只有两条路：/warehouse bot spawn <名字> 或 /warehouse porter spawn。
        if (Bots.isRecalled(name)) {
            return false;
        }
        ServerPlayer bot = get(server, name);
        if (bot != null) {
            prepare(bot, name);
            return true;
        }
        PREPARED.remove(name);
        int cd = COOLDOWN.getOrDefault(name, 0);
        if (cd > 0) {
            COOLDOWN.put(name, cd - 1);
            return false;
        }
        // 只用已验证可用的形式：spawn at <整数>。<gamemode> 那个分支在 at 之后接不上，
        // 维度也不能在这里指定 —— 生成后交给 Java 侧的 teleportTo 精确落点/跨维度。
        int sx = (int) Math.floor(x);
        int sy = (int) Math.floor(y);
        int sz = (int) Math.floor(z);
        run(server, "player " + name + " spawn at " + sx + " " + sy + " " + sz);
        COOLDOWN.put(name, 40);
        return false;
    }

    /** 让它出现在指定坐标（支持跨维度，因为走的是 Java API 而不是指令） */
    public static void moveTo(MinecraftServer server, String name, ServerLevel level,
                              double x, double y, double z) {
        ServerPlayer bot = get(server, name);
        if (bot == null || level == null) {
            return;
        }
        if (bot.level() == level && bot.position().distanceToSqr(x, y, z) < 0.02) {
            return;
        }
        bot.teleportTo(level, x, y, z, Set.of(), bot.getYRot(), bot.getXRot(), true);
    }

    /**
     * 让它手里举着这叠东西（玩家能看见它手上拿着货）。
     *
     * <p>放进去的是**副本** —— 真正要送的东西由搬运工自己的账本管着，
     * 假人手里的只是表演道具，绝不能因为我们清空手而把货弄丢或弄出两份。
     */
    public static void hold(MinecraftServer server, String name, ItemStack stack) {
        ServerPlayer bot = get(server, name);
        if (bot == null) {
            return;
        }
        // 举在**副手**：原版捡东西只会塞进 0..35 格（背包+快捷栏），副手那格永远空着，
        // 所以「表演道具」和「假人真捡到的东西」不会抢同一个格子。
        // 注意：副手携货期间必须由调用方保证清空（交货、退场、任务收尾都要清），
        // remove/removeAll 里已经兜底 —— 否则假人被模组外原因杀死（Carpet 的 /player X kill、
        // 掉虚空、别的模组）时，这一格副本会掉成真实物品，等于复制。
        bot.setItemInHand(InteractionHand.OFF_HAND,
                stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
    }

    /** 假人副手的格子号（举着表演道具的那格，原版捡东西不会用）；没有假人时返回 -1 */
    public static int heldSlot(MinecraftServer server, String name) {
        return get(server, name) == null ? -1 : 40;
    }

    /**
     * 走到某处并**面朝**某个点。
     *
     * <p>原来的 {@link #moveTo} 到位就不发包（省流量），于是假人会「背对着箱子把东西搬走」，
     * 看着很出戏。整理仓库时每一只箱子都要它先转过去看着箱子，才像真的在开箱取货。
     */
    public static void moveToLook(MinecraftServer server, String name, ServerLevel level,
                                  double x, double y, double z,
                                  double lookX, double lookY, double lookZ) {
        ServerPlayer bot = get(server, name);
        if (bot == null || level == null) {
            return;
        }
        float[] rp = yawPitchOf(x, y + 1.62, z, lookX, lookY, lookZ);
        bot.teleportTo(level, x, y, z, Set.of(), rp[0], rp[1], true);
    }

    /** 只转头看一个点（不移动）。 */
    public static void lookAt(MinecraftServer server, String name,
                              double lookX, double lookY, double lookZ) {
        ServerPlayer bot = get(server, name);
        if (bot == null) {
            return;
        }
        float[] rp = yawPitchOf(bot.getX(), bot.getEyeY(), bot.getZ(), lookX, lookY, lookZ);
        bot.setYRot(rp[0]);
        bot.setYHeadRot(rp[0]);
        bot.setXRot(rp[1]);
        if (bot.level() instanceof ServerLevel sl) {
            bot.teleportTo(sl, bot.getX(), bot.getY(), bot.getZ(), Set.of(), rp[0], rp[1], true);
        }
    }

    private static float[] yawPitchOf(double fromX, double fromY, double fromZ,
                                      double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, flat)));
        return new float[] { yaw, pitch };
    }

    /**
     * 开／关箱盖（**纯视觉**）。
     *
     * <p>原版箱子的开合就是靠方块事件 {@code id=1} 广播给客户端（{@code ContainerOpenersCounter}
     * 自己也是发这个），所以这里照样发一次，客户端就会把盖子掀起来／放下去；音效顺手补上
     * （原版的音效是在 {@code openerCountChanged} 里放的，我们没走那条路）。
     *
     * <p><b>不碰容器内容、也不改任何状态</b>：搬运仍旧走 {@code Porter} 那条原子路径，
     * 所以开盖纯属表演，不影响件数、守恒、也不写存档。
     */
    public static void chestLid(ServerLevel level, BlockPos pos, boolean open) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return;
        }
        BlockState st = level.getBlockState(pos);
        lidEvent(level, pos, st, open);
        // 双联箱：另一半的盖子也得跟着动（原版两只箱子各有一份开合动画）
        BlockPos other = Scanner.partnerOf(level, pos, st);
        if (other != null && level.isLoaded(other)) {
            lidEvent(level, other, level.getBlockState(other), open);
        }
    }

    private static void lidEvent(ServerLevel level, BlockPos pos, BlockState st, boolean open) {
        level.blockEvent(pos, st.getBlock(), 1, open ? 1 : 0);
        var sound = st.getBlock() instanceof BarrelBlock
                ? (open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE)
                : (open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE);
        level.playSound(null, pos, sound, SoundSource.BLOCKS,
                0.5F, level.getRandom().nextFloat() * 0.1F + 0.9F);
    }

    /** 挥手动画（取货/交货时用） */
    public static void swing(MinecraftServer server, String name) {
        ServerPlayer bot = get(server, name);
        if (bot != null) {
            // 26.3：挥手动画被参数化 —— swing(InteractionHand, SwingAnimation, boolean)。
            // 第三个参数取 true，才与 26.2 的老语义完全一致（26.2 的 swing(hand) 内部就是 swing(hand, true)）。
            bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        }
    }

    /** 让这个假人退场。不留 playerdata，也不留 usercache 之外的东西。 */
    public static void remove(MinecraftServer server, String name) {
        if (server == null || name == null || name.isEmpty() || !available()) {
            return;
        }
        // 退场前**无条件**先清副手（A3）：kill 会让副手那格跟着假人一起掉落，
        // 而那一格只是表演副本 —— 不清就等于复制物品。这里兜底，不指望调用方记得清。
        hold(server, name, ItemStack.EMPTY);
        if (get(server, name) != null) {
            run(server, "player " + name + " kill");
        }
        PREPARED.remove(name);
        COOLDOWN.put(name, 0);
    }

    /** 全部退场（关服时用） */
    public static void removeAll(MinecraftServer server) {
        for (Bots.Entry e : Bots.list()) {
            // 批量退场同样先清副手（remove 里也会清一次，这里是批量路径的第一道保险）
            hold(server, e.name, ItemStack.EMPTY);
            remove(server, e.name);
        }
    }

    private static void prepare(ServerPlayer bot, String name) {
        if (bot.getUUID().equals(PREPARED.get(name))) {
            return;
        }
        PREPARED.put(name, bot.getUUID());
        try {
            // 创造模式：免疫一切伤害、不用吃饭，也不会被僵尸打死
            bot.setGameMode(GameType.CREATIVE);
            bot.setCustomName(DISPLAY);
        } catch (Throwable t) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 设置搬运工状态失败: {}", t.toString());
        }
    }

    // ------------------------------------------------------------------
    // 位置计算

    /** 一个具体的落点 */
    public record Home(ServerLevel level, double x, double y, double z) {
    }

    /**
     * 往下找落脚点时最多往下找几格。
     *
     * <p>仓库带房顶时要从房顶往下穿到屋里的地面；但也不能一路找到基岩层，
     * 免得「悬空平台」被当成人造地板、把人送到几十格以下的地洞里。
     */
    private static final int MAX_DROP = 24;

    /** 这个位置空不空（没有碰撞箱 = 实体能待在里面） */
    private static boolean free(ServerLevel lvl, BlockPos p) {
        return lvl.getBlockState(p).getCollisionShape(lvl, p).isEmpty();
    }

    /**
     * 这个位置站得住吗：脚和头两格都不挡人，脚下一格有支撑。
     *
     * <p>用碰撞箱判断而不是 {@code blocksMotion()}（26.2 里它已标记过时），
     * 顺带把火把、告示牌这类没有碰撞箱的东西当成「可以站」，更贴近实体真实的移动判定。
     */
    private static boolean standable(ServerLevel lvl, int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        if (!free(lvl, feet) || !free(lvl, feet.above())) {
            return false;
        }
        return !free(lvl, feet.below());
    }

    /**
     * 在这一列里从上往下找第一处「站得住、而且不是房顶/悬空平台」的高度。
     *
     * <p>为什么必须专门判房顶：以前直接用 {@code getHeight(MOTION_BLOCKING_NO_LEAVES)}，
     * 那是「这一列最高的阻挡方块」——仓库上方有房顶时它返回的就是房顶，搬运工于是站在屋顶上。
     * 现在改成：找到一个落脚点后，看它下面第 2、3 格是不是空气；是空气说明脚下是房顶或
     * 悬空平台，继续往下找，直到站在真正的地面上。
     * <p>平地（草/土/石）不会命中这个判定，所以普通情况下结果和以前完全一样。
     *
     * @param fromY 从哪一格开始往下找（一般就是原来的 {@code getHeight} 结果）
     * @return 找不到地板就退回第一个站得住的位置；整列都站不住才是 null
     */
    private static Integer standableY(ServerLevel lvl, int x, int z, int fromY) {
        int stopY = Math.max(lvl.getMinY() + 1, fromY - MAX_DROP);
        Integer firstHit = null;
        for (int y = fromY; y > stopY; y--) {
            if (!standable(lvl, x, y, z)) {
                continue;
            }
            if (firstHit == null) {
                firstHit = y;
            }
            // 脚下那层下面是空的 → 房顶/悬空平台，继续往下找屋里或地面上的落脚点
            if (free(lvl, new BlockPos(x, y - 2, z)) && free(lvl, new BlockPos(x, y - 3, z))) {
                continue;
            }
            return y;
        }
        return firstHit;
    }

    /** 某个仓库区域的正中心、站在地面上（会穿到房顶下面去）；区域不存在就返回 null */
    public static Home centerOf(MinecraftServer server, String regionName) {
        if (server == null || regionName == null || regionName.isEmpty()) {
            return null;
        }
        Region r = RegionStore.REGIONS.get(regionName);
        if (r == null) {
            return null;
        }
        ServerLevel lvl = Scanner.levelOf(server, Scanner.dimensionOf(r));
        if (lvl == null) {
            return null;
        }
        BlockPos lo = r.min();
        BlockPos hi = r.max();
        int x = (lo.getX() + hi.getX()) / 2;
        int z = (lo.getZ() + hi.getZ()) / 2;
        Integer y = standableY(lvl, x, z, lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
        if (y == null) {
            return null;
        }
        return new Home(lvl, x + 0.5, y, z + 0.5);
    }

    /**
     * 仓库里「箱子旁边」那一格：取索引里离区域中心最近的容器，站到它正面那一格。
     *
     * <p>箱子必然在屋里，所以这个落点天生不会被房顶坑到 —— 这是没设自定义值守点时的首选。
     *
     * @return 这个仓库里还没扫到容器、或旁边站不下人时返回 null
     */
    public static Home containerSpot(MinecraftServer server, String regionName) {
        if (server == null || regionName == null || regionName.isEmpty()) {
            return null;
        }
        Region r = RegionStore.REGIONS.get(regionName);
        if (r == null) {
            return null;
        }
        ServerLevel lvl = Scanner.levelOf(server, Scanner.dimensionOf(r));
        if (lvl == null) {
            return null;
        }
        BlockPos lo = r.min();
        BlockPos hi = r.max();
        int cx = (lo.getX() + hi.getX()) / 2;
        int cz = (lo.getZ() + hi.getZ()) / 2;
        ContainerRecord best = null;
        long bestDist = Long.MAX_VALUE;
        for (ContainerRecord rec : WarehouseMod.INDEX.containers.values()) {
            if (rec == null || !r.dimension.equals(rec.dimension) || !r.contains(rec.pos)) {
                continue;
            }
            long dx = rec.pos.getX() - (long) cx;
            long dz = rec.pos.getZ() - (long) cz;
            long d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = rec;
            }
        }
        if (best == null) {
            return null;
        }
        // 先试「箱子正面那一格」（玩家平时开箱子站的地方）
        double[] front = chestSpot(lvl, best.pos);
        BlockPos fp = BlockPos.containing(front[0], front[1], front[2]);
        if (standable(lvl, fp.getX(), fp.getY(), fp.getZ())) {
            return new Home(lvl, fp.getX() + 0.5, fp.getY(), fp.getZ() + 0.5);
        }
        // 再试箱子前后左右同一高度的格子（不站到箱子顶上）
        Direction[] around = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (Direction d : around) {
            BlockPos p = best.pos.relative(d);
            if (standable(lvl, p.getX(), p.getY(), p.getZ())) {
                return new Home(lvl, p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            }
        }
        return null;
    }

    /**
     * 这个假人的值守点：
     * ① 它自己设过的值守点（{@code /warehouse bot spot <名字>}）；
     * ② 名册里给它分了仓库 → 那个仓库里**箱子旁边**；没扫到箱子时用仓库正中心（都带穿房顶）；
     * ③ 否则用全局设过的值守点（{@code /warehouse porter home}）；
     * ④ 否则第一个仓库区域的正中心；⑤ 再不行就主世界原点地面。
     */
    public static Home standby(MinecraftServer server, String name) {
        if (server == null) {
            return null;
        }
        Bots.Spot spot = Bots.spotOf(name);
        if (spot != null) {
            ServerLevel lvl = Scanner.levelOf(server, spot.dim());
            if (lvl != null) {
                return new Home(lvl, spot.x(), spot.y(), spot.z());
            }
        }
        String region = Bots.regionOf(name);
        if (region != null && !region.isEmpty()) {
            Home h = containerSpot(server, region);
            if (h == null) {
                h = centerOf(server, region);
            }
            if (h != null) {
                return h;
            }
        }
        AppConfig cfg = AppConfig.get();
        if (cfg.botHomeDim != null && !cfg.botHomeDim.isEmpty()) {
            ServerLevel lvl = Scanner.levelOf(server, cfg.botHomeDim);
            if (lvl != null) {
                return new Home(lvl, cfg.botHomeX, cfg.botHomeY, cfg.botHomeZ);
            }
        }
        for (Region r : RegionStore.REGIONS.values()) {
            Home h = centerOf(server, r.name);
            if (h != null) {
                return h;
            }
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return null;
        }
        int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        return new Home(overworld, 0.5, y, 0.5);
    }

    /** 把全局值守点设成这个玩家现在站的地方（没给它分仓库的假人会用这个点） */
    public static void setHomeHere(ServerPlayer player) {
        AppConfig cfg = AppConfig.get();
        cfg.botHomeDim = player.level().dimension().identifier().toString();
        cfg.botHomeX = player.getX();
        cfg.botHomeY = player.getY();
        cfg.botHomeZ = player.getZ();
        cfg.save();
    }

    /**
     * 把「某个假人自己的值守点」设成这个玩家现在站的地方。
     *
     * <p>只影响这一个假人；{@code /warehouse porter home} 设的是「所有没分配仓库的假人」
     * 共用的全局点，两者互不覆盖。
     *
     * @return false = 名册里没这个假人
     */
    public static boolean setSpotHere(ServerPlayer player, String name) {
        if (Bots.of(name) == null) {
            return false;
        }
        String dim = player.level().dimension().identifier().toString();
        return Bots.setSpot(name, dim, player.getX(), player.getY(), player.getZ());
    }

    /** 箱子「前面」那一格 —— 玩家平时开箱子站的地方 */
    public static double[] chestSpot(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        Direction d = st.hasProperty(ChestBlock.FACING) ? st.getValue(ChestBlock.FACING) : Direction.SOUTH;
        BlockPos s = pos.relative(d);
        return new double[] { s.getX() + 0.5, s.getY(), s.getZ() + 0.5 };
    }

    /** 玩家正前方 1.4 格 —— 假人出现在这儿，看起来像是走到你面前 */
    public static double[] frontOf(ServerPlayer target) {
        double rad = Math.toRadians(target.getYRot());
        return new double[] {
                target.getX() - Math.sin(rad) * 1.4,
                target.getY(),
                target.getZ() + Math.cos(rad) * 1.4
        };
    }

    // ------------------------------------------------------------------
    // 说明文字 / JSON

    /** 一句话说明这个假人在哪。{@code name} 为 null 时说的是「人形功能」整体 */
    public static String describe(MinecraftServer server, String name) {
        if (!available()) {
            return "人形：不可用（整合包未安装 Carpet，搬运工当前为隐形形态，功能相同）";
        }
        if (!AppConfig.get().porterBody) {
            return "人形：已关闭（用 /warehouse porter body on 开启）";
        }
        if (name == null) {
            return "人形：共 " + Bots.size() + " 个搬运工";
        }
        ServerPlayer bot = get(server, name);
        String where = Bots.regionOf(name);
        String hint = (where == null || where.isEmpty()) ? "" : "，值守仓库「" + where + "」";
        String own = Bots.spotText(name);
        if (!own.isEmpty()) {
            hint = hint + "，值守点 " + own + "（自行设置）";
        }
        if (bot == null) {
            return "人形：" + name + " 目前不在世界中" + hint;
        }
        return String.format(Locale.ROOT, "人形：%s 位于 (%.0f, %.0f, %.0f) %s%s",
                name, bot.getX(), bot.getY(), bot.getZ(),
                Names.dimension(bot.level().dimension().identifier().toString()), hint);
    }

    public static Map<String, Object> json(MinecraftServer server, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supported", available());
        m.put("enabled", enabled());
        m.put("name", name);
        m.put("region", Bots.regionOf(name) == null ? "" : Bots.regionOf(name));
        // 空串 = 自动判定值守点；有值 = 自己设的（形如 "15,-60,4"）
        m.put("spot", Bots.spotText(name));
        m.put("text", describe(server, name));
        ServerPlayer bot = get(server, name);
        m.put("present", bot != null);
        if (bot != null) {
            m.put("x", Math.round(bot.getX()));
            m.put("y", Math.round(bot.getY()));
            m.put("z", Math.round(bot.getZ()));
            m.put("dimension", bot.level().dimension().identifier().toString());
        }
        return m;
    }

    // ------------------------------------------------------------------

    /** 派发一条指令。用原版指令派发器转发给 Carpet，不碰它的内部类。 */
    private static void run(MinecraftServer server, String command) {
        try {
            // 26.2 里 performPrefixedCommand 是实例方法，得从服务器的指令派发器上拿
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        } catch (Throwable t) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 搬运工指令执行失败: {} — {}", command, t.toString());
        }
    }
}
