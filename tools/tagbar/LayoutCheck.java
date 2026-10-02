import com.ds.warehouse.client.TagBarLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 箱子标签栏布局自检（批次 3 · 附录 J.4）。
 *
 * <p>本环境没有 Minecraft 客户端，像素级观感只能交给玩家；这里守住的是<b>几何正确性</b>：
 * 在六种典型窗口 + 三种容器界面下，标签栏的每个按钮都
 * ① 落在窗口内 ② 不压住箱子界面 ③ 彼此不重叠 ④ 按优先级退化到该有的形态。
 *
 * <p>用法（普通 JDK 即可，不需要 Minecraft）：
 * <pre>
 *   cd &lt;仓库根目录&gt;
 *   javac -d build/tools/tagbar src/main/java/com/ds/warehouse/client/TagBarLayout.java tools/tagbar/LayoutCheck.java
 *   java -cp build/tools/tagbar LayoutCheck
 * </pre>
 */
public final class LayoutCheck {

    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    private LayoutCheck() {
    }

    public static void main(String[] args) {
        int[][] screens = {
                {854, 480}, {1280, 720}, {1920, 1080}, {1024, 768}, {2560, 1080}, {320, 240},
        };
        // 箱子（3 行 / 6 行）与一个「模组容器」——外框只大不小地估
        int[][] guis = {
                {176, 114 + 3 * 18}, {176, 114 + 6 * 18}, {200, 250},
        };
        for (int[] screen : screens) {
            for (int[] gui : guis) {
                check(screen[0], screen[1], gui[0], gui[1], 10, 3);
            }
        }

        // 退化路径必须真的走到：窄窗 → 横条，荒谬小窗 → 不显示
        // 320x240 的 3 行箱子：两侧塞不下竖栏，但下沿还放得下一行小按钮
        expectMode(320, 240, 176, 168, TagBarLayout.Mode.TINY);
        // 360 宽放不下竖栏（右侧差 8 像素），但横条够宽
        expectMode(360, 480, 176, 222, TagBarLayout.Mode.STRIP);
        // 箱子界面自己就顶满窗口（6 行箱 @ 界面尺寸 3）：横条上下都塞不下 → 挤进左右余量的窄条，
        // 不再整条消失，也不再居中压住箱子中间那几行
        expectMode(320, 240, 176, 222, TagBarLayout.Mode.TINY);
        expectMode(200, 150, 176, 222, TagBarLayout.Mode.TINY);
        // 玩家实机复现：截图抓图 873x529（客户区 854x480）、界面尺寸 3 ⇒ 逻辑 285x163，guiY 为负（箱子界面比窗口高）
        expectMode(285, 163, 54, -29, 176, 222, TagBarLayout.Mode.TINY);
        // 同一尺寸、按标签栏真实配置（分类走自绘下拉 ⇒ mainCount=0，附加 3 个）复核：
        // 窄条必须落在箱子界面右侧的余量里（guiX+guiW+6 = 236，窗口 285 ⇒ 有 43 像素可用）
        {
            TagBarLayout.Plan p = TagBarLayout.plan(285, 163, 54, -29, 176, 222, 0, 3);
            ok(p.narrow, "285x163：应走窄条兜底（narrow=true），实际 mode=" + p.mode + " note=" + p.note);
            ok(!p.overlapsGui, "285x163：窄条不该压住箱子界面（overlapsGui 应为 false）");
            ok(p.boxes.size() == 4, "285x163：窄条应只有 4 个按钮，实际 " + p.boxes.size());
            for (TagBarLayout.Box b : p.boxes) {
                ok(b.inside(285, 163), "285x163：" + b + " 越界");
                ok(b.x >= 236, "285x163：" + b + " 落在箱子界面里（x 应 ≥ 236）");
            }
            ok(p.box("title") != null && p.box("title").w >= TagBarLayout.MIN_TINY_W,
                    "285x163：标题按钮宽度应 ≥ " + TagBarLayout.MIN_TINY_W);
        }
        // 只有连一行 4 个小按钮都排不下（窗口窄得离谱）才什么都不显示
        expectMode(60, 150, 176, 222, TagBarLayout.Mode.HIDDEN);
        // 宽屏 → 竖栏（右边放得下）
        expectMode(1920, 1080, 176, 222, TagBarLayout.Mode.RIGHT);
        // 640 宽仍然放得下竖栏（右边缘 526 ≤ 634；WIDE 从 88 调到 112 之后）
        expectMode(640, 480, 176, 222, TagBarLayout.Mode.RIGHT);
        // 界面贴着左边缘时右边还有地方，仍走右侧竖栏
        expectMode(1920, 1080, 4, 4, 176, 222, TagBarLayout.Mode.RIGHT);

        // ---------------------------------------------------------------- 0.22.0 · BUG1 停靠偏好
        // 面板「维护」页里那个循环按钮（auto/right/left/top/bottom，客户端 config 落盘）。
        // 偏好只改「谁先试」，不放宽任何几何条件 ⇒ 每个取值都要把上面整套体检重跑一遍。
        String[] docks = {"auto", "right", "left", "top", "bottom"};
        for (String dock : docks) {
            for (int[] screen : screens) {
                for (int[] gui : guis) {
                    checkDocked(screen[0], screen[1], gui[0], gui[1], 10, 3, dock, 2);
                }
            }
            // 分类走自绘下拉时的真实配置（mainCount=0）：常规窗口 + 玩家实机复现的窄窗各一遍
            checkDocked(854, 480, 176, 222, 0, 3, dock, 2);
            checkDocked(285, 163, 176, 222, 0, 3, dock, 2);
        }
        // 两侧都放得下时的先后顺序：auto 在界面尺寸 ≤1 时先贴左（窗口逻辑宽度大 ⇒ JEI 占着右边），
        // >1 时保持老行为「先贴右」；显式 right/left 则完全按玩家说的来
        expectDockedMode(1920, 1080, 176, 222, "auto", 1, TagBarLayout.Mode.LEFT);
        expectDockedMode(1920, 1080, 176, 222, "auto", 2, TagBarLayout.Mode.RIGHT);
        expectDockedMode(1920, 1080, 176, 222, "left", 2, TagBarLayout.Mode.LEFT);
        expectDockedMode(1920, 1080, 176, 222, "right", 1, TagBarLayout.Mode.RIGHT);
        // 上/下停靠：大窗口里竖栏本来更优先，偏好生效后必须真的走横条，并且贴对那一条边
        {
            TagBarLayout.Plan top = TagBarLayout.plan(1920, 1080, 872, 107, 176, 222, 10, 3, "top", 2);
            TagBarLayout.Plan bottom = TagBarLayout.plan(1920, 1080, 872, 107, 176, 222, 10, 3, "bottom", 2);
            ok(top.mode == TagBarLayout.Mode.STRIP, "停靠「上」：应走横条，实际 " + top.mode + "（" + top.note + "）");
            ok(bottom.mode == TagBarLayout.Mode.STRIP, "停靠「下」：应走横条，实际 " + bottom.mode + "（" + bottom.note + "）");
            ok(minY(top) == TagBarLayout.MARGIN, "停靠「上」：横条应贴上沿（minY=" + minY(top) + "）");
            ok(maxBottom(bottom) == 1080 - TagBarLayout.MARGIN,
                    "停靠「下」：横条应贴下沿（maxBottom=" + maxBottom(bottom) + "）");
            ok(minY(top) < minY(bottom), "停靠「上」的横条应画在「下」的上方");
        }
        // 竖向空间不足时：「上/下」偏好也不能让按钮跑出屏幕或压住箱子界面，只能按老降级链退化
        for (String dock : new String[]{"top", "bottom"}) {
            TagBarLayout.Plan p = TagBarLayout.plan(1920, 300, 872, 39, 176, 222, 10, 3, dock, 2);
            ok(p.mode != TagBarLayout.Mode.HIDDEN, "停靠 " + dock + "：放不下横条时应退化到别的形态，而不是整条不显示");
            for (TagBarLayout.Box b : p.boxes) {
                ok(b.inside(1920, 300), "停靠 " + dock + "：" + b + " 越界");
            }
        }
        // 非法 / null 停靠值一律当 auto（客户端 config 是手改得到的文本文件，不能信）
        ok(TagBarLayout.plan(1920, 1080, 872, 107, 176, 222, 10, 3, "sideways", 2).mode
                        == TagBarLayout.plan(1920, 1080, 872, 107, 176, 222, 10, 3, "auto", 2).mode,
                "非法停靠值应等价于 auto");
        ok(TagBarLayout.plan(1920, 1080, 872, 107, 176, 222, 10, 3, null, 2).mode
                        == TagBarLayout.plan(1920, 1080, 872, 107, 176, 222, 10, 3, "auto", 2).mode,
                "null 停靠值应等价于 auto");

        // 文案统一后的常量体检：标题一律「标签：<值>」，所以窄到 MIN 也必须放得下被截断的「标签：…」，
        // WIDE 也必须放得下最长的一条「标签：建材方块（自动）▾」（4 字分类名 + 自动后缀 + 箭头）。
        // 本文件没有字体，按「全角字 9 像素、ASCII 6 像素」估宽 —— Minecraft 默认字体里中文走 unifont，步进就是 9。
        int prefixW = width("标签：");
        int needMin = prefixW + width("…") + 2;
        ok(TagBarLayout.MIN_STRIP_W >= needMin,
                "MIN_STRIP_W=" + TagBarLayout.MIN_STRIP_W + " 放不下「标签：…」（需要 " + needMin + "）");
        ok(TagBarLayout.MIN_TINY_W >= needMin,
                "MIN_TINY_W=" + TagBarLayout.MIN_TINY_W + " 放不下「标签：…」（需要 " + needMin + "）");
        int widest = prefixW + width("建材方块") + width("（自动）") + width("▾") + 2;
        ok(TagBarLayout.WIDE >= widest,
                "WIDE=" + TagBarLayout.WIDE + " 放不下最长标题「标签：建材方块（自动）▾」（需要 " + widest + "）");

        System.out.println("布局自检：" + checks + " 项断言" + (failures.isEmpty() ? "，全部通过" : "，失败 " + failures.size() + " 项"));
        for (String f : failures) {
            System.out.println("  ✗ " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void check(int screenW, int screenH, int guiW, int guiH, int mainCount, int extraCount) {
        int guiX = (screenW - guiW) / 2;
        int guiY = (screenH - guiH) / 2;
        verify(TagBarLayout.plan(screenW, screenH, guiX, guiY, guiW, guiH, mainCount, extraCount),
                screenW, screenH, guiX, guiY, guiW, guiH, mainCount, extraCount,
                screenW + "x" + screenH + " gui " + guiW + "x" + guiH);
    }

    /** 同一套体检，但走带停靠偏好的重载（0.22.0 · BUG1） */
    private static void checkDocked(int screenW, int screenH, int guiW, int guiH,
                                    int mainCount, int extraCount, String dock, int guiScale) {
        int guiX = (screenW - guiW) / 2;
        int guiY = (screenH - guiH) / 2;
        verify(TagBarLayout.plan(screenW, screenH, guiX, guiY, guiW, guiH, mainCount, extraCount, dock, guiScale),
                screenW, screenH, guiX, guiY, guiW, guiH, mainCount, extraCount,
                screenW + "x" + screenH + " gui " + guiW + "x" + guiH + " dock=" + dock + " scale=" + guiScale);
    }

    /** 一次布局的几何体检：按钮数、越界、压住箱子界面、彼此重叠 */
    private static void verify(TagBarLayout.Plan plan, int screenW, int screenH, int guiX, int guiY,
                               int guiW, int guiH, int mainCount, int extraCount, String where) {
        if (plan.mode == TagBarLayout.Mode.HIDDEN) {
            ok(plan.boxes.isEmpty(), where + "：HIDDEN 时不该有按钮");
            return;
        }

        int total = 1 + mainCount + extraCount;
        if (plan.mode == TagBarLayout.Mode.RIGHT || plan.mode == TagBarLayout.Mode.LEFT) {
            ok(plan.boxes.size() == total, where + "：竖栏应有 " + total + " 个按钮，实际 " + plan.boxes.size());
        } else if (plan.mode == TagBarLayout.Mode.STRIP) {
            ok(plan.boxes.size() == total, where + "：横条应有 " + total + " 个按钮，实际 " + plan.boxes.size());
        } else if (plan.mode == TagBarLayout.Mode.TINY) {
            ok(plan.boxes.size() == 4, where + "：退化模式应有 4 个按钮，实际 " + plan.boxes.size());
            ok(plan.box("title") != null && plan.box("e0") != null && plan.box("e1") != null && plan.box("e2") != null,
                    where + "：退化模式缺角色");
            ok(plan.box("c0") == null, where + "：退化模式不该有分类按钮");
        }

        for (TagBarLayout.Box box : plan.boxes) {
            ok(box.inside(screenW, screenH), where + "：" + box + " 越界");
            boolean hitGui = box.x < guiX + guiW && guiX < box.x + box.w
                    && box.y < guiY + guiH && guiY < box.y + box.h;
            // 居中兜底（窗口里根本放不下第二条边）时允许压住箱子界面——总比整条标签栏消失好
            ok(plan.overlapsGui || !hitGui, where + "：" + box + " 压住了箱子界面 " + guiX + "," + guiY + " " + guiW + "x" + guiH);
        }
        for (int i = 0; i < plan.boxes.size(); i++) {
            for (int j = i + 1; j < plan.boxes.size(); j++) {
                TagBarLayout.Box a = plan.boxes.get(i);
                TagBarLayout.Box b = plan.boxes.get(j);
                ok(!a.overlaps(b), where + "：" + a + " 与 " + b + " 重叠");
            }
        }
        checks++;
    }

    /** 最高的那条边（横条可能铺两行，所以按所有按钮取 min/max） */
    private static int minY(TagBarLayout.Plan plan) {
        int y = Integer.MAX_VALUE;
        for (TagBarLayout.Box b : plan.boxes) {
            y = Math.min(y, b.y);
        }
        return y == Integer.MAX_VALUE ? -1 : y;
    }

    /** 最低的那条边 */
    private static int maxBottom(TagBarLayout.Plan plan) {
        int y = Integer.MIN_VALUE;
        for (TagBarLayout.Box b : plan.boxes) {
            y = Math.max(y, b.y + b.h);
        }
        return y == Integer.MIN_VALUE ? -1 : y;
    }

    private static void expectDockedMode(int screenW, int screenH, int guiW, int guiH,
                                        String dock, int guiScale, TagBarLayout.Mode want) {
        int guiX = (screenW - guiW) / 2;
        int guiY = (screenH - guiH) / 2;
        TagBarLayout.Plan plan = TagBarLayout.plan(screenW, screenH, guiX, guiY, guiW, guiH, 10, 3, dock, guiScale);
        ok(plan.mode == want, screenW + "x" + screenH + " dock=" + dock + " scale=" + guiScale
                + "：期望 " + want + "，实际 " + plan.mode + "（" + plan.note + "）");
        checks++;
    }

    private static void expectMode(int screenW, int screenH, int guiW, int guiH, TagBarLayout.Mode want) {
        expectMode(screenW, screenH, (screenW - guiW) / 2, (screenH - guiH) / 2, guiW, guiH, want);
    }

    private static void expectMode(int screenW, int screenH, int guiX, int guiY, int guiW, int guiH, TagBarLayout.Mode want) {
        TagBarLayout.Plan plan = TagBarLayout.plan(screenW, screenH, guiX, guiY, guiW, guiH, 10, 3);
        ok(plan.mode == want, screenW + "x" + screenH + " gui@" + guiX + "," + guiY + "：" + guiW + "x" + guiH
                + " 期望 " + want + "，实际 " + plan.mode + "（" + plan.note + "）");
        checks++;
    }

    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures.add(message);
        }
    }

    /** 估一下像素宽（这里没有字体可用）：全角/中文 9 像素，ASCII 6 像素 —— 与 Minecraft 默认字体的步进一致 */
    private static int width(String text) {
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            w += text.charAt(i) < 128 ? 6 : 9;
        }
        return w;
    }
}
