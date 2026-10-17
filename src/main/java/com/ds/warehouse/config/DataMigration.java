package com.ds.warehouse.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 「把老位置的数据一份不落地搬进存档目录」——**纯文件逻辑**，不依赖 Fabric / Minecraft，
 * 所以能脱离游戏、拿真实目录在 experiments 里跑一遍验证（做法与语音包那边的离线解析器一致）。
 *
 * <p>1.2.0 的迁移只有一句话概括：「按时间猜是不是同一个存档，猜错就改名归档」。它有三个毛病，
 * 1.2.1 在这里一次性解决：
 * <ol>
 *     <li><b>只认识 5 个固定文件名</b>：老目录里别的东西（例如 {@code index.json.v1.bak}）留在原地不动；</li>
 *     <li><b>只在新目录还没有任何数据时才迁</b>：两边都有文件就整份跳过，剩下的永远迁不过去；</li>
 *     <li><b>靠改名表示「迁移过」</b>：玩家看到的是「仓库没了 / 假人不见了」，而且下次进档再也找不到那个目录。</li>
 * </ol>
 *
 * <p>这里的规矩（无例外）：
 * <ul>
 *     <li><b>只复制，绝不删除、绝不改名</b>老位置的数据 —— 老位置永远留着完整一份；</li>
 *     <li><b>任何文件都不会被覆盖掉</b>：目标已存在且内容不同时，要么把目标另存为
 *         {@code <名字>.replaced-<时间戳>}（目标比源小、且这个文件名还没有被更新的来源处理过，
 *         例如 1.2.0 建的空壳），要么把这来的这一份另存为 {@code <名字>.legacy-<时间戳>}
 *         （两边都像真数据，两份都保留）；</li>
 *     <li><b>多个老位置来源时以最新的为准</b>：调用方按最新在前传入来源（见
 *         {@link #mergeInto(Path, Path, Set)}），同一个文件名只由最新的那一份决定
 *         「顶掉空壳」还是「另存保留」，更旧的来源不会被拿来压住更新的数据；</li>
 *     <li><b>每次复制都校验</b>：字节数与 sha256 都与源一致才算成功，否则记为 {@link Status#FAILED}，
 *         失败的半截文件会被删掉，老位置的数据一个字节都不动；</li>
 *     <li>迁移过的老目录会写一个 {@code .migrated.json} 标记（记下迁进了哪个 worldId），
 *         同一个存档再次进档直接跳过；换了新存档（新 worldId）也不会把旧数据再混进来。</li>
 * </ul>
 */
public final class DataMigration {

    /** 单个文件这次的结果。 */
    public enum Status {
        /** 目标没有，已复制过去。 */
        COPIED,
        /** 目标已有且字节完全相同，什么也没做。 */
        SAME,
        /**
         * 目标比源还小，而且这个文件名还没被更新的来源处理过（例如 1.2.0 刚建出来的 402 B 空索引、
         * 186 B 的占位名册）：已用源替换，旧目标另存为 {@code .replaced-<时间戳>}。
         * 目标一个字节都不会丢。
         */
        REPLACED,
        /**
         * 两边都像真数据（目标不小于源，或已由更新的来源决定过）：目标不动，源另存为
         * {@code .legacy-<时间戳>}，两份都留着。
         */
        KEPT_BOTH,
        /** 复制或校验失败：没有改动任何一边，失败时连半截文件都清掉。 */
        FAILED
    }

    /** 一个文件的迁移结果。 */
    public record Item(String name, Status status, long bytes) {
    }

    /** 一次迁移（一个源目录 / 一个文件）的结果。 */
    public record Result(String source, List<Item> items) {

        public int count(Status status) {
            int n = 0;
            for (Item item : items) {
                if (item.status() == status) {
                    n++;
                }
            }
            return n;
        }

        public long bytesOf(Status status) {
            long n = 0L;
            for (Item item : items) {
                if (item.status() == status) {
                    n += item.bytes();
                }
            }
            return n;
        }

        /** 有没有出错。 */
        public boolean ok() {
            return count(Status.FAILED) == 0;
        }

        /** 实际搬过来的文件数（含替换空壳的那份）。 */
        public int migrated() {
            return count(Status.COPIED) + count(Status.REPLACED);
        }

        /** 一行汇总，进日志用。 */
        public String summary() {
            return String.format(Locale.ROOT,
                    "%s：共 %d 个文件（新迁入 %d，已相同 %d，替换空壳 %d，两份保留 %d，失败 %d），搬入 %d 字节",
                    source, items.size(), count(Status.COPIED), count(Status.SAME),
                    count(Status.REPLACED), count(Status.KEPT_BOTH), count(Status.FAILED),
                    bytesOf(Status.COPIED) + bytesOf(Status.REPLACED));
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private DataMigration() {
    }

    /**
     * 把一个老数据目录（含子目录）整体并进目标目录。
     *
     * @param source 老目录（不会被改动）
     * @param target 目标目录（会被创建/补齐）
     */
    public static Result mergeInto(Path source, Path target) {
        return mergeInto(source, target, new HashSet<>());
    }

    /**
     * 同上，但由调用方提供 {@code decided}：本次迁移里**已经由更新的来源决定过**的文件名。
     * 同一个文件名有多个老位置来源时，只让最新的那一份去顶掉空壳，之后更旧的来源一律另存保留
     * （见 {@link #copyOne(Path, Path, Set)}）。
     *
     * @param source  老目录（不会被改动）
     * @param target  目标目录（会被创建/补齐）
     * @param decided 已决定过的文件名（按引用修改，可跨多次调用共用）
     */
    public static Result mergeInto(Path source, Path target, Set<String> decided) {
        Set<String> owns = decided == null ? new HashSet<>() : decided;
        List<Item> items = new ArrayList<>();
        if (source == null || target == null || !Files.isDirectory(source)) {
            return new Result(nameOf(source), items);
        }
        try (Stream<Path> walk = Files.walk(source)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> !nameOf(p).startsWith(".migrated")) // 迁移标记本身不跟着搬
                    .sorted()
                    .toList();
            for (Path from : files) {
                Path rel = source.relativize(from);
                items.add(copyOne(from, target.resolve(rel), owns));
            }
        } catch (IOException e) {
            items.add(new Item(nameOf(source), Status.FAILED, 0L));
        }
        return new Result(nameOf(source), items);
    }

    /**
     * 把老位置根下的单个文件（0.15.x 及以前的布局）并进目标目录。
     *
     * @param source   老文件（会被读取，不会改动）
     * @param targetDir 目标目录
     * @param name     目标文件名
     */
    public static Result mergeFile(Path source, Path targetDir, String name) {
        List<Item> items = new ArrayList<>();
        if (source == null || targetDir == null || !Files.isRegularFile(source)) {
            return new Result(name == null ? nameOf(source) : name, items);
        }
        items.add(copyOne(source, targetDir.resolve(name), new HashSet<>()));
        return new Result(name, items);
    }

    /** 单个文件：复制 / 判同 / 替换空壳 / 两份保留，全程不丢字节。 */
    private static Item copyOne(Path from, Path to) {
        return copyOne(from, to, new HashSet<>());
    }

    /**
     * 单个文件：复制 / 判同 / 替换空壳 / 两份保留，全程不丢字节。
     *
     * <p>{@code decided} 里的名字表示「这个文件已经由更新的来源处理过」：此时即使老来源更大，
     * 也不再顶掉目标，只把它另存保留 —— 多个老位置来源（原目录 + {@code .orphan-*} 归档）
     * 同时存在时，最终生效的一定是最新的那一份。
     */
    private static Item copyOne(Path from, Path to, Set<String> decided) {
        String name = nameOf(from);
        try {
            long fromSize = Files.size(from);
            if (!Files.exists(to)) {
                decided.add(name);
                return write(from, to, fromSize, Status.COPIED);
            }
            long toSize = Files.size(to);
            if (toSize == fromSize && sameBytes(from, to)) {
                decided.add(name);
                return new Item(name, Status.SAME, fromSize);
            }
            if (toSize < fromSize && decided.add(name)) {
                // 目标比源还小：典型就是 1.2.0 刚进档时建出来的空壳（402 B 空索引、186 B 占位名册）。
                // 先把旧目标另存一份，再让源顶上去 —— 两份都在，谁也不会丢。
                keepAside(to, ".replaced-" + stamp());
                return write(from, to, fromSize, Status.REPLACED);
            }
            // 两边都像真数据（或已由更新的来源决定过）：目标不动，这一份另存，两份都留着
            Path aside = sibling(to, name + ".legacy-" + stamp());
            return write(from, aside, fromSize, Status.KEPT_BOTH);
        } catch (IOException e) {
            return new Item(name, Status.FAILED, 0L);
        }
    }

    /** 复制 + 校验（字节数与 sha256 都要一致）；校验不过就把半截文件删掉。 */
    private static Item write(Path from, Path to, long size, Status okStatus) {
        String name = nameOf(from);
        boolean existed = Files.exists(to);
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(to) != size || !sameBytes(from, to)) {
                removeHalfWritten(to, existed);
                return new Item(name, Status.FAILED, 0L);
            }
            return new Item(name, okStatus, size);
        } catch (IOException e) {
            removeHalfWritten(to, existed);
            return new Item(name, Status.FAILED, 0L);
        }
    }

    /** 失败时把刚写坏的半截文件清掉（原来就有的文件不动）。 */
    private static void removeHalfWritten(Path to, boolean existed) {
        if (existed) {
            return;
        }
        try {
            Files.deleteIfExists(to);
        } catch (IOException ignored) {
            // 删不掉就留着，日志里已经记了 FAILED
        }
    }

    /** 另存一份（不删除原文件）。 */
    private static void keepAside(Path file, String suffix) throws IOException {
        Files.copy(file, sibling(file, nameOf(file) + suffix),
                StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path sibling(Path file, String name) {
        Path parent = file.getParent();
        return parent == null ? Path.of(name) : parent.resolve(name);
    }

    /** 两个文件内容是否完全相同（先比长度，再比 sha256）。 */
    public static boolean sameBytes(Path a, Path b) {
        try {
            if (Files.size(a) != Files.size(b)) {
                return false;
            }
            return sha256(a).equals(sha256(b));
        } catch (IOException e) {
            return false;
        }
    }

    /** 文件内容的 sha256（小写十六进制）。 */
    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("sha256 不可用", e);
        }
    }

    /** 文本文件的内容（读不动返回 null）。 */
    public static String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public static String stamp() {
        return LocalDateTime.now().format(STAMP);
    }

    private static String nameOf(Path p) {
        if (p == null) {
            return "?";
        }
        Path name = p.getFileName();
        return name == null ? p.toString() : name.toString();
    }
}
