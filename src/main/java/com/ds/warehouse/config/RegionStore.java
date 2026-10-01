package com.ds.warehouse.config;

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
 * 仓库区域定义 + 持久化。
 *
 * 存储位置：<游戏实例>/config/warehouse-keeper/worlds/&lt;存档目录名&gt;/regions.json
 * （每个存档一份；0.15.x 及以前放在 config 根下的老文件会在进存档时自动迁移过来）
 * 刻意放在 config 而不是存档目录里 —— 卸载模组后存档里不留任何残留。
 */
public final class RegionStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Region>>() {
    }.getType();

    /** 内存中的区域表，key = 区域名（小写） */
    public static final Map<String, Region> REGIONS = new LinkedHashMap<>();

    private RegionStore() {
    }

    private static Path file() {
        return WorldStore.file("regions.json");
    }

    public static void load() {
        REGIONS.clear();
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Map<String, Region> loaded = GSON.fromJson(r, MAP_TYPE);
            if (loaded != null) {
                REGIONS.putAll(loaded);
            }
        } catch (Exception e) {
            com.ds.warehouse.WarehouseMod.LOGGER.warn("[warehouse-keeper] 读取 regions.json 失败: {}", e.toString());
            // 读不动的文件不能被下一次 save() 直接覆盖掉：先留一份 .bak（只留第一次的），再照原样继续
            Path bak = f.resolveSibling(f.getFileName() + ".bak");
            try {
                if (!Files.exists(bak)) {
                    Files.copy(f, bak, StandardCopyOption.COPY_ATTRIBUTES);
                    com.ds.warehouse.WarehouseMod.LOGGER.info("[warehouse-keeper] 已把读不动的 regions.json 另存为 {}", bak.getFileName());
                }
            } catch (IOException copyFailed) {
                // 备份失败不影响读取流程
            }
        }
    }

    public static void save() {
        Path f = file();
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(REGIONS, MAP_TYPE, w);
            }
            // 先写临时文件再原子替换：直接写 regions.json 的话，中途崩了/掉电会留下半截文件，
            // 下次启动整份仓库定义都读不回来
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            com.ds.warehouse.WarehouseMod.LOGGER.warn("[warehouse-keeper] 写入 regions.json 失败: {}", e.toString());
            try {
                Files.deleteIfExists(tmp); // 别把没替换成功的半截文件留在目录里
            } catch (IOException ignored) {
                // 删不掉就算了，下次写会被 REPLACE_EXISTING 覆盖
            }
        }
    }
}
