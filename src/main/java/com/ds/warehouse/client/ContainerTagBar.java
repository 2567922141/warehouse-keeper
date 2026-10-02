package com.ds.warehouse.client;

import com.ds.warehouse.config.CategoryRules;
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
 *   <li><b>只认已贴/未贴，不猜分类</b>：分类清单来自 {@link Categories#order()}（创造栏页签顺序 + 「其他」，
 *       类目数随整合包的页签数浮动），当前生效的那个打勾；清单由「标签：… ▾」按钮弹出（自绘下拉，配色与仓库面板的「仓库：… ▾」一致）。
 *       清单比屏幕高时按行滚动，右下角用「a~b / n」标出位置。</li>
 *   <li><b>自动档 / 暂存箱锁住分类</b>：这两种状态下分类由箱内内容或「只出不进」策略决定，
 *       所以下拉按钮置灰点不开 —— 免得手选把自动结果覆盖掉。</li>
 * </ul>
 */
public final class ContainerTagBar {

    /** 射线够得着的距离（方块） */
    private static final int REACH = 6;
    /**
     * 迟滞阈值（客户端 tick）：射线连续落空多少次之后才把标签栏藏起来。
     * 12 tick ≈ 0.6 s —— 够盖住「手抖 / 转头 / 方块实体这一帧还没同步到客户端」这类瞬时落空，
     * 又不至于让玩家挪开视线后还长时间挂着一栏点不掉的按钮。
     */
    private static final int MISS_LIMIT = 12;
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
        /**
         * 当前**锁定**的箱子：这一帧照着它画、按钮也照着它发指令的那一只。
         * 与 {@link #lastPos} 分开：{@code lastPos} 是「文案用的是哪只箱」，
         * {@code locked} 是「这一帧画的是哪只箱」—— 后者带迟滞，不随单 tick 射线落空而变。
         */
        BlockPos locked;
        /** 锁定目标失效后连续落空的 tick 数；够 {@code MISS_LIMIT} 才隐藏 */
        int missTicks;
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
        /**
         * 这一份清单的类目**键**（显示名画的时候再翻）。刷新时重算 ——
         * 类目数是动态的（10 → 14+ 都正常），所以不能是常量表。
         */
        final List<String> cats = new ArrayList<>();
    }

    /**
     * 每个屏幕实例一套 Holder，跨 resize 复用：{@code Screen.resize} 会重新 init、再触发一次
     * {@code AFTER_INIT}，没有这张表的话控件会被重复注册、锁定坐标与 miss 计数会被清零。
     * 键就是屏幕实例本身；用 {@link java.util.WeakHashMap} 是希望屏幕关掉后条目能自己走掉。
     */
    private static final java.util.Map<Screen, Holder> HOLDERS = new java.util.WeakHashMap<>();

    private ContainerTagBar() {
    }

    /** 在客户端初始化时调一次 */
    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) {
                return;
            }
            // 真门禁：个人物品栏 / 创造物品栏也是 AbstractContainerScreen，但服务端并没有开容器菜单，
            // 这时 containerMenu 还是那份常驻的 inventoryMenu —— 用它把「背包界面」挡在外面，
            // 免得准心对着箱子按 E 时，标签栏也贴到个人物品栏旁边。
            // （两个字段都是原版 public，不需要 mixin。）
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.player.containerMenu == mc.player.inventoryMenu) {
                return;
            }
            Holder cached = HOLDERS.get(screen);
            if (cached == null) {
                // 第一次见到这个屏幕：建 Holder、建控件、挂事件。holder 之后不再重新赋值，
                // 所以下面这些 lambda 能直接捕获它（按钮要按「锁定坐标」发指令）。
                Holder holder = new Holder();
                HOLDERS.put(screen, holder);
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
                holder.extras.add(add(screen, 0, 0, 43, () -> tag(holder, "auto")));
                holder.extras.add(add(screen, 0, 0, 43, () -> tag(holder, "staging")));
                holder.extras.add(add(screen, 0, 0, 43, () -> tag(holder, "clear")));

                // 点清单 → 选分类；点别处 → 收起；都吃掉这次点击，免得误碰箱子槽位
                ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> clickList(holder, event));
                ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, amountX, amountY)
                        -> scrollList(holder, amountY));
                ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> keyList(holder, event));
                // 清单画在容器界面之后（afterExtract 就是「界面自己的东西都提完」那一趟）
                ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta)
                        -> drawList(holder, graphics, mouseX, mouseY));

                ScreenEvents.afterTick(screen).register(s -> refresh(holder));
                // 屏幕关掉时把这一条从表里摘掉：HOLDERS 虽然是 WeakHashMap，但 value 里的
                // holder.screen 反过来强引用 key，条目并不会自己消失 —— 不摘的话每开一次箱子
                // 就会留住一套 Holder + 4 个按钮直到客户端退出。
                ScreenEvents.remove(screen).register(s -> HOLDERS.remove(s));
                refresh(holder);
                return;
            }
            // resize 又触发了一次 AFTER_INIT：复用同一个 Holder（锁定坐标、miss 计数、下拉展开状态、
            // 上次文案全都留着）。事件**不再重挂**一遍 —— 它们是挂在屏幕实例上的，resize 不会清掉，
            // 重挂只会让每 tick 多刷一次。
            // 但控件表会被原版清空，所以必须把**已有的**控件挂回去（幂等），否则 resize 后按钮就没了。
            cached.screen = screen;
            attach(screen, cached.title);
            for (Button b : cached.extras) {
                attach(screen, b);
            }
            refresh(cached);
        });
    }

    /**
     * 发一条标签指令。坐标取**当前锁定坐标**（也就是标签栏正在显示的那只箱子），而不是再打一次射线：
     * 迟滞期间玩家把准心挪开时，按钮发的指令必须和栏上写的是同一只箱子。
     * 锁定目标已经不在了（被拆掉 / 还没锁定）就一条指令都不发，绝不对着空气发指令。
     */
    private static void tag(Holder holder, String sub) {
        BlockPos pos = lockedTarget(holder);
        if (pos != null) {
            send("warehouse tag " + sub + " " + coords(pos));
        }
    }

    /** 现在可以对着发指令的锁定坐标：已锁定、且那一格现在仍是容器；否则 null */
    private static BlockPos lockedTarget(Holder holder) {
        return isContainer(Minecraft.getInstance(), holder.locked) ? holder.locked : null;
    }

    /**
     * 这一次要画出来的类目键清单。
     *
     * <p>首选 {@link Categories#order()} —— 创造栏页签顺序 + 「其他」，就是服务端
     * {@code Categories.of} 会产出的那套键，所以勾选比对是同一个坐标系。
     *
     * <p>但客户端**没有 {@code MinecraftServer}**，{@code CreativeOrder} 的表只有服务端
     * {@code ensure(server)} 才建得起来 ⇒ 单机/联机的客户端拿到的 {@code order()} 往往只剩
     * 「其他」一个。这时退回旧中文类目名（{@link CategoryRules#LEGACY}）：服务端
     * {@code Categories.resolve} 认得它们（父类语义），点了也能贴标签。
     *
     * <p>最后把「当前生效但不在表里」的键补到末尾，否则箱子明明贴着某个类目，下拉里却没有勾。
     */
    private static List<String> categoryKeys(String current) {
        List<String> keys = new ArrayList<>(Categories.order());
        if (keys.size() <= 1) {
            keys = new ArrayList<>();
            for (String legacy : CategoryRules.LEGACY) {
                // 旧清单最后那个「其他」在新体系里换成了合成键，勾要对得上就得跟着换
                keys.add("其他".equals(legacy) ? Categories.OTHER : legacy);
            }
        }
        if (current != null && !current.isEmpty() && !keys.contains(current)) {
            keys.add(current);
        }
        return keys;
    }

    /** 读当前清单；万一某一帧先于 {@code refresh} 用到（还没算过），就地用兜底清单顶上 */
    private static List<String> catsOf(Holder holder) {
        if (holder.cats.isEmpty()) {
            holder.cats.addAll(categoryKeys(currentCategory(holder)));
        }
        return holder.cats;
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
        int max = Math.max(0, catsOf(holder).size() - holder.rows);
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
        return index < catsOf(holder).size() ? index : -1;
    }

    private static void pick(Holder holder, int index) {
        BlockPos pos = lockedTarget(holder);   // 同 tag()：发指令只认锁定坐标
        List<String> keys = catsOf(holder);
        if (pos != null && index >= 0 && index < keys.size()) {
            // 发的是**键**：服务端 Categories.resolve 认页签键，也认旧中文父类名
            send("warehouse tag set \"" + keys.get(index) + "\" " + coords(pos));
        }
        holder.open = false;
        refresh(holder);
    }

    /** 锁定箱子当前生效的分类（暂存箱没有分类）；下拉勾选也走这里，免得迟滞期间勾跟着射线乱跳 */
    private static String currentCategory(Holder holder) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = holder.locked;
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
        String current = currentCategory(holder);
        List<String> keys = catsOf(holder);
        for (int row = 0; row < holder.rows; row++) {
            int index = holder.scroll + row;
            if (index >= keys.size()) {
                break;
            }
            int y = holder.listY + 2 + row * LINE_H;
            int x = holder.listX + 1;
            int w = holder.listW - 2;
            boolean chosen = keys.get(index).equals(current);
            if (chosen) {
                g.fill(x, y, x + w, y + LINE_H, 0xFF1D3350);
            } else if (index == holder.hover) {
                g.fill(x, y, x + w, y + LINE_H, 0xFF24313F);
            }
            // 显示名走 Categories.displayName（创造栏页签的中文名 / 旧类目名 / 「其他」），
            // 模组页签名可能很长，按清单宽度用既有的 fit 截断
            String name = Categories.displayName(keys.get(index));
            String text = fit(chosen ? "✓ " + name : name, w - 4);
            int tx = x + Math.max(0, (w - mc.font.width(text)) / 2);
            int ty = y + Math.max(0, (LINE_H - mc.font.lineHeight) / 2);
            g.text(mc.font, text, tx, ty, chosen ? 0xFFFFFFFF : 0xFFD6DEEA);
        }
        int total = keys.size();
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
        // 先藏起来：位置和可见性一律由 refresh() 决定。带迟滞之后 refresh() 可能连着十几个 tick
        // 谁都不画（还没锁定目标），那时绝不能把「刚建好、停在 (0,0)、文字还是个空格」的按钮露出来。
        button.visible = false;
        attach(screen, button);
        return button;
    }

    /**
     * 把控件挂回屏幕的控件表。<b>幂等</b>：{@code Screen.resize} 会清空控件表并重新 init，
     * 于是 {@code AFTER_INIT} 会再触发一次 —— 那时要把**同一个**按钮挂回去，而不是再造一个新的；
     * 也不能因为「表里已经有了」就干脆不挂（不挂 = resize 之后按钮消失）。
     */
    private static void attach(Screen screen, AbstractWidget widget) {
        List<AbstractWidget> widgets = Screens.getWidgets(screen);
        if (!widgets.contains(widget)) {
            widgets.add(widget);
        }
    }

    /**
     * 每个客户端 tick 对一次（20 次/秒）：目标换了、标签变了、窗口缩放了，都在这儿重新贴。
     *
     * <p>本方法带<b>迟滞</b>：单个 tick 的射线落空（手抖、转头、方块实体这一帧还没同步到客户端）
     * 不会把整条标签栏藏掉 —— 只要锁定目标还在（{@link #isContainer}）就继续照着它画，
     * 连续 {@link #MISS_LIMIT} 个 tick 都拿不到有效目标才 {@link #hide}。
     */
    private static void refresh(Holder holder) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            hide(holder);
            return;
        }
        // 本 tick 射线打到的容器：打到就（重新）锁定它、miss 计数清零 —— 换箱子也在这一步生效。
        BlockPos hit = target();
        if (hit != null) {
            holder.locked = hit;
            holder.missTicks = 0;
        } else if (holder.locked == null || !isContainer(mc, holder.locked)) {
            // 射线落空，而且锁定目标也失效了（被拆掉 / 从来没锁上）：开始数 miss。
            // 没数够阈值就只 return —— 不画、也不改文案，上一 tick 的画面原样留着，这正是「迟滞」。
            if (++holder.missTicks >= MISS_LIMIT) {
                hide(holder);
                return;
            }
            return;
        } else {
            // 射线落空但锁定目标还在（最常见：玩家把准心挪开了）：继续照它画，miss 计数清零。
            holder.missTicks = 0;
        }
        BlockPos pos = holder.locked;   // 之后一律用锁定坐标，不再看瞬时射线结果
        String dimension = mc.level.dimension().identifier().toString();
        ClientSnapshot.Tag tag = tagNear(mc, pos);
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
                // 键 → 中文显示名（创造栏页签名 / 旧类目名 / 「其他」）
                String shown = Categories.displayName(category);
                value = auto ? shown + "（自动）" : shown;
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
        // 布局退化成「标签：… + 自动分类 + 暂存 + 清除标签」四个按钮。
        // BUG1：停靠偏好（面板「维护」页里那个循环按钮，存客户端 config）在这里读进来 ——
        // TagBarLayout 是纯几何类（不 import Minecraft/Gson），所以由调用方把偏好与界面尺寸传进去；
        // auto + 界面尺寸 ≤ 1 时它会先试左侧（窗口逻辑宽度大 ⇒ JEI 面板占着箱子右边）。
        TagBarLayout.Plan plan = TagBarLayout.plan(screenW, screenH, guiX, guiY, guiW, guiH,
                0, holder.extras.size(), ClientPrefs.dock(), mc.getWindow().getGuiScale());
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
        // 类目数是动态的：每 tick 重算一次清单（含「当前键不在表里就补上」的兜底）
        holder.cats.clear();
        holder.cats.addAll(categoryKeys(currentCategory(holder)));
        int total = holder.cats.size();
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
        // 宽度：够放标题，也够放最长的显示名（模组页签名可能很长），但不超出屏幕
        int w = Math.max(holder.title.getWidth(), 96);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            for (String key : holder.cats) {
                w = Math.max(w, mc.font.width(Categories.displayName(key)) + 16);
            }
        }
        w = Math.min(w, Math.max(1, screenW));
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
        // 一并解锁：锁定坐标与 miss 计数归零，下一 tick 重新从射线取目标。
        // 不清的话 hide() 之后 locked 还停在那只箱子上，下一次落空会被误判成「锁定还有效」。
        holder.locked = null;
        holder.missTicks = 0;
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

    /** 这个坐标上现在还是不是一个容器方块（锁定的目标是否还有效） */
    private static boolean isContainer(Minecraft mc, BlockPos pos) {
        return mc != null && pos != null && mc.level != null
                && mc.level.getBlockEntity(pos) instanceof Container;
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
