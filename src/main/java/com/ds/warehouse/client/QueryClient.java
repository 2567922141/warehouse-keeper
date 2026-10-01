package com.ds.warehouse.client;

import com.ds.warehouse.net.ViewQueryPayload;
import com.ds.warehouse.net.ViewResultPayload;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端的「按需查询」小管家（批次 5 阶段 1）。
 *
 * <p>面板上的每一页都只通过它取数据：
 * <ul>
 *   <li>{@link #open} —— 打开某个页签时调一次：有新鲜缓存就直接画，没有就发一条请求；</li>
 *   <li>{@link #request} —— 条件变了（换仓库 / 翻页 / 改关键字）时登记一条请求，由 tick 发出去；</li>
 *   <li>同一个 kind 同一时刻只允许在途一条：回包到了才允许发下一条，所以按住翻页键
 *       也不会把服务端刷爆；超过 5 秒没回包（丢包 / 服务端不认）就允许重发；</li>
 *   <li>服务端没装本模组或版本比客户端老（没有这个通道）时 {@link #supported()} 为假，
 *       页面显示「当前服务器不支持面板查询」，不会崩。</li>
 * </ul>
 *
 * <p>所有方法都在客户端主线程调用（渲染线程）。
 */
public final class QueryClient {

    /** 单页行数（与服务端 {@link ViewQueryPayload#MAX_SIZE} 对齐） */
    public static final int DEFAULT_SIZE = ViewQueryPayload.MAX_SIZE;

    /**
     * 缓存多久算「旧」：打开页签时超过它就顺手问一次。
     *
     * <p>批次 6 起从 3 秒收紧到 0.4 秒 —— 玩家点来点去时，3 秒的缓存会让页面显示上一次的旧数据，
     * 看起来就是「按钮按下去没反应」。
     */
    private static final long CACHE_TTL_MS = 400;

    /** 在途超过这么久就当作丢包，允许重发 */
    private static final long IN_FLIGHT_TIMEOUT_MS = 2000;

    private static final Gson GSON = new Gson();
    private static final int SLOTS = 16;

    /** 每种查询最近一次成功的结果 */
    private static final JsonObject[] CACHE = new JsonObject[SLOTS];
    private static final long[] CACHE_AT = new long[SLOTS];
    /** 想查但还没发出去 / 还没回包的请求 */
    private static final Request[] WANT = new Request[SLOTS];
    /** 已经发出去、还没收到回包的 kind */
    private static final boolean[] IN_FLIGHT = new boolean[SLOTS];
    private static final long[] SENT_AT = new long[SLOTS];

    private static boolean supported = true;

    private QueryClient() {
    }

    /** 一次查询的参数（就是 {@link ViewQueryPayload} 的内容） */
    public record Request(int kind, String keyword, String category, String region, String key,
                          String sort, int page, int size) {

        public static Request of(int kind, String region) {
            return new Request(kind, "", "", region == null ? "" : region, "", "", 1, DEFAULT_SIZE);
        }

        public Request withPage(int p) {
            return new Request(kind, keyword, category, region, key, sort, Math.max(1, p), size);
        }

        public Request withKeyword(String k) {
            return new Request(kind, k == null ? "" : k, category, region, key, sort, 1, size);
        }

        public Request withCategory(String c) {
            return new Request(kind, keyword, c == null ? "" : c, region, key, sort, 1, size);
        }

        public Request withRegion(String r) {
            return new Request(kind, keyword, category, r == null ? "" : r, key, sort, 1, size);
        }

        public Request withSort(String s) {
            return new Request(kind, keyword, category, region, key, s == null ? "" : s, 1, size);
        }

        public Request withKey(String k) {
            return new Request(kind, keyword, category, region, k == null ? "" : k, sort, 1, size);
        }
    }

    /** 服务端是否支持面板查询（老服务端没有注册这个通道） */
    public static boolean supported() {
        return supported;
    }

    /** 通道是否可用（没连服务器时也为假） */
    private static boolean channel() {
        try {
            return Minecraft.getInstance().getConnection() != null
                    && ClientPlayNetworking.canSend(ViewQueryPayload.TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开页签：命中新鲜缓存就用它，否则登记一条请求 */
    public static void open(Request... requests) {
        long now = System.currentTimeMillis();
        for (Request r : requests) {
            if (r == null) {
                continue;
            }
            // 与 request() 一致：先挡住越界的 kind，别让 CACHE 越界
            if (r.kind() <= 0 || r.kind() >= SLOTS) {
                continue;
            }
            if (CACHE[r.kind()] != null && CACHE_AT[r.kind()] > 0
                    && now - CACHE_AT[r.kind()] < CACHE_TTL_MS) {
                continue;
            }
            request(r);
        }
    }

    /** 登记一条请求（tick 里真正发出去；同一 kind 在途时只更新参数） */
    public static void request(Request r) {
        if (r == null || r.kind() <= 0 || r.kind() >= SLOTS) {
            return;
        }
        WANT[r.kind()] = r;
    }

    /** 清掉某个 kind 的缓存（重新扫描之后想让页面拿到新数据时用） */
    public static void invalidate(int kind) {
        if (kind > 0 && kind < SLOTS) {
            CACHE[kind] = null;
            CACHE_AT[kind] = 0;
        }
    }

    /**
     * 清掉所有查询的缓存。
     *
     * <p>服务端刚推来一份新快照（内容变了）时调用：这样玩家切到「物品总览」之类的页签时，
     * 不会再拿到变化之前那次查询的结果。
     */
    public static void invalidateAll() {
        for (int kind = 1; kind < SLOTS; kind++) {
            CACHE[kind] = null;
            CACHE_AT[kind] = 0;
        }
    }

    /** 强制重发上一次的请求（「刷新」按钮） */
    public static void refresh(int kind) {
        Request r = WANT[kind] != null ? WANT[kind] : last(kind);
        if (r != null) {
            CACHE[kind] = null;
            CACHE_AT[kind] = 0;
            WANT[kind] = r;
        }
    }

    private static Request last(int kind) {
        return kind > 0 && kind < SLOTS ? WANT[kind] : null;
    }

    /** 每个客户端 tick 调一次：把登记的请求发出去 */
    public static void tick() {
        long now = System.currentTimeMillis();
        boolean ok = channel();
        // 通道能力实时跟随服务器：刚进游戏时握手还没完成 canSend 会短暂为假，
        // 早期版本在这里只置假不复位，导致整个会话永远退回整包快照（实机踩到过一次）。
        if (hasConnection()) {
            supported = ok;
        }
        for (int kind = 1; kind < SLOTS; kind++) {
            if (IN_FLIGHT[kind]) {
                if (now - SENT_AT[kind] > IN_FLIGHT_TIMEOUT_MS) {
                    // 超时：当作丢了，允许重发
                    IN_FLIGHT[kind] = false;
                } else {
                    continue;
                }
            }
            Request r = WANT[kind];
            if (r == null) {
                continue;
            }
            if (!ok) {
                continue;
            }
            supported = true;
            WANT[kind] = null;
            IN_FLIGHT[kind] = true;
            SENT_AT[kind] = now;
            try {
                ClientPlayNetworking.send(new ViewQueryPayload(r.kind(), r.keyword(), r.category(),
                        r.region(), r.key(), r.sort(), r.page(), r.size()));
            } catch (Throwable t) {
                IN_FLIGHT[kind] = false;
            }
        }
    }

    /** 有没有连着服务器（用来区分「单机菜单」和「老服务端」两种情况） */
    private static boolean hasConnection() {
        return Minecraft.getInstance().getConnection() != null;
    }

    /** 收到服务端回包（由 {@link WarehouseClient} 注册的接收器调用） */
    static void accept(int kind, byte[] json) {
        if (kind < 1 || kind >= SLOTS) {
            return;
        }
        IN_FLIGHT[kind] = false;
        try {
            CACHE[kind] = GSON.fromJson(new String(json, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Throwable ignored) {
            CACHE[kind] = new JsonObject();
        }
        if (CACHE[kind] == null) {
            CACHE[kind] = new JsonObject();
        }
        CACHE_AT[kind] = System.currentTimeMillis();
    }

    /** 清空（断开连接时调用） */
    public static void clear() {
        for (int i = 0; i < SLOTS; i++) {
            CACHE[i] = null;
            WANT[i] = null;
            IN_FLIGHT[i] = false;
            CACHE_AT[i] = 0;
            SENT_AT[i] = 0;
        }
        supported = true;
    }

    // ------------------------------------------------------------------
    // 取缓存数据的便捷入口（面板用）

    /** 取某个 kind 的缓存（没有就是 null） */
    public static JsonObject raw(int kind) {
        return kind > 0 && kind < SLOTS ? CACHE[kind] : null;
    }

    /** 取一个字符串字段，缺失返回 "" */
    public static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        try {
            return o.get(key).getAsString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 取一个 long 字段，缺失返回 0 */
    public static long num(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return o.get(key).getAsLong();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 取一个 bool 字段 */
    public static boolean flag(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return false;
        }
        try {
            return o.get(key).getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取数组字段（不是数组就返回空列表） */
    public static List<JsonObject> arr(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (o == null || !o.has(key)) {
            return out;
        }
        try {
            for (var e : o.getAsJsonArray(key)) {
                if (e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        } catch (Throwable ignored) {
            // 字段形状不对就当作空
        }
        return out;
    }
}
