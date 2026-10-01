package com.ds.warehouse.web;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.util.PlayerPerms;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * 旧网页账号库（只读遗留数据）。
 *
 * <p>内嵌网页端已于「批次 5 阶段 3」整体删除。这个类不再承担 HTTP 会话 / 注册 / 登录 /
 * 邀请码等任何职责，只保留<b>读旧账号库</b>的能力，供
 * {@link PlayerPerms#migrateFromWeb(boolean)} 一次性把老账号的三项权限导入游戏内权限表。
 *
 * <p>文件格式与解密方式完全不变：
 * <ul>
 *   <li>整个账号库序列化成一个 JSON，用 <b>AES-256-GCM</b> 加密后写成
 *       {@code config/warehouse-keeper/web-users.dat}；</li>
 *   <li>密钥是 32 字节，存在 {@code config/warehouse-keeper/web-key.dat}；</li>
 *   <li>权限迁移只读名字 + 三个开关，密码校验值一概不碰；</li>
 *   <li>文件读不出来时<b>不删除</b>，改名成 {@code .bad-<时间戳>} 留证据。</li>
 * </ul>
 *
 * <p>本类只碰文件与内存，<b>绝不</b>碰世界/玩家，可安全地从任意线程调用。
 */
public final class WebUsers {

    private static final int ITERATIONS = 210_000;
    private static final int KEY_LEN = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 一个玩家账号。密码只留 salt+hash，不可逆。 */
    public static final class Account {
        public String name = "";
        public String salt = "";
        public String hash = "";
        public int iterations = ITERATIONS;
        public long createdAt;
        public long lastLogin;
        /** 允许取物品 */
        public boolean canTake = true;
        /** 允许指挥假人（加人 / 撤人 / 分配仓库 / 叫出来 / 让他退场） */
        public boolean canBot = true;
        /** 允许整理仓库（刷新索引 / 整理仓库 / 清扫地面 / 停下） */
        public boolean canTidy = true;

        public Account copy() {
            Account c = new Account();
            c.name = name;
            c.salt = salt;
            c.hash = hash;
            c.iterations = iterations;
            c.createdAt = createdAt;
            c.lastLogin = lastLogin;
            c.canTake = canTake;
            c.canBot = canBot;
            c.canTidy = canTidy;
            return c;
        }
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
        List<Account> users = new ArrayList<>();
    }

    private static Store STORE = new Store();

    private WebUsers() {
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("warehouse-keeper");
    }

    /** 加密后的账号库 */
    public static Path dataPath() {
        return dir().resolve("web-users.dat");
    }

    /** AES 密钥（base64） */
    public static Path keyPath() {
        return dir().resolve("web-key.dat");
    }

    // ------------------------------------------------------------------ 只读载入

    public static synchronized void load() {
        STORE = new Store();
        Path p = dataPath();
        if (!Files.isRegularFile(p)) {
            return;
        }
        try {
            String plain = decrypt(Files.readString(p));
            JsonObject o = JsonParser.parseString(plain).getAsJsonObject();
            Store s = new Store();
            if (o.has("users") && o.get("users").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("users")) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject j = el.getAsJsonObject();
                    Account a = new Account();
                    a.name = j.has("name") ? j.get("name").getAsString() : "";
                    a.salt = j.has("salt") ? j.get("salt").getAsString() : "";
                    a.hash = j.has("hash") ? j.get("hash").getAsString() : "";
                    a.iterations = j.has("iterations") ? j.get("iterations").getAsInt() : ITERATIONS;
                    a.createdAt = j.has("createdAt") ? j.get("createdAt").getAsLong() : 0L;
                    a.lastLogin = j.has("lastLogin") ? j.get("lastLogin").getAsLong() : 0L;
                    // 旧格式没有这三个字段 -> 按「都允许」读，保持向后兼容
                    a.canTake = !j.has("canTake") || j.get("canTake").getAsBoolean();
                    a.canBot = !j.has("canBot") || j.get("canBot").getAsBoolean();
                    a.canTidy = !j.has("canTidy") || j.get("canTidy").getAsBoolean();
                    if (!a.name.isEmpty() && !a.hash.isEmpty()) {
                        s.users.add(a);
                    }
                }
            }
            STORE = s;
            WarehouseMod.LOGGER.info("[warehouse-keeper] 旧网页账号库已载入（只读，供权限迁移）：{} 个账号",
                    s.users.size());
        } catch (Throwable t) {
            Path bad = p.resolveSibling(p.getFileName() + ".bad-" + System.currentTimeMillis());
            try {
                Files.move(p, bad, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 改不了名就算了，下次还会报
            }
            WarehouseMod.LOGGER.error("[warehouse-keeper] 旧网页账号库读不出来（密钥丢了或者文件损坏），"
                    + "已把它改名成 {} 并重新开始：{}", bad.getFileName(), t.toString());
        }
    }

    // ------------------------------------------------------------------ 账号（只读）

    public static synchronized boolean anyAccount() {
        return !STORE.users.isEmpty();
    }

    public static synchronized List<String> names() {
        List<String> out = new ArrayList<>();
        for (Account a : STORE.users) {
            out.add(a.name);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    public static synchronized boolean has(String name) {
        return find(name) != null;
    }

    /** 账号副本（按名字排序） */
    public static synchronized List<Account> accounts() {
        List<Account> out = new ArrayList<>();
        for (Account a : STORE.users) {
            out.add(a.copy());
        }
        out.sort(Comparator.comparing((Account a) -> a.name));
        return out;
    }

    /**
     * 三项权限；没有这个账号时返回 null
     *
     * <p>批次 5 阶段 2 起，游戏内权限表 {@link PlayerPerms} 是权威：这里先读它，
     * 只有表里没有（还没迁移过来的老账号）才回退读旧库自己存的三个开关。
     */
    public static synchronized Perm perm(String name) {
        PlayerPerms.Perm p = PlayerPerms.perm(name);
        if (p != null) {
            return new Perm(p.take(), p.bot(), p.tidy());
        }
        Account a = find(name);
        return a == null ? null : new Perm(a.canTake, a.canBot, a.canTidy);
    }

    // ------------------------------------------------------------------ 内部

    private static Account find(String name) {
        String n = clean(name);
        if (n.isEmpty()) {
            return null;
        }
        for (Account a : STORE.users) {
            if (a.name.equalsIgnoreCase(n)) {
                return a;
            }
        }
        return null;
    }

    private static String clean(String name) {
        return name == null ? "" : name.trim();
    }

    private static byte[] key() throws IOException {
        Path kp = keyPath();
        if (Files.isRegularFile(kp)) {
            try {
                byte[] k = Base64.getDecoder().decode(Files.readString(kp).trim());
                if (k.length == KEY_LEN) {
                    return k;
                }
            } catch (Throwable ignored) {
                // 坏掉了就重新生成一把（数据文件会解不开，load 里会改名留证据）
            }
        }
        byte[] k = new byte[KEY_LEN];
        RANDOM.nextBytes(k);
        writeAtomic(kp, Base64.getEncoder().encodeToString(k));
        return k;
    }

    private static String decrypt(String blob) throws Exception {
        JsonObject o = JsonParser.parseString(blob).getAsJsonObject();
        byte[] iv = Base64.getDecoder().decode(o.get("iv").getAsString());
        byte[] ct = Base64.getDecoder().decode(o.get("ct").getAsString());
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
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
