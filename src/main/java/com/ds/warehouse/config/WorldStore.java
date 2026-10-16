package com.ds.warehouse.config;

import com.ds.warehouse.WarehouseMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
 * <p>一次性迁移：进存档时如果发现老位置 {@code config/warehouse-keeper/worlds/&lt;存档名&gt;/}
 * 还有数据，就按「数据目录是不是这个世界建的」判断一次
 * （{@link #isOrphan(Path, String)}：老目录的创建时间早于世界目录的创建时间
 * ＝ 它属于一个已经不存在的旧存档）：
 * <ul>
 *   <li>不是孤儿 → 把五份文件复制进存档目录，老目录改名为
 *       {@code worlds/&lt;存档名&gt;.migrated-&lt;时间戳&gt;} 保留；</li>
 *   <li>是孤儿 → <b>不继承</b>，老目录改名为
 *       {@code worlds/&lt;存档名&gt;.orphan-&lt;时间戳&gt;} 保留（想找回来可以手动改回名字再进存档）。</li>
 * </ul>
 * 判定只看时间戳，因此「把整个世界文件夹复制/还原进来」会被当成新存档
 * （旧数据进 .orphan- 目录，不会自动继承）。迁移只改名、不删除，最坏情况也能人工找回。
 */
public final class WorldStore {

    /** 存档目录里放数据的子目录名 */
    public static final String FOLDER = "warehouse-keeper";

    /** 按存档存的五份数据文件（{@code meta.json} 是身份文件，不算数据，也不参与迁移） */
    private static final List<String> DATA_FILES =
            List.of("regions.json", "bots.json", "homes.json", "container-tags.json", "index.json");

    private static final String META = "meta.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

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

    /** 进存档：记住这是哪个存档，建/读它的身份文件，并把老位置的数据搬进来 */
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
        readOrCreateMeta(dataDir);
        // 老布局（config/warehouse-keeper/worlds/<存档名>/）→ 存档内目录：一次性搬迁
        migrateLegacyDir(legacy, dataDir, path);
        // 更老的文件：直接躺在 config/warehouse-keeper/ 根下（0.15.x 及以前），
        // 「最近玩过的那个存档」只能靠旧索引文件的修改时间判断
        String owner = lastPlayedKey();
        migrate("regions.json", owner);
        migrate("bots.json", owner);
        migrate("homes.json", owner);
        migrate("container-tags.json", owner);
        migrateIndex();
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

    /** 老位置 {@code worlds/<存档名>/} → 存档内目录 */
    private static void migrateLegacyDir(Path legacy, Path dir, String worldPath) {
        if (legacy == null || dir == null || !Files.isDirectory(legacy)) {
            return;
        }
        if (same(legacy, dir)) {
            return; // 本存档本来就用的老位置（存档内目录建不出来）
        }
        if (WorldIdentity.hasAnyData(dir, DATA_FILES)) {
            // 两边都有数据：不合并、也不动老目录 —— 宁可留两份，也不覆盖任何一边
            WarehouseMod.LOGGER.info("[warehouse-keeper] 老数据目录仍在 {}（新目录已有数据，未自动迁移，可人工处理）", legacy);
            return;
        }
        if (isOrphan(legacy, worldPath)) {
            Path orphan = renameAside(legacy, ".orphan-" + LocalDateTime.now().format(STAMP));
            WarehouseMod.LOGGER.warn("[warehouse-keeper] {} 是更早的存档留下的数据，本次不继承；已改名保留为 {}",
                    legacy, orphan == null ? legacy : orphan.getFileName());
            return;
        }
        int moved = 0;
        for (String name : DATA_FILES) {
            Path from = legacy.resolve(name);
            if (!Files.isRegularFile(from)) {
                continue;
            }
            try {
                Files.createDirectories(dir);
                Files.copy(from, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                moved++;
            } catch (IOException e) {
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 迁移 {} 失败: {}", name, e.toString());
            }
        }
        Path old = renameAside(legacy, ".migrated-" + LocalDateTime.now().format(STAMP));
        WarehouseMod.LOGGER.info("[warehouse-keeper] 已把 {} 份数据从 {} 搬进存档目录（老目录保留为 {}）",
                moved, legacy, old == null ? legacy : old.getFileName());
    }

    /**
     * 老数据是不是「一个已经不存在的存档」留下的：见 {@link WorldIdentity#isOrphan(Path, Path)}。
     *
     * <p>时间读不到就只能当同一个存档处理（宁可迁移，也别把玩家的数据判成孤儿）。
     */
    private static boolean isOrphan(Path legacy, String worldPath) {
        if (worldPath == null || worldPath.isBlank() || "unknown-world".equals(worldPath)) {
            return false;
        }
        try {
            return WorldIdentity.isOrphan(legacy, Path.of(worldPath));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean same(Path a, Path b) {
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 改名保留（不删除任何东西）；失败返回 null，调用方按原路径记日志 */
    private static Path renameAside(Path dir, String suffix) {
        Path to = dir.resolveSibling(dir.getFileName() + suffix);
        try {
            Files.move(dir, to, StandardCopyOption.REPLACE_EXISTING);
            return to;
        } catch (IOException e) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 归档老数据目录 {} 失败（内容原样保留）: {}", dir, e.toString());
            return null;
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

    private static void migrate(String name, String owner) {
        Path legacy = base().resolve(name);
        Path target = file(name);
        if (!Files.isRegularFile(legacy) || Files.exists(target)) {
            return;
        }
        if (owner != null && !owner.equals(key())) {
            // 老文件是别的存档的：留着，等那个存档进来时再搬（免得又串一次档）
            WarehouseMod.LOGGER.info("[warehouse-keeper] 老文件 {} 属于存档 {}，这次进的是 {}，先不迁移",
                    name, owner, key());
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
            WarehouseMod.LOGGER.info("[warehouse-keeper] 已把老的 {} 迁移到当前存档目录", name);
        } catch (IOException e) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 迁移 {} 失败: {}", name, e.toString());
        }
    }

    private static void migrateIndex() {
        Path legacy = base().resolve("index").resolve(key() + ".json");
        Path target = file("index.json");
        if (!Files.isRegularFile(legacy) || Files.exists(target)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
            WarehouseMod.LOGGER.info("[warehouse-keeper] 已把老的索引 index/{}.json 迁移到当前存档目录", key());
        } catch (IOException e) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 迁移索引失败: {}", e.toString());
        }
    }
}
