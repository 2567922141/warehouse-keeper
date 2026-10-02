package com.ds.warehouse.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 箱子标签栏的<b>纯</b>布局数学（批次 3 · 附录 J.4）。
 *
 * <p>刻意不 import 任何 Minecraft 类：这样 {@code tools/tagbar/LayoutCheck.java} 能拿一个普通 JDK
 * 直接跑，对六种窗口尺寸断言「按钮不越界、不压住箱子界面、彼此不重叠」。
 * 本环境没有 Minecraft 客户端，像素级观感只能交给玩家，几何正确性由这里守住。
 *
 * <p>摆放优先级（与 J.4 一致，2026-10-01 追加「居中兜底」，2026-10-02 追加「停靠偏好」）：
 * <ol>
 *   <li><b>停靠偏好</b>（{@code dock} 参数，玩家在面板「维护」页里选）：{@code auto} 时下面第 1、2 条
 *       按「右 → 左」；{@code right/left} 时把该方向提到最前；{@code top/bottom} 时把横条提到竖栏前面，
 *       并先贴上／下沿。**无论怎么排，下面这些形态与降级次序一个都不少** —— 偏好只改「谁先试」，
 *       不放宽任何几何条件，所以永远不会出现「按钮跑到屏幕外」。</li>
 *   <li>{@link Mode#RIGHT} 右侧竖栏 —— {@code auto} 的首选，箱子界面右边有空就贴右边（竖直方向对齐箱子界面中线）</li>
 *   <li>{@link Mode#LEFT} 左侧竖栏 —— 右边放不下就放左边（{@code auto} 且界面尺寸 ≤ 1 时提到最前：
 *       窗口逻辑宽度很大时 JEI 的配方/物品列表几乎必然占着右边，贴左才不重叠）</li>
 *   <li>{@link Mode#STRIP} 屏幕底部/顶部横条 —— 两侧都放不下（窄窗）时铺两行</li>
 *   <li>{@link Mode#TINY} 单行四个小按钮 —— 再放不下就只留「标签：… / 自动分类 / 暂存：开·关 / 清除标签」</li>
 *   <li><b>窄条兜底</b>（{@link Plan#narrow}）—— 上下都塞不进横条（箱子界面比窗口还高）时，
 *       改成一列四个小按钮挤进箱子界面旁边的余量里，不压住箱子界面</li>
 *   <li>以上横条若上下都塞不进、左右余量也不够，改为<b>窗口竖直居中</b>（{@link Plan#overlapsGui}）
 *       ——宁可压住箱子界面，也不能整条标签栏消失</li>
 *   <li>{@link Mode#HIDDEN} 什么都不加 —— 只有连一行 4 个小按钮都排不下（窗口窄得离谱）才走这里</li>
 * </ol>
 */
public final class TagBarLayout {

    public enum Mode {
        RIGHT, LEFT, STRIP, TINY, HIDDEN
    }

    /** 按钮高（原版默认 20，这里压到 18 更紧凑） */
    public static final int BTN_H = 18;
    /** 按钮间距 */
    public static final int GAP = 2;
    /**
     * 竖栏宽。批次 3 收尾：标题统一成 {@code 标签：<值>} 之后，最长的一条是
     * {@code 标签：建材方块（自动）▾}（前缀 3 字 + 4 字分类名 + 「（自动）」 + 箭头），
     * 按 Minecraft 默认字体全角字 9 像素估约 108 像素，这里留一点余量。
     */
    public static final int WIDE = 112;
    /** 与屏幕边缘 / 箱子界面之间至少留这么多像素 */
    public static final int MARGIN = 6;
    /** 横条铺几行 */
    public static final int STRIP_ROWS = 2;
    /**
     * 横条按钮窄于此值就不算「放得下」。底线是放得下被截断的标题 {@code 标签：…}
     * （前缀 27 + 省略号 9 + 两侧余量 2 = 38）—— 前缀「标签：」在任何形态下都不许消失。
     */
    public static final int MIN_STRIP_W = 40;
    /** 退化小按钮窄于此值就干脆不显示。同 {@link #MIN_STRIP_W}，底线也是放得下 {@code 标签：…}。 */
    public static final int MIN_TINY_W = 40;

    private TagBarLayout() {
    }

    /** 一个按钮的位置（屏幕像素坐标） */
    public static final class Box {
        public final String role;
        public final int x;
        public final int y;
        public final int w;
        public final int h;

        Box(String role, int x, int y, int w, int h) {
            this.role = role;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean inside(int screenW, int screenH) {
            return x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= screenW && y + h <= screenH;
        }

        public boolean overlaps(Box o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }

        @Override
        public String toString() {
            return role + "@" + x + "," + y + " " + w + "x" + h;
        }
    }

    /** 一次布局的结果 */
    public static final class Plan {
        public final Mode mode;
        public final List<Box> boxes;
        public final String note;
        /**
         * 是否为「居中兜底」摆法：箱子界面自己就跟窗口一样高（甚至更高），上下都塞不下横条，
         * 这时候宁可居中压住箱子界面，也不能整条标签栏看不见。
         */
        public final boolean overlapsGui;
        /**
         * 是否为「窄条兜底」摆法：按钮只有一列、宽度贴着箱子界面旁边的余量（最少 {@link #MIN_TINY_W}）。
         * 批次 3 收尾后客户端宽窄**同一套文案**、只按像素截断（前缀「标签：」永远保留），
         * 所以这个标志现在只表示「按钮很窄、文字大概率会被截断」，不再用来换短标签。
         */
        public final boolean narrow;

        Plan(Mode mode, List<Box> boxes, String note) {
            this(mode, boxes, note, false, false);
        }

        Plan(Mode mode, List<Box> boxes, String note, boolean overlapsGui) {
            this(mode, boxes, note, overlapsGui, false);
        }

        Plan(Mode mode, List<Box> boxes, String note, boolean overlapsGui, boolean narrow) {
            this.mode = mode;
            this.boxes = List.copyOf(boxes);
            this.note = note;
            this.overlapsGui = overlapsGui;
            this.narrow = narrow;
        }

        /** 取某个角色的按钮；该模式下没有这个角色就返回 null（客户端会跳过） */
        public Box box(String role) {
            for (Box b : boxes) {
                if (b.role.equals(role)) {
                    return b;
                }
            }
            return null;
        }
    }

    /**
     * 算一次布局（默认停靠：{@code auto}、界面尺寸按 &gt;1 处理）。
     *
     * <p>这个 8 参签名必须留着：{@code tools/tagbar/LayoutCheck.java} 用它断言几何，而那个工具是用
     * 普通 javac 和本类一起编译的 —— 本类不能 import Minecraft，也就读不到玩家设的停靠偏好。
     * 客户端（{@code ContainerTagBar}）走下面带 {@code dock}/{@code guiScale} 的重载。
     *
     * @param screenW    当前窗口逻辑宽（缩放后）
     * @param screenH    当前窗口逻辑高
     * @param guiX       箱子界面左上角 x
     * @param guiY       箱子界面左上角 y
     * @param guiW       箱子界面宽（原版 176）
     * @param guiH       箱子界面高（原版 114 + 行数×18）
     * @param mainCount  分类按钮个数（分类走自绘下拉时是 0）
     * @param extraCount 附加按钮个数（自动 / 暂存 / 清除 = 3）
     */
    public static Plan plan(int screenW, int screenH, int guiX, int guiY, int guiW, int guiH,
                            int mainCount, int extraCount) {
        return plan(screenW, screenH, guiX, guiY, guiW, guiH, mainCount, extraCount, "auto", 2);
    }

    /**
     * 算一次布局（带停靠偏好）—— BUG1 的入口。
     *
     * <p>语义（与玩家拍板的方案一致）：
     * <ul>
     *   <li>{@code auto}：优先「贴箱子界面右侧」（原行为），但当界面尺寸 {@code <= 1} 时**先试左侧**
     *       （窗口逻辑宽度大 ⇒ JEI 面板占右），左侧放不下再退回原有降级链；</li>
     *   <li>{@code right/left}：优先该方向的竖栏，横条照旧；</li>
     *   <li>{@code top/bottom}：把横条提到竖栏前面，并先贴该沿（放不下就试另一沿）；</li>
     *   <li>不论哪种偏好，放不下时**仍走原有五级降级链**：竖栏 → 横条 → 单行小按钮 → 窄条兜底
     *       → 窗口居中兜底 → {@link Mode#HIDDEN}。偏好不好时返回 HIDDEN 的判据和以前完全一样。</li>
     * </ul>
     *
     * @param dock     停靠偏好：{@code auto|right|left|top|bottom}（非法值当 {@code auto}）
     * @param guiScale 当前界面尺寸；只有 {@code auto} 读它（{@code <= 1} 时左优先）
     */
    public static Plan plan(int screenW, int screenH, int guiX, int guiY, int guiW, int guiH,
                            int mainCount, int extraCount, String dock, int guiScale) {
        mainCount = Math.max(0, mainCount);
        extraCount = Math.max(0, extraCount);
        int total = 1 + mainCount + extraCount;

        // 停靠偏好只决定「谁先试」，不放宽任何几何条件
        String pref = normalizeDock(dock);
        boolean stripFirst = "top".equals(pref) || "bottom".equals(pref);
        boolean topFirst = "top".equals(pref);
        boolean leftFirst = "left".equals(pref) || ("auto".equals(pref) && guiScale <= 1);

        // ① 竖栏：标题一行 + 分类两列 + 附加按钮各占一整行（右、左两个候选都先算出来）
        int rows = (mainCount + 1) / 2;
        int mainH = rows * BTN_H + Math.max(0, rows - 1) * GAP;
        int extraH = extraCount * BTN_H + Math.max(0, extraCount - 1) * GAP;
        int barH = BTN_H + GAP + mainH + GAP + extraH;
        Plan rightWide = null;
        Plan leftWide = null;
        if (barH <= screenH - 2 * MARGIN) {
            // 竖栏整体对齐「箱子界面的竖直中线」：原来贴 guiY 顶边，小窗口里看着像飘在屏幕上半截
            int barY = clamp(guiY + (guiH - barH) / 2, MARGIN, screenH - MARGIN - barH);
            int rightX = guiX + guiW + MARGIN;
            if (rightX + WIDE <= screenW - MARGIN) {
                rightWide = wide(Mode.RIGHT, rightX, barY, mainCount, extraCount, "右侧竖栏");
            }
            int leftX = guiX - MARGIN - WIDE;
            if (leftX >= MARGIN) {
                leftWide = wide(Mode.LEFT, leftX, barY, mainCount, extraCount, "左侧竖栏");
            }
        }

        // ② 横条：所有按钮铺两行，贴屏幕下沿（放不下就上沿）
        int cols = (total + STRIP_ROWS - 1) / STRIP_ROWS;
        int stripW = (screenW - 2 * MARGIN - (cols - 1) * GAP) / cols;
        int stripH = STRIP_ROWS * BTN_H + (STRIP_ROWS - 1) * GAP;
        boolean stripFits = stripW >= MIN_STRIP_W;
        int stripY0 = stripFits ? stripY(screenH, guiY, guiH, stripH) : -1;
        List<String> stripRoles = new ArrayList<>();
        stripRoles.add("title");
        for (int i = 0; i < mainCount; i++) {
            stripRoles.add("c" + i);
        }
        for (int i = 0; i < extraCount; i++) {
            stripRoles.add("e" + i);
        }

        // ③ 退化：单行四个（当前标签 / 自动 / 暂存 / 清除），分类走自绘下拉，所以功能不缺
        int tinyCols = 4;
        int tinyW = (screenW - 2 * MARGIN - (tinyCols - 1) * GAP) / tinyCols;
        boolean tinyFits = tinyW >= MIN_TINY_W;
        int tinyY0 = tinyFits ? stripY(screenH, guiY, guiH, BTN_H) : -1;

        // ④ 先挑「不压住箱子界面」的形态。
        //    top/bottom 停靠：横条优先（否则大窗口下右竖栏总是先被抢走，玩家的选择等于没生效）
        if (stripFirst) {
            int y = topFirst
                    ? stripYTopFirst(screenH, guiY, guiH, stripH)
                    : stripY(screenH, guiY, guiH, stripH);
            if (y >= 0) {
                return strip(Mode.STRIP, MARGIN, y, stripW, cols, stripRoles,
                        topFirst ? "横条（顶部停靠）" : "横条（底部停靠）", false);
            }
            Plan wall = wall(leftFirst, rightWide, leftWide);
            if (wall != null) {
                return wall;
            }
            if (tinyY0 >= 0) {
                return strip(Mode.TINY, MARGIN, tinyY0, tinyW, tinyCols,
                        List.of("title", "e0", "e1", "e2"), "单行小按钮", false);
            }
        } else {
            Plan wall = wall(leftFirst, rightWide, leftWide);
            if (wall != null) {
                return wall;
            }
            if (stripY0 >= 0) {
                return strip(Mode.STRIP, MARGIN, stripY0, stripW, cols, stripRoles, "横条", false);
            }
            if (tinyY0 >= 0) {
                return strip(Mode.TINY, MARGIN, tinyY0, tinyW, tinyCols,
                        List.of("title", "e0", "e1", "e2"), "单行小按钮", false);
            }
        }

        // ⑤ 窄条兜底：窗口里放不下第二条边（例如界面尺寸 3 时 6 行箱子比窗口还高）时，
        //    先试着挤进箱子界面左右两侧的余量里——一列 4 个小按钮，**不压住箱子界面**。
        //    用户反馈：居中兜底虽然能看见，但正好盖住箱子中间那几行。
        Plan narrowPlan = narrowFallback(screenW, screenH, guiX, guiY, guiW, guiH, leftFirst);
        if (narrowPlan != null) {
            return narrowPlan;
        }

        // ⑥ 真的连一条边都塞不进：窗口居中兜底，
        //    压住箱子界面是没办法的事——总好过整条标签栏看不见（用户反馈：小分辨率下标签栏整条消失）
        if (stripFits) {
            return strip(Mode.STRIP, MARGIN, centerY(screenH, stripH), stripW, cols, stripRoles,
                    "横条（窗口居中兜底）", true);
        }
        if (tinyFits) {
            return strip(Mode.TINY, MARGIN, centerY(screenH, BTN_H), tinyW, tinyCols,
                    List.of("title", "e0", "e1", "e2"), "单行小按钮（窗口居中兜底）", true);
        }

        return new Plan(Mode.HIDDEN, List.of(), "窗口太小，标签栏不显示");
    }

    /** 停靠值白名单 —— 本类不 import 客户端配置类（{@code ClientPrefs}），所以就地归一化 */
    private static String normalizeDock(String dock) {
        if (dock == null) {
            return "auto";
        }
        String v = dock.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "right", "left", "top", "bottom" -> v;
            default -> "auto";
        };
    }

    /** 按偏好挑竖栏：左优先时左→右，否则右→左 */
    private static Plan wall(boolean leftFirst, Plan rightWide, Plan leftWide) {
        if (leftFirst) {
            return leftWide != null ? leftWide : rightWide;
        }
        return rightWide != null ? rightWide : leftWide;
    }

    /** 横条优先贴下沿（不压住箱子界面），下沿放不下就贴上沿；都放不下返回 -1 */
    private static int stripY(int screenH, int guiY, int guiH, int stripH) {
        int bottom = screenH - MARGIN - stripH;
        if (bottom >= guiY + guiH + MARGIN) {
            return bottom;
        }
        int top = MARGIN;
        if (top + stripH <= guiY - MARGIN) {
            return top;
        }
        return -1;
    }

    /** 停靠「上」时：先贴上沿，上沿放不下再退回原来的「下沿优先」 */
    private static int stripYTopFirst(int screenH, int guiY, int guiH, int stripH) {
        int top = MARGIN;
        if (top + stripH <= guiY - MARGIN) {
            return top;
        }
        return stripY(screenH, guiY, guiH, stripH);
    }

    /**
     * 居中兜底：箱子界面自己就顶满（甚至超出）窗口，横条上下都塞不下时，
     * 把它放到窗口竖直中线上。压住箱子界面是没办法的事——总好过整条标签栏看不见。
     */
    private static int centerY(int screenH, int stripH) {
        return clamp((screenH - stripH) / 2, MARGIN, Math.max(MARGIN, screenH - MARGIN - stripH));
    }

    /**
     * 窄条兜底：箱子界面比窗口还高（上下都没边）、两侧又放不下整条竖栏时，
     * 改成一列四个小按钮，塞进箱子界面旁边的余量里。宽的地方用满（最多 {@link #WIDE}），
     * 窄到只剩 {@link #MIN_TINY_W} 也认——客户端会用同一套文案 + 省略号配合。
     *
     * @param leftFirst 左停靠（或 auto + 界面尺寸 ≤ 1）时先看箱子左边的余量，否则先看右边
     * @return 放不下（连 {@link #MIN_TINY_W} 都没有，或窗口高度连一列都装不下）时返回 null
     */
    private static Plan narrowFallback(int screenW, int screenH, int guiX, int guiY, int guiW, int guiH,
                                       boolean leftFirst) {
        List<String> roles = List.of("title", "e0", "e1", "e2");
        int h = roles.size() * BTN_H + (roles.size() - 1) * GAP;
        if (h > screenH - 2 * MARGIN) {
            return null;
        }
        int y = clamp(guiY + (guiH - h) / 2, MARGIN, screenH - MARGIN - h);
        int rightX = guiX + guiW + MARGIN;
        int room = screenW - MARGIN - rightX;
        int roomLeft = guiX - 2 * MARGIN;
        boolean rightOk = room >= MIN_TINY_W;
        boolean leftOk = roomLeft >= MIN_TINY_W;
        if (leftFirst ? leftOk : rightOk) {
            int w = Math.min(WIDE, leftFirst ? roomLeft : room);
            return narrow(leftFirst ? guiX - MARGIN - w : rightX, y, w, roles,
                    leftFirst ? "左侧窄条（贴边兜底）" : "右侧窄条（贴边兜底）");
        }
        if (leftFirst ? rightOk : leftOk) {
            int w = Math.min(WIDE, leftFirst ? room : roomLeft);
            return narrow(leftFirst ? rightX : guiX - MARGIN - w, y, w, roles,
                    leftFirst ? "右侧窄条（贴边兜底）" : "左侧窄条（贴边兜底）");
        }
        return null;
    }

    private static Plan narrow(int x, int y, int w, List<String> roles, String note) {
        List<Box> out = new ArrayList<>(roles.size());
        for (int i = 0; i < roles.size(); i++) {
            out.add(new Box(roles.get(i), x, y + i * (BTN_H + GAP), w, BTN_H));
        }
        return new Plan(Mode.TINY, out, note, false, true);
    }

    private static Plan wide(Mode mode, int x, int y, int mainCount, int extraCount, String note) {
        List<Box> out = new ArrayList<>();
        int rows = (mainCount + 1) / 2;
        out.add(new Box("title", x, y, WIDE, BTN_H));
        int colW = (WIDE - GAP) / 2;
        int firstY = y + BTN_H + GAP;
        for (int i = 0; i < mainCount; i++) {
            int col = i % 2;
            int row = i / 2;
            out.add(new Box("c" + i, x + col * (colW + GAP), firstY + row * (BTN_H + GAP), colW, BTN_H));
        }
        int extraY = firstY + rows * BTN_H + Math.max(0, rows - 1) * GAP + GAP;
        for (int i = 0; i < extraCount; i++) {
            out.add(new Box("e" + i, x, extraY + i * (BTN_H + GAP), WIDE, BTN_H));
        }
        return new Plan(mode, out, note);
    }

    private static Plan strip(Mode mode, int x, int y, int btnW, int cols, List<String> roles, String note,
                              boolean overlapsGui) {
        List<Box> out = new ArrayList<>(roles.size());
        for (int i = 0; i < roles.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            out.add(new Box(roles.get(i), x + col * (btnW + GAP), y + row * (BTN_H + GAP), btnW, BTN_H));
        }
        return new Plan(mode, out, note, overlapsGui);
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) {
            return lo;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
