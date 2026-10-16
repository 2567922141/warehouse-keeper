package com.ds.warehouse.client;

/**
 * 面板配色（用户 m33633 选的第 3 项：设置页「界面配色」）。
 *
 * 默认主题「深海蓝」的每个槽位值都与加配色功能之前的字面量逐字一致 ⇒ 不改主题时观感完全不变。
 * 其它主题只做 HSL 色相/饱和度变换、**不动明度** ⇒ 对比度与默认主题保持一致。
 */
public final class Palette {

    /** 主题名（顺序即设置页循环顺序；主题 0 必须是最初的配色）。 */
    public static final String[] THEME_NAMES = { "深海蓝", "石墨", "松林", "暮紫" };

    /** 每套主题：{ 背景色相, 背景饱和系数, 强调色相, 强调饱和系数, 警告色相, 警告饱和系数, 文字饱和系数 } */
    private static final int[][] THEMES = {
        { 218, 100, 213, 100, 38, 100, 100 }, // 0 深海蓝（默认，直接用原值）
        { 210,  12, 210,  35, 38,  60,  12 }, // 1 石墨（强调留 35% 饱和，选中行才看得出来）
        { 158,  85, 152,  90, 40,  90,  50 }, // 2 松林
        { 272,  85, 278,  95, 40,  95,  55 }  // 3 暮紫
    };

    /** 默认（深海蓝）原值，索引即槽位下标。 */
    private static final int[] DEF = {
        0xB4000000, // MODAL_DIM
        0x8A000000, // OVERLAY_DIM
        0xF0141A24, // PANEL_BG
        0xFF3D4757, // PANEL_BORDER
        0xFF0C1017, // LIST_BG
        0xFF141B26, // LIST_BG_ALT
        0xFF2A3342, // LIST_BORDER
        0xFF1A2331, // ROW_BG
        0xFF24313F, // ROW_BG_ALT
        0xFF1A2230, // ROW_BG_DEEP
        0xFF161D29, // ROW_BG_DEEP2
        0xFF262F3D, // ROW_BORDER_SOFT
        0xFF1D3350, // ROW_BG_SEL
        0xFF1D4E89, // ROW_BG_SEL_DEEP
        0xFF4E8BD8, // ACCENT_BORDER
        0xFF8FB7E8, // ACCENT_TEXT
        0xFF9FC4F0, // ACCENT_TEXT_BRIGHT
        0xFF24405C, // ACCENT_BG
        0x40000000, // SCROLL_TRACK
        0xFF5B6B84, // SCROLL_THUMB
        0xFFFFFFFF, // TEXT_TITLE
        0xFFD5DEEA, // TEXT_BODY
        0xFFCFE0F5, // TEXT_BODY2
        0xFFD8E6F8, // TEXT_BODY3
        0xFFE8F1FF, // TEXT_BODY4
        0xFFF2F6FB, // TEXT_BODY5
        0xFFD5E2F0, // TEXT_BODY6
        0xFFD6DEEA, // TEXT_BODY_HUD
        0xFFB9C8DA, // TEXT_HEADER
        0xFF8FA0B8, // TEXT_LABEL
        0xFF93A3B8, // TEXT_LABEL2
        0xFF9FB3CC, // TEXT_LABEL3
        0xFF9FB0C6, // TEXT_DIM
        0xFF6E7E93, // TEXT_MUTED
        0xFF7F8EA3, // TEXT_FAINT
        0xFF7C8CA1, // TEXT_FAINT2
        0xFF556070, // TEXT_OFF
        0xFF8A8A8A, // TEXT_GRAY
        0xFFE0B36A, // GOLD
        0xFFFFD98A, // GOLD_BRIGHT
        0xFF2E7D32, // SUCCESS_BG
        0xFF1F5B24, // SUCCESS_BG_DARK
        0xFF7FD18A, // SUCCESS_BORDER
        0xFF232B36, // BUTTON_BG
        0xFF3A4658, // BUTTON_BG_HOVER
        0xFF22303F, // BUTTON_BG_HOVER2
        0xFF151C26, // BUTTON_BG_HOVER3
        0xFF232C38, // BUTTON_BORDER
        0xFF3A4757, // BUTTON_BORDER_HOVER
        0xFF4A586B, // BUTTON_BORDER_PRIMARY
        0xFF161E2A, // TOOLTIP_BG
    };

    /** 每槽位色族：0=背景 1=文字 2=强调 3=警告 4=成功。 */
    private static final int[] FAMILY = {
        0, // MODAL_DIM
        0, // OVERLAY_DIM
        0, // PANEL_BG
        0, // PANEL_BORDER
        0, // LIST_BG
        0, // LIST_BG_ALT
        0, // LIST_BORDER
        0, // ROW_BG
        0, // ROW_BG_ALT
        0, // ROW_BG_DEEP
        0, // ROW_BG_DEEP2
        0, // ROW_BORDER_SOFT
        2, // ROW_BG_SEL
        2, // ROW_BG_SEL_DEEP
        2, // ACCENT_BORDER
        2, // ACCENT_TEXT
        2, // ACCENT_TEXT_BRIGHT
        2, // ACCENT_BG
        2, // SCROLL_TRACK
        2, // SCROLL_THUMB
        1, // TEXT_TITLE
        1, // TEXT_BODY
        1, // TEXT_BODY2
        1, // TEXT_BODY3
        1, // TEXT_BODY4
        1, // TEXT_BODY5
        1, // TEXT_BODY6
        1, // TEXT_BODY_HUD
        1, // TEXT_HEADER
        1, // TEXT_LABEL
        1, // TEXT_LABEL2
        1, // TEXT_LABEL3
        1, // TEXT_DIM
        1, // TEXT_MUTED
        1, // TEXT_FAINT
        1, // TEXT_FAINT2
        1, // TEXT_OFF
        1, // TEXT_GRAY
        3, // GOLD
        3, // GOLD_BRIGHT
        4, // SUCCESS_BG
        4, // SUCCESS_BG_DARK
        4, // SUCCESS_BORDER
        0, // BUTTON_BG
        0, // BUTTON_BG_HOVER
        0, // BUTTON_BG_HOVER2
        0, // BUTTON_BG_HOVER3
        0, // BUTTON_BORDER
        0, // BUTTON_BORDER_HOVER
        0, // BUTTON_BORDER_PRIMARY
        0, // TOOLTIP_BG
    };

    private static final int[] cur = DEF.clone();
    private static int applied = -1;

    private Palette() { }

    /** 当前主题名。 */
    public static String themeName() {
        int t = applied < 0 ? 0 : applied;
        return t >= 0 && t < THEME_NAMES.length ? THEME_NAMES[t] : THEME_NAMES[0];
    }

    /** 切换主题；同一主题重复调用直接返回。 */
    public static synchronized void apply(int theme) {
        if (theme < 0 || theme >= THEME_NAMES.length) theme = 0;
        if (theme == applied) return;
        applied = theme;
        if (theme == 0) { System.arraycopy(DEF, 0, cur, 0, DEF.length); return; }
        int[] p = THEMES[theme];
        for (int i = 0; i < DEF.length; i++) cur[i] = shift(DEF[i], FAMILY[i], p);
    }

    private static int shift(int argb, int family, int[] p) {
        int a = (argb >>> 24) & 0xFF;
        float[] hsl = toHsl((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
        float h = hsl[0], s = hsl[1], l = hsl[2];
        if (l <= 0.004f || l >= 0.996f) return argb;            // 纯黑/纯白不动
        switch (family) {
            case 1 -> { h = p[0]; s *= p[6] / 100f; }             // 文字：跟背景色相、低饱和
            case 2 -> { h = p[2]; s *= p[3] / 100f; }             // 强调
            case 3 -> { h = p[4]; s *= p[5] / 100f; }             // 警告金
            case 4 -> { s *= Math.max(0.4f, Math.min(1f, p[1] / 100f)); } // 成功绿：只微调饱和
            default -> { h = p[0]; s *= p[1] / 100f; }            // 背景与边框
        }
        int rgb = fromHsl(h, s, l);
        return (a << 24) | rgb;
    }

    private static float[] toHsl(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float l = (max + min) / 2f;
        float h = 0f, s = 0f;
        float d = max - min;
        if (d > 1e-6f) {
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == rf) h = (gf - bf) / d + (gf < bf ? 6f : 0f);
            else if (max == gf) h = (bf - rf) / d + 2f;
            else h = (rf - gf) / d + 4f;
            h *= 60f;
        }
        return new float[] { h, s, l };
    }

    private static int fromHsl(float h, float s, float l) {
        h = ((h % 360f) + 360f) % 360f;
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float hp = h / 60f;
        float x = c * (1f - Math.abs(hp % 2f - 1f));
        float r = 0f, g = 0f, b = 0f;
        if (hp < 1f) { r = c; g = x; }
        else if (hp < 2f) { r = x; g = c; }
        else if (hp < 3f) { g = c; b = x; }
        else if (hp < 4f) { g = x; b = c; }
        else if (hp < 5f) { r = x; b = c; }
        else { r = c; b = x; }
        float m = l - c / 2f;
        return (b255(r + m) << 16) | (b255(g + m) << 8) | b255(b + m);
    }

    private static int b255(float v) {
        int i = Math.round(v * 255f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    // ---- 槽位读取口（静态，任何绘制上下文都能用） ----
    public static int modalDim() { return cur[0]; } // 0xB4000000
    public static int overlayDim() { return cur[1]; } // 0x8A000000
    public static int panelBg() { return cur[2]; } // 0xF0141A24
    public static int panelBorder() { return cur[3]; } // 0xFF3D4757
    public static int listBg() { return cur[4]; } // 0xFF0C1017
    public static int listBgAlt() { return cur[5]; } // 0xFF141B26
    public static int listBorder() { return cur[6]; } // 0xFF2A3342
    public static int rowBg() { return cur[7]; } // 0xFF1A2331
    public static int rowBgAlt() { return cur[8]; } // 0xFF24313F
    public static int rowBgDeep() { return cur[9]; } // 0xFF1A2230
    public static int rowBgDeep2() { return cur[10]; } // 0xFF161D29
    public static int rowBorderSoft() { return cur[11]; } // 0xFF262F3D
    public static int rowBgSel() { return cur[12]; } // 0xFF1D3350
    public static int rowBgSelDeep() { return cur[13]; } // 0xFF1D4E89
    public static int accentBorder() { return cur[14]; } // 0xFF4E8BD8
    public static int accentText() { return cur[15]; } // 0xFF8FB7E8
    public static int accentTextBright() { return cur[16]; } // 0xFF9FC4F0
    public static int accentBg() { return cur[17]; } // 0xFF24405C
    public static int scrollTrack() { return cur[18]; } // 0x40000000
    public static int scrollThumb() { return cur[19]; } // 0xFF5B6B84
    public static int textTitle() { return cur[20]; } // 0xFFFFFFFF
    public static int textBody() { return cur[21]; } // 0xFFD5DEEA
    public static int textBody2() { return cur[22]; } // 0xFFCFE0F5
    public static int textBody3() { return cur[23]; } // 0xFFD8E6F8
    public static int textBody4() { return cur[24]; } // 0xFFE8F1FF
    public static int textBody5() { return cur[25]; } // 0xFFF2F6FB
    public static int textBody6() { return cur[26]; } // 0xFFD5E2F0
    public static int textBodyHud() { return cur[27]; } // 0xFFD6DEEA
    public static int textHeader() { return cur[28]; } // 0xFFB9C8DA
    public static int textLabel() { return cur[29]; } // 0xFF8FA0B8
    public static int textLabel2() { return cur[30]; } // 0xFF93A3B8
    public static int textLabel3() { return cur[31]; } // 0xFF9FB3CC
    public static int textDim() { return cur[32]; } // 0xFF9FB0C6
    public static int textMuted() { return cur[33]; } // 0xFF6E7E93
    public static int textFaint() { return cur[34]; } // 0xFF7F8EA3
    public static int textFaint2() { return cur[35]; } // 0xFF7C8CA1
    public static int textOff() { return cur[36]; } // 0xFF556070
    public static int textGray() { return cur[37]; } // 0xFF8A8A8A
    public static int gold() { return cur[38]; } // 0xFFE0B36A
    public static int goldBright() { return cur[39]; } // 0xFFFFD98A
    public static int successBg() { return cur[40]; } // 0xFF2E7D32
    public static int successBgDark() { return cur[41]; } // 0xFF1F5B24
    public static int successBorder() { return cur[42]; } // 0xFF7FD18A
    public static int buttonBg() { return cur[43]; } // 0xFF232B36
    public static int buttonBgHover() { return cur[44]; } // 0xFF3A4658
    public static int buttonBgHover2() { return cur[45]; } // 0xFF22303F
    public static int buttonBgHover3() { return cur[46]; } // 0xFF151C26
    public static int buttonBorder() { return cur[47]; } // 0xFF232C38
    public static int buttonBorderHover() { return cur[48]; } // 0xFF3A4757
    public static int buttonBorderPrimary() { return cur[49]; } // 0xFF4A586B
    public static int tooltipBg() { return cur[50]; } // 0xFF161E2A
}
