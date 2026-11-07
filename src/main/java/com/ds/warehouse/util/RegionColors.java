package com.ds.warehouse.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 仓库代表颜色：把玩家输入的写法（{@code #RRGGBB} / {@code RRGGBB} / 中文颜色名 / {@code random}）
 * 解析成 {@code 0xRRGGBB}，也负责把面板预设色和默认白色集中放在一处。
 *
 * <p>颜色只是显示用（边界长方体 + 面板色块），不参与任何库存/区域判定 —— 所以这里做全量的
 * 「解析失败就返回 null」，由调用方给出提示，不做兜底猜测，免得玩家以为改成功了。
 */
public final class RegionColors {

    /** 默认颜色：白色（旧仓库没有 color 字段时也是它） */
    public static final int DEFAULT = 0xFFFFFF;

    /** 面板里给的常用预设色（顺着常见颜色命名，颜色值选的是游戏里容易分辨的亮色） */
    public static final Map<String, Integer> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("白", 0xFFFFFF);
        PRESETS.put("红", 0xFF5555);
        PRESETS.put("橙", 0xFFA347);
        PRESETS.put("黄", 0xFFFF55);
        PRESETS.put("绿", 0x55FF55);
        PRESETS.put("青", 0x55FFFF);
        PRESETS.put("蓝", 0x5599FF);
        PRESETS.put("紫", 0xBB77FF);
        PRESETS.put("粉", 0xFF8CC8);
        PRESETS.put("灰", 0xAAAAAA);
        PRESETS.put("棕", 0xC08A4B);
        PRESETS.put("黑", 0x303030);
    }

    private RegionColors() {
    }

    /**
     * 解析玩家写的颜色。
     *
     * @return {@code 0xRRGGBB}；写法不认识时返回 {@code null}（调用方负责说清哪里不对）
     */
    public static Integer parse(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        if (text.equals("random") || text.equals("随机")) {
            return ThreadLocalRandom.current().nextInt(0x1000000);
        }
        if (text.equals("white") || text.equals("默认")) {
            return DEFAULT;
        }
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (text.length() == 6 && text.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return Integer.parseInt(text, 16);
        }
        Integer preset = PRESETS.get(text);
        if (preset != null) {
            return preset;
        }
        // 允许「带颜色的英文名」：red/green/blue…（只收最常见的一批，避免拼写歧义）
        return switch (text) {
            case "red" -> 0xFF5555;
            case "orange" -> 0xFFA347;
            case "yellow" -> 0xFFFF55;
            case "green" -> 0x55FF55;
            case "cyan" -> 0x55FFFF;
            case "blue" -> 0x5599FF;
            case "purple" -> 0xBB77FF;
            case "pink" -> 0xFF8CC8;
            case "gray", "grey" -> 0xAAAAAA;
            case "black" -> 0x303030;
            default -> null;
        };
    }

    /** {@code 0xRRGGBB} → {@code #RRGGBB}（给界面/指令回显用，永远带 # 与 6 位） */
    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** 这个颜色在预设表里叫什么；不是预设色时返回 {@code ""}（面板上就只显示色块） */
    public static String presetName(int rgb) {
        for (Map.Entry<String, Integer> e : PRESETS.entrySet()) {
            if (e.getValue() == (rgb & 0xFFFFFF)) {
                return e.getKey();
            }
        }
        return "";
    }
}
