package com.ds.warehouse.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * 「这份老数据是不是已经删掉的那个存档留下的」——纯逻辑，不依赖 Fabric / Minecraft，
 * 好让 {@link WorldStore} 的迁移判定能脱离游戏单独验证（见 experiments 里的 WorldIdentityTest）。
 *
 * <p>判据只有一条时间线：老数据目录是什么时候建的、当前世界目录是什么时候建的。
 * 正常升级时数据目录是世界建好之后（第一次用到仓库数据时）才出现的，一定更晚；
 * 「删掉旧存档 → 建一个同名的新存档」时，世界目录是新的，而老数据目录还停在旧存档那会儿。
 *
 * <p>时间读不到就返回「不是孤儿」（宁可照旧迁移，也别把玩家的数据误判成孤儿丢掉）。
 */
public final class WorldIdentity {

    /**
     * 世界目录的创建时间比数据目录晚多少才算「另一个存档留下的」。
     * 文件系统时间戳有粒度（FAT 系 2 秒），留 5 秒余量；正常升级时数据目录总是更晚，不会误判。
     */
    public static final long ORPHAN_SLACK_MS = 5_000L;

    private WorldIdentity() {
    }

    /** 目录里有没有任何一份数据文件（{@code meta.json} 不算数据） */
    public static boolean hasAnyData(Path dir, List<String> files) {
        if (dir == null) {
            return false;
        }
        for (String name : files) {
            if (Files.isRegularFile(dir.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 老数据目录是不是「一个已经不存在的存档」留下的。
     *
     * @param legacyDir 老位置数据目录（{@code config/warehouse-keeper/worlds/<存档名>/}）
     * @param worldDir  当前世界目录（{@code saves/<存档名>/}）
     * @return true ＝ 明显比这个世界更早，本次进档不该继承它的数据
     */
    public static boolean isOrphan(Path legacyDir, Path worldDir) {
        if (legacyDir == null || worldDir == null) {
            return false;
        }
        long dataAt = createdMillis(legacyDir);
        long worldAt = createdMillis(worldDir);
        if (dataAt <= 0L || worldAt <= 0L) {
            return false;
        }
        return dataAt + ORPHAN_SLACK_MS < worldAt;
    }

    /** 创建时间（毫秒）；文件系统不提供时退回修改时间，读不到返回 0 */
    public static long createdMillis(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            long t = a.creationTime().toMillis();
            return t > 0L ? t : a.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
