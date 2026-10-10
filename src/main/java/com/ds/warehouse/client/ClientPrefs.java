package com.ds.warehouse.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端本地偏好（批次 0.22.0 · BUG1）。
 *
 * <p>故意的「客户端专属」：箱子标签栏贴在箱子界面的哪一边，是**每个玩家自己**的观感问题
 * （谁的 JEI 在右边、谁的窗口有多宽都不一样），所以存在客户端 config 目录，不进服务端
 * 设置、不进存档、不走网络。服务端完全不知道这个文件存在。
 *
 * <p>存储位置：&lt;游戏实例&gt;/config/warehouse-keeper-client.json
 * （与 {@code config/warehouse-keeper/settings.json} 并排；卸载模组后零残留）。
 *
 * <p>写法照抄 {@link com.ds.warehouse.config.AppConfig}：Gson + prettyPrinting，
 * 读取失败只记一条 warn 并用默认值，绝不因为一个坏配置文件让客户端起不来。
 */
public final class ClientPrefs {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 可选的停靠位置，顺序就是「维护」页那个循环按钮的点击顺序 */
    public static final String[] DOCKS = {"auto", "right", "left", "top", "bottom"};

    /** 中文显示名（面板按钮 + 状态提示共用一份，免得两处文案不一样） */
    private static final String[] DOCK_LABELS = {"自动", "右", "左", "上", "下"};

    private static ClientPrefs instance;

    /**
     * 标签栏停靠位置：{@code auto|right|left|top|bottom}。
     *
     * <p>{@code auto} = 默认：优先贴箱子界面右侧，但界面尺寸 {@code guiScale <= 1}
     * （窗口逻辑宽度很大、JEI 的配方/物品列表几乎必然占着右边）时先试左侧。
     * 非法值一律回落 {@code auto}（见 {@link #normalize()}）。
     */
    public String tagBarDock = "auto";

    public static ClientPrefs get() {
        ClientPrefs c = instance;
        if (c == null) {
            c = load();
        }
        return c;
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("warehouse-keeper-client.json");
    }

    /**
     * 当前停靠偏好（已归一化）。
     *
     * <p>给 {@code TagBarLayout} 用：那个类是**纯几何**、刻意不依赖 Minecraft/Gson
     * （{@code tools/tagbar/LayoutCheck.java} 用普通 javac 和它一起编译），
     * 所以它不 import 本类，改由调用方把这一小段读出来传进去。
     */
    public static String dock() {
        return get().tagBarDock;
    }

    /** 停靠值 → 面板上的中文名；未知值当 auto */
    public static String label(String dock) {
        return DOCK_LABELS[index(dock)];
    }

    /** 循环按钮：auto → right → left → top → bottom → auto */
    public static String next(String dock) {
        return DOCKS[(index(dock) + 1) % DOCKS.length];
    }

    /** 归一化：非法 / null / 大小写混杂一律回落 auto */
    public static String normalizeDock(String value) {
        if (value == null) {
            return "auto";
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (String d : DOCKS) {
            if (d.equals(v)) {
                return d;
            }
        }
        return "auto";
    }

    private static int index(String dock) {
        String v = normalizeDock(dock);
        for (int i = 0; i < DOCKS.length; i++) {
            if (DOCKS[i].equals(v)) {
                return i;
            }
        }
        return 0;
    }

    public static ClientPrefs load() {
        Path f = configPath();
        ClientPrefs c = null;
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                c = GSON.fromJson(r, ClientPrefs.class);
            } catch (Exception e) {
                com.ds.warehouse.WarehouseMod.LOGGER.warn(
                        "[warehouse-keeper] 读取 warehouse-keeper-client.json 失败，先用默认值: {}", e.toString());
            }
        }
        if (c == null) {
            c = new ClientPrefs();
        }
        c.normalize();
        instance = c;
        return c;
    }

    public void save() {
        normalize();
        // 先写 .tmp 再原子替换：直接写目标文件，中途崩了/掉电会留下半截 json（审查发现 T2）
        com.ds.warehouse.config.WorldStore.writeAtomic(configPath(), tmp -> {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
        });
    }

    public void normalize() {
        tagBarDock = normalizeDock(tagBarDock);
    }
}
