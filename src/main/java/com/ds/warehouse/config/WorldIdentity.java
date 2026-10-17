package com.ds.warehouse.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 「这份老数据是不是已经删掉的那个存档留下的」——纯逻辑，不依赖 Fabric / Minecraft，
 * 好让 {@link WorldStore} 的迁移判定能脱离游戏单独验证（见 experiments 里的离线校验）。
 *
 * <p><b>1.2.0 的教训</b>：当时只用一条时间线（老数据目录的创建时间早于世界目录的创建时间 ⇒ 孤儿），
 * 于是「把整个世界文件夹还原进来」也被判成孤儿 —— 数据被改名归档、玩家看到的是「仓库和假人全没了」。
 * 1.2.1 起判据改成两条，并且**只用来决定「放在顶层还是放进 legacy- 子目录」，绝不再决定「迁不迁」**：
 * <ol>
 *     <li>{@link #likelyAnotherWorld}：老目录里最新的数据文件比世界目录还老 ⇒ 时间上像旧存档（弱信号）；</li>
 *     <li>{@link #pointsAt}：老数据自己记着的 {@code worldPath} 是不是当前世界路径（强信号，1.1.x 的
 *         {@code index.json} / {@code meta.json} 里都有）。两个信号同时成立才按「像别的存档」处理。</li>
 * </ol>
 * 无论判定结果如何，数据都会**复制**进存档目录，只是位置不同：顶层（正常继承）或
 * {@code <存档>/warehouse-keeper/legacy-<时间戳>/}（不直接顶替当前数据，等人工确认）。
 */
public final class WorldIdentity {

    /**
     * 世界目录的创建时间比数据文件晚多少才算「时间上像另一个存档」。
     * 文件系统时间戳有粒度（FAT 系 2 秒），留 5 秒余量。
     */
    public static final long ORPHAN_SLACK_MS = 5_000L;

    /** {@code "worldPath": "…"}（Gson 写出来的样子，只抽这一个字段，不引入 JSON 依赖） */
    private static final Pattern WORLD_PATH =
            Pattern.compile("\"worldPath\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** {@code "worldId": "…"} */
    private static final Pattern WORLD_ID =
            Pattern.compile("\"worldId\"\\s*:\\s*\"([^\"]*)\"");

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
     * 时间上像不像「一个已经不存在的存档」留下的数据。
     *
     * <p>老目录里最新的数据文件（{@code files} 里的任何一份；一份都没有时看整个目录里最新的文件）
     * 比当前世界目录的创建时间还早 {@link #ORPHAN_SLACK_MS} 以上 ⇒ 像。
     * 只要有任何一个文件比世界新，就当成「就是这个世界」——还原/复制进来的存档属于这一类。
     *
     * @param legacyDir 老位置数据目录
     * @param worldDir  当前世界目录
     * @param files     按存档存的数据文件名
     * @return true ＝ 时间上像别的存档（**不代表不迁移**，只影响放顶层还是放 legacy- 子目录）
     */
    public static boolean likelyAnotherWorld(Path legacyDir, Path worldDir, List<String> files) {
        if (legacyDir == null || worldDir == null) {
            return false;
        }
        long worldAt = createdMillis(worldDir);
        if (worldAt <= 0L) {
            return false; // 时间读不到：宁可当同一个存档（照旧迁到顶层）
        }
        boolean anyKnown = false;
        long newest = Long.MIN_VALUE;
        if (files != null) {
            for (String name : files) {
                Path f = legacyDir.resolve(name);
                if (!Files.isRegularFile(f)) {
                    continue;
                }
                anyKnown = true;
                long t = modifiedMillis(f);
                if (t > newest) {
                    newest = t;
                }
            }
        }
        if (!anyKnown) {
            newest = newestFileMillis(legacyDir);
        }
        if (newest <= 0L) {
            return false;
        }
        return newest + ORPHAN_SLACK_MS < worldAt;
    }

    /**
     * 老数据自己记着的世界路径，是不是就是当前世界。
     *
     * <p>先看 {@code meta.json}，再看 {@code index.json}（1.1.x 两种都写了 {@code worldPath}）。
     * Windows 下大小写与分隔符都不敏感，比较前统一成小写、{@code /} 分隔、去掉末尾分隔符。
     *
     * @return true ＝ 这份老数据明确属于当前世界
     */
    public static boolean pointsAt(Path legacyDir, String worldPath) {
        String recorded = worldPathOf(legacyDir);
        return recorded != null && normalize(recorded).equals(normalize(worldPath));
    }

    /** 老目录里记着的世界路径（{@code meta.json} 优先，其次 {@code index.json}），没有返回 null */
    public static String worldPathOf(Path legacyDir) {
        if (legacyDir == null) {
            return null;
        }
        String fromMeta = jsonString(legacyDir.resolve("meta.json"), WORLD_PATH);
        return fromMeta != null ? fromMeta : jsonString(legacyDir.resolve("index.json"), WORLD_PATH);
    }

    /** 老目录里记着的 worldId（{@code meta.json} 里），没有返回 null */
    public static String worldIdOf(Path legacyDir) {
        return legacyDir == null ? null : jsonString(legacyDir.resolve("meta.json"), WORLD_ID);
    }

    /** 从文件头上读出某个字符串字段（只读前 64 KB，够用且不把大索引全读进内存） */
    public static String jsonString(Path file, Pattern pattern) {
        String head = head(file, 64 * 1024);
        if (head == null) {
            return null;
        }
        Matcher m = pattern.matcher(head);
        if (!m.find()) {
            return null;
        }
        return unescape(m.group(1));
    }

    /** 文件开头的一段文本（读不动返回 null） */
    public static String head(Path file, int maxBytes) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = in.readNBytes(maxBytes);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Gson 会把 Windows 路径里的反斜杠写成 {@code \\}，这里还原 */
    public static String unescape(String raw) {
        if (raw == null || raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 路径比较用的归一化形式（小写、{@code /}、无末尾分隔符） */
    public static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String s = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 旧判据（仅时间、看目录创建时间）——1.2.1 起游戏里不再用它做迁移决定，
     * 保留给离线对比：它正是把「还原回来的存档」误判成孤儿的那条规则。
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

    /** 修改时间（毫秒），读不到返回 0 */
    public static long modifiedMillis(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 目录（含子目录）里最新的文件修改时间，目录空/读不到返回 0 */
    public static long newestFileMillis(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0L;
        }
        long newest = 0L;
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                long t = modifiedMillis(p);
                if (t > newest) {
                    newest = t;
                }
            }
        } catch (IOException e) {
            return newest;
        }
        return newest;
    }
}
