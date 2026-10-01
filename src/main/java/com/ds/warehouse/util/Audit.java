package com.ds.warehouse.util;

import com.ds.warehouse.WarehouseMod;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;

/**
 * 玩家操作日志：谁、什么时候、做了什么。
 *
 * <p>写在 {@code config/warehouse-keeper/audit.log}，一行一条 JSON；内存里保留最近 {@link #MAX} 条
 * 供网页/指令即时查看。文件超过 {@link #MAX_BYTES} 就轮转成 {@code audit.log.1}。
 *
 * <p>所有方法 synchronized：HTTP 线程（网页）和 tick 线程（游戏内）都会调用。
 */
public final class Audit {

    /** 内存里保留多少条 */
    private static final int MAX = 1000;
    /** 日志文件多大之后轮转 */
    private static final long MAX_BYTES = 1024 * 1024;
    private static final Gson GSON = new Gson();
    private static final Deque<Entry> RECENT = new ArrayDeque<>();

    /** 一条日志。{@code ts} 是毫秒时间戳。 */
    public record Entry(long ts, String who, String action, String detail) {
    }

    private Audit() {
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("warehouse-keeper").resolve("audit.log");
    }

    /** 记一条（失败不影响主流程） */
    public static synchronized void add(String who, String action, String detail) {
        Entry e = new Entry(System.currentTimeMillis(), text(who), text(action), text(detail));
        RECENT.addFirst(e);
        while (RECENT.size() > MAX) {
            RECENT.removeLast();
        }
        try {
            append(e);
        } catch (Throwable t) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 操作日志写不进去：{}", t.toString());
        }
    }

    /** 服务器启动时读回来 */
    public static synchronized void load() {
        RECENT.clear();
        Path p = path();
        if (!Files.isRegularFile(p)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - MAX);
            for (int i = from; i < lines.size(); i++) {
                Entry e = parse(lines.get(i));
                if (e != null) {
                    RECENT.addFirst(e);
                }
            }
            if (!RECENT.isEmpty()) {
                WarehouseMod.LOGGER.info("[warehouse-keeper] 操作日志已载入 {} 条", RECENT.size());
            }
        } catch (Throwable t) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 操作日志读不出来：{}", t.toString());
        }
    }

    /** 最近的操作（新的在前）；{@code offset} 是跳过多少条 */
    public static synchronized List<Entry> recent(int limit, int offset) {
        List<Entry> out = new ArrayList<>();
        if (limit <= 0) {
            return out;
        }
        int seen = 0;
        for (Entry e : RECENT) {
            if (seen++ < Math.max(0, offset)) {
                continue;
            }
            out.add(e);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    public static synchronized int size() {
        return RECENT.size();
    }

    /** 清空内存缓存（文件保留） */
    public static synchronized void clearCache() {
        RECENT.clear();
    }

    /** 日志里的时间文本，例如 {@code 09-26 21:03:11} */
    public static String timeText(long ts) {
        if (ts <= 0) {
            return "时间未知";
        }
        return new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(ts));
    }

    // ------------------------------------------------------------------ 内部

    private static void append(Entry e) throws IOException {
        Path p = path();
        Files.createDirectories(p.getParent());
        if (Files.isRegularFile(p) && Files.size(p) > MAX_BYTES) {
            try {
                Files.move(p, p.resolveSibling(p.getFileName() + ".1"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 轮转不了就继续追加
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("ts", e.ts());
        o.addProperty("who", e.who());
        o.addProperty("action", e.action());
        o.addProperty("detail", e.detail());
        Files.writeString(p, GSON.toJson(o) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static Entry parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            return new Entry(
                    o.has("ts") ? o.get("ts").getAsLong() : 0L,
                    o.has("who") ? o.get("who").getAsString() : "",
                    o.has("action") ? o.get("action").getAsString() : "",
                    o.has("detail") ? o.get("detail").getAsString() : "");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String text(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
