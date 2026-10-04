package com.ds.warehouse.net;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.ContainerTags;
import com.ds.warehouse.config.Region;
import com.ds.warehouse.config.RegionStore;
import com.ds.warehouse.index.ContainerRecord;
import com.ds.warehouse.index.ItemIds;
import com.ds.warehouse.index.Scanner;
import com.ds.warehouse.index.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import com.ds.warehouse.porter.Body;
import com.ds.warehouse.porter.Bots;
import com.ds.warehouse.porter.Porter;
import com.ds.warehouse.porter.Tasks;
import com.ds.warehouse.util.Admin;
import com.ds.warehouse.util.Categories;
import com.ds.warehouse.util.Names;
import com.ds.warehouse.util.PlayerPerms;
import com.ds.warehouse.web.WebSnapshot;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「仓库区域 + 每个仓库里有什么」推给装了本模组的客户端（联机同步）。
 *
 * <p>推送时机：
 * <ul>
 *   <li>玩家刚进服务器（{@link ServerPlayConnectionEvents#JOIN}）；</li>
 *   <li>之后在服务端 tick 里定期比对，内容变了才推（仓库改了、扫描出结果、搬运工搬了东西）。</li>
 * </ul>
 * 内容没变时只做一次便宜的比对，不发包；内容变了最快 2 秒推一次，
 * 免得扫描期间把网络刷爆。
 */
public final class SnapshotSync {

    /** 单包上限（registerLarge 用）；超过就截断物品列表 */
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    /** 每个仓库最多同步多少种物品 —— 防超大仓库把包撑爆，截断时会在面板上提示 */
    private static final int MAX_ITEMS_PER_REGION = 600;
    /** 每个仓库最多同步多少只箱子（「箱子总览」）。一只约 150 字节，600 只 ≈ 90 KB，离单包上限还很远 */
    private static final int MAX_BOXES_PER_REGION = 600;
    /** 扫描期间两次推送之间的最小间隔（那时快照一直在变，别刷屏） */
    private static final int MIN_INTERVAL_TICKS = 40;
    /** 平时内容变了以后的最小推送间隔 —— 只要 0.2 秒，面板要即时看到「刚加完假人 / 刚改完权限」 */
    private static final int HOT_INTERVAL_TICKS = 4;
    /** 内容没变时多久比对一次 */
    private static final int IDLE_INTERVAL_TICKS = 20;

    private static final Gson GSON = new Gson();

    /** 给普通客户端的那一份（不含账号权限） */
    private static byte[] lastPlain;
    /** 给管理员客户端的那一份（多一段账号权限，管理员才收得到） */
    private static byte[] lastAdmin;
    private static int cooldown;
    private static long lastRevision = -1L;
    private static String lastSig = "";
    /**
     * 每个在线玩家上一次被看到的「管理员身份」。
     *
     * <p>快照分两份（普通 / 管理员版，后者才带账号权限表）。玩家进服时还不是 OP 的话，
     * 他拿到的是普通版；之后被 {@code op} 了必须再收一份管理员版，否则面板「权限」页
     * 永远显示「共 0 个」。这里记住上次看到的状态，一变就重发。
     */
    private static final java.util.Map<java.util.UUID, Boolean> ADMIN_SEEN = new java.util.HashMap<>();
    /**
     * 标签也得有个上限：标签数随打标的箱子无限长，而 items/boxes 都是每仓截断的。
     * 过万条标签会把包顶到单包上限，那时 {@link #send} 会**对所有玩家**都发不出去。
     * 4000 条 ≈ 250 KB，离 4 MB 还很远。
     */
    private static final int MAX_TAGS = 4000;

    /**
     * 上一个冷却周期**没送到**的玩家 → 补发截止时刻（毫秒）。
     *
     * <p>以前 {@link #send} 失败（客户端还没准备好收包 / 包过大 / 抛异常）只是静默返回 false，
     * 而版本记账在发送**之前**就提交了 ⇒ 这名玩家要等到下一次数据变化或重新进服才可能补上。
     */
    private static final java.util.Map<java.util.UUID, Long> PENDING = new java.util.HashMap<>();
    /**
     * 补发最多坚持多久。
     *
     * <p>要够长，才能盖住「刚进服那几秒客户端还没准备好收包」；又要有上限 —— 没装模组的玩家
     * {@code canSend} 永远是 false，不设期限的话他每次都会重新进补发名单，空闲时的冷却也就永远压不下去。
     */
    private static final long PENDING_TTL_MS = 60_000L;
    /** 因为超过单包上限而被跳过的次数（{@code /warehouse status} 里会显示） */
    private static volatile int oversizeSkips;
    /** 上一次「快照过大」的日志时刻：十秒内不重复刷屏 */
    private static long lastOversizeLogAt;

    /** {@link #send} 的结果：送出去了 */
    private static final int SENT = 0;
    /** 这个客户端收不了（没有内容可发）：不必重试 */
    private static final int SKIP = 1;
    /** 这次没送成（客户端还没准备好 / 发送异常）：下个周期补发 */
    private static final int FAILED = 2;

    private SnapshotSync() {
    }

    /** 在公共入口调用一次：注册包类型 + 玩家进服时推一次 */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay()
                .registerLarge(SnapshotPayload.TYPE, SnapshotPayload.CODEC, MAX_BYTES);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            long revision = WarehouseMod.INDEX.revision();
            String sig = signature(server);
            if (lastPlain == null || revision != lastRevision || !sig.equals(lastSig)) {
                rebuild(server);
                lastRevision = revision;
                lastSig = sig;
            }
            // 刚进服这一下客户端可能还没准备好收包（作者在查询通道踩过同样的坑）：
            // 没送成要记进补发名单，下个周期接着送，而不是让这名玩家整场空着。
            if (send(player, Admin.isAdmin(server, player)) == FAILED) {
                markPending(player.getUUID());
            } else {
                PENDING.remove(player.getUUID());
            }
        });
    }

    /** 每个服务端 tick 调一次 */
    public static void tick(MinecraftServer server) {
        // 玩家的管理员身份变了（刚被 op / 被 deop）⇒ 必须尽快重发：管理员版快照才带权限表。
        // 这个比对很便宜，所以每 tick 都做，不受下面的冷却影响
        boolean adminChanged = false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean a = Admin.isAdmin(server, p);
            Boolean old = ADMIN_SEEN.get(p.getUUID());
            if (old == null || old.booleanValue() != a) {
                ADMIN_SEEN.put(p.getUUID(), a);
                adminChanged = true;
            }
        }
        // 补发名单：下线的人要清掉（免得这张表随进出玩家无限长大），过了期限的也要清掉
        // （没装模组的玩家永远送不到，不能让他把「空闲冷却」一直顶住）
        if (!PENDING.isEmpty()) {
            long now = System.currentTimeMillis();
            PENDING.entrySet().removeIf(e -> e.getValue() < now
                    || server.getPlayerList().getPlayer(e.getKey()) == null);
        }
        // 几个便宜的比对：索引内容改过没有、仓库/假人/权限/分类体系/假人在不在岗改过没有。
        // 都没变就直接返回，不用重新序列化一遍（大仓库能省下不少时间）
        long revision = WarehouseMod.INDEX.revision();
        String sig = signature(server);
        boolean changed = adminChanged || lastPlain == null
                || revision != lastRevision || !sig.equals(lastSig);
        // 内容没变、也没有人要补发 ⇒ 把冷却归零。
        // 以前这里把冷却重置成 IDLE_INTERVAL_TICKS，于是「闲着的时候改了东西」还要白等 1 秒才推出去
        // —— 玩家看到的就是「收回假人 / 贴新标签后，别人那边要等一秒才有反应」。
        if (!changed && PENDING.isEmpty()) {
            cooldown = 0;
            return;
        }
        // 内容变了就尽快推，面板才能即时看到结果：
        // 扫描期间索引每 tick 都在变，那时才用长冷却压住推送频率；平时只留一个很小的间隔（约 0.2 秒）。
        // 管理员身份变化优先级最高，可以穿透冷却
        if (cooldown > 0 && !adminChanged) {
            cooldown--;
            return;
        }
        if (changed) {
            // 记账挪到**组包成功之后**：组包一旦抛异常（超大快照 / 序列化问题），先把指纹记成
            // 「已推送」就会静默停更 —— 内容不再变，于是永远不再重建，玩家一直看旧包。
            try {
                rebuild(server);
            } catch (Throwable t) {
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 组包仓库快照失败，稍后重试: {}", t.toString());
                cooldown = HOT_INTERVAL_TICKS;
                return;
            }
            lastRevision = revision;
            lastSig = sig;
            cooldown = Scanner.isRunning() ? MIN_INTERVAL_TICKS : HOT_INTERVAL_TICKS;
        } else {
            // 纯补发：也留一个很小的间隔，别每个 tick 都去撞同一个坏连接
            cooldown = HOT_INTERVAL_TICKS;
        }
        int sent = 0;
        int retry = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            // 只有「内容变了 / 身份变了 / 上次没送到」的人才重发，别人一个字节都不发
            if (!changed && !adminChanged && !PENDING.containsKey(p.getUUID())) {
                continue;
            }
            int r = send(p, Admin.isAdmin(server, p));
            if (r == SENT) {
                PENDING.remove(p.getUUID());
                sent++;
            } else if (r == FAILED) {
                markPending(p.getUUID());
                retry++;
            } else {
                PENDING.remove(p.getUUID());
            }
        }
        if (sent > 0) {
            WarehouseMod.LOGGER.info("[warehouse-keeper] 已向 {} 名玩家同步仓库快照（{} KB）", sent,
                    lastPlain.length / 1024);
        }
        if (retry > 0) {
            WarehouseMod.LOGGER.debug("[warehouse-keeper] 有 {} 名玩家的仓库快照这次没送达，下个周期补发", retry);
        }
    }

    /** 重新组包 + 序列化两份（普通 / 管理员） */
    private static void rebuild(MinecraftServer server) {
        Dto dto = buildDto(server);
        dto.accounts = null;
        lastPlain = json(dto);
        dto.accounts = accountsDto();
        lastAdmin = json(dto);
    }

    private static byte[] json(Dto dto) {
        return GSON.toJson(dto).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 便宜的变化检测：分类体系版本 + 仓库定义 + 标签 + 假人名册（值守仓库 / 自定义值守点 / 显示名 /
     * 在忙什么 / 在不在岗 / 进度粗档）+ 账号权限。
     *
     * <p>以前这里刻意不含假人进度（那玩意儿每几秒就变，会让快照一路刷包）。现在改成只取<b>粗档</b>
     * （{@link Tasks#progressStage(String)}，每 5 只箱子一档）：面板上的「已整理 x/y 箱」能跟着走，
     * 刷包频率却只有原来的五分之一。
     */
    private static String signature(MinecraftServer server) {
        // 分类体系版本：覆盖表重读 / 页签表重建 / 记忆化清空都要能被看见 ——
        // 否则「改了覆盖表但箱子没动」在服务端看起来什么都没变，玩家的面板会一直用旧的分类。
        StringBuilder sb = new StringBuilder("K:").append(Categories.revision())
                .append(':').append(Categories.order().hashCode())
                // 物品显示名刷新的次数：名字只改 INDEX.items[].displayName，索引 revision 与仓库范围都
                // 看不出来，不并进指纹的话「管理员推中文名」在别的客户端就一直不生效（F7 的另一半）。
                .append(":N").append(ViewQueryService.namesRevision()).append('|');
        sb.append(regionSignature());
        for (Map.Entry<String, ContainerTags.Tag> en : new ArrayList<>(ContainerTags.all().entrySet())) {
            ContainerTags.Tag tag = en.getValue();
            if (tag == null) {
                continue;
            }
            sb.append("T:").append(en.getKey()).append('=').append(tag.staging ? 'S' : '-')
                    .append(tag.isAutoMode() ? 'A' : 'M').append('#').append(catKey(tag)).append('|');
        }
        for (Bots.Entry e : Bots.list()) {
            sb.append(e.name).append('@').append(e.region == null ? "" : e.region)
                    .append('#').append(Bots.spotText(e.name))
                    // 显示名也是签名的一部分：改了昵称，面板必须立刻刷新。
                    // 用 displayOf（已回落成注册名）而不是 e.display：null 与「等于注册名」在界面上
                    // 是同一个结果，归一化后不会因为两种写法各刷一次包。
                    .append("/D:").append(Bots.displayOf(e.name))
                    // 在忙什么（整理 / 送货 / 闲置）也算签名的一部分：状态一变面板就要刷，
                    // 但刻意不含进度百分比（那东西几秒就变一次，会让快照一路推包）
                    .append("/B:").append(busyKind(e.name))
                    // 在不在岗（上岗 / 收回）也进指纹：面板那一列按钮按它变
                    .append("/P:").append(Body.present(server, e.name) ? '1' : '0')
                    // 进度只取「粗档」（每 5 只箱子一档）：面板能跟着走，又不会每搬一件就刷一次包
                    .append("/G:").append(Tasks.progressStage(e.name)).append('|');
        }
        for (PlayerPerms.Row a : PlayerPerms.rows()) {
            sb.append(a.name()).append(a.take() ? '1' : '0').append(a.bot() ? '1' : '0')
                    .append(a.tidy() ? '1' : '0').append(';');
        }
        return sb.toString();
    }

    /**
     * 标签上的类目 → 规范的类目**键**（签名与快照共用一份口径）。
     *
     * <p>标签里存的可能是创造栏页签键（新）、旧中文类目名（父类）或什么都没有。统一过一遍
     * {@link Categories#resolve}：认得出就换成规范键；认不出、或本来就是旧父类名（旧名是合法输入，
     * 代表「它原来管的那一片」）就原样留着 —— 服务端匹配靠 {@code Categories.matches} 展开子类。
     *
     * <p>返回空串表示「未贴标签 / 暂存」。**协议字段 {@code TagDto.category} 的语义没有变**，仍是键。
     */
    private static String catKey(ContainerTags.Tag tag) {
        String raw = tag.effectiveCategory();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String resolved = Categories.resolve(raw);
        return resolved != null ? resolved : raw;
    }

    /**
     * 这个搬运工现在在忙什么（闲置就是空串）。
     *
     * <p>只返回状态本身、不带进度：进度里含坐标与秒数，变化太频繁，放进签名会让快照一路刷包。
     */
    private static String busyKind(String name) {
        if (Tasks.busy(name)) {
            String kind = Tasks.kindOf(name);
            return kind == null ? "忙" : kind;
        }
        return Porter.busyWithOrder(name) ? "送货" : "";
    }

    /** 仓库定义（名字 / 范围 / 维度）有没有变过 —— 比序列化整个快照便宜得多 */
    private static String regionSignature() {
        StringBuilder sb = new StringBuilder();
        for (Region r : RegionStore.REGIONS.values()) {
            sb.append(r.name).append(':')
                    .append(r.from[0]).append(',').append(r.from[1]).append(',').append(r.from[2]).append('-')
                    .append(r.to[0]).append(',').append(r.to[1]).append(',').append(r.to[2])
                    .append(r.fullHeight ? 'F' : 'f').append(r.dimension).append('|');
        }
        return sb.toString();
    }

    /**
     * 只有装了本模组、且声明能收这个包的客户端才推（原版客户端不会被塞未知包踢下线）。
     *
     * @return {@link #SENT} 送出去了；{@link #SKIP} 这次跳过而且不必重试；
     *         {@link #FAILED} 这次没送成 —— 调用方要把这名玩家记进 {@link #PENDING}，下个周期补发
     */
    private static int send(ServerPlayer player, boolean admin) {
        byte[] json = admin ? lastAdmin : lastPlain;
        if (json == null) {
            return SKIP;
        }
        if (!ServerPlayNetworking.canSend(player, SnapshotPayload.TYPE)) {
            // 客户端还没准备好收这个包：刚进服握手还没走完，或者根本没装模组。
            // 两种都记成待补发 —— 没装模组的那位只是每次做一个便宜的查表，不占带宽；
            // 而「刚进服那一下」正是以前会静默丢掉、让玩家整场看不到仓库的那种情况。
            return FAILED;
        }
        // 超过注册的单包上限就直接不发：宁可玩家这次没刷新，也不要让 send 抛异常打断整个 tick
        if (json.length > MAX_BYTES) {
            oversizeSkips++;
            long now = System.currentTimeMillis();
            if (now - lastOversizeLogAt > 10_000L) {
                lastOversizeLogAt = now;
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 仓库快照过大（{} KB，上限 {} KB），已跳过推送"
                        + "（第 {} 次，/warehouse status 可看计数）；请缩小仓库范围或减少箱子 / 标签数量",
                        json.length / 1024, MAX_BYTES / 1024, oversizeSkips);
            }
            return SKIP;
        }
        try {
            ServerPlayNetworking.send(player, new SnapshotPayload(json));
        } catch (Throwable t) {
            // 单个玩家发包失败（连接正在断开等）不能影响其它玩家与后续 tick
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 向 {} 推送仓库快照失败", player.getName().getString(), t);
            return FAILED;
        }
        return SENT;
    }

    /**
     * 同步通道的自查数据，给 {@code /warehouse status} 用。
     *
     * <p>「快照过大」以前只写一次日志、玩家那边毫无提示，出了问题根本查不出来；这里把它和一个周期内
     * 没送达的玩家数摆到台面上。
     *
     * @return 一行文字
     */
    public static String statusText() {
        int kb = lastPlain == null ? 0 : lastPlain.length / 1024;
        return "仓库同步: 上次组包 " + kb + " KB（上限 " + (MAX_BYTES / 1024) + " KB）"
                + "  待补发 " + PENDING.size() + " 人"
                + "  过大跳过 " + oversizeSkips + " 次";
    }

    /**
     * 记进补发名单（带期限，见 {@link #PENDING_TTL_MS}）。
     *
     * <p>用 {@code putIfAbsent}：**不延长**已有的期限。没装模组的玩家 {@code canSend} 永远为 false，
     * 如果每次失败都把期限往后推，这张表就永远清不掉、空闲时的冷却也永远压不下去。现在他最多被
     * 重试一分钟，之后静默退出名单（真正在握手的模组客户端两三秒内就收上了，一分钟足够富余）。
     */
    private static void markPending(java.util.UUID id) {
        PENDING.putIfAbsent(id, System.currentTimeMillis() + PENDING_TTL_MS);
    }

    /**
     * 玩家登出时清掉按 UUID 记的状态。
     *
     * <p>这些表只在玩家进服时写入，不清就会随进出的玩家无限长大。
     */
    public static void forget(java.util.UUID id) {
        ADMIN_SEEN.remove(id);
        PENDING.remove(id);
    }

    // ------------------------------------------------------------------
    // 组包

    private static Dto buildDto(MinecraftServer server) {
        Dto dto = new Dto();
        dto.version = WarehouseMod.VERSION;
        dto.at = System.currentTimeMillis();
        dto.regions = new ArrayList<>();
        dto.bots = new ArrayList<>();
        // 类目清单（1.1.1）：客户端进程里没有 MinecraftServer，自己算不出这套键
        // ⇒ 由服务端发过去，访客的标签栏下拉才会与房主完全一致（连顺序都一样）。
        dto.categories = new ArrayList<>(Categories.order());

        WebSnapshot snap = WebSnapshot.current();
        for (Region r : RegionStore.REGIONS.values()) {
            RegionDto rd = new RegionDto();
            rd.name = r.name;
            rd.dimension = r.dimension;
            rd.fullHeight = r.fullHeight;
            rd.from = new int[]{r.from[0], r.from[1], r.from[2]};
            rd.to = new int[]{r.to[0], r.to[1], r.to[2]};

            WebSnapshot.View view = snap.view(r.name);
            rd.totalItems = view.totalItems;
            rd.usedSlots = view.usedSlots;
            rd.capacity = view.capacity;
            rd.items = new ArrayList<>();
            int n = 0;
            outer:
            for (WebSnapshot.ItemRow row : view.items) {
                // 0.23.0：附魔书/附魔装备按附魔拆行（同一件物品可能有十几种附魔组合）
                for (ItemDto item : variantItems(row)) {
                    if (n++ >= MAX_ITEMS_PER_REGION) {
                        dto.truncated = true;
                        break outer;
                    }
                    rd.items.add(item);
                }
            }

            // 箱子总览（批次 4）：一只箱子一行。
            // 双联箱在索引里本来就是**同一条记录**（Scanner 只给坐标较小的一半建档），所以这里天然不会出现两行。
            // 数据全部来自既有读模型与索引，不重扫、不改扫描器。
            rd.boxes = new ArrayList<>();
            int bn = 0;
            for (WebSnapshot.ContainerRow row : snap.containers(r.name)) {
                if (bn++ >= MAX_BOXES_PER_REGION) {
                    dto.truncated = true;
                    break;
                }
                rd.boxes.add(boxDto(row));
            }
            dto.regions.add(rd);
        }

        // 假人名册：面板的「搬运工」页要用（名字 / 值守哪个仓库 / 值守点 / 在忙什么）
        for (Bots.Entry e : Bots.list()) {
            BotDto bot = new BotDto();
            bot.name = e.name;
            // 显示名：只给界面看，服务端已经回落成注册名（没设昵称时两者相同）
            bot.display = Bots.displayOf(e.name);
            bot.region = e.region == null ? "" : e.region;
            bot.spot = Bots.spotText(e.name);
            String kind = Tasks.busy(e.name) ? Tasks.kindOf(e.name) : null;
            if (kind == null && Porter.busyWithOrder(e.name)) {
                kind = "送货";
            }
            if (kind != null) {
                // 批次 6 · D5：整理时把「已整理 x/y 箱 · 剩约 n 秒」接在忙碌文字后面（面板直接显示这段）
                String progress = Tasks.progressOf(e.name);
                if (!progress.isEmpty()) {
                    kind = kind + " · " + progress;
                }
            }
            bot.busy = kind == null ? "" : kind;
            bot.present = Body.present(server, e.name);
            dto.bots.add(bot);
        }

        // 箱子标签（批次 3）：游戏内标签栏与网页面板都要看「这只箱子现在贴的是什么」
        dto.tags = new ArrayList<>();
        for (Map.Entry<String, ContainerTags.Tag> en : new ArrayList<>(ContainerTags.all().entrySet())) {
            // 标签没有「每仓截断」那层保护，这里必须有：标签多到把包顶爆时，受害的是所有玩家
            if (dto.tags.size() >= MAX_TAGS) {
                dto.truncated = true;
                break;
            }
            ContainerTags.Tag tag = en.getValue();
            if (tag == null) {
                continue;
            }
            TagDto td = new TagDto();
            td.key = en.getKey();
            td.label = tag.label();
            // 协议字段 category 的语义不变（仍是**键**，客户端拿它跟面板/过滤比），
            // 只是取值统一走 catKey 规范化；给人看的中文名由客户端 Categories.displayName 翻
            td.category = catKey(tag);
            td.mode = tag.isAutoMode() ? ContainerTags.AUTO : ContainerTags.MANUAL;
            td.staging = tag.staging;
            dto.tags.add(td);
        }
        return dto;
    }

    /**
     * 账号权限（只发给管理员客户端）。
     *
     * <p>刻意只带「名字 + 三项开关」：密码的 salt/hash、注册时间、最后登录时间一律不出服务端。
     * 批次 5 阶段 2 起数据来自游戏内玩家权限表（网页账号库删掉之后这张表还在）。
     */
    private static List<AccountDto> accountsDto() {
        List<AccountDto> out = new ArrayList<>();
        for (PlayerPerms.Row a : PlayerPerms.rows()) {
            AccountDto d = new AccountDto();
            d.name = a.name();
            d.take = a.take();
            d.bot = a.bot();
            d.tidy = a.tidy();
            out.add(d);
        }
        return out;
    }

    /** 面板上鼠标悬停时显示的那句话（跟着物品一起发过去，客户端就不用再拼了） */
    private static String locationText(WebSnapshot.ItemRow row) {
        if (row.locations.isEmpty()) {
            return "";
        }
        return locText(row.locations.get(0), row.refs);
    }

    /** 一格的「位置 + 第几格（+ 共几处）」文案；{@code refs} 是这个变体/物品占的处数 */
    private static String locText(WebSnapshot.LocRow loc, int refs) {
        return loc.pos + " 第 " + (loc.slot + 1) + " 格" + (refs > 1 ? "，共 " + refs + " 处" : "");
    }

    /**
     * 把索引里的一只物品（{@code ItemRow}）拆成「附魔变体」若干行。
     *
     * <p>0.23.0：取货页要能看见属性、按附魔精确取货，所以**每种附魔组合单独一行**。
     * 逐格附魔从内存索引里现取（与 {@code ViewQueryService.itemMeta} 同一份数据、同一格式），
     * 不重扫世界。没有附魔的物品照旧只有一行（{@code ench} 为空串），行为与老版本完全一致。
     *
     * <p>索引里查不到的格子（幽灵箱子）不能凭空丢掉它们的件数：先把能算的加起来、记下总和，
     * 最后把差额并进「无附魔」那一行，保证各行件数之和 = {@code row.count}。
     */
    private static List<ItemDto> variantItems(WebSnapshot.ItemRow row) {
        List<ItemDto> out = new ArrayList<>();
        Map<String, ItemDto> byEnch = new LinkedHashMap<>();
        Map<String, WebSnapshot.LocRow> firstLoc = new LinkedHashMap<>();
        long resolved = 0;
        for (WebSnapshot.LocRow loc : row.locations) {
            String ench = "";
            String custom = "";
            int count = 0;
            boolean found = false;
            ContainerRecord rec = WarehouseMod.INDEX.containers.get(
                    WarehouseIndex.key(loc.dimension, new BlockPos(loc.x, loc.y, loc.z)));
            if (rec != null) {
                for (ContainerRecord.StoredStack ss : rec.contents) {
                    if (ss.slot() != loc.slot) {
                        continue;
                    }
                    ItemStack st = ss.stack();
                    ench = Scanner.enchantText(st);
                    custom = Scanner.customNameText(st);
                    count = st.getCount();
                    found = true;
                    break;
                }
            }
            if (found) {
                resolved += count;
            }
            ItemDto dto = byEnch.get(ench);
            if (dto == null) {
                dto = new ItemDto();
                dto.id = row.id;
                dto.name = row.name;
                dto.ench = ench;
                byEnch.put(ench, dto);
                firstLoc.put(ench, loc);
                out.add(dto);
            }
            dto.count += count;
            dto.refs++;
            if (!custom.isEmpty() && !dto.customName.contains(custom)) {
                dto.customName = dto.customName.isEmpty() ? custom : dto.customName + "; " + custom;
            }
        }
        long rest = row.count - resolved;
        if (rest > 0 || out.isEmpty()) {
            ItemDto plain = byEnch.get("");
            if (plain == null) {
                plain = new ItemDto();
                plain.id = row.id;
                plain.name = row.name;
                plain.loc = locationText(row);
                out.add(plain);
            }
            plain.count += Math.max(0, rest);
        }
        // 处数只有遍历完才知道，位置文案最后重新拼一遍（首格 + 这个变体自己的处数）
        for (Map.Entry<String, WebSnapshot.LocRow> e : firstLoc.entrySet()) {
            ItemDto dto = byEnch.get(e.getKey());
            if (dto != null) {
                dto.loc = locText(e.getValue(), dto.refs);
            }
        }
        return out;
    }

    /**
     * 一只箱子一行（「箱子总览」用）。
     *
     * <p>「装了什么东西」直接从索引里的内容现算：箱子数量级是几百，逐箱遍历格子的开销很小，
     * 而且走到这里的前提本来就是「内容变过了」。索引里没有这条记录（幽灵箱子）时按空箱发，
     * 面板上会如实显示「0 件」。
     */
    private static BoxDto boxDto(WebSnapshot.ContainerRow row) {
        BoxDto box = new BoxDto();
        box.x = row.x;
        box.y = row.y;
        box.z = row.z;
        box.block = row.block == null ? "" : row.block;
        box.name = row.blockName == null ? "" : row.blockName;
        box.size = row.size;
        box.used = row.used;
        box.dbl = row.doubleChest;

        long items = 0;
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, long[]> byCat = new LinkedHashMap<>();
        ContainerRecord rec = WarehouseMod.INDEX.containers.get(row.key);
        if (rec != null) {
            for (ContainerRecord.StoredStack st : rec.contents) {
                if (st == null || st.stack() == null || st.stack().isEmpty()) {
                    continue;
                }
                int c = st.stack().getCount();
                items += c;
                String id = ItemIds.of(st.stack());
                counts.merge(id, (long) c, Long::sum);
                long[] cell = byCat.computeIfAbsent(Categories.of(id), k -> new long[2]);
                cell[0]++;
                cell[1] += c;
            }
        }
        box.items = items;
        box.top = topText(counts);
        box.topIds = topIds(counts);
        dominant(box, byCat);
        return box;
    }

    /**
     * 批次 6 · D3：这箱「主要装什么」。
     *
     * <p>按<b>非空槽数</b>优先、总件数次之挑一个分类（都相同就保留先遇到的那个），
     * 槽数与件数都发出去，面板可以写「主：矿物金属 12 格/768 件」。空箱不发（留空串）。
     */
    private static void dominant(BoxDto box, Map<String, long[]> byCat) {
        String cat = "";
        long slots = 0;
        long count = 0;
        for (Map.Entry<String, long[]> e : byCat.entrySet()) {
            long s = e.getValue()[0];
            long c = e.getValue()[1];
            if (s > slots || (s == slots && c > count)) {
                cat = e.getKey();
                slots = s;
                count = c;
            }
        }
        box.dominant = cat;
        box.domSlots = (int) slots;
        box.domCount = count;
    }

    /** 主要物品：按个数从多到少取前 3 种，拼成「铁锭×320、圆石×128」 */
    private static String topText(Map<String, Long> counts) {
        if (counts.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, Long>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 3; i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(Names.item(list.get(i).getKey())).append('×').append(list.get(i).getValue());
        }
        return sb.toString();
    }

    /**
     * 主要物品的 id 与个数（`id=count;id=count`，取前 3，顺序同 {@link #topText}）。
     *
     * <p>为什么还要单发 id：{@link Names} 是按**服务端语言**解析名字的，专用服务器通常是 en_us，
     * 面板上就会中英混排。面板是客户端画的，拿到 id 就能按客户端自己的语言重新翻译
     * （见 {@code client.ClientNames}）；{@link BoxDto#top} 保留下来给老客户端兜底。
     */
    private static String topIds(Map<String, Long> counts) {
        if (counts.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, Long>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 3; i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(list.get(i).getKey()).append('=').append(list.get(i).getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 序列化用的结构（字段名就是 JSON 键名，客户端 ClientSnapshot 按同样的结构解析）

    static final class Dto {
        String version;
        long at;
        boolean truncated;
        List<RegionDto> regions = new ArrayList<>();
        List<BotDto> bots = new ArrayList<>();
        /** 箱子标签（批次 3）：游戏内标签栏与网页面板共用 */
        List<TagDto> tags = new ArrayList<>();
        /**
         * 类目键清单（1.1.1）：服务端 {@code Categories.order()} 的展示顺序 + 「其他」。
         *
         * <p>给客户端标签栏下拉用 —— 客户端没有 {@code MinecraftServer}，自己算不出这套键。
         * 老服务端不发这一段 ⇒ 客户端解析后是空表 ⇒ 自动退回本机注册表兜底。
         */
        List<String> categories = new ArrayList<>();
        /** 只有管理员那份才带；普通客户端收到的 JSON 里没有这个字段 */
        List<AccountDto> accounts;
    }

    static final class RegionDto {
        String name;
        String dimension;
        boolean fullHeight;
        int[] from = new int[]{0, 0, 0};
        int[] to = new int[]{0, 0, 0};
        long totalItems;
        long usedSlots;
        long capacity;
        List<ItemDto> items = new ArrayList<>();
        /** 箱子总览（批次 4）：一只箱子一行；老服务端没有这一段，客户端要能容忍 */
        List<BoxDto> boxes;
    }

    /** 箱子总览的一行（坐标 / 方块 / 格数 / 占用 / 物品总数 / 主要物品） */
    static final class BoxDto {
        int x;
        int y;
        int z;
        String block;
        String name;
        int size;
        int used;
        long items;
        boolean dbl;
        /** 服务端语言的主要物品文本（老客户端兜底） */
        String top;
        /** `id=count;id=count`：客户端按自己的语言翻译后再写进主要物品那一行 */
        String topIds;
        /** 批次 6 · D3 主导分类：这一箱主要装哪一类；空箱留空串 */
        String dominant;
        /** 主导分类占的非空槽数 */
        int domSlots;
        /** 主导分类的总件数 */
        long domCount;
    }

    static final class ItemDto {
        /** 物品 id：客户端按自己的语言重新取名字（服务端语言可能不是中文） */
        String id;
        String name;
        long count;
        int refs;
        String loc;
        /**
         * 0.23.0：附魔组合（{@code 注册名@等级} 用 {@code ,} 连接）；空 = 无附魔。
         *
         * <p>取货页据此把附魔书/附魔装备**按附魔分行**显示，点哪一行就用
         * {@code <物品>#<这个串>} 下单，服务端逐格按集合比对后只取对得上的那些。
         */
        String ench = "";
        /** 同一变体里出现过的自定义名（{@code ; } 连接）；空 = 没改名 */
        String customName = "";
    }

    static final class BotDto {
        /** 注册名：Carpet 身份、指令、过滤 / 跳转一律用它，语义不变 */
        String name;
        /**
         * 显示名（JSON 字段名 {@code display}，只用于「给人看」的地方）。
         *
         * <p>服务端已经回落好了：设过昵称就是昵称，没设过（或名册里没这个人）就是 {@link #name}，
         * 所以客户端拿到的一定是非 null 的可显示字符串。
         */
        public String display = "";
        String region;
        /** "x,y,z"；空 = 自动判定 */
        String spot;
        /** 正在干什么；空 = 闲着 */
        String busy;
        /** 现在在世界里（在岗）；面板据此显示「上岗」还是「收回」 */
        boolean present;
    }

    static final class AccountDto {
        String name;
        boolean take;
        boolean bot;
        boolean tidy;
    }

    /** 一条箱子标签（键 = "维度@x,y,z"，坐标已归一化到双联箱的主半） */
    static final class TagDto {
        String key;
        /** 给人看的一行：暂存 / 农业（手动档） / 未打标签 */
        String label;
        /** 生效的分类；暂存箱或没打标签时是空串 */
        String category;
        /** MANUAL / AUTO */
        String mode;
        boolean staging;
    }
}
