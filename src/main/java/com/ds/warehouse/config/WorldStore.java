package com.ds.warehouse.config;

import com.ds.warehouse.WarehouseMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * 「现在在哪个存档」以及这个存档自己的数据目录。
 *
 * <p>原来的做法是把 regions.json / bots.json / homes.json 直接放在 config 根下，
 * 结果换一个存档就会看到上一个存档的仓库、搬运工名册和「物品归位记忆」——
 * 那些坐标在别的世界里毫无意义，甚至会让人以为数据被「导入」了。
 * 现在每个存档一个目录：
 *
 * <pre>
 * config/warehouse-keeper/
 *   settings.json  web.json  web-users.dat  web-key.dat  audit.log   ← 实例级，所有存档共用
 *   worlds/&lt;存档目录名&gt;/
 *       regions.json   仓库区域
 *       bots.json      搬运工名册
 *       homes.json     物品归位记忆
 *       index.json     仓库索引
 * </pre>
 *
 * <p>老文件会在进存档时**一次性迁移**：只迁移给「最近玩过的那个存档」
 * （用旧 index/ 目录里最新的那个索引文件判断是哪个），迁完老文件就没了，
 * 因此不会再被下一个存档继承。仓库数据依旧不在 world/ 里，卸载模组仍然零残留。
 */
public final class WorldStore {

    private static volatile String key = "";
    private static volatile String path = "";

    private WorldStore() {
    }

    public static Path base() {
        return FabricLoader.getInstance().getConfigDir().resolve(WarehouseMod.MOD_ID);
    }

    /** 当前存档的数据目录 */
    public static Path dir() {
        return base().resolve("worlds").resolve(key());
    }

    /** 当前存档的数据文件 */
    public static Path file(String name) {
        return dir().resolve(name);
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

    /** 进存档：记住这是哪个存档，并把老版本放在 config 根下的文件搬进来 */
    public static void enter(MinecraftServer server) {
        path = rawWorldPath(server);
        key = safeName(path);
        try {
            Files.createDirectories(dir());
        } catch (IOException e) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 建存档数据目录失败: {}", e.toString());
        }
        // 「最近玩过的那个存档」——老文件没有记录自己属于谁，只能靠旧索引文件的修改时间判断
        String owner = lastPlayedKey();
        migrate("regions.json", owner);
        migrate("bots.json", owner);
        migrate("homes.json", owner);
        migrateIndex();
        WarehouseMod.LOGGER.info("[warehouse-keeper] 存档数据目录: {}", dir());
    }

    /** 退存档：之后再读写数据都会落到 unknown 目录，避免串档 */
    public static void leave() {
        key = "";
        path = "";
    }

    /** 当前存档的目录（用来区分不同存档） */
    public static String rawWorldPath(MinecraftServer server) {
        try {
            return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
        } catch (Throwable t) {
            return "unknown-world";
        }
    }

    /** 把存档路径变成能当目录名的短名字 */
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
    // 一次性迁移（0.15.x 及以前的老文件）

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
