package com.ds.warehouse.porter;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.WorldStore;
import com.ds.warehouse.index.WarehouseIndex;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「这个东西该放哪个箱子」的记忆。
 *
 * <p>索引（{@code INDEX.items}）只知道「箱子**现在**有什么」。一旦搬运工把某种物品
 * <b>全部取走</b>，索引里就没有它了 —— 这时候玩家想把东西交回来，索引答不上来，
 * 但东西明明就是从那个箱子拿的。
 *
 * <p>所以这里额外记一份「物品 → 箱子」的归属：它只增不减，跟箱子里还剩多少无关，
 * 而且会<b>存到硬盘</b>，关服重开也还记得。
 *
 * <p>文件写在 {@code config/warehouse-keeper/homes.json}，不进存档目录。
 */
public final class Homes {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type TYPE = new TypeToken<LinkedHashMap<String, String>>() {
    }.getType();

    /** itemId → 容器 key（格式见 {@link WarehouseIndex#key(String, net.minecraft.core.BlockPos)}） */
    private static final Map<String, String> MAP = new LinkedHashMap<>();
    private static boolean dirty;

    private Homes() {
    }

    private static Path file() {
        // 每个存档一份：箱子坐标在别的世界里毫无意义，串档会导致搬运工往错误的位置放东西
        return WorldStore.file("homes.json");
    }

    public static void load() {
        MAP.clear();
        dirty = false;
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Map<String, String> read = GSON.fromJson(r, TYPE);
            if (read != null) {
                MAP.putAll(read);
            }
            WarehouseMod.LOGGER.info("已载入 {} 条「物品该放哪个箱子」的记忆: {}", MAP.size(), f);
        } catch (Exception e) {
            WarehouseMod.LOGGER.warn("读取 {} 失败（不影响使用，扫描时会重新学）: {}", f, e.toString());
        }
    }

    public static void save() {
        if (!dirty) {
            return;
        }
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(MAP, TYPE, w);
            }
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            WarehouseMod.LOGGER.info("已存 {} 条「物品该放哪个箱子」的记忆: {}", MAP.size(), f);
        } catch (Exception e) {
            WarehouseMod.LOGGER.warn("写入 {} 失败: {}", f, e.toString());
        }
    }

    /**
     * 从索引里学：每种物品记一个「主箱子」—— 装得最多的那个。
     *
     * <p>索引里没有这个物品时<b>什么都不做</b>（绝不把旧记忆抹掉，这正是关键）。
     */
    public static void learn(WarehouseIndex idx) {
        for (WarehouseIndex.ItemEntry e : idx.items.values()) {
            WarehouseIndex.SlotRef best = null;
            for (WarehouseIndex.SlotRef ref : e.refs) {
                if (best == null || ref.count() > best.count()) {
                    best = ref;
                }
            }
            if (best != null) {
                remember(e.itemId, WarehouseIndex.key(best.dimension(), best.pos()));
            }
        }
    }

    /** 记一条：这种物品刚刚是从这个箱子拿的 / 放进这个箱子的。 */
    public static void remember(String itemId, String containerKey) {
        if (itemId == null || containerKey == null) {
            return;
        }
        if (!containerKey.equals(MAP.get(itemId))) {
            MAP.put(itemId, containerKey);
            dirty = true;
        }
    }

    /** @return 这个物品该放的箱子 key；没记过就是 null */
    public static String home(String itemId) {
        return MAP.get(itemId);
    }

    /** 记忆有改动、还没落盘？ */
    public static boolean isDirty() {
        return dirty;
    }

    /** 记忆里那种物品的「家」：维度 + 坐标。 */
    public record Spot(String dimension, net.minecraft.core.BlockPos pos) {
    }

    /**
     * 把 key 解析成维度 + 坐标。
     *
     * <p>刻意<b>不查 {@code INDEX.containers}</b>：{@code /warehouse give all} 处理第一叠东西时就会
     * 触发一次重扫，而 {@code Scanner.start} 第一件事就是 {@code INDEX.clear()} ——
     * 之后的几叠要是还得靠索引才找得到箱子，就会全部失败。坐标就写在 key 里，自己解析最稳。
     */
    public static Spot spot(String itemId) {
        String key = MAP.get(itemId);
        if (key == null) {
            return null;
        }
        int at = key.lastIndexOf('@');
        if (at <= 0) {
            return null;
        }
        String[] xyz = key.substring(at + 1).split(",");
        if (xyz.length != 3) {
            return null;
        }
        try {
            return new Spot(key.substring(0, at), new net.minecraft.core.BlockPos(
                    Integer.parseInt(xyz[0].trim()),
                    Integer.parseInt(xyz[1].trim()),
                    Integer.parseInt(xyz[2].trim())));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int size() {
        return MAP.size();
    }
}
