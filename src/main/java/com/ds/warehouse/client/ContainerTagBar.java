package com.ds.warehouse.client;

import com.ds.warehouse.util.Categories;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 箱子标签栏（批次 3 · 附录 J.4）：开着箱子时，在箱子界面旁边贴一列按钮，
 * 点一下就等于替玩家敲一条 {@code /warehouse tag …} 指令。
 *
 * <p>几条刻意的取舍：
 * <ul>
 *   <li><b>界面里不写网络代码</b>：按钮只发指令，跟玩家手打完全同一条路径，
 *       权限、文案、持久化都由服务端那一套负责（也就不可能出现「按钮能用、指令不能用」的分叉）。</li>
 *   <li><b>坐标走客户端射线</b>：目标箱由玩家自己看着的那个方块决定（6 格内），
 *       而不是猜「当前打开的容器是哪只」——潜影盒、模组容器也一视同仁。</li>
 *   <li><b>几何自己算</b>：26.2 的 {@code AbstractContainerScreen} 没给公开的几何访问口，
 *       项目里也没有 mixin 基础设施，所以按原版常量估一个「只大不小」的外框，
 *       再由 {@link TagBarLayout} 决定贴左/贴右/横条/退化（纯数学，另有 runner 跑六种分辨率断言）。</li>
 *   <li><b>只认已贴/未贴，不猜分类</b>：分类清单就是仓库那 10 个分类（{@link Categories#ORDER}），
 *       当前生效的那个打勾；清单由「标签：… ▾」按钮弹出（自绘下拉，配色与仓库面板的「仓库：… ▾」一致）。</li>
 *   <li><b>自动档 / 暂存箱锁住分类</b>：这两种状态下分类由箱内内容或「只出不进」策略决定，
 *       所以下拉按钮置灰点不开 —— 免得手选把自动结果覆盖掉。</li>
 * </ul>
 */
public final class ContainerTagBar {

    /** 射线够得着的距离（方块） */
    private static final int REACH = 6;
    /** 附加按钮：自动 / 暂存 / 清除 */
    private static final int EXTRA = 3;
    /** 下拉清单每行高度（和仓库面板的下拉同一套手感） */
    private static final int LINE_H = 12;
    /**
     * 标题的统一前缀。所有箱子、所有宽窄形态下都从这三个字开始，截断只砍前缀后面的值、
     * 绝不砍前缀 —— 这是「不管什么箱子，右侧标签栏长得一样」的底线。
     */
    private static final String PREFIX = "标签：";
    /** 按钮内文字两侧各留的像素（截断预算按 {@code box.w - PAD} 算） */
    private static final int PAD = 2;
    /** 截断收尾符 */
    private static final String ELLIPSIS = "…";

    /** 每个箱子界面一套控件 */
    private static final class Holder {
        Screen screen;
        Button title;
        final List<Button> extras = new ArrayList<>();
        /** 上次定位到的箱子（用来判断要不要刷新文字） */
        BlockPos lastPos;
        /** 上次真正画出来的按钮文字（可能带箭头、可能被截断） */
        String lastTitle = "\u0000";
        /** 上次的**完整**标题（没截断的那份）：窄条下分类名被截掉时，靠它才认得出 tooltip 该换 */
        String lastFullTitle = "\u0000";
        /** 上次给每个附加按钮挂的悬停提示（null = 还没挂过） */
        final String[] extraTip = new String[EXTRA];
        /** 分类下拉是否展开（默认收起 = 只剩「标签：… ▾」一个按钮，界面更精简） */
        boolean open;
        /** 上次是不是「锁定」状态（自动档 / 暂存箱时不许手选分类） */
        boolean lastLocked;
        /** 展开时那张下拉清单的几何（和仓库面板里的下拉同一套配色） */
        int listX;
        int listY;
        int listW;
        int listH;
        int rows;
        int scroll;
        /** 页脚高度（>0 时用来放「a~b / n」计数，不占条目行） */
        int footer;
        int hover = -1;
    }

    private ContainerTagBar() {
    }

    /** 在客户端初始化时调一次 */
    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) {
                return;
            }
            Holder holder = new Holder();
            holder.screen = screen;

            // 分类不再是 10 个按钮，而是「标签：… ▾」按钮弹出的一张自绘清单
            // （和仓库面板里的「仓库：… ▾」同一种样式：深底、蓝框、当前项打勾、悬停高亮）。
            holder.title = add(screen, 0, 0, TagBarLayout.WIDE, () -> {
                if (holder.lastLocked) {
                    return;   // 自动档 / 暂存箱：按钮已置灰，这里再兜一次底
                }
                holder.open = !holder.open;
                holder.scroll = 0;
                refresh(holder);
            });
            // ① 自动：按箱内现有东西重新猜一次分类 ② 暂存：开关 ③ 清除
            holder.extras.add(add(screen, 0, 0, 43, () -> tag("auto")));
            holder.extras.add(add(screen, 0, 0, 43, () -> tag("staging")));
            holder.extras.add(add(screen, 0, 0, 43, () -> tag("clear")));

            // 点清单 → 选分类；点别处 → 收起；都吃掉这次点击，免得误碰箱子槽位
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> clickList(holder, event));
            ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, amountX, amountY)
                    -> scrollList(holder, amountY));
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> keyList(holder, event));
            // 清单画在容器界面之后（afterExtract 就是「界面自己的东西都提完」那一趟）
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta)
                    -> drawList(holder, graphics, mouseX, mouseY));

            ScreenEvents.afterTick(screen).register(s -> refresh(holder));
            refresh(holder);
        });
    }

    private static void tag(String sub) {
        BlockPos pos = target();
        if (pos != null) {
            send("warehouse tag " + sub + " " + coords(pos));
        }
    }

    /** 点下拉清单：选中一项就发指令并收起，点清单外面只是收起 —— 两种情况都吃掉这次点击，免得误碰箱子槽位 */
    private static boolean clickList(Holder holder, MouseButtonEvent event) {
        if (!holder.open) {
            return true;
        }
        int index = event.button() == 0 ? indexAt(holder, event.x(), event.y()) : -1;
        if (index >= 0) {
            pick(holder, index);
            return false;
        }
        holder.open = false;
        refresh(holder);
        return false;
    }

    private static boolean scrollList(Holder holder, double amountY) {
        if (!holder.open) {
            return true;
        }
        int max = Math.max(0, Categories.ORDER.size() - holder.rows);
        int next = holder.scroll - (int) Math.signum(amountY);
        holder.scroll = Math.max(0, Math.min(max, next));
        return false;
    }

    private static boolean keyList(Holder holder, KeyEvent event) {
        if (holder.open && event.key() == 256) {
            holder.open = false;
            refresh(holder);
            return false;   // Esc 先收下拉，不要顺手把箱子界面也关了
        }
        return true;
    }

    /** 鼠标落在清单第几项（不在清单里就是 -1） */
    private static int indexAt(Holder holder, double mouseX, double mouseY) {
        if (mouseX < holder.listX || mouseX >= holder.listX + holder.listW
                || mouseY < holder.listY || mouseY >= holder.listY + holder.listH) {
            return -1;
        }
        int row = (int) ((mouseY - holder.listY - 2) / LINE_H);
        if (row < 0 || row >= holder.rows) {
            return -1;
        }
        int index = holder.scroll + row;
        return index < Categories.ORDER.size() ? index : -1;
    }

    private static void pick(Holder holder, int index) {
        BlockPos pos = target();
        if (pos != null) {
            send("warehouse tag set \"" + Categories.ORDER.get(index) + "\" " + coords(pos));
        }
        holder.open = false;
        refresh(holder);
    }

    /** 目标箱子当前生效的分类（暂存箱没有分类） */
    private static String currentCategory() {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = target();
        if (mc.level == null || pos == null) {
            return "";
        }
        ClientSnapshot.Tag tag = tagNear(mc, pos);
        return tag == null || tag.staging || tag.category == null ? "" : tag.category;
    }

    /**
     * 按坐标取标签，带「双联箱归一化」兜底。
     *
     * <p>服务端把标签记在双联箱归一化的那一半上，而玩家可能正开着另一半，所以要往邻格看一眼。
     * 但只有<b>原版箱子</b>会拼成双联箱（铁箱子等的 TYPE 恒为 SINGLE），所以只有它才允许看邻格 ——
     * 否则旁边一只桶/箱子的标签会串到当前这只箱子上。
     */
    private static ClientSnapshot.Tag tagNear(Minecraft mc, BlockPos pos) {
        if (mc == null || mc.level == null || pos == null) {
            return null;
        }
        String dim = mc.level.dimension().identifier().toString();
        ClientSnapshot.Tag t = ClientSnapshot.tagOf(dim, pos.getX(), pos.getY(), pos.getZ());
        if (t != null) {
            return t;
        }
        if (!(mc.level.getBlockState(pos).getBlock() instanceof ChestBlock)) {
            return null;
        }
        for (BlockPos n : new BlockPos[]{pos.west(), pos.east(), pos.north(), pos.south()}) {
            ClientSnapshot.Tag near = ClientSnapshot.tagOf(dim, n.getX(), n.getY(), n.getZ());
            if (near != null) {
                return near;
            }
        }
        return null;
    }

    /** 自绘下拉清单：深底 + 蓝框 + 当前项打勾 + 悬停高亮（与仓库面板的「仓库：… ▾」同一套） */
    private static void drawList(Holder holder, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!holder.open || holder.listH <= 0 || holder.listW <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int x1 = holder.listX + holder.listW;
        int y1 = holder.listY + holder.listH;
        g.fill(holder.listX, holder.listY, x1, y1, 0xFF141B26);
        border(g, holder.listX, holder.listY, x1, y1, 0xFF4E8BD8);

        holder.hover = indexAt(holder, mouseX, mouseY);
        String current = currentCategory();
        for (int row = 0; row < holder.rows; row++) {
            int index = holder.scroll + row;
            if (index >= Categories.ORDER.size()) {
                break;
            }
            int y = holder.listY + 2 + row * LINE_H;
            int x = holder.listX + 1;
            int w = holder.listW - 2;
            boolean chosen = Categories.ORDER.get(index).equals(current);
            if (chosen) {
                g.fill(x, y, x + w, y + LINE_H, 0xFF1D3350);
            } else if (index == holder.hover) {
                g.fill(x, y, x + w, y + LINE_H, 0xFF24313F);
            }
            String text = chosen ? "✓ " + Categories.ORDER.get(index) : Categories.ORDER.get(index);
            int tx = x + Math.max(0, (w - mc.font.width(text)) / 2);
            int ty = y + Math.max(0, (LINE_H - mc.font.lineHeight) / 2);
            g.text(mc.font, text, tx, ty, chosen ? 0xFFFFFFFF : 0xFFD6DEEA);
        }
        int total = Categories.ORDER.size();
        if (holder.footer > 0) {
            // 计数条画在预留的页脚里，别压在最后一行上
            String more = (holder.scroll + 1) + "~" + (holder.scroll + holder.rows) + " / " + total;
            g.text(mc.font, more, x1 - mc.font.width(more) - 3,
                    holder.listY + holder.listH - holder.footer + 1, 0xFF8FA0B8);
        }
    }

    private static void border(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    private static Button add(Screen screen, int x, int y, int w, Runnable press) {
        Button button = Button.builder(Component.literal(" "), b -> press.run())
                .bounds(x, y, w, TagBarLayout.BTN_H).build();
        Screens.getWidgets(screen).add(button);
        return button;
    }

    /** 每秒（每个客户端 tick）对一次：目标换了、标签变了、窗口缩放了，都在这儿重新贴 */
    private static void refresh(Holder holder) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = target();
        if (mc.level == null || pos == null) {
            hide(holder);
            return;
        }
        String dimension = mc.level.dimension().identifier().toString();
        ClientSnapshot.Tag tag = tagNear(Minecraft.getInstance(), pos);
        // 锁定：暂存箱（只出不进）或自动档（分类由箱内内容决定）时**不允许手选分类**，
        // 下拉按钮置灰、展开也强制收起 —— 这就是「启动自动分配或选择暂存模式后不能再选其他标签」。
        boolean staging = tag != null && tag.staging;
        boolean auto = tag != null && "AUTO".equalsIgnoreCase(tag.mode);
        boolean locked = staging || auto;
        if (locked) {
            holder.open = false;
        }
        if (!pos.equals(holder.lastPos)) {
            holder.open = false;   // 换了箱子就收起下拉，免得展开态跟着玩家跑
        }

        // 统一文案（批次 3 收尾）：标题**永远**是「标签：<值>」，宽窄同一套字，
        // 窄条只按像素截断、绝不再退化成裸词「标签」/「标签✓」——这样不管开哪种箱子，右侧长得都一样。
        // <值> 严格只有四种：未设置 / <分类名> / <分类名>（自动）/ 暂存。
        // 自动档但还没定案时不另造第五种写法：仍是「未设置」，靠按钮置灰 + 不带箭头来区分。
        String value;
        if (staging) {
            value = "暂存";
        } else {
            String category = tag == null || tag.category == null ? "" : tag.category;
            if (category.isEmpty()) {
                value = "未设置";
            } else {
                value = auto ? category + "（自动）" : category;
            }
        }
        String fullTitle = PREFIX + value;
        String titleTip = locked
                ? fullTitle + (staging ? "\n暂存箱：只出不进，分类不可手选" : "\n自动档：分类由箱内内容决定，不可手选")
                : fullTitle + "\n点击展开分类清单";

        int guiW = 176;
        int guiH = 168;
        if (holder.screen instanceof AbstractContainerScreen<?> cs) {
            AbstractContainerMenu menu = cs.getMenu();
            if (menu instanceof ChestMenu chest) {
                guiH = 114 + chest.getRowCount() * 18;
            } else if (menu != null) {
                int maxX = 0;
                int maxY = 0;
                for (Slot slot : menu.slots) {
                    maxX = Math.max(maxX, slot.x);
                    maxY = Math.max(maxY, slot.y);
                }
                guiW = Math.max(guiW, maxX + 26);
                guiH = Math.max(guiH, maxY + 114);
            }
        }
        int screenW = Math.max(1, mc.getWindow().getGuiScaledWidth());
        int screenH = Math.max(1, mc.getWindow().getGuiScaledHeight());
        int guiX = (screenW - guiW) / 2;
        int guiY = (screenH - guiH) / 2;

        // 分类清单是自绘下拉，不占 TagBarLayout 的格子 ⇒ mainCount 恒为 0，
        // 布局退化成「标签：… + 自动分类 + 暂存 + 清除标签」四个按钮
        TagBarLayout.Plan plan = TagBarLayout.plan(screenW, screenH, guiX, guiY, guiW, guiH,
                0, holder.extras.size());
        if (plan.mode == TagBarLayout.Mode.HIDDEN) {
            hide(holder);
            return;
        }

        // 标题按钮 = 分类下拉开关；锁定（自动档 / 暂存箱）时置灰点不动，文字与悬停提示一起说明原因
        TagBarLayout.Box titleBox = plan.box("title");
        // 箭头只在按钮**可点**（有下拉能展开 / 收起）时才画：不可点时一个箭头都不出现，
        // 免得同一栏里「有的带箭头有的不带」显得突兀。
        String suffix = locked ? "" : (holder.open ? "▴" : "▾");
        String title = label(value, suffix, (titleBox == null ? TagBarLayout.WIDE : titleBox.w) - PAD);
        holder.title.visible = true;
        holder.title.active = !locked;
        place(holder.title, titleBox);
        layoutList(holder, screenW, screenH);
        boolean sameBox = pos.equals(holder.lastPos);
        // 判据里带上**完整**标题：窄条下分类名被截断时按钮文字可能没变，但 tooltip 必须跟着换，
        // 否则「改了分类、悬停提示还是旧的」这个 bug 还在。
        if (!sameBox || !title.equals(holder.lastTitle) || !fullTitle.equals(holder.lastFullTitle)
                || locked != holder.lastLocked) {
            holder.title.setMessage(Component.literal(title));
            holder.title.setTooltip(Tooltip.create(Component.literal(titleTip)));
            holder.lastPos = pos;
            holder.lastTitle = title;
            holder.lastFullTitle = fullTitle;
            holder.lastLocked = locked;
        }

        // 附加按钮：宽窄同一套短语与写法（不再有「自动 / 暂存✓ / 清除」这种窄态缩写）
        String[] extraText = {
                "自动分类",
                staging ? "暂存：开" : "暂存：关",
                "清除标签",
        };
        String[] extraTip = {
                "按箱内物品重新算一次分类（自动档才会生效）",
                staging ? "取消暂存：整理时不再只出不进" : "设为暂存箱：整理时假人只往外取，不往里放",
                "清除这只箱子的标签，整理时回到默认启发式",
        };
        for (int i = 0; i < holder.extras.size(); i++) {
            Button b = holder.extras.get(i);
            TagBarLayout.Box box = plan.box("e" + i);
            if (box == null) {
                b.visible = false;
                continue;
            }
            b.visible = true;
            place(b, box);
            String text = fit(extraText[i], box.w - PAD);
            String tip = extraTip[i];
            if (!sameBox || !text.contentEquals(b.getMessage().getString()) || !tip.equals(holder.extraTip[i])) {
                b.setMessage(Component.literal(text));
                b.setTooltip(Tooltip.create(Component.literal(tip)));
                holder.extraTip[i] = tip;
            }
        }
    }

    /**
     * 标题的统一写法：{@code 标签：<值>[箭头]}。前缀 {@link #PREFIX} 无论多窄都保留，
     * 只有后面的「值 + 箭头」会被按像素截断 —— 于是宽的地方读全名、窄的地方读「标签：建筑…」，
     * 两侧是同一种句式（不再是裸词「标签」/「标签✓」那种突兀感）。
     *
     * @param value  值：未设置 / 分类名 / 分类名（自动）/ 暂存
     * @param suffix 箭头（可点才给），不可点时传空串
     * @param max    按钮里能用的像素宽（已扣掉两侧留白）
     */
    private static String label(String value, String suffix, int max) {
        Minecraft mc = Minecraft.getInstance();
        int room = max - mc.font.width(PREFIX);
        if (room <= 0) {
            return PREFIX;   // 再窄也只丢值，前缀不丢
        }
        String tail = value + suffix;
        // 箭头排在最后：先保分类名完整，分类名完整时还放得下才补箭头
        if (!suffix.isEmpty() && !fit(tail, room).equals(tail)) {
            tail = value;
        }
        return PREFIX + fit(tail, room);
    }

    /**
     * 按像素宽度截断（同仓库 {@code WarehouseScreen.fit} 的做法；那个是实例方法、拿的是屏里的 font，
     * 这里就地写一个小工具，不引新依赖）。放不下就补 {@link #ELLIPSIS}，一个字都放不下时至少留个省略号，
     * 让人看出「这里被截了」而不是「这里本来就是空的」。
     */
    private static String fit(String text, int max) {
        Minecraft mc = Minecraft.getInstance();
        String value = text == null ? "" : text;
        if (max <= 0) {
            return "";
        }
        if (mc.font.width(value) <= max) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (mc.font.width(sb.toString() + value.charAt(i) + ELLIPSIS) > max) {
                break;
            }
            sb.append(value.charAt(i));
        }
        return sb.length() == 0 ? (mc.font.width(ELLIPSIS) <= max ? ELLIPSIS : "") : sb + ELLIPSIS;
    }

    /** 下拉清单贴在「标签：…」按钮正下方（下面放不下就翻到上面），并夹在屏幕里 */
    private static void layoutList(Holder holder, int screenW, int screenH) {
        int total = Categories.ORDER.size();
        int btnBottom = holder.title.getY() + holder.title.getHeight();
        int below = Math.max(0, screenH - btnBottom - 2);
        int above = Math.max(0, holder.title.getY() - 2);
        boolean down = below >= above;
        int room = down ? below : above;
        int rows = Math.min(total, Math.max(1, (room - 4) / LINE_H));
        // 条目放不下要加一条页脚放「a~b / n」，先把它算进去再决定还能露几行
        if (rows < total && rows > 1 && rows * LINE_H + 4 + 11 > room) {
            rows--;
        }
        holder.rows = Math.max(1, rows);
        holder.footer = total > holder.rows ? 11 : 0;
        int w = Math.max(holder.title.getWidth(), 96);
        int h = holder.rows * LINE_H + 4 + holder.footer;
        int y = down ? btnBottom + 2 : Math.max(0, holder.title.getY() - 2 - h);
        holder.listW = w;
        holder.listH = h;
        holder.listX = Math.max(0, Math.min(holder.title.getX(), Math.max(0, screenW - w)));
        holder.listY = Math.max(0, Math.min(y, Math.max(0, screenH - h)));
        holder.scroll = Math.max(0, Math.min(holder.scroll, Math.max(0, total - holder.rows)));
    }

    private static void place(AbstractWidget widget, TagBarLayout.Box box) {
        if (box == null) {
            widget.visible = false;
            return;
        }
        widget.setX(box.x);
        widget.setY(box.y);
        widget.setWidth(box.w);
        widget.setHeight(box.h);
    }

    private static void hide(Holder holder) {
        holder.open = false;
        holder.title.visible = false;
        for (Button b : holder.extras) {
            b.visible = false;
        }
        holder.lastPos = null;
    }

    /** 玩家正看着的容器方块（客户端射线，不区分箱子/木桶/潜影盒/模组容器） */
    private static BlockPos target() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return null;
        }
        HitResult hit = player.pick(REACH, 0.0F, false);
        if (!(hit instanceof BlockHitResult block)) {
            return null;
        }
        BlockPos pos = block.getBlockPos();
        return mc.level.getBlockEntity(pos) instanceof Container ? pos : null;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static void send(String command) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null && player.connection != null) {
            player.connection.sendCommand(command);
        }
    }
}
