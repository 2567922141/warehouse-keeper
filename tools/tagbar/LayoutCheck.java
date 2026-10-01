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
        TagBarLayout.Plan plan = TagBarLayout.plan(screenW, screenH, guiX, guiY, guiW, guiH, mainCount, extraCount);
        String where = screenW + "x" + screenH + " gui " + guiW + "x" + guiH;

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
