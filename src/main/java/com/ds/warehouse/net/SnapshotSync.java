package com.ds.warehouse.net;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.ContainerTags;
import com.ds.warehouse.config.Region;
import com.ds.warehouse.config.RegionStore;
import com.ds.warehouse.index.ContainerRecord;
import com.ds.warehouse.index.ItemIds;
import com.ds.warehouse.index.Scanner;
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
    /** 「快照过大」只提醒一次，免得每 tick 刷日志 */
    private static boolean oversizeLogged;

    private SnapshotSync() {
    }

    /** 在公共入口调用一次：注册包类型 + 玩家进服时推一次 */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay()
                .registerLarge(SnapshotPayload.TYPE, SnapshotPayload.CODEC, MAX_BYTES);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            long revision = WarehouseMod.INDEX.revision();
            String sig = signature();
            if (lastPlain == null || revision != lastRevision || !sig.equals(lastSig)) {
                rebuild(server);
                lastRevision = revision;
                lastSig = sig;
            }
            send(player, Admin.isAdmin(server, player));
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
        // 几个便宜的比对：索引内容改过没有、仓库/假人/权限/假人忙闲改过没有。
        // 都没变就直接返回，不用重新序列化一遍（大仓库能省下不少时间）
        long revision = WarehouseMod.INDEX.revision();
        String sig = signature();
        boolean changed = adminChanged || lastPlain == null
                || revision != lastRevision || !sig.equals(lastSig);
        if (!changed) {
            cooldown = IDLE_INTERVAL_TICKS;
            return;
        }
        // 内容变了就尽快推，面板才能即时看到结果：
        // 扫描期间索引每 tick 都在变，那时才用长冷却压住推送频率；平时只留一个很小的间隔（约 0.2 秒）。
        // 管理员身份变化优先级最高，可以穿透冷却
        if (cooldown > 0 && !adminChanged) {
            cooldown--;
            return;
        }
        lastRevision = revision;
        lastSig = sig;

        rebuild(server);
        cooldown = Scanner.isRunning() ? MIN_INTERVAL_TICKS : HOT_INTERVAL_TICKS;
        int sent = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (send(p, Admin.isAdmin(server, p))) {
                sent++;
            }
        }
        if (sent > 0) {
            WarehouseMod.LOGGER.info("[warehouse-keeper] 已向 {} 名玩家同步仓库快照（{} KB）", sent,
                    lastPlain.length / 1024);
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
     * 便宜的变化检测：仓库定义 + 假人名册（值守仓库 / 自定义值守点）+ 账号权限。
     *
     * <p>刻意**不含**假人当前在忙什么 —— 那玩意儿每几秒就变，会让快照一路刷包；
     * 忙碌状态跟着下一次真正的变化一起发出去就够了。
     */
    private static String signature() {
        StringBuilder sb = new StringBuilder(regionSignature());
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
                    // 在忙什么（整理 / 送货 / 闲置）也算签名的一部分：状态一变面板就要刷，
                    // 但刻意不含进度百分比（那东西几秒就变一次，会让快照一路推包）
                    .append("/B:").append(busyKind(e.name)).append('|');
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

    /** 只有装了本模组、且声明能收这个包的客户端才推（原版客户端不会被塞未知包踢下线） */
    private static boolean send(ServerPlayer player, boolean admin) {
        byte[] json = admin ? lastAdmin : lastPlain;
        if (json == null) {
            return false;
        }
        if (!ServerPlayNetworking.canSend(player, SnapshotPayload.TYPE)) {
            return false;
        }
        // 超过注册的单包上限就直接不发：宁可玩家这次没刷新，也不要让 send 抛异常打断整个 tick
        if (json.length > MAX_BYTES) {
            if (!oversizeLogged) {
                oversizeLogged = true;
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 仓库快照过大（{} KB，上限 {} KB），已跳过推送；"
                        + "请缩小仓库范围或减少箱子数量", json.length / 1024, MAX_BYTES / 1024);
            }
            return false;
        }
        try {
            ServerPlayNetworking.send(player, new SnapshotPayload(json));
        } catch (Throwable t) {
            // 单个玩家发包失败（连接正在断开等）不能影响其它玩家与后续 tick
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 向 {} 推送仓库快照失败", player.getName().getString(), t);
            return false;
        }
        return true;
    }

    /**
     * 玩家登出时清掉按 UUID 记的状态。
     *
     * <p>这些表只在玩家进服时写入，不清就会随进出的玩家无限长大。
     */
    public static void forget(java.util.UUID id) {
        ADMIN_SEEN.remove(id);
    }

    // ------------------------------------------------------------------
    // 组包

    private static Dto buildDto(MinecraftServer server) {
        Dto dto = new Dto();
        dto.version = WarehouseMod.VERSION;
        dto.at = System.currentTimeMillis();
        dto.regions = new ArrayList<>();
        dto.bots = new ArrayList<>();

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
            for (WebSnapshot.ItemRow row : view.items) {
                if (n++ >= MAX_ITEMS_PER_REGION) {
                    dto.truncated = true;
                    break;
                }
                ItemDto item = new ItemDto();
                item.id = row.id;
                item.name = row.name;
                item.count = row.count;
                item.refs = row.refs;
                item.loc = locationText(row);
                rd.items.add(item);
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
        WebSnapshot.LocRow loc = row.locations.get(0);
        return loc.pos + " 第 " + (loc.slot + 1) + " 格" + (row.refs > 1 ? "，共 " + row.refs + " 处" : "");
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
    }

    static final class BotDto {
        String name;
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
