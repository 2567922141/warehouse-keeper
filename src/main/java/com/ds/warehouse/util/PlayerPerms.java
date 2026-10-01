package com.ds.warehouse.util;

import com.ds.warehouse.WarehouseMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 游戏内玩家权限表（批次 5 阶段 2）：谁可以取货、指挥假人、整理仓库。
 *
 * <p>为什么要有它：这三项权限原来存在网页账号库 {@code web-users.dat}（AES 加密，含密码校验值），
 * 网页端要删掉，权限不能跟着一起没。所以搬到这里：
 * <ul>
 *   <li>文件 {@code config/warehouse-keeper/players.json}，<b>明文</b> JSON —— 里面只有游戏名和三个开关，
 *       没有任何密码、邀请码之类的个人信息（那些仍然只留在网页账号库里，随网页端一起退役）；</li>
 *   <li>首次启动时若本表为空、而旧的 {@code web-users.dat} 还在，自动把其中的名字与三个开关导入一次
 *       （见 {@link #migrateFromWeb()}），导入后写 {@code migratedFromWeb} 标记，之后不再重复；</li>
 *   <li>没在本表里的玩家 = 没有权限（与原来「没在网页注册 = 没权限」的语义一致），
 *       管理员走 {@link Admin}，与这张表无关。</li>
 * </ul>
 *
 * <p>所有方法 synchronized：tick 线程（命令 / 面板动作）、网页线程都会碰。
 */
public final class PlayerPerms {

    private static final int FORMAT = 1;
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    /** 一个玩家的一行；{@code addedAt} 是毫秒时间戳 */
    public record Row(String name, boolean take, boolean bot, boolean tidy, long addedAt) {
    }

    /** 三项权限的只读快照 */
    public record Perm(boolean take, boolean bot, boolean tidy) {
        public static final Perm ALL = new Perm(true, true, true);

        public String text() {
            return "取货 " + (take ? "开" : "关") + " / 指挥搬运工 " + (bot ? "开" : "关")
                    + " / 整理仓库 " + (tidy ? "开" : "关");
        }
    }

    private static final class Store {
        boolean migratedFromWeb;
        final List<Row> users = new ArrayList<>();
    }

    private static Store STORE = new Store();

    private PlayerPerms() {
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("warehouse-keeper").resolve("players.json");
    }

    // ------------------------------------------------------------------ 读写

    public static synchronized void load() {
        STORE = new Store();
        Path p = path();
        if (Files.isRegularFile(p)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                STORE.migratedFromWeb = o.has("migratedFromWeb") && o.get("migratedFromWeb").getAsBoolean();
                if (o.has("users") && o.get("users").isJsonArray()) {
                    for (JsonElement el : o.getAsJsonArray("users")) {
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        JsonObject j = el.getAsJsonObject();
                        String name = j.has("name") ? j.get("name").getAsString().trim() : "";
                        if (name.isEmpty()) {
                            continue;
                        }
                        // 缺字段按「允许」读，与旧格式保持兼容
                        STORE.users.add(new Row(name,
                                !j.has("take") || j.get("take").getAsBoolean(),
                                !j.has("bot") || j.get("bot").getAsBoolean(),
                                !j.has("tidy") || j.get("tidy").getAsBoolean(),
                                j.has("addedAt") ? j.get("addedAt").getAsLong() : 0L));
                    }
                }
                WarehouseMod.LOGGER.info("[warehouse-keeper] 玩家权限表已载入：{} 个玩家", STORE.users.size());
            } catch (Throwable t) {
                Path bad = p.resolveSibling(p.getFileName() + ".bad-" + System.currentTimeMillis());
                try {
                    Files.move(p, bad, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // 改不了名就算了
                }
                WarehouseMod.LOGGER.error("[warehouse-keeper] 玩家权限表读不出来，已改名成 {} 并重新开始：{}",
                        bad.getFileName(), t.toString());
            }
        }
    }

    public static synchronized boolean save() {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("migratedFromWeb", STORE.migratedFromWeb);
        JsonArray arr = new JsonArray();
        for (Row r : STORE.users) {
            JsonObject j = new JsonObject();
            j.addProperty("name", r.name());
            j.addProperty("take", r.take());
            j.addProperty("bot", r.bot());
            j.addProperty("tidy", r.tidy());
            j.addProperty("addedAt", r.addedAt());
            arr.add(j);
        }
        o.add("users", arr);
        try {
            writeAtomic(path(), PRETTY.toJson(o));
            return true;
        } catch (Throwable t) {
            WarehouseMod.LOGGER.error("[warehouse-keeper] 玩家权限表写不进去：{}", t.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------ 查询 / 修改

    public static synchronized int count() {
        return STORE.users.size();
    }

    public static synchronized boolean has(String name) {
        return find(name) != null;
    }

    /** 三项权限；表中没有这个玩家时返回 null（= 没有权限） */
    public static synchronized Perm perm(String name) {
        Row r = find(name);
        return r == null ? null : new Perm(r.take(), r.bot(), r.tidy());
    }

    /** 名字列表（按名字排序） */
    public static synchronized List<String> names() {
        List<String> out = new ArrayList<>();
        for (Row r : STORE.users) {
            out.add(r.name());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** 全部行的副本（按名字排序），给面板 / 快照用 */
    public static synchronized List<Row> rows() {
        List<Row> out = new ArrayList<>(STORE.users);
        out.sort(Comparator.comparing(Row::name));
        return out;
    }

    /** 改三项权限；表中没有这个玩家就先建一行（房主直接在面板里给人开权限） */
    public static synchronized boolean setPerm(String name, boolean take, boolean bot, boolean tidy) {
        String n = clean(name);
        if (n.isEmpty()) {
            return false;
        }
        Row old = find(n);
        if (old == null) {
            STORE.users.add(new Row(n, take, bot, tidy, System.currentTimeMillis()));
        } else {
            STORE.users.set(STORE.users.indexOf(old), new Row(old.name(), take, bot, tidy, old.addedAt()));
        }
        return save();
    }

    /** 删掉一行（= 收回这个玩家的全部权限） */
    public static synchronized boolean remove(String name) {
        Row r = find(name);
        if (r == null) {
            return false;
        }
        STORE.users.remove(r);
        save();
        return true;
    }

    /** 一句话状态（命令回显用） */
    public static synchronized String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("玩家权限表：").append(STORE.users.size()).append(" 个");
        if (STORE.migratedFromWeb) {
            sb.append("（已从旧网页账号库导入）");
        }
        if (!STORE.users.isEmpty()) {
            sb.append("，名单：").append(String.join("、", names()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 迁移

    /**
     * 从旧的网页账号库导入一次：只在「本表还是空的」且「旧库存在」时动手。
     *
     * <p>导入的是名字 + 三个开关（{@code canTake/canBot/canTidy}），密码、邀请码一概不搬。
     * 网页账号库的文件本身不动，网页端在阶段 3 之前还能照常用。
     *
     * @return 给玩家/日志看的一句话；没做事时说明原因
     */
    public static synchronized String migrateFromWeb() {
        return migrateFromWeb(false);
    }

    /**
     * @param force true = 本表非空也强行导入（已存在的玩家只补缺、不覆盖）
     */
    public static synchronized String migrateFromWeb(boolean force) {
        if (!STORE.users.isEmpty() && !force) {
            return "本表已有 " + STORE.users.size() + " 个玩家，跳过导入（要强行导入用 /warehouse user migrate force）。";
        }
        if (!com.ds.warehouse.web.WebUsers.anyAccount()) {
            STORE.migratedFromWeb = true;
            save();
            return "没有找到旧网页账号库（web-users.dat），没什么可导入的。";
        }
        List<String> names = com.ds.warehouse.web.WebUsers.names();
        int added = 0;
        int kept = 0;
        for (String n : names) {
            com.ds.warehouse.web.WebUsers.Perm p = com.ds.warehouse.web.WebUsers.perm(n);
            if (p == null) {
                continue;
            }
            if (find(n) != null) {
                kept++;
                continue;
            }
            STORE.users.add(new Row(n, p.take(), p.bot(), p.tidy(), System.currentTimeMillis()));
            added++;
        }
        STORE.migratedFromWeb = true;
        save();
        // 日志由调用方打（WarehouseMod 启动时打一次 / 命令回显时打），这里不重复
        return "已从旧网页账号库导入 " + added + " 个玩家" + (kept > 0 ? "（另有 " + kept + " 个已存在，未改动）" : "");
    }

    public static synchronized boolean migratedFromWeb() {
        return STORE.migratedFromWeb;
    }

    // ------------------------------------------------------------------ 内部

    private static Row find(String name) {
        String n = clean(name);
        if (n.isEmpty()) {
            return null;
        }
        for (Row r : STORE.users) {
            if (r.name().equalsIgnoreCase(n)) {
                return r;
            }
        }
        return null;
    }

    private static String clean(String name) {
        return name == null ? "" : name.trim();
    }

    private static void writeAtomic(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
