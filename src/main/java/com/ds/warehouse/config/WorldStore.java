package com.ds.warehouse.config;

import com.ds.warehouse.WarehouseMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 「现在在哪个存档」以及这个存档自己的数据目录。
 *
 * <p>1.1.7 起数据<b>跟着存档走</b>：所有按存档存的文件都放在世界目录里的
 * {@code <存档目录>/warehouse-keeper/}：
 *
 * <pre>
 * &lt;游戏实例&gt;/saves/&lt;存档目录&gt;/
 *     level.dat  region/  data/ …
 *     warehouse-keeper/            ← 本模组的存档数据（1.1.7 起）
 *         meta.json              世界身份：worldId / worldPath / createdAt
 *         regions.json           仓库区域
 *         bots.json              搬运工名册
 *         homes.json             物品归位记忆
 *         container-tags.json    容器分类标签
 *         index.json             仓库索引
 *
 * &lt;游戏实例&gt;/config/warehouse-keeper/      ← 实例级，所有存档共用，不随存档走
 *     settings.json  web.json  web-users.dat  web-key.dat  audit.log  players.json  categories.json
 *     client.json                            客户端偏好（每个客户端自己的）
 *     worlds/&lt;存档目录名&gt;/                    ← 1.1.7 前的老位置，迁移后改名保留
 * </pre>
 *
 * <p>为什么要搬进存档：以前只用一个「存档目录名」当身份（见 {@link #safeName(String)}），
 * 于是删掉一个叫「新的世界」的存档、再建一个同名的，新存档会直接读到上一个存档的
 * regions / bots / homes / container-tags —— 那些坐标和标签在别的世界里毫无意义，
 * 玩家看到的就是「上个存档的数据混进来了」。数据放在世界目录里以后，
 * 删存档就把数据一起删了，同名重建也不会继承；备份/搬运整个世界时仓库数据跟着走。
 *
 * <p>代价（明确记下来）：世界目录里多一个 {@code warehouse-keeper/} 目录，
 * 卸载模组不再「零残留」；把存档分享给别人时，里面的仓库坐标也一并带过去。
 *
 * <p><b>一次性迁移（1.2.1 重写）</b>：进存档时把老位置的数据**一份不落地复制**进这个存档目录。
 * 老位置包括 {@code config/warehouse-keeper/worlds/&lt;存档名&gt;/}、它被 1.2.0 改过名的
 * {@code &lt;存档名&gt;.orphan-*} / {@code .migrated-*} / {@code .legacy-*} 兄弟目录，
 * 以及更老的布局（文件直接躺在 {@code config/warehouse-keeper/} 根下、索引在 {@code index/&lt;存档名&gt;.json}）。
 * 规矩见 {@link DataMigration}：<b>只复制，绝不删除、绝不改名，任何文件都不会被覆盖掉</b>，
 * 一次迁移里出现的「两边都有真数据」会把来的那一份另存为 {@code &lt;名字&gt;.legacy-&lt;时间戳&gt;}，
 * 「目标是个空壳」（例如 1.2.0 刚建出来的 402 B 空索引）会把空壳另存为 {@code .replaced-&lt;时间戳&gt;} 再顶上去。
 *
 * <p>判定（{@link WorldIdentity#likelyAnotherWorld} ＋ {@link WorldIdentity#pointsAt}）
 * <b>不再决定「迁不迁」，只决定放哪儿</b>：时间上像旧存档、而且它自己记着的 worldPath 也不是这个世界时，
 * 数据仍会全部搬进存档目录，但放在 {@code &lt;存档&gt;/warehouse-keeper/legacy-&lt;时间戳&gt;/} 下等人工确认，
 * 不会直接顶替当前存档的数据。迁过的老目录会写 {@code .migrated.json}（记下迁进了哪个 worldId），
 * 同一个存档再次进档直接跳过；换了新存档（新 worldId）也不会把旧数据再混进来。
 */
public final class WorldStore {

    /** 存档目录里放数据的子目录名 */
    public static final String FOLDER = "warehouse-keeper";

    /** 按存档存的五份数据文件（{@code meta.json} 是身份文件，不算数据，也不参与迁移） */
    private static final List<String> DATA_FILES =
            List.of("regions.json", "bots.json", "homes.json", "container-tags.json", "index.json");

    private static final String META = "meta.json";
    /** 迁进过某个存档的老目录会留下这个标记（记 worldId / 时间 / 文件数），避免同一个存档反复搬 */
    private static final String MIGRATION_MARKER = ".migrated.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile String key = "";
    private static volatile String path = "";
    private static volatile String worldId = "";
    /** 本次进档实际用的数据目录；进存档前为 null */
    private static volatile Path dataDir = null;

    private WorldStore() {
    }

    /** 实例级配置目录（客户端偏好、Web、权限、审计等仍然在这里） */
    public static Path base() {
        return FabricLoader.getInstance().getConfigDir().resolve(WarehouseMod.MOD_ID);
    }

    /** 当前存档的数据目录：存档内的 {@code warehouse-keeper/}；还没进存档时按老位置算 */
    public static Path dir() {
        Path d = dataDir;
        return d != null ? d : legacyDir(key());
    }

    /** 当前存档的数据文件 */
    public static Path file(String name) {
        return dir().resolve(name);
    }

    /** 世界身份文件（worldId / worldPath / createdAt） */
    public static Path metaPath() {
        return dir().resolve(META);
    }

    /** 当前存档的稳定身份（随机 UUID，跟着 worldPath 走；读不到时返回空串） */
    public static String worldId() {
        return worldId;
    }

    /** 是否已经进入某个存档（没进存档时不该读写任何存档数据） */
    public static boolean ready() {
        return !key.isEmpty();
    }

    public static String key() {
        return key.isEmpty() ? "unknown" : key;
    }

    public static String worldPath() {
        return path;
    }

    /** 老位置：{@code config/warehouse-keeper/worlds/<存档名>/} */
    private static Path legacyDir(String k) {
        return base().resolve("worlds").resolve(k.isEmpty() ? "unknown" : k);
    }

    /** 存档内的数据目录：{@code <世界目录>/warehouse-keeper/}；世界目录不可知时返回 null */
    private static Path innerDir(String worldPath) {
        if (worldPath == null || worldPath.isBlank() || "unknown-world".equals(worldPath)) {
            return null;
        }
        try {
            return Path.of(worldPath).resolve(FOLDER);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 进存档：记住这是哪个存档，把老位置的数据全部搬进来，再建/读它的身份文件 */
    public static void enter(MinecraftServer server) {
        path = rawWorldPath(server);
        key = safeName(path);
        Path legacy = legacyDir(key);
        dataDir = pickDir(innerDir(path), legacy);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 建存档数据目录失败: {}", e.toString());
        }
        // 顺序要紧：先采用老位置的 meta.json（worldId 跨迁移保持不变），再定身份，最后才搬数据
        // ——「迁进了哪个 worldId」要在搬的时候就知道，而且必须是最终值。
        adoptLegacyMeta(dataDir);
        readOrCreateMeta(dataDir);
        List<DataMigration.Result> reports = claimLegacy(legacy, dataDir);
        // 更老的文件：直接躺在 config/warehouse-keeper/ 根下（0.15.x 及以前），
        // 「最近玩过的那个存档」只能靠旧索引文件的修改时间判断
        String owner = lastPlayedKey();
        reports.add(migrate("regions.json", owner));
        reports.add(migrate("bots.json", owner));
        reports.add(migrate("homes.json", owner));
        reports.add(migrate("container-tags.json", owner));
        reports.add(migrateIndex());
        reportMigrations(reports, server);
        WarehouseMod.LOGGER.info("[warehouse-keeper] 存档数据目录: {}（存档 {}，worldId {}）",
                dataDir, key, worldId.isEmpty() ? "?" : worldId);
    }

    /** 退存档：之后再读写数据都会落到 unknown 目录，避免串档 */
    public static void leave() {
        key = "";
        path = "";
        worldId = "";
        dataDir = null;
    }

    /** 选一个能写的数据目录：优先存档内，建不出来/不可写就退回老位置（并 warn） */
    private static Path pickDir(Path inner, Path legacy) {
        if (inner == null) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 拿不到世界目录，数据暂存 config 下的老位置 {}",
                    legacy);
            return legacy;
        }
        try {
            Files.createDirectories(inner);
            if (!Files.isWritable(inner)) {
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 存档内数据目录不可写，退回 {}（本存档不随存档走）", legacy);
                return legacy;
            }
            return inner;
        } catch (IOException | SecurityException e) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 存档内数据目录 {} 不可用（{}），退回 {}",
                    inner, e.toString(), legacy);
            return legacy;
        }
    }

    /** 当前存档的目录（用来区分不同存档） */
    public static String rawWorldPath(MinecraftServer server) {
        try {
            return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
        } catch (Throwable t) {
            return "unknown-world";
        }
    }

    /** 把存档路径变成能当目录名的短名字（只用于老位置目录名与日志） */
    public static String safeName(String path) {
        String base;
        try {
            Path p = Path.of(path);
            Path name = p.getFileName();
            base = name == null ? "world" : name.toString();
        } catch (Throwable t) {
            base = "world";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < 64; i++) {
            char c = base.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        if (sb.length() == 0) {
            sb.append("world");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 世界身份（meta.json）

    /** meta.json 的内容：一个随机 ID + 它属于哪个世界路径 */
    private static final class Meta {
        String worldId;
        String worldPath;
        long createdAt;
    }

    private static void readOrCreateMeta(Path dir) {
        Path f = dir.resolve(META);
        Meta meta = null;
        if (Files.isRegularFile(f)) {
            try {
                meta = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Meta.class);
            } catch (Exception e) {
                WorldStore.backupUnreadable(f);
                WarehouseMod.LOGGER.warn("[warehouse-keeper] {} 读不动（{}），重新生成身份", f.getFileName(), e.toString());
            }
        }
        boolean changed = false;
        if (meta == null) {
            meta = new Meta();
            changed = true;
        }
        if (meta.worldId == null || meta.worldId.isBlank()) {
            meta.worldId = UUID.randomUUID().toString();
            changed = true;
        }
        if (meta.createdAt <= 0L) {
            meta.createdAt = System.currentTimeMillis();
            changed = true;
        }
        if (meta.worldPath == null || !meta.worldPath.equals(path)) {
            if (meta.worldPath != null && !meta.worldPath.isBlank()) {
                WarehouseMod.LOGGER.info("[warehouse-keeper] 存档位置变了（{} → {}），worldId {} 保持不变",
                        meta.worldPath, path, meta.worldId);
            }
            meta.worldPath = path;
            changed = true;
        }
        worldId = meta.worldId;
        if (changed || !Files.isRegularFile(f)) {
            Meta copy = meta;
            WorldStore.writeAtomic(f, tmp -> Files.writeString(tmp, GSON.toJson(copy), StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------
    // 一次性迁移

    // ------------------------------------------------------------------
    // 一次性迁移（1.2.1：只复制，绝不删除、绝不改名、绝不覆盖 —— 细节见 DataMigration）

    /** 老位置的所有候选来源：原名字目录，以及 1.2.0 改过名的 .orphan-/.migrated-/.legacy- 兄弟目录 */
    private static List<Path> legacySources(Path legacy) {
        List<Path> sources = new ArrayList<>();
        if (legacy == null) {
            return sources;
        }
        if (Files.isDirectory(legacy)) {
            sources.add(legacy);
        }
        Path parent = legacy.getParent();
        String prefix = legacy.getFileName().toString() + ".";
        if (parent != null && Files.isDirectory(parent)) {
            try (Stream<Path> list = Files.list(parent)) {
                sources.addAll(list
                        .filter(Files::isDirectory)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith(prefix) && (name.contains(".orphan-")
                                    || name.contains(".migrated-") || name.contains(".legacy-"));
                        })
                        .toList());
            } catch (IOException e) {
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 列老数据目录失败: {}", e.toString());
            }
        }
        // 最新的来源排在最前面：同一个文件有多个来源时，由最新的那一份决定「顶掉空壳」还是「另存保留」
        sources.sort(Comparator.comparingLong(WorldIdentity::newestFileMillis).reversed()
                .thenComparing(p -> p.getFileName().toString()));
        return sources;
    }

    /** 存档内还没有身份文件时，先采用老位置的 meta.json —— worldId 跨迁移保持不变 */
    private static void adoptLegacyMeta(Path dir) {
        if (dir == null || Files.isRegularFile(dir.resolve(META))) {
            return;
        }
        for (Path source : legacySources(legacyDir(key()))) {
            Path from = source.resolve(META);
            if (!Files.isRegularFile(from)) {
                continue;
            }
            DataMigration.Result result = DataMigration.mergeFile(from, dir, META);
            if (result.ok() && result.migrated() > 0) {
                WarehouseMod.LOGGER.info("[warehouse-keeper] 采用老位置 {} 里的身份文件（worldId 保持不变）",
                        source.getFileName());
            }
            return;
        }
    }

    /** 把老位置的数据全部复制进存档目录；老位置原样保留（不删除、不改名），逐份记结果 */
    private static List<DataMigration.Result> claimLegacy(Path legacy, Path target) {
        List<DataMigration.Result> reports = new ArrayList<>();
        if (target == null) {
            return reports;
        }
        Path world = worldDir();
        Set<String> decided = new HashSet<>();
        for (Path source : legacySources(legacy)) {
            if (same(source, target) || !Files.isDirectory(source)) {
                continue;
            }
            String owner = migratedInto(source);
            if (owner != null && !owner.isEmpty() && !owner.equals(worldId)) {
                WarehouseMod.LOGGER.info("[warehouse-keeper] {} 曾迁进存档 worldId {}，本次进的是 {}，不重复迁移",
                        source.getFileName(), owner, worldId);
                continue;
            }
            Path into = target;
            if (WorldIdentity.likelyAnotherWorld(source, world, DATA_FILES)
                    && !WorldIdentity.pointsAt(source, path)) {
                into = target.resolve("legacy-" + DataMigration.stamp());
                WarehouseMod.LOGGER.warn("[warehouse-keeper] {} 时间上像旧存档、记的 worldPath 也不是本存档："
                                + "数据全部复制进 {}（一个字节都没丢；确认是自己的数据后，把里面的文件移到上一层即可）",
                        source.getFileName(), into.getFileName());
            }
            DataMigration.Result result = DataMigration.mergeInto(source, into, decided);
            reports.add(result);
            if (result.ok()) {
                markMigrated(source, result, into);
            } else {
                WarehouseMod.LOGGER.warn("[warehouse-keeper] {} 有文件没迁过来，老位置原样保留，下次进档会重试",
                        source.getFileName());
            }
        }
        return reports;
    }

    /** 这份老目录已经迁进过的 worldId（读 .migrated.json），没迁过返回 null */
    private static String migratedInto(Path source) {
        Path marker = source.resolve(MIGRATION_MARKER);
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        try {
            Marker parsed = GSON.fromJson(Files.readString(marker, StandardCharsets.UTF_8), Marker.class);
            return parsed == null ? null : parsed.worldId;
        } catch (Exception e) {
            return null; // 标记读不动就当没迁过（再搬一次也不会丢东西）
        }
    }

    /** .migrated.json 的内容 */
    private static final class Marker {
        String worldId;
        long at;
    }

    /** 在迁过的老目录里写个标记（不动任何数据），下次进档不再重复搬 */
    private static void markMigrated(Path source, DataMigration.Result result, Path into) {
        long bytes = result.bytesOf(DataMigration.Status.COPIED) + result.bytesOf(DataMigration.Status.REPLACED);
        String json = "{\n"
                + "  \"worldId\": " + GSON.toJson(worldId == null ? "" : worldId) + ",\n"
                + "  \"at\": " + System.currentTimeMillis() + ",\n"
                + "  \"into\": " + GSON.toJson(into == null ? "" : into.toString()) + ",\n"
                + "  \"files\": " + result.migrated() + ",\n"
                + "  \"bytes\": " + bytes + ",\n"
                + "  \"summary\": " + GSON.toJson(result.summary()) + "\n"
                + "}\n";
        writeAtomic(source.resolve(MIGRATION_MARKER), tmp -> Files.writeString(tmp, json, StandardCharsets.UTF_8));
    }

    /** 迁移结果进日志，并在游戏里给玩家一行提示；失败绝不静默 */
    private static void reportMigrations(List<DataMigration.Result> reports, MinecraftServer server) {
        int files = 0;
        long bytes = 0L;
        int failed = 0;
        for (DataMigration.Result r : reports) {
            if (r.items().isEmpty()) {
                continue;
            }
            if (r.ok()) {
                WarehouseMod.LOGGER.info("[warehouse-keeper] 老数据迁移 {}", r.summary());
            } else {
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 老数据迁移未完成（老位置数据没动，下次进档会重试）{}", r.summary());
            }
            files += r.migrated();
            bytes += r.bytesOf(DataMigration.Status.COPIED) + r.bytesOf(DataMigration.Status.REPLACED);
            failed += r.count(DataMigration.Status.FAILED);
        }
        if (files == 0 && failed == 0) {
            return;
        }
        String msg = failed > 0
                ? String.format(Locale.ROOT, "[仓库] 老数据迁移：%d 个文件已复制进本存档，%d 个失败（老位置数据没动，详见日志）",
                        files, failed)
                : String.format(Locale.ROOT, "[仓库] 老数据迁移：%d 个文件（%d 字节）已复制进本存档目录", files, bytes);
        try {
            server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        } catch (Throwable t) {
            // 单机刚加载、还没进人：日志里已经记全了
        }
    }

    /** 当前世界目录（拿不到返回 null） */
    private static Path worldDir() {
        if (path == null || path.isBlank() || "unknown-world".equals(path)) {
            return null;
        }
        try {
            return Path.of(path);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean same(Path a, Path b) {
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 读写兜底（审查发现 T1/T2/T3）

    /** 往临时文件里落内容的写法，允许抛异常 */
    public interface Sink {
        void write(Path tmp) throws Exception;
    }

    /**
     * 先写 {@code <名字>.tmp} 再原子替换。
     *
     * <p>直接写目标文件的话，中途崩了/掉电会留下半截文件，下次启动整份数据都读不回来；
     * 有些平台不支持原子改名，那就退回普通替换（至少内容已经完整落盘）。
     *
     * @return 是否写成功
     */
    public static boolean writeAtomic(Path f, Sink sink) {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try {
            Files.createDirectories(f.getParent());
            sink.write(tmp);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 写入 {} 失败: {}", f, e.toString());
            try {
                Files.deleteIfExists(tmp); // 别把半截文件留在目录里
            } catch (IOException ignored) {
                // 删不掉就算了，下次写会被 REPLACE_EXISTING 覆盖
            }
            return false;
        }
    }

    /**
     * 读不动的配置文件不能被下一次 save() 直接覆盖掉：先留一份 {@code .bak}（只留第一次的）。
     *
     * <p>以前只有 regions.json / players.json 这么做，其余几份配置是「解析失败 → 只 warn →
     * 内存里当空表 → 下一次 save 覆盖原文件」，等于把玩家手改坏/写到一半的那份静默吃掉
     * （审查发现 T1）。这里集中一份实现，所有按存档存的 json 共用。
     *
     * @return 备份文件；没备份成功时返回 null
     */
    public static Path backupUnreadable(Path f) {
        if (f == null || !Files.isRegularFile(f)) {
            return null;
        }
        Path bak = f.resolveSibling(f.getFileName() + ".bak");
        try {
            if (!Files.exists(bak)) {
                Files.copy(f, bak, StandardCopyOption.COPY_ATTRIBUTES);
                WarehouseMod.LOGGER.info("[warehouse-keeper] 已把读不动的 {} 另存为 {}",
                        f.getFileName(), bak.getFileName());
            }
            return bak;
        } catch (IOException e) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 备份 {} 失败（继续按默认值处理）: {}",
                    f.getFileName(), e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 更老的一次性迁移（0.15.x 及以前，文件直接躺在 config/warehouse-keeper/ 根下）

    /** 旧 index/ 目录里最新的那个索引文件 = 最近玩过的存档 */
    private static String lastPlayedKey() {
        Path indexDir = base().resolve("index");
        if (!Files.isDirectory(indexDir)) {
            return null;
        }
        String best = null;
        long bestTime = Long.MIN_VALUE;
        try (Stream<Path> list = Files.list(indexDir)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                try {
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t > bestTime) {
                        bestTime = t;
                        best = name.substring(0, name.length() - ".json".length());
                    }
                } catch (IOException ignored) {
                    // 读不到时间就当没这个文件
                }
            }
        } catch (IOException e) {
            return null;
        }
        return best;
    }

    /** 老文件（0.15.x 及以前直接躺在 config 根下）：同样只复制、不搬走 */
    private static DataMigration.Result migrate(String name, String owner) {
        Path legacy = base().resolve(name);
        if (!Files.isRegularFile(legacy)) {
            return new DataMigration.Result(name, List.of());
        }
        if (owner != null && !owner.equals(key())) {
            // 老文件是别的存档的：留着，等那个存档进来时再搬（免得又串一次档）
            WarehouseMod.LOGGER.info("[warehouse-keeper] 老文件 {} 属于存档 {}，这次进的是 {}，先不迁移",
                    name, owner, key());
            return new DataMigration.Result(name, List.of());
        }
        return DataMigration.mergeFile(legacy, dir(), name);
    }

    private static DataMigration.Result migrateIndex() {
        return DataMigration.mergeFile(base().resolve("index").resolve(key() + ".json"), dir(), "index.json");
    }
}
