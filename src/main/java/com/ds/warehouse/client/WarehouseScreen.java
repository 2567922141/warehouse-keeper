package com.ds.warehouse.client;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.net.ViewQueryPayload;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 仓库管理界面（按 B 打开，或用客户端指令 /warehousegui、/whg）。
 *
 * <p>这个界面刻意不含任何业务逻辑：它把硬盘上的 regions.json 画成列表，
 * 把玩家的点按翻译成一条 {@code /warehouse} 指令发给服务端。所以：
 * <ul>
 *   <li>界面与指令永远不会行为不一致</li>
 *   <li>服务端与存档完全不用改（卸载依然只是删一个 jar）</li>
 *   <li>网页端已删除（0.19.0 阶段 3）</li>
 * </ul>
 *
 * <h2>布局（0.17.1 重写）</h2>
 * 这个界面只有<b>一处</b>地方算几何：{@link #layout()}。它按当前 {@code this.width / this.height}
 * 把面板切成区域矩形 {@link R}，子区域一律由父区域用 {@link Flow} 切出来：
 * <ul>
 *   <li>切出来的子区域永远在父区域里 —— 不可能画到父容器之外；</li>
 *   <li>同一父区域里的子区域是「依次切」出来的 —— 不可能互相重叠；</li>
 *   <li>放不下时 {@code take()} 返回 {@code null}，调用方据此降级（不建控件、不画），
 *       而不是硬撑出一个必定重叠的最小高度；</li>
 *   <li>绘制、命中、滚动三处都只消费这些矩形（以及由它们算出的行数），
 *       同一个几何量不会再有第二份算法。</li>
 * </ul>
 * 文字全部走 {@link #textIn}/{@link #textMid}/{@link #textRight}（内部先按区域宽度截断），
 * 可滚动区域全部走 {@link #clipped}（scissor 成对，异常也保证配对）。
 * resize / GUI Scale 变化时 {@code Screen.resize(w,h) -> repositionElements() -> rebuildWidgets() -> init()}，
 * 于是 {@link #layout()} 会自动重算一遍；界面里没有任何一处缓存像素尺寸。
 */
public class WarehouseScreen extends Screen {

    private static final String[] DIR_KEYS = {"north", "south", "east", "west", "up", "down"};
    private static final String[] DIR_NAMES = {"北", "南", "东", "西", "上", "下"};

    // ------------------------------------------------------------ 布局常量
    /** 面板离屏幕边缘的留白 */
    private static final int MARGIN = 8;
    /** 面板内部的左右留白 */
    private static final int PAD = 12;
    /** 元件之间的标准间距 */
    private static final int GAP = 6;
    /** 面板尺寸上限：屏幕再大也不无限拉伸 */
    private static final int MAX_W = 680;
    private static final int MAX_H = 420;
    /** 标题带 / 页签带 / 小标题带的高度 */
    private static final int HEAD_H = 20;
    private static final int TAB_H = 18;
    /** 二级页签带的高度（比顶级页签矮一点，省内容高度）；再挤不下时用下面那个 */
    private static final int SUB_H = 15;
    private static final int SUB_H_TINY = 11;
    private static final int CAP_H = 11;
    /** 控件行（按钮 / 输入框）的标准行高与可压缩下限 */
    private static final int ROW_H = 20;
    private static final int ROW_H_MIN = 14;
    /** 正文一行的行高 */
    private static final int LINE_H = 11;
    /** 左栏（仓库列表）的基准宽度、下限，以及在内容区里占的比例 */
    private static final int LIST_W = 190;
    private static final int LIST_W_MIN = 120;
    private static final int LIST_RATIO = 45;
    /** 页签的内边距（文字与边框之间至少留这么多）、最小宽度、首选/最小间距 */
    private static final int TAB_PADX = 9;
    private static final int SUB_PADX = 6;
    private static final int TAB_MIN_W = 30;
    private static final int SUB_MIN_W = 24;
    private static final int TAB_GAP_MIN = 3;
    private static final int SUB_GAP_MIN = 2;
    /** 右栏（按钮网格）至少要有多宽才值得分两栏 */
    private static final int MIN_COL_W = 132;
    /** 「仓库」页右栏按钮网格的完整行数（管理员 6 行；普通玩家 1 行） */
    private static final int GRID_ROWS = 6;
    /** 低于这个内容高度就只给「最近结果」留一行 */
    private static final int MIN_CONTENT_H = 140;
    /** 关闭按钮宽度 */
    private static final int CLOSE_W = 66;
    /** 小标题带右端留给「滚轮 1~7 / 12」这类计数的宽度（计数与说明各占一块，永不重叠） */
    private static final int CAP_RIGHT_W = 96;
    /** 「箱子」子页一行的高度：两行文本（坐标那行 + 内容那行） */
    private static final int BOX_ROW_H = 24;

    // ------------------------------------------------------------ 布局结果
    // 全部由 layout() 赋值；null / 空 = 这块放不下，不画也不建控件。
    private R panel;
    private R header;
    /** 标题文字的那一小块（标题带减去关闭按钮的宽度）—— 保证标题不会画到关闭按钮底下 */
    private R headerTitle;
    private R closeBtn;
    private R tabs;
    private R caption;
    /** 小标题带切成左右两块：左边是说明，右边放「滚轮 1~7 / 12」这类计数 */
    private R captionLeft;
    private R captionRight;
    private R content;
    private R statusBox;
    /** 「仓库」页：列表框 / 搜索行 / 物品标题带 / 物品框（列表的标题直接用上方的小标题带） */
    private R listBox;
    private R searchRow;
    private R infoCap;
    /** 物品统计（「3 种 · 共 111 个 · 占 4/54 格」）画在物品标题带右端，物品框里就全是物品行了 */
    private R infoCapLeft;
    private R infoCapRight;
    private R infoBox;
    /** 「仓库」页右栏：整栏 / 「当前选中」信息块 / 按钮网格 */
    private R rightCol;
    private R infoBlock;
    private R gridBox;
    private int gridRows;
    private int rowPitch;
    private int gridRowH;
    private boolean gridTruncated;
    /**
     * 页签宽度不是「平均分」，而是按每个页签里文字的实际像素宽度 + 左右内边距算出来的，
     * 余下的宽度再均分回去；挤不下时先压间距、再压内边距、最后等比缩。这三个字段就是
     * 「本帧算出来的宽度/间距」，画页签、点页签、自检全都读它们，所以永远对得上。
     */
    private int[] tabCells;
    private int[] subCells;
    private int tabGap = GAP;
    private int subGap = 4;
    /** 「仓库」页左侧列表每行的实际高度（名字一行 + 详情最多两行，按文字宽度折行算出来） */
    private int regionRowH = ROW_H;
    /** 「取货」页：筛选行 / 清单标题带 / 清单 / 底部下单行 */
    private R pickRow;
    private R pickCap;
    private R pickBox;
    private R orderRow;
    /**
     * 面板里的下拉框（取货页的仓库选择 / 搬运工页「整理」的仓库选择）。
     * 两处共用同一套实现：一个按钮 + 一份展开的、可滚动的候选清单（弹出层，画在最上面）。
     */
    private Dropdown pickDd;
    private Dropdown taskDd;
    /** 「搬运工」页「整理」子页里那行仓库选择器（按钮铺满整行） */
    private R taskRow;
    /**
     * 输入框的占位提示：原版 EditBox 的 hint 是左对齐的，跟「文字默认在自己框里居中」不一致，
     * 所以这里自己收着，统一在 {@link #extractRenderState} 里居中画一遍（原版 hint 置空，避免画两遍）。
     */
    private final List<EditHint> editHints = new ArrayList<>();

    private record EditHint(EditBox box, String hint) {
    }
    /** 「搬运工」「权限」页：行区 / 底部按钮行 / 底部说明（标题用上方的小标题带） */
    private R rowsBox;
    private R footRow;
    private R notesBox;
    private int rowH;
    /**
     * 「搬运工」页的分配那一行：说明 / 仓库选择框 / 确定按钮。
     * 选好之后 {@code assignList} 才是展开的仓库清单（弹出层，画在最上面，不算布局叶子）。
     */
    private R assignRow;
    private R assignLabel;
    private R assignPick;
    private R assignOk;
    private R assignList;
    /** 展开中的仓库清单：可显示的行数与滚动位置 */
    private int assignRows;
    private int assignScroll;
    /** 选择框是否展开 */
    private boolean pickOpen;
    /** 当前选中的搬运工名与目标仓库（「确定」按这两个值发指令） */
    private String pickBot = "";
    private String pickRegion = "";

    // 兼容用的派生值（只读，layout() 里统一赋值）
    private int px;
    private int py;
    private int pw;
    private int ph;
    private boolean compact;

    /** 第 0 页左栏选中的仓库；{@code -1} = 全部仓库（0.22.0 · 优化10 的默认值） */
    private int sel = -1;   // -1 = 全部仓库
    private int scroll;
    private int dir;

    // ---- 分页：按功能把界面分成「仓库 / 取货 / 搬运工 / 权限」----
    /** 当前页：0 仓库、1 取货、2 搬运工、3 权限（普通玩家只有前两页） */
    private int tab;
    /** 二级页签带；这一页没有子页时是 null（不画也不点） */
    private R subTabs;
    /** 当前页选中了第几个子页（含义跟着页走，见 {@link #subNames()}） */
    private int sub;
    /** 每页各自记住自己停在哪个子页（切回来还在原处） */
    private final int[] subOf = new int[4];

    // 「箱子」子页：控件行（排序 / 只看非空）+ 箱子清单
    private R boxRow;
    private R boxBox;
    /** 排序方式：0 占用（默认）、1 坐标、2 物品数 */
    private int boxSort;
    /** 只显示非空箱 */
    private boolean boxNonEmptyOnly;
    /** 箱子清单的滚动位置与缓存（画 / 滚动 / 点击共用同一份数据） */
    private int boxScroll;
    private List<ClientSnapshot.Box> boxView = List.of();
    private String boxKey = "";
    private long boxStamp = -1L;
    /** 名册 / 账号的指纹：变了就重建控件（每行按钮的数量跟着名单走） */
    private String botSig = "";
    private String accSig = "";
    /** 上一次布局时的仓库个数：变了要重建第 0 页（列表框高度是按仓库个数算的） */
    private int listSig = -1;
    /** 「取货」页自己的筛选框与滚动位置 */
    private String pickQuery = "";
    private int pickScroll;
    /** 「这个仓库里有什么」的滚动位置 */
    private int infoScroll;

    // ---- 批次 5 阶段 1：「物品 / 物品详情 / 箱子 / 容器详情」四页改走按需查询（{@link QueryClient}）----
    /** 物品页：分类下拉（条目 =「全部分类」+ 总览里的 categories）；{@code entries} 由 {@link #catList()} 填 */
    private Dropdown catDd;
    /** 物品页：分类 + 排序 那一行 */
    private R itemCtlRow;
    /** 物品页：翻页那一行（◀ / 第 x/y 页 · 共 n 种 / ▶） */
    private R pageRow;
    /** 分类下拉的候选项：「全部分类」+ 总览返回的分类名（每帧按总览结果刷新） */
    private final List<String> catNames = new ArrayList<>();
    /** 排序口径：0 数量降序（默认）、1 数量升序、2 名称、3 存放位置数 */
    private int itemSort;
    /** 物品页当前页码（从 1 开始） */
    private int itemPage = 1;
    /** 「权限」页审计子页：第几页（每页 50 条，服务端最多给最近 500 条） */
    private int auditPage = 1;
    /** 审计页翻页按钮的矩形（画和点共用一份几何，没画出来就是 null） */
    private R auditPrev;
    private R auditNext;
    /** 非空 = 物品详情态：正在看这个物品 id 的全部存放位置（空 = 列表态） */
    private String itemDetailKey = "";
    /** 物品详情的滚动位置 */
    private int itemDetailScroll;
    /** 非空 = 容器详情态：正在看这只箱子的逐格内容（空 = 清单态） */
    private String boxDetailKey = "";
    /** 容器详情的滚动位置 */
    private int boxDetailScroll;
    /** 箱子页关键字（坐标或方块名；空 = 不过滤） */
    private String boxFilter = "";
    private EditBox boxFilterBox;
    /**
     * 「箱子」子页的附魔 / 自定义名全局搜索行（0.22.0 · 优化11）：一个输入框 + 一个按钮，
     * 点下去＝替玩家敲 {@code /warehouse enchants find <输入>}，结果由服务端输出在聊天栏。
     * 高度不够时这里是 {@code null}（整行不建，清单优先）。
     */
    private R boxSearchRow;
    private EditBox boxEnchBox;
    /** 附魔搜索框里当前的文字（发指令用；不落盘） */
    private String boxEnchText = "";
    /** 服务端太旧、没有按需查询通道时的提示（四页共用一句话） */
    private static final String QUERY_UNSUPPORTED = "当前服务器不支持面板查询（服务端版本较旧）。";

    private EditBox nameBox;
    private EditBox countBox;
    private EditBox orderBox;
    private EditBox orderCountBox;
    /** 物品搜索框（按关键字过滤下面那个「这个仓库里有什么」列表） */
    private EditBox searchBox;
    private EditBox pickQueryBox;
    private Button dirBtn;
    private Button borderBtn;
    /** 当前玩家能不能改仓库设置（OP / 单人房主 / 权限等级 ≥2）。只影响界面显示，拦截在服务端。 */
    private boolean admin = true;

    private String nameText = "";
    private String countText = "5";
    private String orderText = "";
    private String orderCountText = "1";
    private String searchText = "";
    /** 物品列表缓存（搜索过滤后）：见 itemsOf() */
    private List<ClientSnapshot.Item> viewItems = List.of();
    private String viewKey = "\u0000";
    private long viewStamp = -1L;
    private String status = "请在左侧选择一项仓库，再点击右侧按钮。所有操作均不会修改存档。";
    private long refreshAt;

    // ---- 危险操作确认框 ----
    /** 待确认的危险操作；非 null 时整个面板被一个模态小窗挡住，其它按钮点不到、按键也进不去。 */
    private Runnable pending;
    private String pendingTitle = "";
    private String pendingDetail = "";
    /** 确认框两个按钮的矩形：在 drawConfirm() 里算好，mouseClicked() 里用来判定 */
    private R okBtn;
    private R noBtn;

    public WarehouseScreen() {
        super(Component.literal("仓库管理"));
    }

    // ================================================================== 区域模型

    /** 一个矩形区域（左上角 + 宽高）。界面里所有的 x/y/width/height 都是它。 */
    private record R(int x, int y, int w, int h) {
        int right() {
            return x + w;
        }

        int bottom() {
            return y + h;
        }

        boolean empty() {
            return w <= 0 || h <= 0;
        }

        boolean holds(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        /** 自己是否完全落在父区域里 */
        boolean inside(R p) {
            return x >= p.x && y >= p.y && right() <= p.right() && bottom() <= p.bottom();
        }

        /** 两个矩形是否有重叠面积 */
        boolean hits(R o) {
            return x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom();
        }

        R at(int nx, int ny) {
            return new R(nx, ny, w, h);
        }

        R inset(int dx, int dy) {
            return new R(x + dx, y + dy, Math.max(0, w - dx * 2), Math.max(0, h - dy * 2));
        }

        R withH(int nh) {
            return new R(x, y, w, Math.max(0, nh));
        }

        R withW(int nw) {
            return new R(x, y, Math.max(0, nw), h);
        }

        /** 从左起 want 宽的一块 */
        R leftBlock(int want) {
            return new R(x, y, Math.max(0, Math.min(want, w)), h);
        }

        /** 右对齐：右边贴着自己的右边界、宽 want 的一块 */
        R rightBlock(int want) {
            int width = Math.max(0, Math.min(want, w));
            return new R(right() - width, y, width, h);
        }

        /** 自己右侧 dx 处、剩下的宽度 */
        R after(int dx) {
            return new R(x + dx, y, Math.max(0, w - dx), h);
        }

        /** 自己下面 dy 处、剩下的高度 */
        R below(int dy) {
            return new R(x, y + dy, w, Math.max(0, h - dy));
        }

        R shift(int dx, int dy) {
            return new R(x + dx, y + dy, w, h);
        }
    }

    /**
     * 把一个区域从上往下（或从下往上）依次切成若干条。
     *
     * <p>这是「不重叠、不越界」的保证：每条都从当前游标往内切，游标只会往里走；
     * 放不下时返回 {@code null}（而不是硬挤），调用方据此降级。
     */
    private static final class Flow {
        private final R box;
        private int top;
        private int bottom;

        Flow(R box) {
            this.box = box;
            this.top = box.y();
            this.bottom = box.bottom();
        }

        /** 中间还剩多少高度 */
        int left() {
            return Math.max(0, bottom - top);
        }

        boolean fitsTop(int h) {
            return h > 0 && top + h <= bottom;
        }

        boolean fitsBottom(int h) {
            return h > 0 && bottom - h >= top;
        }

        /** 从上面切一条 h 高的（gap = 它与下一条之间的间距） */
        R takeTop(int h, int gap) {
            if (!fitsTop(h)) {
                return null;
            }
            R r = new R(box.x(), top, box.w(), h);
            top += h + gap;
            return r;
        }

        /** 从下面切一条 h 高的 */
        R takeBottom(int h, int gap) {
            if (!fitsBottom(h)) {
                return null;
            }
            R r = new R(box.x(), bottom - h, box.w(), h);
            bottom -= h + gap;
            return r;
        }

        /** 剩下的整块（可能为 null） */
        R rest() {
            return fitsTop(1) ? new R(box.x(), top, box.w(), left()) : null;
        }
    }

    // ================================================================== 布局

    /**
     * 唯一算几何的地方。屏幕尺寸 / GUI Scale / 窗口大小一变，Minecraft 会走
     * {@code resize -> repositionElements -> rebuildWidgets -> init}，于是这里再跑一遍。
     */
    private void layout() {
        admin = ClientAdmin.isAdmin();
        if (tab < 0 || tab >= tabNames().length) {
            tab = 0;
        }
        // 二级页签：把下标夹回本页的范围，并把它记回「这一页停在哪」。
        // 普通玩家看到的「仓库」子页和 OP 不一样，所以夹取必须发生在 subNames() 之后。
        // 切页时（mouseClicked / keyPressed）会显式把旧页的下标存进 subOf、再取新页的，这里只做兜底。
        if (sub < 0 || sub >= subNames().length) {
            sub = 0;
        }
        subOf[Math.max(0, Math.min(tab, subOf.length - 1))] = sub;
        listSig = RegionCache.list().size();

        // ① 面板：屏幕居中，四周留 MARGIN；MAX_* 防止大屏被拉得过宽过高。
        //    屏幕本身比 MAX 还小时以屏幕为准 —— 面板绝不画到屏幕外。
        int w = Math.max(1, Math.min(MAX_W, this.width - MARGIN * 2));
        int h = Math.max(1, Math.min(MAX_H, this.height - MARGIN * 2));
        panel = new R((this.width - w) / 2, (this.height - h) / 2, w, h);
        px = panel.x();
        py = panel.y();
        pw = panel.w();
        ph = panel.h();
        compact = ph < 300;

        // ② 从上往下：标题带 / 页签带 / 小标题带；从下往上：「最近结果」。
        //    两者之间的就是内容区 —— 它的大小完全由这两头推出来。
        Flow v = new Flow(panel.inset(PAD, 0));
        header = v.takeTop(HEAD_H, GAP);
        tabs = v.takeTop(TAB_H, GAP);
        // 二级页签带：只有「仓库」「搬运工」两页有子页；挤不下时先变矮、再整条放弃（内容优先）
        subTabs = subNames().length == 0 ? null : v.takeTop(compact ? SUB_H_TINY : SUB_H, GAP);
        caption = v.takeTop(CAP_H, GAP);
        statusBox = v.takeBottom(statusHeight(), GAP);
        content = v.rest();
        if (content == null) {
            content = new R(panel.x() + PAD, panel.bottom(), Math.max(0, panel.w() - PAD * 2), 0);
        }
        closeBtn = header == null ? null
                : new R(header.right() - CLOSE_W, header.y() + Math.max(0, (header.h() - ROW_H) / 2),
                CLOSE_W, Math.min(ROW_H, header.h()));
        // 标题文字只占「标题带 − 关闭按钮」那一段：即使标题很长也不会钻到按钮底下
        headerTitle = header == null ? null
                : new R(header.x(), header.y(), Math.max(0, header.w() - (closeBtn == null ? 0 : CLOSE_W + GAP)),
                header.h());
        // 小标题带也切成左右两块：左边说明、右边计数（两块互不相交，说明再长也顶不到计数上）
        captionLeft = caption == null ? null
                : new R(caption.x(), caption.y(), Math.max(0, caption.w() - CAP_RIGHT_W), caption.h());
        captionRight = caption == null ? null : caption.rightBlock(CAP_RIGHT_W);

        // ②′ 页签宽度：按每个页签里文字的实际像素宽度 + 左右内边距来分（不写死、不平均分），
        //    余下的宽度均分回去；挤不下时先压间距、再压内边距、最后等比缩。
        //    画、点、自检都读这一份结果，所以永远不会「画的框」和「点的地方」对不上。
        int[] gapBox = new int[1];
        tabCells = fitCells(tabNames(), tabs, TAB_PADX, TAB_MIN_W, GAP, TAB_GAP_MIN, gapBox);
        tabGap = gapBox[0];
        if (subTabs != null) {
            subCells = fitCells(subNames(), subTabs, SUB_PADX, SUB_MIN_W, 4, SUB_GAP_MIN, gapBox);
            subGap = gapBox[0];
        } else {
            subCells = null;
        }

        // ③ 每页自己的区域（每次重算前先清空上一轮的，避免残留旧坐标）
        clearTabRegions();
        switch (tab) {
            case 0 -> layoutRegions();
            case 1 -> layoutPick();
            case 2 -> layoutPorter();
            default -> layoutPerm();
        }
        layoutCheck();
    }

    private void clearTabRegions() {
        listBox = null;
        searchRow = null;
        infoCap = null;
        infoCapLeft = null;
        infoCapRight = null;
        infoBox = null;
        rightCol = null;
        infoBlock = null;
        gridBox = null;
        gridRows = 0;
        rowPitch = 0;
        gridRowH = 0;
        gridTruncated = false;
        pickRow = null;
        pickCap = null;
        pickBox = null;
        orderRow = null;
        taskRow = null;
        if (pickDd != null) {
            pickDd.close();
        }
        if (taskDd != null) {
            taskDd.close();
        }
        rowsBox = null;
        footRow = null;
        notesBox = null;
        boxRow = null;
        boxBox = null;
        itemCtlRow = null;
        pageRow = null;
        boxFilterBox = null;
        boxSearchRow = null;
        boxEnchBox = null;
        if (catDd != null) {
            catDd.close();
        }
        rowH = 0;
        assignRow = null;
        assignLabel = null;
        assignPick = null;
        assignOk = null;
        assignList = null;
        assignRows = 0;
        pickOpen = false;
    }

    /** 「最近结果」需要多高：一行标题 + 1~3 行正文（按折行后的真实行数算，不再写死常量） */
    private int statusHeight() {
        return CAP_H + statusLines() * LINE_H + 2;
    }

    private int statusLines() {
        // 状态句可能很长（「已按你现在设的点1/点2 建了仓库 xxx」）：先按面板宽度折行数出来，
        // 最多给 3 行，面板太矮时只留 1 行，把高度让给内容区。
        int width = Math.max(40, (pw > 0 ? pw : 320) - 2 * PAD);
        int need = 0;
        for (String part : statusText().split("\n")) {
            need += Math.max(1, wrap(part, width).size());
        }
        int lines = Math.max(1, Math.min(3, need));
        int fixed = HEAD_H + TAB_H + CAP_H + GAP * 5 + MARGIN * 2;
        // 面板太矮时只留一行，把高度让给内容区
        if (ph - fixed - (CAP_H + lines * LINE_H + 2) < MIN_CONTENT_H) {
            lines = 1;
        }
        return lines;
    }

    /** 第 0 页「仓库」：左栏（列表 + 物品）+ 右栏（信息块 + 按钮网格），按二级页签分工 */
    private void layoutRegions() {
        String name = subName();
        // 「物品」子页要的就是一整块清单：这时不分两栏，物品框直接占满内容区宽度。
        // 其余子页都是「左栏挑仓库 + 右栏干活」。
        boolean itemsPage = "物品".equals(name);
        boolean boxesPage = "箱子".equals(name);

        // 左栏宽度 = max(内容实测需要的宽, 内容区的 45%)，但不能把右栏挤到分不出两栏。
        // 大窗口下列表跟着变宽（以前写死 190，1280 宽下文字会顶到边框），小窗口则退回单栏。
        int listNeed = listNeedWidth();
        int leftW = Math.min(Math.max(listNeed, content.w() * LIST_RATIO / 100),
                Math.max(LIST_W_MIN, content.w() - MIN_COL_W - GAP));
        leftW = Math.max(LIST_W_MIN, Math.min(leftW, content.w()));
        int rightW = content.w() - leftW - GAP;
        boolean twoCol = rightW >= MIN_COL_W && !itemsPage;
        if (!twoCol) {
            leftW = content.w();
            rightW = 0;
        }
        // 每行高度按「名字一行 + 详情最多两行」的实测折行结果算，文字永远不会顶到行框外
        regionRowH = regionRowHeight(leftW);

        // ---- 左栏：列表框 → [搜索行] → 小标题带 → 物品框 ----
        //     列表标题画在页面上方那条小标题带里（captionLeft），所以这里不再单开一条。
        //     列表高度按「有几个仓库」来要（2~7 行），剩下的全部给物品框 —— 仓库少时
        //     不会空一大片，仓库多时也不会把物品区挤没。
        Flow lf = new Flow(content.leftBlock(leftW));
        int rowsWanted = Math.max(2, Math.min(7, RegionCache.list().size()));
        int wantList = rowsWanted * regionRowH + 4;
        int listCapH = Math.max(2 * regionRowH + 4, content.h() * 60 / 100);
        // 「物品」子页里清单才是主角：先给物品框留出最小高度（小标题带 + 3 行），剩下的才给上面的
        // 仓库列表。否则仓库一多，物品框就被挤没 —— 清单会整个消失，面板上只剩一片空白。
        if (itemsPage) {
            // 物品页多两行控件（分类/排序 + 翻页），一起先留出来；真放不下时下面的 takeTop 会各自降级
            int reserve = CAP_H + 2 + 3 * LINE_H + GAP + 2 * (ROW_H_MIN + GAP);
            listCapH = Math.min(listCapH, Math.max(2 * regionRowH + 4, content.h() - reserve));
        }
        listBox = lf.takeTop(Math.min(wantList, Math.min(listCapH, lf.left())), GAP);
        R rem = lf.rest();
        if (rem != null && rem.h() >= CAP_H + 2 + 3 * LINE_H) {
            Flow rf = new Flow(rem);
            if (itemsPage) {
                layoutItemsStack(rf, rem.h());
            } else {
                infoCap = rf.takeTop(CAP_H, 2);
                infoBox = rf.rest();
                splitInfoCap();
            }
        }

        // ---- 右栏：按钮网格贴底，信息块在它上面（放不下就只剩网格）----
        if (!twoCol) {
            // 窄到分不出右栏时，控件行 + 清单接着排在仓库列表下面
            // （宁可挤一点，也别让整页空着或只剩一个列表框）。
            // 物品页也必须走这条：否则 infoBox 一直是 null，物品清单根本画不出来
            // —— 854×480 + guiScale=2（界面 427×240）就是这种情况。
            R rest = lf.rest();
            if (rest != null && rest.h() > ROW_H) {
                Flow bf = new Flow(rest);
                if (itemsPage) {
                    layoutItemsStack(bf, rest.h());
                } else if (boxesPage) {
                    layoutBoxesStack(bf);
                }
            }
            return;
        }
        rightCol = new R(content.x() + leftW + GAP, content.y(), rightW, content.h());

        // 「箱子」子页：右栏 = 控件行（排序 / 只看非空，按钮是控件）+ 附魔搜索行 + 箱子清单
        if (boxesPage) {
            Flow bf = new Flow(rightCol);
            layoutBoxesStack(bf);
            return;
        }

        int need = gridNeed(name);
        if (need <= 0) {
            return;
        }
        int maxFit = Math.max(1, (rightCol.h() + 4) / (ROW_H_MIN + 4));
        gridRows = Math.min(need, maxFit);
        gridTruncated = gridRows < need;
        rowPitch = Math.max(ROW_H_MIN + 4, Math.min(ROW_H + 4, (rightCol.h() + 4) / Math.max(1, gridRows)));
        gridRowH = Math.max(ROW_H_MIN, Math.min(ROW_H, rowPitch - 4));
        int gridH = gridRows * rowPitch - 4;
        if (gridH > rightCol.h()) {
            gridH = rightCol.h();
        }
        gridBox = new R(rightCol.x(), rightCol.bottom() - gridH, rightCol.w(), gridH);
        int infoLines = admin ? 3 : 2;
        int infoNeed = infoLines * LINE_H + 2;
        infoBlock = rightCol.h() - gridH - GAP >= infoNeed
                ? new R(rightCol.x(), rightCol.y(), rightCol.w(), infoNeed) : null;
    }

    /**
     * 「箱子」子页右栏的竖直堆叠：控件行（关键字 / 排序 / 只看非空）→ 附魔搜索行 → 箱子清单。
     *
     * <p>附魔搜索行（0.22.0 · 优化11）是**可选**的：只有清单还能留下三行时才建，
     * 小窗口里宁可没有搜索框，也不能让箱子清单消失。两栏与单栏两条布局路径共用这里，
     * 判据完全一致。
     */
    private void layoutBoxesStack(Flow bf) {
        boxRow = bf.takeTop(ROW_H, GAP);
        boxSearchRow = bf.left() >= ROW_H + GAP + 3 * BOX_ROW_H ? bf.takeTop(ROW_H, GAP) : null;
        boxBox = bf.rest();
    }

    /**
     * 「物品」页左栏的竖直堆叠：搜索行 → 分类/排序行 → 翻页行 → 小标题带 → 清单框。
     *
     * <p>降级顺序：物品区 &gt; 翻页行 &gt; 分类/排序行 &gt; 搜索框 —— 三条都按「还留得下物品区」
     * 来判，按优先级逐条决定建还是不建，然后仍旧按视觉顺序从上往下切。
     *
     * @param availH 这段可用高度（两栏与单栏两条布局路径共用，判据完全一致）
     */
    private void layoutItemsStack(Flow f, int availH) {
        int base = CAP_H + 2 + 3 * LINE_H;
        boolean withPage = availH >= ROW_H_MIN + GAP + base;
        boolean withCtl = availH >= 2 * (ROW_H_MIN + GAP) + base;
        boolean withSearch = availH >= (withCtl ? 2 * (ROW_H_MIN + GAP) : 0)
                + (ROW_H_MIN + 2) + GAP + base;
        searchRow = withSearch ? f.takeTop(ROW_H_MIN + 2, GAP) : null;
        if (withCtl) {
            itemCtlRow = f.takeTop(ROW_H_MIN, GAP);
        }
        if (withPage) {
            pageRow = f.takeTop(ROW_H_MIN, GAP);
        }
        infoCap = f.takeTop(CAP_H, 2);
        infoBox = f.rest();
        if (infoBox == null && infoCap != null) {
            // 连一行清单都分不到：宁可让清单吃掉小标题带，也不能整页空白
            infoBox = infoCap;
            infoCap = null;
        }
        splitInfoCap();
    }

    /** 小标题带切左右两块：右块按统计文字实际像素宽来分（文字长就多给，短就还给左标题） */
    private void splitInfoCap() {
        if (infoCap == null) {
            infoCapLeft = null;
            infoCapRight = null;
            return;
        }
        int needW = infoStatWidth();
        int rightCapW = Math.max(48, Math.min(needW, Math.max(48, infoCap.w() - 60)));
        infoCapRight = infoCap.rightBlock(rightCapW);
        infoCapLeft = new R(infoCap.x(), infoCap.y(),
                Math.max(0, infoCap.w() - rightCapW - GAP), infoCap.h());
    }

    /** 第 1 页「取货」：筛选行 / 清单 / 底部下单行 */
    private void layoutPick() {
        Flow f = new Flow(content);
        orderRow = f.takeBottom(ROW_H, GAP);
        pickRow = f.takeTop(ROW_H, GAP);
        pickCap = f.takeTop(CAP_H, 2);
        pickBox = f.rest();
        if (orderRow == null && content.h() > 0) {
            int hh = Math.min(ROW_H, content.h());
            orderRow = new R(content.x(), content.bottom() - hh, content.w(), hh);
        }
        if (pickDd != null) {
            pickDd.layout();
        }
    }

    /** 第 2 页「搬运工」：按二级页签分工（标题画在页面上方的小标题带里） */
    private void layoutPorter() {
        Flow f = new Flow(content);
        // 底部那排按钮两个子页都有（名册：新增/全部停下；整理：整理仓库一个按钮）
        footRow = f.takeBottom(f.left() >= ROW_H + GAP ? ROW_H : ROW_H_MIN, GAP);

        // 子页「整理」：第一行选要整理的仓库，剩下的高度全部给说明文字
        if ("整理".equals(subName())) {
            taskRow = f.takeBottom(ROW_H, GAP);
            notesBox = f.takeBottom(Math.max(2 * LINE_H, Math.min(6 * LINE_H, f.left() / 2)), GAP);
            return;
        }

        assignRow = f.takeBottom(ROW_H, GAP);
        rowsBox = f.rest();
        rowH = fitRowH(rowsBox, Math.max(1, ClientSnapshot.bots().size()), 22);

        // 选中项：默认第一行；名单变了（假人被删/换了）就回退到第一行
        List<ClientSnapshot.Bot> bots = ClientSnapshot.bots();
        int idx = -1;
        for (int i = 0; i < bots.size(); i++) {
            if (bots.get(i).name.equals(pickBot)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            pickBot = bots.isEmpty() ? "" : bots.get(0).name;
            pickRegion = bots.isEmpty() ? "" : (bots.get(0).region == null ? "" : bots.get(0).region);
        }

        if (assignRow != null && !assignRow.empty()) {
            int okW = Math.max(52, Math.min(72, assignRow.w() / 5));
            int pickW = Math.max(70, Math.min(200, assignRow.w() * 40 / 100));
            int labelW = Math.max(0, assignRow.w() - okW - pickW - GAP * 2);
            assignLabel = new R(assignRow.x(), assignRow.y(), labelW, assignRow.h());
            assignPick = new R(assignRow.right() - okW - GAP - pickW, assignRow.y(), pickW, assignRow.h());
            assignOk = new R(assignRow.right() - okW, assignRow.y(), okW, assignRow.h());
        }
        layoutAssignList();
    }

    /**
     * 展开中的仓库清单（弹出层）：优先向上展开（分配那一行贴着底部），
     * 上面实在放不下才向下；高度只取「面板里真正有的空间」，绝不出面板。
     */
    private void layoutAssignList() {
        assignList = null;
        assignRows = 0;
        if (!pickOpen || assignPick == null || assignPick.empty()) {
            return;
        }
        int count = RegionCache.list().size();
        if (count <= 0) {
            return;
        }
        int lineH = LINE_H + 3;
        int want = Math.min(count, 8) * lineH + 4;
        int above = Math.max(0, assignPick.y() - panel.y() - GAP);
        int below = Math.max(0, panel.bottom() - assignPick.bottom() - GAP);
        boolean up = want <= above || above >= below;
        int room = up ? above : below;
        int h = Math.min(want, room);
        if (h < lineH + 4) {
            return;   // 一个条目都放不下：不展开（点选择框不会有反应，也不会画出越界的框）
        }
        int w = Math.max(assignPick.w(), 84);
        int x = Math.min(assignPick.x(), panel.right() - GAP - w);
        x = Math.max(panel.x(), x);
        assignList = up ? new R(x, assignPick.y() - 2 - h, w, h)
                : new R(x, assignPick.bottom() + 2, w, h);
        assignRows = Math.max(0, (h - 4) / lineH);
        assignScroll = Math.max(0, Math.min(assignScroll, Math.max(0, count - assignRows)));
    }

    /** 第 3 页「权限」：一个账号一行 / 底部两行说明（标题画在页面上方的小标题带里） */
    private void layoutPerm() {
        Flow f = new Flow(content);
        notesBox = f.takeBottom(2 * LINE_H + 2, GAP);
        rowsBox = f.rest();
        rowH = fitRowH(rowsBox, Math.max(1, ClientSnapshot.accounts().size()), 22);
    }

    /** 一行行高：22 起，放不下按条目数均分，最低 14（再放不下就少画几行） */
    private static int fitRowH(R box, int count, int want) {
        if (box == null || box.h() <= 0) {
            return 0;
        }
        int h = Math.min(want, box.h() / count);
        return h < ROW_H_MIN ? ROW_H_MIN : h;
    }

    // ------------------------------------------------------------ 内容驱动的尺寸

    /**
     * 给一行页签分宽度：宽 = max(最小宽, 文字实测宽 + 左右内边距)，余下的宽度均分回去。
     *
     * <p>挤不下时的降级顺序（和用户要求一致）：① 压间距（到 {@code gapMin}）② 压内边距
     * ③ 等比缩小每个页签（不低于 {@code minW}）。所以换语言、换窗口大小时文字都还能居中显示，
     * 实在放不下才由 {@code fit()} 截断（并写一条文字自检日志）。
     *
     * @param gapOut 长度 1 的数组，回写这次实际用的间距（画/点都要用同一个）
     */
    private int[] fitCells(String[] names, R band, int padX, int minW, int preferredGap, int gapMin, int[] gapOut) {
        int n = names == null ? 0 : names.length;
        int gap = preferredGap;
        if (gapOut.length > 0) {
            gapOut[0] = gap;
        }
        if (n == 0 || band == null || band.w() <= 0) {
            return new int[0];
        }
        int avail = band.w();
        int[] nat = new int[n];
        int sumNat = 0;
        for (int i = 0; i < n; i++) {
            nat[i] = Math.max(minW, this.font.width(names[i]) + padX * 2);
            sumNat += nat[i];
        }
        // ① 间距：能放多宽就多宽，但不小于下限
        if (n > 1) {
            int room = Math.max(0, (avail - sumNat) / (n - 1));
            gap = Math.max(gapMin, Math.min(preferredGap, room));
        } else {
            gap = 0;
        }
        int total = sumNat + gap * (n - 1);
        int[] w = new int[n];
        if (total <= avail) {
            // 富余的宽度均分给每个页签（多出来的当留白不好看，摊给每个格子更稳）
            int extra = avail - total;
            for (int i = 0; i < n; i++) {
                w[i] = nat[i] + extra / n + (i < extra % n ? 1 : 0);
            }
        } else {
            // ② 内边距已经体现在 nat 里；③ 等比缩到刚好塞下，最少 minW
            int space = Math.max(n * minW, avail - gap * (n - 1));
            int acc = 0;
            for (int i = 0; i < n; i++) {
                w[i] = Math.max(minW, (int) ((long) nat[i] * space / Math.max(1, sumNat)));
                acc += w[i];
            }
            int diff = space - acc;
            for (int guard = 0; diff > 0 && guard < n * 40; guard++) {
                w[guard % n]++;
                diff--;
            }
            for (int guard = 0; diff < 0 && guard < n * 40; guard++) {
                if (w[guard % n] > minW) {
                    w[guard % n]--;
                    diff++;
                }
            }
        }
        if (gapOut.length > 0) {
            gapOut[0] = gap;
        }
        return w;
    }

    /** 一行格子（页签）的矩形：宽度不是平均分，而是上面算出来的那一份 */
    private static R cellRect(R band, int[] cells, int gap, int i) {
        if (band == null || band.empty() || cells == null || i < 0 || i >= cells.length) {
            return null;
        }
        int x = band.x();
        for (int k = 0; k < i; k++) {
            x += cells[k] + gap;
        }
        return new R(x, band.y(), cells[i], band.h());
    }

    /** 仓库列表项里「详情」那部分拆成几段（按重要性排序：数量 → 尺寸 → 高度 → 世界） */
    private static List<String> metaSegments(RegionCache.Entry e) {
        List<String> seg = new ArrayList<>();
        ClientSnapshot.Region vr = ClientSnapshot.find(e.name);
        if (vr != null) {
            seg.add(vr.items.size() + " 种/" + vr.totalItems + " 个");
        }
        for (String part : e.sizeText().split(" · ")) {
            seg.add(part);
        }
        seg.add(e.dimensionText());
        return seg;
    }

    /**
     * 左栏第 0 行（合成的「全部仓库」）下面那 1~2 行小字（0.22.0 · 优化10）。
     *
     * <p>它不是一个真实的仓库，所以不能走 {@link #metaLines}：这里只说清楚「默认范围」和仓库个数。
     */
    private List<String> allRegionsMeta(int regionCount) {
        List<String> out = new ArrayList<>();
        out.add("跨所有仓库统计（默认）");
        out.add(regionCount <= 0 ? "暂无仓库" : ("共 " + regionCount + " 个仓库"));
        return out;
    }

    /**
     * 把「详情」按可用宽度折成 1~2 行：整段整段地放到下一行，绝不在段中间硬切；
     * 单段本身超宽、或超过两行时，才对最后一行做截断（带省略号）。
     */
    private List<String> metaLines(RegionCache.Entry e, int width) {
        int room = Math.max(16, width);
        List<String> seg = metaSegments(e);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : seg) {
            String cand = cur.length() == 0 ? s : cur + " · " + s;
            if (cur.length() > 0 && this.font.width(cand) > room) {
                out.add(cur.toString());
                cur.setLength(0);
                cand = s;
            }
            cur.setLength(0);
            cur.append(cand);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        if (out.isEmpty()) {
            out.add("");
        }
        if (out.size() > 2) {
            StringBuilder second = new StringBuilder(out.get(1));
            for (int i = 2; i < out.size(); i++) {
                second.append(" · ").append(out.get(i));
            }
            List<String> two = new ArrayList<>();
            two.add(out.get(0));
            two.add(second.toString());
            out = two;
        }
        for (int i = 0; i < out.size(); i++) {
            if (this.font.width(out.get(i)) > room) {
                out.set(i, fit(out.get(i), room));
            }
        }
        return out;
    }

    /** 仓库列表至少要这么宽才放得下「名字」和「详情第一行」（文字实测，不靠猜） */
    private int listNeedWidth() {
        int w = 48;
        for (RegionCache.Entry e : RegionCache.list()) {
            w = Math.max(w, this.font.width(e.name));
            List<String> seg = metaSegments(e);
            if (!seg.isEmpty()) {
                w = Math.max(w, this.font.width(seg.get(0)));
            }
        }
        return w + 16;
    }

    /** 仓库列表每行的高度：名字一行 + 详情最多两行，按实际折行结果算 */
    private int regionRowHeight(int listW) {
        int room = Math.max(24, listW - 12);
        int lines = 1;
        for (RegionCache.Entry e : RegionCache.list()) {
            lines = Math.max(lines, metaLines(e, room).size());
        }
        return LINE_H * (1 + Math.max(1, Math.min(2, lines))) + 6;
    }

    // ------------------------------------------------------------ 区域自检

    /**
     * 布局自检：任何区域跑出面板、或两个同级区域相交，就写一条警告日志。
     * 平时一行都不输出；以后谁改坏了布局，日志里立刻能看到，不用靠肉眼看截图。
     */
    private void layoutCheck() {
        List<R> leaves = new ArrayList<>();
        for (R r : new R[]{headerTitle, tabs, subTabs, captionLeft, captionRight, statusBox, closeBtn, listBox,
                searchRow, boxRow, boxSearchRow, boxBox, infoCapLeft, infoCapRight, infoBox, infoBlock, gridBox,
                pickRow, pickCap, pickBox, orderRow, rowsBox, footRow, notesBox, assignLabel, assignPick, assignOk}) {
            if (r == null || r.empty()) {
                continue;
            }
            if (!r.inside(panel)) {
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 布局自检：区域 {} 跑出面板 {}（界面 {}x{}，页签 {}）",
                        r, panel, this.width, this.height, tab);
            }
            leaves.add(r);
        }
        for (int i = 0; i < leaves.size(); i++) {
            for (int j = i + 1; j < leaves.size(); j++) {
                if (leaves.get(i).hits(leaves.get(j))) {
                    WarehouseMod.LOGGER.warn("[warehouse-keeper] 布局自检：区域 {} 与 {} 相交"
                            + "（界面 {}x{}，页签 {}）", leaves.get(i), leaves.get(j), this.width, this.height, tab);
                }
            }
        }
        // 文字自检：二级页签是最窄的一行，放不下就必须报出来（而不是画出去）
        String[] subs = subNames();
        for (int i = 0; i < subs.length; i++) {
            R r = subRect(i);
            if (r == null || r.empty()) {
                continue;
            }
            String label = subLabel(i);
            int tw = this.font.width(label);
            if (tw > Math.max(0, r.w() - 4)) {
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 文字自检：子页签「{}」宽 {} > 区域宽 {}（界面 {}x{}，页签 {}）",
                        label, tw, Math.max(0, r.w() - 4), this.width, this.height, tab);
            }
        }
        // 顶级页签同理（仓库 / 取货 / 搬运工 2 / 权限 3）：宽度是算出来的，放不下必须报
        String[] tops = tabNames();
        for (int i = 0; i < tops.length; i++) {
            R r = tabRect(i);
            if (r == null || r.empty()) {
                continue;
            }
            int tw = this.font.width(tops[i]);
            if (tw > Math.max(0, r.w() - 4)) {
                WarehouseMod.LOGGER.warn("[warehouse-keeper] 文字自检：页签「{}」宽 {} > 区域宽 {}（界面 {}x{}，页签 {}）",
                        tops[i], tw, Math.max(0, r.w() - 4), this.width, this.height, tab);
            }
        }
    }

    // ================================================================== 建控件

    @Override
    protected void init() {
        if (pickDd == null) {
            // 「全部仓库」是第 0 项，其余按 RegionCache 的顺序；默认落到当前选中的那个仓库上
            pickDd = new Dropdown();
            taskDd = new Dropdown();
        }
        layout();   // ← 唯一的几何来源；resize / GUI Scale 变化时这里会再跑一遍
        editHints.clear();

        if (closeBtn != null && !closeBtn.empty()) {
            btn("关闭", closeBtn, b -> onClose());
        }
        switch (tab) {
            case 0 -> initRegions();
            case 1 -> initPick();
            case 2 -> initPorter();
            default -> initPerm();
        }
        clampSel();
    }

    private Button btn(String label, R r, Button.OnPress press) {
        // 按钮里的字也归我们管：原版按钮不会裁剪标签，字长了就直接画到按钮外面去。
        int room = Math.max(8, r.w() - 6);
        String text = pickFit(room, label, fit(label, room));
        if (!text.equals(label)) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 文字自检：按钮「{}」在宽 {} 的按钮里放不下，已缩成「{}」（界面 {}x{}，页签 {}）",
                    label, r.w(), text, this.width, this.height, tab);
        }
        return addRenderableWidget(Button.builder(Component.literal(text), press)
                .bounds(r.x(), r.y(), r.w(), r.h())
                .build());
    }

    /**
     * 从长到短挑一个装得下的提示文字（占位符宁可换短句，也不要被输入框切一半）。
     * 全都装不下时返回空串（输入框就只显示光标）。
     */
    private String hintFor(int width, String... candidates) {
        int room = Math.max(0, width - 8);
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && this.font.width(c) <= room) {
                return c;
            }
        }
        return "";
    }

    /**
     * 记下输入框的占位提示，并把原版 EditBox 的 hint 置空。
     * 原版 hint 是左对齐的，跟「文字默认在自己框里居中」不一致；置空后由
     * {@link #drawEditHint} 在控件画完之后居中重画一遍（不置空会画两遍）。
     */
    private void rememberHint(EditBox box, String hint) {
        if (box == null) {
            return;
        }
        box.setHint(Component.empty());
        if (hint != null && !hint.isEmpty()) {
            editHints.add(new EditHint(box, hint));
        }
    }

    /** 输入框的占位提示：跟别的文字一样，在自己那一格里居中。 */
    private void drawEditHint(GuiGraphicsExtractor g, EditBox box, String hint) {
        if (box == null || hint == null || hint.isEmpty() || !box.getValue().isEmpty()) {
            return;
        }
        textCenter(g, new R(box.getX(), box.getY(), box.getWidth(), box.getHeight()), hint, 0xFF8A8A8A);
    }

    /** 句子版的 {@link #hintFor}：都放不下就用最短的那句（它再被截断也只会短一点，不会只剩半句话） */
    private String pickFit(int width, String... candidates) {
        for (String c : candidates) {
            if (this.font.width(c) <= width) {
                return c;
            }
        }
        return candidates[candidates.length - 1];
    }

    /** 网格里的第 i 行（y 从网格底部往上排，i=0 是最下面一行） */
    private R gridRowFromBottom(int i) {
        return new R(gridBox.x(), gridBox.bottom() - (i + 1) * rowPitch + 4, gridBox.w(), gridRowH);
    }

    private R gridLeft(R row) {
        int half = (row.w() - GAP) / 2;
        return new R(row.x(), row.y(), Math.max(1, half), row.h());
    }

    private R gridRight(R row) {
        int half = (row.w() - GAP) / 2;
        return new R(row.x() + half + GAP, row.y(), Math.max(1, row.w() - half - GAP), row.h());
    }

    /**
     * 「仓库」页的控件：按二级页签分派。
     *
     * <p>每个子页只建自己那几个控件 —— 这样按钮排得开，也不会再出现「窗口过小：其余按钮已省略」。
     * 控件都是从**底部行往上**建的：万一高度被截断，先保住最要紧的那个动作。
     */
    private void initRegions() {
        switch (subName()) {
            case "新建" -> initRegionsNew();
            case "扩建/缩小" -> initRegionsGrowShrink();
            case "物品" -> initRegionsItems();
            case "箱子" -> initRegionsBoxes();
            case "维护" -> initRegionsMaint();
            default -> initRegionsOverview();
        }
    }

    /** 子页「概览」：扫描仓库 + 显示边界（这只仓库的基本信息画在右栏信息块里） */
    private void initRegionsOverview() {
        if (QueryClient.supported()) {
            // 打开这一页就问一次总览：概览页下面的「索引状态」段用它
            QueryClient.open(QueryClient.Request.of(ViewQueryPayload.KIND_OVERVIEW, queryRegion()));
        }
        if (gridBox == null || gridBox.empty() || gridRows <= 0) {
            return;
        }
        R row = gridRowFromBottom(0);
        if (admin) {
            btn("扫描仓库", gridLeft(row), b -> withRegionOrAll(name ->
                    run(name.isEmpty() ? "warehouse scan" : "warehouse scan " + q(name), "已让游戏开始扫描")));
            borderBtn = btn(borderLabel(), gridRight(row), b -> toggleBorder());
        } else {
            borderBtn = btn(borderLabel(), row, b -> toggleBorder());
        }
    }

    /** 子页「新建」：底行是「名字 + 新建仓库」，上面一行是点1 / 点2 */
    private void initRegionsNew() {
        if (gridBox == null || gridBox.empty() || gridRows <= 0) {
            return;
        }
        int rows = gridRows;
        R row = gridRowFromBottom(0);
        int nameW = Math.min(Math.max(80, row.w() - 96), Math.max(40, row.w() / 2));
        nameBox = new EditBox(this.font, row.x(), row.y() + 1, nameW, Math.max(8, row.h() - 2),
                Component.literal("新仓库名字"));
        nameBox.setMaxLength(24);
        nameBox.setValue(nameText);
        rememberHint(nameBox, hintFor(nameW,
                "新仓库名字（支持中文）", "新仓库名字（可中文）", "新仓库名字", "名字"));
        nameBox.setResponder(v -> nameText = v);
        addRenderableWidget(nameBox);
        btn("新建仓库", new R(row.right() - Math.max(60, row.w() - nameW - GAP), row.y(),
                Math.max(60, row.w() - nameW - GAP), row.h()), b -> {
                    String name = clean(nameText);
                    if (name.isEmpty()) {
                        status = "请先在上方输入框中填写仓库名字（支持中文；名字里有空格也可以），再点击“新建仓库”。";
                        return;
                    }
                    run("warehouse region save " + q(name), "已按你当前设置的点1/点2 建了仓库 " + name);
                });
        if (rows > 1) {
            R row0 = gridRowFromBottom(1);
            btn("点1 设在此处", gridLeft(row0), b -> run("warehouse pos1", "已把点1设在当前位置"));
            btn("点2 设在此处", gridRight(row0), b -> run("warehouse pos2", "已把点2设在当前位置"));
        }
    }

    /**
     * 子页「扩建/缩小」（0.22.0 · 优化8：原名就叫「扩建」）。
     *
     * <p>底行是共用的「方向 + 格数 + 扩建」，往上依次是「缩小」（同一个方向/格数，带二次确认）、
     * 「扩建至我当前所站位置」、「缩到我所站位置」、「并进新圈范围」。
     * 四种操作都必须点名一个具体仓库，所以走 {@link #withOneRegion}（选中「全部仓库」时给提示并拒绝）。
     */
    private void initRegionsGrowShrink() {
        if (gridBox == null || gridBox.empty() || gridRows <= 0) {
            return;
        }
        int rows = gridRows;
        R row = gridRowFromBottom(0);
        int countW = Math.min(46, Math.max(28, row.w() / 5));
        int expandW = Math.min(64, Math.max(44, row.w() / 5));
        dirBtn = btn("方向：" + DIR_NAMES[dir],
                new R(row.x(), row.y(), Math.max(40, row.w() - countW - expandW - GAP * 2), row.h()), b -> {
                    dir = (dir + 1) % DIR_NAMES.length;
                    b.setMessage(Component.literal("方向：" + DIR_NAMES[dir]));
                });
        countBox = new EditBox(this.font, row.right() - expandW - GAP - countW, row.y() + 1, countW,
                Math.max(8, row.h() - 2), Component.literal("格数"));
        countBox.setMaxLength(4);
        countBox.setValue(countText);
        countBox.setResponder(v -> countText = v);
        addRenderableWidget(countBox);
        btn("扩建", new R(row.right() - expandW, row.y(), expandW, row.h()), b -> withOneRegion(name -> {
            int amount = parseCount();
            run("warehouse region grow " + q(name) + " " + DIR_KEYS[dir] + " " + amount,
                    "已把仓库 " + name + " 朝" + DIR_NAMES[dir] + "扩 " + amount + " 格");
        }));
        if (rows > 1) {
            R row1 = gridRowFromBottom(1);
            R growTo = gridRight(row1);
            R shrink = gridLeft(row1);
            // 缩小：与扩建共用「方向 + 格数」，但因为不可逆，先弹一次二次确认
            btn(pickFit(shrink.w() - 6, "缩小", "缩"), shrink, b -> withOneRegion(name -> {
                int amount = parseCount();
                ask("缩小仓库「" + name + "」", "将把仓库「" + name + "」朝" + DIR_NAMES[dir] + "缩 " + amount
                        + " 格（箱子及其中的物品不会被改动）。确定要继续吗？",
                        () -> run("warehouse region shrink " + q(name) + " " + DIR_KEYS[dir] + " " + amount,
                                "已把仓库 " + name + " 朝" + DIR_NAMES[dir] + "缩 " + amount + " 格"));
            }));
            btn(pickFit(growTo.w() - 6, "扩建至我当前所站位置", "扩建至我所站位置", "扩建至位置", "扩建到位置"),
                    growTo, b -> withOneRegion(name -> run("warehouse region grow " + q(name),
                            "已把仓库 " + name + " 扩建至你的位置")));
        }
        if (rows > 2) {
            R row2 = gridRowFromBottom(2);
            R shrinkTo = gridLeft(row2);
            R merge = gridRight(row2);
            btn(pickFit(shrinkTo.w() - 6, "缩到我所站位置", "缩到我所站位置", "缩到我的位置", "缩到位置"),
                    shrinkTo, b -> withOneRegion(name ->
                            ask("缩到我所站位置", "将把仓库「" + name + "」的范围收缩到你所站的位置"
                                    + "（箱子及其中的物品不会被改动）。确定要继续吗？",
                                    () -> run("warehouse region shrink " + q(name),
                                            "已把仓库 " + name + " 缩到你的位置"))));
            btn(pickFit(merge.w() - 6, "并进新圈范围", "并进新圈范围", "并进新范围", "并进范围"),
                    merge, b -> withOneRegion(name ->
                            ask("并进新圈范围", "将把你新圈定的范围并入仓库「" + name + "」，此仓库的范围会变大"
                                    + "（箱子内的物品不动，仅定义发生变化）。确定要继续吗？",
                                    () -> run("warehouse region merge " + q(name), "已把你圈的新范围并进 " + name))));
        }
    }

    /**
     * 子页「物品」：搜索框 + 分类/排序行 + 翻页行（清单本身是画出来的）。
     *
     * <p>数据走按需查询 kind=2；点某一行进「物品详情」态（同一子页内，kind=3），
     * 这时分类/排序行整条换成「返回物品列表」。
     */
    private void initRegionsItems() {
        if (!itemDetailKey.isEmpty()) {
            // 详情态：只有「返回」和一次 kind=3 查询
            if (itemCtlRow != null && !itemCtlRow.empty()) {
                btn("← 返回物品列表", itemCtlRow, b -> {
                    itemDetailKey = "";
                    itemDetailScroll = 0;
                    rebuildWidgets();
                });
            }
            if (QueryClient.supported()) {
                QueryClient.open(itemDetailRequest());
            }
            return;
        }
        if (QueryClient.supported()) {
            QueryClient.open(itemRequest(itemPage));
        }
        if (itemCtlRow != null && !itemCtlRow.empty()) {
            int catW = Math.min(150, Math.max(76, itemCtlRow.w() * 46 / 100));
            catDd = catDd == null ? new Dropdown() : catDd;
            catDd.prefix = "分类：";
            catDd.entries = catList();
            catDd.clamp();
            catDd.btnRect = new R(itemCtlRow.x(), itemCtlRow.y(), catW, itemCtlRow.h());
            catDd.button = btn(catDd.label(), catDd.btnRect, b -> catDd.toggle());
            catDd.layout();
            int sortW = Math.min(140, Math.max(64, itemCtlRow.w() - catW - GAP));
            btn(itemSortLabel(), new R(itemCtlRow.right() - sortW, itemCtlRow.y(), sortW, itemCtlRow.h()), b -> {
                itemSort = (itemSort + 1) % 4;
                b.setMessage(Component.literal(itemSortLabel()));
                itemPage = 1;
                infoScroll = 0;
                if (QueryClient.supported()) {
                    QueryClient.request(itemRequest(1));
                }
            });
        }
        if (pageRow != null && !pageRow.empty()) {
            int btnW = Math.min(48, Math.max(28, pageRow.w() / 6));
            btn("◀", new R(pageRow.x(), pageRow.y(), btnW, pageRow.h()), b -> goItemPage(itemPage - 1));
            btn("▶", new R(pageRow.right() - btnW, pageRow.y(), btnW, pageRow.h()), b -> goItemPage(itemPage + 1));
        }
        if (searchRow == null || searchRow.empty()) {
            return;
        }
        searchBox = new EditBox(this.font, searchRow.x(), searchRow.y() + 1, searchRow.w(),
                Math.max(8, searchRow.h() - 2), Component.literal("搜索物品"));
        searchBox.setMaxLength(48);
        searchBox.setValue(searchText);
        rememberHint(searchBox, hintFor(searchRow.w(),
                "在仓库内搜索物品：例 钻石", "仓库内搜物品：例 钻石", "搜物品", "搜索"));
        searchBox.setResponder(v -> {
            searchText = v;
            infoScroll = 0;
            itemPage = 1;
            if (QueryClient.supported()) {
                QueryClient.request(itemRequest(1));
            }
        });
        addRenderableWidget(searchBox);
    }

    /** 子页「箱子」：关键字框 + 排序 / 只看非空（清单走 kind=4；点一行进容器详情态 kind=5） */
    private void initRegionsBoxes() {
        if (boxDetailKey.isEmpty() && QueryClient.supported()) {
            QueryClient.open(boxRequest());
        }
        if (!boxDetailKey.isEmpty()) {
            // 详情态：控件行整条换成「返回箱子列表」
            if (boxRow != null && !boxRow.empty()) {
                btn("← 返回箱子列表", boxRow, b -> {
                    boxDetailKey = "";
                    boxDetailScroll = 0;
                    rebuildWidgets();
                });
            }
            if (QueryClient.supported()) {
                QueryClient.open(boxDetailRequest());
            }
            return;
        }
        if (boxRow == null || boxRow.empty()) {
            return;
        }
        int kwW = 0;
        if (QueryClient.supported()) {
            kwW = Math.min(150, Math.max(70, boxRow.w() * 34 / 100));
            boxFilterBox = new EditBox(this.font, boxRow.x(), boxRow.y() + 1, kwW,
                    Math.max(8, boxRow.h() - 2), Component.literal("坐标或方块名"));
            boxFilterBox.setMaxLength(48);
            boxFilterBox.setValue(boxFilter);
            rememberHint(boxFilterBox, hintFor(kwW, "坐标或方块名：例 12,-60,4", "坐标或方块名", "筛选", ""));
            boxFilterBox.setResponder(v -> {
                boxFilter = v;
                boxScroll = 0;
                if (QueryClient.supported()) {
                    QueryClient.request(boxRequest());
                }
            });
            addRenderableWidget(boxFilterBox);
        }
        int left = boxRow.x() + (kwW > 0 ? kwW + GAP : 0);
        int restW = Math.max(0, boxRow.right() - left);
        int sortW = Math.min(150, Math.max(60, restW * 55 / 100));
        btn(sortLabel(), new R(left, boxRow.y(), sortW, boxRow.h()), b -> {
            boxSort = (boxSort + 1) % 3;
            b.setMessage(Component.literal(sortLabel()));
            boxScroll = 0;
        });
        int filterW = Math.min(120, Math.max(56, restW - sortW - GAP));
        btn(boxFilterLabel(), new R(boxRow.right() - filterW, boxRow.y(), filterW, boxRow.h()), b -> {
            boxNonEmptyOnly = !boxNonEmptyOnly;
            b.setMessage(Component.literal(boxFilterLabel()));
            boxScroll = 0;
        });

        // 0.22.0 · 优化11：全局搜索入口（附魔 / 自定义名）。点一下＝替玩家敲一条
        // `/warehouse enchants find <输入>`，结果由服务端输出在聊天栏 —— 这里不新增任何协议/请求类型，
        // 也不依赖按需查询通道（老服务端照样能用），所以放在 QueryClient.supported() 判断之外。
        if (boxSearchRow != null && !boxSearchRow.empty()) {
            int btnW = Math.min(160, Math.max(96, boxSearchRow.w() * 32 / 100));
            int inW = Math.max(60, boxSearchRow.w() - btnW - GAP);
            boxEnchBox = new EditBox(this.font, boxSearchRow.x(), boxSearchRow.y() + 1, inW,
                    Math.max(8, boxSearchRow.h() - 2), Component.literal("附魔 / 自定义名"));
            boxEnchBox.setMaxLength(48);
            boxEnchBox.setValue(boxEnchText);
            rememberHint(boxEnchBox, hintFor(inW, "附魔 / 自定义名：例 锋利 · 结果输出在聊天栏",
                    "例 锋利 · 结果输出在聊天栏", "附魔 / 自定义名", "搜附魔"));
            boxEnchBox.setResponder(v -> boxEnchText = v);
            addRenderableWidget(boxEnchBox);
            btn(pickFit(btnW - 6, "搜索附魔/自定义名", "搜附魔/自定义名", "搜索附魔", "搜附魔"),
                    new R(boxSearchRow.right() - btnW, boxSearchRow.y(), btnW, boxSearchRow.h()),
                    b -> searchEnchants());
        }
    }

    /**
     * 「箱子」页的附魔 / 自定义名全局搜索（0.22.0 · 优化11）。
     *
     * <p>面板只负责把玩家写的词拼进指令：{@code warehouse enchants find <词>}，
     * 服务端的输出直接进聊天栏（{@link #run} 已经挂着聊天栏回执的提示通道）。
     */
    private void searchEnchants() {
        String kw = boxEnchText == null ? "" : boxEnchText.replace('\n', ' ').replace('\r', ' ').trim();
        if (kw.isEmpty()) {
            status = "请先在「搜索附魔/自定义名」输入框里填一个附魔名或物品自定义名。";
            return;
        }
        run("warehouse enchants find " + kw, "已请求搜索「" + kw + "」，结果输出在聊天栏。");
    }

    /**
     * 子页「维护」：底行是「重新扫描 + 显示/隐藏边界（按当前仓库）」，上面一行是
     * 「标签栏位置（循环切换）+ 删除仓库」。
     */
    private void initRegionsMaint() {
        if (gridBox == null || gridBox.empty() || gridRows <= 0) {
            return;
        }
        int rows = gridRows;
        R row = gridRowFromBottom(0);
        btn("重新扫描", gridLeft(row), b -> withRegionOrAll(name ->
                run(name.isEmpty() ? "warehouse scan" : "warehouse scan " + q(name), "已让游戏开始扫描")));
        borderBtn = btn(borderLabel(), gridRight(row), b -> toggleBorder());
        // 优化10：边界是「按仓库」的，没选中具体仓库时这个按钮点不动（文案里也写明了）
        borderBtn.active = !selectedRegion().isEmpty();
        if (rows > 1) {
            R up = gridRowFromBottom(1);
            // BUG1：标签栏停靠位置（客户端 config，逐个玩家自己设），点一下循环 自动→右→左→上→下
            btn(tagBarDockLabel(), gridLeft(up), b -> cycleTagBarDock(b));
            btn("删除仓库", gridRight(up), b -> withOneRegion(name ->
                    ask("删除仓库「" + name + "」", "将仅删除此仓库的「范围定义」：箱子及其中的物品不会被改动，"
                            + "搬运工亦不会操作它们。删除后可重新圈定范围再次创建。确定要删除吗？",
                            () -> run("warehouse region remove " + q(name), "已删除仓库 " + name))));
        }
    }

    /** 「维护」页那个循环按钮上的字（按钮挪不动，只换文案） */
    private String tagBarDockLabel() {
        return "标签栏位置：" + ClientPrefs.label(ClientPrefs.dock());
    }

    /**
     * BUG1：循环切换箱子标签栏的停靠位置，并立刻写客户端 config。
     *
     * <p>标签栏每次布局都重新调 {@code TagBarLayout.plan(..., ClientPrefs.dock(), guiScale)}，
     * 所以不用重开箱子界面就已经生效；状态栏那句话就是给玩家确认用的。
     */
    private void cycleTagBarDock(Button b) {
        ClientPrefs prefs = ClientPrefs.get();
        prefs.tagBarDock = ClientPrefs.next(prefs.tagBarDock);
        prefs.save();
        b.setMessage(Component.literal(tagBarDockLabel()));
        status = "标签栏位置已设为「" + ClientPrefs.label(prefs.tagBarDock) + "」，已生效"
                + ("auto".equals(prefs.tagBarDock) ? "（默认：界面尺寸 ≤ 1 时优先贴箱子左侧，避开 JEI）" : "") + "。";
    }

    /** 第 1 页「取货」的控件 */
    private void initPick() {
        if (pickRow != null && !pickRow.empty()) {
            // 仓库改成下拉选择：按钮显示当前仓库，点开就是一份可滚动的仓库清单
            // （比原来点一下轮换一个仓库直观，仓库多的时候也不用点很多次）
            int btnW = Math.min(112, Math.max(72, pickRow.w() * 28 / 100));
            int filterW = Math.max(60, pickRow.w() - btnW - GAP);
            pickQueryBox = new EditBox(this.font, pickRow.x(), pickRow.y() + 1,
                    filterW, Math.max(8, pickRow.h() - 2), Component.literal("筛选"));
            pickQueryBox.setMaxLength(48);
            pickQueryBox.setValue(pickQuery);
            rememberHint(pickQueryBox, hintFor(filterW,
                    "从仓库中选物品，支持名称筛选", "选物品：输入名称筛选", "筛选物品", "筛选"));
            pickQueryBox.setResponder(v -> {
                pickQuery = v;
                pickScroll = 0;
            });
            addRenderableWidget(pickQueryBox);
            pickDd.btnRect = new R(pickRow.right() - btnW, pickRow.y(), btnW, pickRow.h());
            if (!pickDd.touched) {
                pickDd.choice = sel + 1;   // 没手动选过：跟着「仓库」页的选中项走
            }
            pickDd.button = btn(pickDd.label(), pickDd.btnRect, b -> pickDd.toggle());
            pickDd.layout();
        }
        if (orderRow == null || orderRow.empty()) {
            return;
        }
        int countW = 34;
        int orderW = Math.min(64, Math.max(48, orderRow.w() / 6));
        int nameW = Math.max(60, orderRow.w() - countW - orderW - GAP * 2);
        orderBox = new EditBox(this.font, orderRow.x(), orderRow.y() + 1, nameW, Math.max(8, orderRow.h() - 2),
                Component.literal("物品"));
        orderBox.setMaxLength(48);
        orderBox.setValue(orderText);
        rememberHint(orderBox, hintFor(nameW, "取货物品（例 钻石）", "取货物品", "物品"));
        orderBox.setResponder(v -> orderText = v);
        addRenderableWidget(orderBox);

        orderCountBox = new EditBox(this.font, orderRow.x() + nameW + GAP, orderRow.y() + 1, countW,
                Math.max(8, orderRow.h() - 2), Component.literal("数量"));
        orderCountBox.setMaxLength(4);
        orderCountBox.setValue(orderCountText);
        orderCountBox.setResponder(v -> orderCountText = v);
        addRenderableWidget(orderCountBox);

        btn("取货", new R(orderRow.right() - orderW, orderRow.y(), orderW, orderRow.h()), b -> orderNow());
    }

    /** 一行搬运工里那 4 个按钮的矩形（右对齐；宽度按行宽等比，一定放得下） */
    private R[] botButtons(R row) {
        int gaps = GAP * 3;
        int block = Math.min(row.w(), Math.max(150, row.w() * 48 / 100));
        int total = Math.max(4 * 40, block - gaps);
        int[] weight = {29, 25, 23, 23};
        int[] w = new int[4];
        int used = 0;
        for (int k = 0; k < 3; k++) {
            w[k] = Math.max(40, total * weight[k] / 100);
            used += w[k];
        }
        w[3] = Math.max(40, total - used);
        int sum = w[0] + w[1] + w[2] + w[3] + gaps;
        int x = row.right() - Math.min(sum, row.w());
        R[] out = new R[4];
        for (int k = 0; k < 4; k++) {
            out[k] = new R(x, row.y(), w[k], row.h());
            x += w[k] + GAP;
        }
        return out;
    }

    /** 一行权限里那 3 个开关的矩形（右对齐） */
    private R[] permButtons(R row) {
        int gaps = GAP * 2;
        int block = Math.min(row.w(), Math.max(150, row.w() * 44 / 100));
        int total = Math.max(3 * 40, block - gaps);
        int[] weight = {24, 28, 24};
        int[] w = new int[3];
        int used = 0;
        for (int k = 0; k < 2; k++) {
            w[k] = Math.max(38, total * weight[k] / 100);
            used += w[k];
        }
        w[2] = Math.max(38, total - used);
        int sum = w[0] + w[1] + w[2] + gaps;
        int x = row.right() - Math.min(sum, row.w());
        R[] out = new R[3];
        for (int k = 0; k < 3; k++) {
            out[k] = new R(x, row.y(), w[k], row.h());
            x += w[k] + GAP;
        }
        return out;
    }

    /** 第 2 页「搬运工」的控件：按二级页签分派 */
    private void initPorter() {
        if (!admin) {
            return;
        }
        if ("整理".equals(subName())) {
            initPorterTasks();
        } else {
            initPorterRoster();
        }
    }

    /** 子页「名册值守」：一个假人一行（设点位 / 上岗收回 / 停止）+ 分配那一行 */
    private void initPorterRoster() {
        List<ClientSnapshot.Bot> bots = ClientSnapshot.bots();
        int visible = rowsVisible(rowsBox, rowH);
        for (int idx = 0; idx < Math.min(bots.size(), visible); idx++) {
            R row = rowRect(rowsBox, idx, rowH);
            R[] b = botButtons(row);
            ClientSnapshot.Bot bot = bots.get(idx);
            // name = 注册名（指令里必须用它，那是身份）；shown = 给人看的名字（优化7 的自定义显示名）
            String name = bot.name;
            String shown = bot.shown();
            btn(pickFit(Math.max(8, b[0].w() - 6), "设值守点", "设点位", "点位"), b[0], x ->
                    run("warehouse bot spot " + q(name), "已把「" + shown + "」值守点设为你的当前位置"));
            btn(bot.present ? "收回" : "上岗", b[1], x ->
                    run(bot.present ? "warehouse bot kill " + q(name) : "warehouse bot spawn " + q(name),
                            bot.present ? "已让「" + shown + "」退场" : "已让「" + shown + "」上岗"));
            btn("停止", b[2], x ->
                    run("warehouse bot stop " + q(name), "已让「" + shown + "」停止当前任务"));
            btn("删除", b[3], x -> ask("删除搬运工「" + shown + "」",
                    "将从名册里删掉「" + shown + "」：它身上的物品会先收回箱子里，然后这个人形从世界里消失。"
                            + "此操作不可撤销（要用可以再点「＋新增搬运工」）。确定删除吗？",
                    () -> run("warehouse bot remove " + q(name), "已删除搬运工「" + shown + "」")));
        }

        // 分配那一行：选择框（点开仓库清单）+ 确定
        if (assignPick != null && !assignPick.empty()) {
            String label = pickRegion.isEmpty() ? "选择仓库 ▾" : pickRegion + " ▾";
            btn(pickFit(Math.max(8, assignPick.w() - 6), label), assignPick, b -> {
                if (pickBot.isEmpty()) {
                    status = "请先点击上方一行选中搬运工，再选择目标仓库。";
                    return;
                }
                List<RegionCache.Entry> list = RegionCache.list();
                if (list.isEmpty()) {
                    status = "暂无任何仓库。请先到「仓库」页圈定一个仓库。";
                    return;
                }
                pickOpen = !pickOpen;
                assignScroll = 0;
                layoutAssignList();
            });
        }
        if (assignOk != null && !assignOk.empty()) {
            btn("确定", assignOk, b -> {
                if (pickBot.isEmpty()) {
                    status = "请先点击上方一行选中搬运工，再选择目标仓库。";
                    return;
                }
                if (pickRegion.isEmpty()) {
                    status = "请先在左侧选择框里选择目标仓库，再点击“确定”。";
                    return;
                }
                pickOpen = false;
                layoutAssignList();
                run("warehouse bot assign " + q(pickBot) + " " + q(pickRegion),
                        "已让「" + pickBot + "」值守仓库 " + pickRegion);
            });
        }

        if (footRow == null || footRow.empty()) {
            return;
        }
        R add = gridLeft(footRow);
        btn("＋新增搬运工", add, b -> run("warehouse porter add", "已新增一个搬运工"));
        btn("全部停下", gridRight(footRow), b -> run("warehouse porter stop", "已让所有搬运工停止当前任务"));
    }

    /**
     * 子页「整理」：唯一的动作按钮（说明文字画在 {@code notesBox} 里）。
     *
     * <p>动作会先弹一次确认框（会动到箱子里的东西），确认后才发指令。
     * 「清扫地面」已删除（用户拍板：彻底移除自动拾取 / 清扫），所以这排只剩「整理仓库」一个按钮，
     * 它铺满整行 —— 再看格子数去摆位会留出半行空白。
     */
    private void initPorterTasks() {
        if (taskRow != null && !taskRow.empty()) {
            // 整理作用于哪个仓库：一行下拉按钮（第 0 项「全部仓库」= 各搬运工各管自己的值守仓库）
            taskDd.btnRect = new R(taskRow.x(), taskRow.y(), taskRow.w(), taskRow.h());
            if (!taskDd.touched) {
                taskDd.choice = sel + 1;   // 没手动选过：跟着「仓库」页的选中项走
            }
            taskDd.button = btn(taskDd.label(), taskDd.btnRect, b -> taskDd.toggle());
            taskDd.layout();
        }
        if (footRow == null || footRow.empty()) {
            return;
        }
        btn("整理仓库", footRow, b ->
                withTaskRegion(region -> ask("整理仓库" + (region.isEmpty() ? " · 全部仓库" : " · " + region),
                        "搬运工将开始把箱内物品压实、按顺序排好，并把放错箱子的物品搬回其所属箱子。"
                                + "整理过程中请勿再向箱子放入物品。确定现在开始吗？",
                        () -> runTask("warehouse porter tidy", region, "整理仓库"))));
    }

    /** 第 3 页「权限」的控件：一个账号一行三个开关；「审计」子页（sub=1）没有开关 */
    private void initPerm() {
        if (!admin || sub == 1) {
            return;
        }
        List<ClientSnapshot.Account> accounts = ClientSnapshot.accounts();
        int visible = rowsVisible(rowsBox, rowH);
        for (int idx = 0; idx < Math.min(accounts.size(), visible); idx++) {
            R row = rowRect(rowsBox, idx, rowH);
            R[] b = permButtons(row);
            ClientSnapshot.Account acc = accounts.get(idx);
            String name = acc.name;
            btn(acc.take ? "取货 开" : "取货 关", b[0], x ->
                    run("warehouse user perm " + q(name) + " take " + (acc.take ? "off" : "on"),
                            "已" + (acc.take ? "关闭" : "开启") + "「" + name + "」的取货权限"));
            btn(acc.bot ? "指挥 开" : "指挥 关", b[1], x ->
                    run("warehouse user perm " + q(name) + " bot " + (acc.bot ? "off" : "on"),
                            "已" + (acc.bot ? "关闭" : "开启") + "「" + name + "」的指挥搬运工权限"));
            btn(acc.tidy ? "整理 开" : "整理 关", b[2], x ->
                    run("warehouse user perm " + q(name) + " tidy " + (acc.tidy ? "off" : "on"),
                            "已" + (acc.tidy ? "关闭" : "开启") + "「" + name + "」的整理仓库权限"));
        }
    }

    /**
     * 页内下拉框：一个按钮 + 一份展开的候选清单（弹出层，画在最上面）。
     *
     * <p>第 0 项固定是「全部仓库」（不指定仓库）：取货页表示「所有仓库的物品一起看」，
     * 搬运工页「整理」表示「每名搬运工各管自己的值守仓库」。其余项按 {@link RegionCache} 的顺序。
     * 取货页与整理页共用这一份实现，省得两处各写一遍「展开 / 收起 / 滚动 / 点选」。
     */
    private final class Dropdown {
        /** 按钮矩形（由各页 layout 阶段填进来） */
        private R btnRect;
        /** 展开的清单矩形；没展开时是 null */
        private R list;
        /** 清单里能画几行 */
        private int rows;
        private int scroll;
        private Button button;
        private boolean open;
        /** 0 = 全部仓库；k = RegionCache 里第 k 个仓库 */
        private int choice = 1;
        /** 玩家在这个下拉里手动选过没有：没选过就跟随「仓库」页的选中项 */
        private boolean touched;
        /**
         * 非 null = 通用条目列表（「物品」页的分类下拉用），下标 0 就是它列表里的第一条；
         * null = 仓库列表（条目动态来自 {@link RegionCache}）。
         */
        private List<String> entries;
        /** 按钮文字前缀：「仓库：」/「分类：」 */
        private String prefix = "仓库：";
        /** 选中回调（通用条目用；仓库下拉为 null） */
        private java.util.function.IntConsumer onSelect;

        /** 候选条目数（「全部仓库」+ 每个仓库；通用条目就是列表长度） */
        private int count() {
            return entries != null ? entries.size() : 1 + RegionCache.list().size();
        }

        /** 第 i 条候选的名字 */
        private String entryName(int i) {
            if (entries != null) {
                return i >= 0 && i < entries.size() ? entries.get(i) : "";
            }
            if (i <= 0) {
                return "全部仓库";
            }
            List<RegionCache.Entry> regions = RegionCache.list();
            int k = i - 1;
            return k < regions.size() ? regions.get(k).name : "";
        }

        /** 候选列表变了（新增 / 删除仓库、分类增减）之后把选中项收回合法范围 */
        private void clamp() {
            if (entries != null) {
                if (choice < 0 || choice >= Math.max(1, entries.size())) {
                    choice = 0;
                }
                return;
            }
            if (choice < 0 || choice >= count()) {
                choice = 0;
            }
        }

        /** 按钮上的字：当前范围 + 展开方向（画与点共用，改文案只改这一处） */
        private String label() {
            clamp();
            String name = entryName(choice);
            if (name == null || name.isEmpty()) {
                name = "无";
            }
            return prefix + name + (open ? " ▴" : " ▾");
        }

        /** 收起下拉并把按钮上的字改回来（{@code init()} 里也会调一次，保证换页后不留展开态） */
        private void close() {
            open = false;
            list = null;
            rows = 0;
            if (button != null) {
                button.setMessage(Component.literal(label()));
            }
        }

        private void toggle() {
            open = !open;
            scroll = 0;
            if (button != null) {
                button.setMessage(Component.literal(label()));
            }
            if (open) {
                layout();
            } else {
                list = null;
                rows = 0;
            }
        }

        /** 展开时算清单的位置：按钮下面优先，下面放不下才往上；绝不出面板 */
        private void layout() {
            list = null;
            rows = 0;
            if (!open || btnRect == null || btnRect.empty()) {
                return;
            }
            clamp();
            int n = count();
            int lineH = LINE_H + 3;
            int vis = Math.min(n, 8);
            // 条目超过一屏时，给「a~b / n」计数留一条页脚，免得它压住最后一行
            int footer = n > vis ? LINE_H + 2 : 0;
            int want = vis * lineH + 4 + footer;
            int below = Math.max(0, panel.bottom() - btnRect.bottom() - GAP);
            int above = Math.max(0, btnRect.y() - panel.y() - GAP);
            boolean down = want <= below || below >= above;
            int room = down ? below : above;
            int h = Math.min(want, room);
            if (footer > 0 && h - 4 - footer < lineH) {
                footer = 0;   // 地方太挤就先不留页脚，至少让条目露出来
                h = Math.min(vis * lineH + 4, room);
            }
            if (n <= 0 || h < lineH + 4) {
                open = false;   // 一条都放不下：干脆不展开，也不留一个越界的空框
                return;
            }
            int w = Math.max(btnRect.w(), 96);
            int x = Math.min(btnRect.x(), panel.right() - GAP - w);
            x = Math.max(panel.x(), x);
            list = down ? new R(x, btnRect.bottom() + 2, w, h) : new R(x, btnRect.y() - 2 - h, w, h);
            rows = Math.max(0, (h - 4 - footer) / lineH);
            scroll = Math.max(0, Math.min(scroll, Math.max(0, n - rows)));
        }

        /**
         * 选中第 i 条候选（仓库下拉选具体仓库时顺带同步「仓库」页的选中项；不发任何指令）。
         *
         * <p>0.22.0 · 优化10：第 0 条是合成的「全部仓库」，选中它就是 {@code sel = -1} ——
         * 这时**不去碰** {@link RegionBorder}（边界是逐个仓库的，没有「全部」这种开关）。
         */
        private void select(int i) {
            if (i < 0 || i >= count()) {
                return;
            }
            choice = i;
            touched = true;
            if (entries == null) {
                List<RegionCache.Entry> regions = RegionCache.list();
                if (i <= 0) {
                    sel = -1;
                } else {
                    int k = i - 1;
                    if (k < regions.size()) {
                        sel = k;
                        RegionBorder.touch(regions.get(k));
                    }
                }
                pickScroll = 0;
                infoScroll = 0;
            }
            if (onSelect != null) {
                onSelect.accept(i);
            }
            close();
        }

        /**
         * 展开着的时候吃掉一次点击：点条目就选中，点别处就收起来。
         *
         * @return true = 这次点击已经处理掉了；false = 点在按钮本身上（交给原版按钮去 toggle）
         */
        private boolean click(double mx, double my) {
            if (btnRect != null && btnRect.holds(mx, my)) {
                return false;
            }
            if (list != null && !list.empty() && list.holds(mx, my)) {
                int lineH = LINE_H + 3;
                int row = Math.max(0, (int) ((my - list.y() - 2) / lineH));
                if (row < rows) {
                    select(scroll + row);
                }
                return true;
            }
            close();
            return true;
        }

        /** 展开着的时候处理滚轮（清单外的滚轮交给原逻辑） */
        private boolean scrollBy(double mx, double my, double dy) {
            if (list == null || list.empty() || !list.holds(mx, my)) {
                return false;
            }
            int max = Math.max(0, count() - rows);
            scroll = Math.max(0, Math.min(scroll + (dy > 0 ? 1 : -1), max));
            return true;
        }

        private void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            if (list == null || list.empty()) {
                return;
            }
            clamp();
            fillIn(g, list, 0xFF141B26);
            borderIn(g, list, 0xFF4E8BD8);
            final int lineH = LINE_H + 3;
            final int first = scroll;
            final int vis = rows;
            final int n = count();
            clipped(g, list, gg -> {
                for (int i = 0; i < vis; i++) {
                    int idx = first + i;
                    if (idx >= n) {
                        break;
                    }
                    R line = new R(list.x() + 2, list.y() + 2 + i * lineH, Math.max(0, list.w() - 4), lineH);
                    boolean chosen = idx == choice;
                    if (chosen) {
                        fillIn(gg, line, 0xFF1D3350);
                    } else if (line.holds(mouseX, mouseY)) {
                        fillIn(gg, line, 0xFF24313F);
                    }
                    textCenter(gg, line, (chosen ? "✓ " : "") + entryName(idx),
                            chosen ? 0xFFFFFFFF : 0xFFD5DEEA);
                }
                if (n > vis) {
                    textRight(gg, new R(list.x(), list.bottom() - LINE_H - 2, Math.max(0, list.w() - 3), LINE_H),
                            (first + 1) + "~" + Math.min(n, first + vis) + " / " + n, 0xFF7C8CA1);
                }
            });
        }
    }

    /** 取货页当前的取货范围：选中「全部仓库」时是全部，否则是当前仓库 */
    private String pickScope() {
        if (pickDd == null || pickDd.choice <= 0) {
            return "全部仓库";
        }
        // 优化10：与 pickItems() 用同一个来源（下拉里选中的那一条），不再绕道 selectedRegion()
        String region = clean(pickDd.entryName(pickDd.choice));
        return region.isEmpty() ? "未选仓库" : region;
    }

    /** 「整理」页当前选的仓库：空串 = 全部（每名搬运工各扫自己的值守仓库） */
    private String taskRegion() {
        if (taskDd == null || taskDd.choice <= 0) {
            return "";
        }
        return clean(taskDd.entryName(taskDd.choice));
    }

    /** 「整理」页发指令用：选「全部仓库」就不带仓库名（服务端按各假人自己的值守仓库办） */
    private void withTaskRegion(NameUser user) {
        String region = taskRegion();
        user.use(region.isEmpty() || RegionCache.list().isEmpty() ? "" : region);
    }

    /**
     * 「整理」页真正发指令：选了具体仓库就发一条；选「全部仓库」就**逐间派单**。
     *
     * <p>为什么不能只发一条不带仓库名的命令：服务端 {@code Tasks.start} 会把空仓库名换成
     * 「被挑中那名空闲假人自己的值守仓库」（名册里第一个满足条件的），于是其它仓库根本没人碰
     * ——玩家会以为「点了整理却什么都没发生」。这里按名册里**有值守仓库**的假人逐间发一条，
     * 每间由值守它的假人整理，彼此用 claim 互斥，不会两只手同时动同一只箱子。
     *
     * <p>一个值守仓库都没有时仍然发那条不带仓库名的命令，好让服务端照常给出它自己的提示。
     */
    private void runTask(String base, String region, String what) {
        if (!region.isEmpty()) {
            run(base + " " + q(region), "已让搬运工开始" + what + "（" + region + "）");
            return;
        }
        List<String> targets = botRegions();
        if (targets.isEmpty()) {
            run(base, "已让搬运工开始" + what);
            return;
        }
        for (String r : targets) {
            run(base + " " + q(r), "已让搬运工开始" + what + "（" + r + "）");
        }
    }

    /** 名册里「有值守仓库」的仓库名（去重，保持名册顺序） */
    private static List<String> botRegions() {
        List<String> out = new ArrayList<>();
        for (ClientSnapshot.Bot b : ClientSnapshot.bots()) {
            if (b == null) {
                continue;
            }
            String r = clean(b.region == null ? "" : b.region);
            if (!r.isEmpty() && !out.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }

    // ================================================================== 分页

    /** 页签名字：普通玩家只有「仓库」「取货」两页 */
    private String[] tabNames() {
        return admin ? new String[]{"仓库", "取货", "搬运工", "权限"} : new String[]{"仓库", "取货"};
    }

    /** 第 i 个页签的矩形（画和点都用这一个，不可能对不上） */
    private R tabRect(int i) {
        return cellRect(tabs, tabCells, tabGap, i);
    }

    /** 点到了哪个页签；没点中返回 -1 */
    private int tabAt(double mx, double my) {
        String[] names = tabNames();
        for (int i = 0; i < names.length; i++) {
            R r = tabRect(i);
            if (r != null && r.holds(mx, my)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 二级页签的名字。
     *
     * <p>「仓库」页管理员 6 个（概览 / 新建 / 扩建/缩小 / 物品 / 箱子 / 维护），普通玩家只给 3 个（概览 / 物品 / 箱子）；
     * 「搬运工」页 2 个（名册值守 / 整理）；「权限」页 2 个（权限 / 审计）；「取货」没有子页。
     */
    private String[] subNames() {
        return switch (tab) {
            case 0 -> admin
                    ? new String[]{"概览", "新建", "扩建/缩小", "物品", "箱子", "维护"}
                    : new String[]{"概览", "物品", "箱子"};
            case 2 -> admin ? new String[]{"名册值守", "整理"} : new String[0];
            case 3 -> admin ? new String[]{"权限", "审计"} : new String[0];
            default -> new String[0];
        };
    }

    /** 当前子页的名字；本页没有子页时是空串 */
    private String subName() {
        String[] names = subNames();
        return names.length == 0 ? "" : names[Math.max(0, Math.min(sub, names.length - 1))];
    }

    /** 第 i 个二级页签的矩形（画和点都用这一个，和顶级页签同一个套路） */
    private R subRect(int i) {
        String[] names = subNames();
        if (names.length == 0) {
            return null;
        }
        return cellRect(subTabs, subCells, subGap, i);
    }

    /** 点到了哪个二级页签；没点中返回 -1 */
    private int subAt(double mx, double my) {
        String[] names = subNames();
        for (int i = 0; i < names.length; i++) {
            R r = subRect(i);
            if (r != null && r.holds(mx, my)) {
                return i;
            }
        }
        return -1;
    }

    /** 切二级页：记住本页停在哪个子页，然后重建控件 */
    private void openSub(int i) {
        if (i < 0 || i >= subNames().length || i == sub) {
            return;
        }
        sub = i;
        subOf[Math.max(0, Math.min(tab, subOf.length - 1))] = sub;
        boxScroll = 0;
        rebuildWidgets();
    }

    /** 切顶级页：先把旧页的子页下标存好，再把新页的那一个取回来 */
    private void openTab(int t) {
        if (t < 0 || t >= tabNames().length || t == tab) {
            return;
        }
        subOf[Math.max(0, Math.min(tab, subOf.length - 1))] = sub;
        tab = t;
        sub = subOf[Math.max(0, Math.min(t, subOf.length - 1))];
        if (sub < 0 || sub >= subNames().length) {
            sub = 0;
        }
        scroll = 0;
        infoScroll = 0;
        pickScroll = 0;
        boxScroll = 0;
        pickOpen = false;
        rebuildWidgets();
    }

    /** 「仓库」页各子页右栏按钮网格要几行（0 = 右栏不放网格） */
    private int gridNeed(String subName) {
        return switch (subName) {
            case "概览" -> 1;          // 扫描仓库 + 显示边界 并排一行
            case "新建" -> 2;          // 底行 名字+新建仓库，上行 点1/点2
            case "扩建/缩小" -> 3;      // 扩建行 / 缩小+扩建到此处行 / 缩到此处+并进新范围行
            case "维护" -> 2;
            default -> 0;   // 物品（整块清单）、箱子（自带控件行）
        };
    }

    /** 每页小标题带上那句话（按可用宽度从长到短挑，宁可说短句，也不要被切成半句） */
    private String captionText() {
        int w = captionLeft == null ? 0 : captionLeft.w();
        return switch (tab) {
            case 1 -> pickFit(w,
                    "取货物品：输入物品名（中文名或 ID 均可）—— 也可点击下方清单中一行",
                    "取货物品：输入物品名，或点击下方清单中一行",
                    "取货物品：输入物品名或点击清单",
                    "取货物品");
            case 2 -> {
                int n = ClientSnapshot.bots().size();
                int vis = Math.max(0, rowsVisible(rowsBox, rowH));
                String more = n > vis && vis > 0 ? "（只显示前 " + vis + " 个）" : "";
                if ("整理".equals(subName())) {
                    yield pickFit(w,
                            "整理 · 把箱子压实、并把放错箱子的物品搬回它所属的箱子",
                            "整理 · 整理箱子里的物品",
                            "整理");
                }
                yield pickFit(w,
                        "搬运工 · 共 " + n + " 个（点一行选中搬运工，再选择目标仓库并点击「确定」）" + more,
                        "搬运工 · " + n + " 个（点一行选中，再选仓库并确定）" + more,
                        "搬运工 · 共 " + n + " 个（点一行选中再确定）" + more,
                        "搬运工 · 共 " + n + " 个" + more,
                        "搬运工 · " + n + " 个");
            }
            case 3 -> {
                int n = ClientSnapshot.accounts().size();
                yield pickFit(w,
                        "玩家权限 · 共 " + n + " 个（修改后立即生效）",
                        "玩家权限 · 共 " + n + " 个（修改后生效）",
                        "玩家权限 · 共 " + n + " 个");
            }
            default -> {
                String sel = selectedRegion();
                // 优化10：sel == -1 时左栏选中的就是「全部仓库」，不要再写成「未选中仓库」
                String who = sel.isEmpty() ? "全部仓库" : sel;
                int regions = RegionCache.list().size();
                yield switch (subName()) {
                    case "新建" -> pickFit(w,
                            "新建仓库：站到一角点「点1」→ 站到对角点「点2」→ 填名字点「新建仓库」",
                            "新建仓库：点1 → 点2 → 填名字 → 新建",
                            "新建仓库：点1 → 点2 → 新建");
                    case "扩建/缩小" -> pickFit(w,
                            "扩建/缩小 · 选中：" + who + "（缩到我所站位置，或选方向填格数）",
                            "扩建/缩小 · " + who + "（也可选方向填格数）",
                            "扩建/缩小 · " + who);
                    case "箱子" -> {
                        int cnt = boxesOf(sel).size();
                        yield pickFit(w,
                                "箱子总览 · " + who + " · 共 " + cnt + " 只（数据来自最近一次扫描/整理）",
                                "箱子总览 · " + who + " · 共 " + cnt + " 只",
                                "箱子 · " + cnt + " 只");
                    }
                    case "维护" -> pickFit(w,
                            "维护 · 选中：" + who + "（标签栏位置、删除仓库、重新扫描都在这里）",
                            "维护 · " + who,
                            "维护");
                    case "物品" -> pickFit(w,
                            "物品总览 · " + who + "（点一行把名字填进「取货」页）",
                            "物品总览 · " + who,
                            "物品 · " + who);
                    default -> {
                        String full = "仓库列表 · " + RegionCache.note() + (admin ? "" : "（普通用户：只读权限）");
                        yield pickFit(w, full, "仓库列表 · 共 " + regions + " 个仓库",
                                "共 " + regions + " 个仓库");
                    }
                };
            }
        };
    }

    /**
     * 「取货」页里那份物品清单（按筛选词过滤，滚动位置另存）。
     *
     * <p>选了「全部仓库」时把各仓库里的同种物品**合并成一行**：个数相加，位置写成「位于 X」/
     * 「分布在 N 个仓库」，最后按个数从多到少排。「取货」发的指令本来就不带仓库名，
     * 所以这里只是把「所有仓库一起看」这件事在界面上做出来，不需要服务端改任何东西。
     */
    private List<ClientSnapshot.Item> pickItems() {
        String q = pickQuery.trim().toLowerCase(Locale.ROOT);
        if (pickDd == null || pickDd.choice > 0) {
            // 优化10：直接取下拉里那一条的名字（不再依赖 selectedRegion() 的隐式同步 ——
            // 「仓库」页选「全部仓库」时 selectedRegion() 是空串，而下拉可以仍然指着某个具体仓库）
            String name = pickDd == null ? selectedRegion() : pickDd.entryName(pickDd.choice);
            List<ClientSnapshot.Item> all = itemsOf(name);
            if (q.isEmpty()) {
                return all;
            }
            List<ClientSnapshot.Item> out = new ArrayList<>();
            for (ClientSnapshot.Item it : all) {
                if (matches(it, q)) {
                    out.add(it);
                }
            }
            return out;
        }
        Map<String, ClientSnapshot.Item> byKey = new LinkedHashMap<>();
        Map<String, Set<String>> where = new LinkedHashMap<>();
        for (RegionCache.Entry entry : RegionCache.list()) {
            for (ClientSnapshot.Item it : itemsOf(clean(entry.name))) {
                if (!matches(it, q)) {
                    continue;
                }
                String key = itemKey(it);
                ClientSnapshot.Item row = byKey.get(key);
                if (row == null) {
                    row = new ClientSnapshot.Item();
                    row.id = it.id;
                    row.name = it.name;
                    row.count = 0;
                    row.refs = 0;
                    byKey.put(key, row);
                    where.put(key, new LinkedHashSet<>());
                }
                row.count += it.count;
                row.refs += it.refs;
                where.get(key).add(clean(entry.name));
            }
        }
        List<ClientSnapshot.Item> out = new ArrayList<>(byKey.values());
        for (ClientSnapshot.Item it : out) {
            Set<String> names = where.get(itemKey(it));
            if (names == null || names.isEmpty()) {
                it.loc = "";
            } else if (names.size() == 1) {
                it.loc = "位于 " + names.iterator().next();
            } else {
                it.loc = "分布在 " + names.size() + " 个仓库";
            }
        }
        out.sort((a, b) -> Long.compare(b.count, a.count));
        return out;
    }

    /** 合并「全部仓库」视图用的键：优先物品 id，老服务端没给 id 就退回名字 */
    private static String itemKey(ClientSnapshot.Item it) {
        return it.id == null || it.id.isEmpty() ? String.valueOf(it.name) : it.id;
    }

    /**
     * 搜索命中规则：id、服务端给的名字、客户端自己的译名 三者任一命中即可。
     *
     * <p>为什么要三个都算：服务端（专用服务器）往往给英文名，玩家手里搜的是中文；
     * 反过来老服务端可能只给名字不给 id。只匹配其中一种都会让搜索莫名其妙地搜不到。
     */
    private static boolean matches(ClientSnapshot.Item it, String q) {
        if (q.isEmpty()) {
            return true;
        }
        if (it.id != null && it.id.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (it.name != null && it.name.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        String local = ClientNames.item(it.id);
        return local != null && local.toLowerCase(Locale.ROOT).contains(q);
    }

    /**
     * 查询回包（kind=2/3/5）里的物品名：客户端自己的语言优先。
     * 服务端缓存的名字可能是建库时的英文名（专用服务端 Language 是 en_us），
     * 而 {@code ClientNames} 查的是本客户端当前语言，所以这里先按 id 取本地名。
     */
    private static String queryItemName(JsonObject row) {
        String id = QueryClient.str(row, "id");
        String local = ClientNames.item(id);
        return local != null && !local.isEmpty() ? local : QueryClient.str(row, "name");
    }

    // ------------------------------------------------ 容器详情：附魔 / 自定义名（优化11）

    /**
     * 附魔 id → 客户端语言显示名的缓存。附魔名在进游戏后不会变，所以缓存一次就够
     * （和 {@link ClientNames} 对物品名做的事一样，只是附魔那边没有现成的方法可用）。
     */
    private static final Map<String, String> ENCHANT_NAMES = new LinkedHashMap<>();

    /**
     * 容器详情一格的补充信息（0.22.0 · 优化11）：附魔 + 自定义名。
     *
     * <p>字段来自写者 A 的 {@code SlotDetail}：{@code ench} 形如
     * {@code minecraft:sharpness@5,minecraft:unbreaking@3}，{@code customName} 是自定义名原文
     * （**不是** {@code name} —— 那个字段是物品显示名，{@link #queryItemName} 还在用它兜底）。
     * 两个字段都没有时（老服务端 / 附魔字段还没上线）返回空串，行不变。
     */
    private static String slotMeta(JsonObject row) {
        StringBuilder sb = new StringBuilder();
        String ench = QueryClient.str(row, "ench");
        if (!ench.isEmpty()) {
            String text = enchantList(ench);
            if (!text.isEmpty()) {
                sb.append(" · ").append(text);
            }
        }
        String custom = QueryClient.str(row, "customName");
        if (!custom.isEmpty()) {
            sb.append(" · «").append(custom).append('»');
        }
        return sb.toString();
    }

    /** {@code id@等级,id@等级} → 「锋利 V · 耐久 III」（等级解析不出来就只显示名字） */
    private static String enchantList(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split(",")) {
            String one = part.trim();
            if (one.isEmpty()) {
                continue;
            }
            String id = one;
            int level = 0;
            int at = one.lastIndexOf('@');
            if (at > 0) {
                id = one.substring(0, at).trim();
                try {
                    level = Integer.parseInt(one.substring(at + 1).trim());
                } catch (NumberFormatException ignored) {
                    level = 0;
                }
            }
            String name = enchantName(id);
            if (name.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(name);
            if (level > 0) {
                sb.append(' ').append(roman(level));
            }
        }
        return sb.toString();
    }

    /**
     * 附魔注册名 → 客户端语言显示名；查不到就退回 id 的 path。
     *
     * <p>不走注册表：原版把附魔翻译键固定拼成 {@code enchantment.<命名空间>.<路径>}
     * （例如 {@code minecraft:sharpness} → {@code enchantment.minecraft.sharpness}），
     * 直接问语言表就够了，也省掉一次注册表查询可能带来的异常。
     */
    private static String enchantName(String enchantId) {
        if (enchantId == null || enchantId.isEmpty()) {
            return "";
        }
        String cached = ENCHANT_NAMES.get(enchantId);
        if (cached != null) {
            return cached;
        }
        String name = "";
        int colon = enchantId.indexOf(':');
        String namespace = colon > 0 ? enchantId.substring(0, colon) : "minecraft";
        String path = colon > 0 ? enchantId.substring(colon + 1) : enchantId;
        try {
            String translated = Component.translatable("enchantment." + namespace + "."
                    + path.replace('/', '.')).getString();
            if (translated != null && !translated.isBlank() && !translated.startsWith("enchantment.")) {
                name = translated;
            }
        } catch (Throwable ignored) {
            // 语言表还没准备好 —— 下面退回可读的 path
        }
        if (name.isEmpty()) {
            name = path.isEmpty() ? enchantId : path;
        }
        ENCHANT_NAMES.put(enchantId, name);
        return name;
    }

    /** 等级 → 罗马数字（1→I、4→IV、5→V、10→X…），超出常规范围就退回阿拉伯数字 */
    private static String roman(int level) {
        if (level <= 0 || level > 3999) {
            return String.valueOf(level);
        }
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        int left = level;
        for (int i = 0; i < values.length && left > 0; i++) {
            while (left >= values[i]) {
                sb.append(symbols[i]);
                left -= values[i];
            }
        }
        return sb.toString();
    }

    /** 物品显示名：客户端自己的语言优先，取不到才用快照里服务端给的名字 */
    private static String itemName(ClientSnapshot.Item it) {
        if (it == null) {
            return "";
        }
        String local = ClientNames.item(it.id);
        if (local != null && !local.isEmpty()) {
            return local;
        }
        return it.name == null ? "" : it.name;
    }

    /** 方块显示名：客户端语言优先；都取不到时用服务端名字，再不行用 id。双联箱走「大型箱子」 */
    private static String boxName(ClientSnapshot.Box box) {
        String local = ClientNames.box(box.block, box.dbl);
        if (local != null && !local.isEmpty()) {
            return local;
        }
        return box.name.isEmpty() ? box.block : box.name;
    }

    /**
     * 箱子里「主要物品」那一行：客户端语言优先（快照另带了 {@code topIds} = {@code id=count;…}），
     * 老服务端没带 id 时才退回服务端拼好的 {@code top} 文本。
     */
    private static String topTextOf(ClientSnapshot.Box box) {
        String ids = box.topIds == null ? "" : box.topIds;
        if (!ids.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (String part : ids.split(";")) {
                int eq = part.lastIndexOf('=');
                if (eq <= 0 || eq >= part.length() - 1) {
                    continue;
                }
                String id = part.substring(0, eq);
                String count = part.substring(eq + 1);
                String local = ClientNames.item(id);
                if (shown > 0) {
                    sb.append('、');
                }
                sb.append(local == null || local.isEmpty() ? id : local).append('×').append(count);
                shown++;
            }
            if (shown > 0) {
                return sb.toString();
            }
        }
        return box.top == null ? "" : box.top;
    }

    /**
     * 点一行物品后填进「取货」输入框的文本。
     *
     * <p>填名字的前提是「服务端解析得动这个名字」：服务端按**自己的语言**解析（先当 id 解析，
     * 再按自己的显示名做包含匹配）。所以只在客户端译名与服务端名字一致（同一语言）时填名字，
     * 否则填 id —— id 与服务端语言无关，{@code /warehouse order <id>} 一定解析得到。
     */
    private static String orderFill(ClientSnapshot.Item it) {
        String local = ClientNames.item(it.id);
        if (local != null && !local.isEmpty() && local.equals(it.name)) {
            return it.name;
        }
        return it.id == null || it.id.isEmpty() ? it.name : it.id;
    }

    /** 「取货」：把写的物品与数量变成一条取货指令 */
    private void orderNow() {
        String what = orderText.trim();
        if (what.isEmpty()) {
            status = "请在上方输入框中填写取货物品（例如 钻石 / diamond），再点击“取货”。";
            return;
        }
        int amount = parseOrderCount();
        run("warehouse order " + what + " " + amount,
                "已下单：让搬运工给你取 " + amount + " 个 " + what);
    }

    /**
     * 搬运工给人看的名字（0.22.0 · 优化7）：设了自定义显示名就用它，否则用注册名。
     *
     * <p>只在「画文字 / 弹提示」时用；发给服务端的指令参数必须继续用注册名（{@code bot.name}），
     * 因为名册、任务、值守都是以注册名为键的。查不到这个注册名（刚被删）时原样返回。
     */
    private String botLabel(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        for (ClientSnapshot.Bot b : ClientSnapshot.bots()) {
            if (name.equals(b.name)) {
                return b.shown();
            }
        }
        return name;
    }

    /** 名册指纹：名字 + 显示名 + 值守仓库 + 值守点 + 在不在岗。变了就重建控件（每行按钮跟着名单走） */
    private String botSig() {
        StringBuilder sb = new StringBuilder();
        for (ClientSnapshot.Bot b : ClientSnapshot.bots()) {
            sb.append(b.name).append('/').append(b.display).append('@').append(b.region).append('#').append(b.spot)
                    .append(b.present ? '+' : '-').append(';');
        }
        return sb.toString();
    }

    /** 账号指纹：名字 + 三项权限（按钮文案跟着它变） */
    private String accSig() {
        StringBuilder sb = new StringBuilder();
        for (ClientSnapshot.Account a : ClientSnapshot.accounts()) {
            sb.append(a.name).append(a.take ? '1' : '0').append(a.bot ? '1' : '0')
                    .append(a.tidy ? '1' : '0').append(';');
        }
        return sb.toString();
    }

    /**
     * 边界按钮的文案：查的是**当前选中那个仓库**的开启状态（0.22.0 · 优化9）。
     *
     * <p>没选中具体仓库（「全部仓库」）时一律显示「显示边界」—— 按钮同时是禁用的，
     * 文案只是个静态说明。
     */
    private String borderLabel() {
        String name = selectedRegion();
        return !name.isEmpty() && RegionBorder.isOn(name) ? "隐藏边界" : "显示边界";
    }

    // ================================================================== 动作

    private interface NameUser {
        void use(String name);
    }

    /**
     * 只在**确实选中了一个具体仓库**时才执行（0.22.0 · 优化10）。
     *
     * <p>用于那些语义上必须点名一个仓库的操作：边界显示、扩建/缩小、并进新圈范围、删除仓库。
     * 选中「全部仓库」时不再偷偷改成第一个仓库，而是提示玩家先去左栏选一个。
     */
    private void withOneRegion(NameUser user) {
        List<RegionCache.Entry> list = RegionCache.list();
        if (list.isEmpty()) {
            status = "暂无任何仓库。请先站到两个角上分别点击“点1”“点2”，填写名字再点击“新建仓库”。";
            return;
        }
        if (sel < 0) {
            status = "请先在左栏选中一个具体仓库（当前是「全部仓库」）。";
            return;
        }
        if (sel >= list.size()) {
            sel = list.size() - 1;
        }
        RegionCache.Entry entry = list.get(sel);
        RegionBorder.touch(entry);   // 只是让边界用上最新坐标，绝不顺手打开
        user.use(clean(entry.name));
    }

    /** 按语义取仓库名：选中「全部仓库」时给空串（服务端把空串当全部）。 */
    private void withRegion(NameUser user) {
        List<RegionCache.Entry> list = RegionCache.list();
        if (list.isEmpty() || sel < 0) {
            user.use("");
            return;
        }
        if (sel >= list.size()) {
            sel = list.size() - 1;
        }
        RegionCache.Entry entry = list.get(sel);
        RegionBorder.touch(entry);
        user.use(clean(entry.name));
    }

    /** 让「扫描」在没有仓库时也能跑（扫全部）。 */
    private void withRegionOrAll(NameUser user) {
        List<RegionCache.Entry> list = RegionCache.list();
        if (list.isEmpty()) {
            user.use("");
            return;
        }
        withRegion(user);
    }

    /**
     * 开/关**当前选中仓库**的边界粒子（0.22.0 · 优化9：每个仓库各存一份开关）。
     *
     * <p>选中「全部仓库」时不能按仓库开边界，所以直接拒绝并提示先选一个具体仓库。
     */
    private void toggleBorder() {
        List<RegionCache.Entry> list = RegionCache.list();
        if (list.isEmpty()) {
            status = "暂无任何仓库，没有边界可以显示。";
            return;
        }
        if (sel < 0) {
            status = "请先在左栏选中一个具体仓库，边界是逐个仓库单独显示的。";
            return;
        }
        if (sel >= list.size()) {
            sel = list.size() - 1;
        }
        RegionCache.Entry entry = list.get(sel);
        RegionBorder.touch(entry);
        boolean next = !RegionBorder.isOn(entry.name);
        RegionBorder.toggle(entry.name, next);
        status = next ? ("正在用发光粒子勾出 " + entry.name + " 的边界（其他仓库的开关不受影响）。切换维度后将不可见。")
                : ("已关闭 " + entry.name + " 的边界粒子。");
        rebuildWidgets();
    }

    private void run(String command, String note) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) {
            status = "现在连不上服务端，指令发不出去。";
            return;
        }
        ClientFeedback.arm();
        mc.player.connection.sendCommand(command);
        status = note;
        refreshAt = System.currentTimeMillis() + 350L;
    }

    // ------------------------------------------------------------- 危险操作确认框

    /**
     * 弹出危险操作的确认框。
     *
     * <p>「危险」指的是点错了会丢东西或改动仓库定义的那些：删除仓库、并进新圈范围、整理仓库。
     * 弹窗期间整个面板被挡住：鼠标点击一律被吃掉（只认「确定 / 取消」），键盘按键也一概不透传，
     * 免得手快连点两次把底下真正的按钮也按了。
     */
    private void ask(String title, String detail, Runnable action) {
        pendingTitle = title;
        pendingDetail = detail;
        pending = action;
    }

    private void cancelAsk() {
        pending = null;
        status = "已取消，未进行任何操作。";
    }

    /** 按像素宽度把一句话拆成若干行（逐字量宽，中英文都适用）。 */
    /** 中文排版禁则：这些标点不能出现在行首，折行时让它跟着上一个字一起挪到下一行 */
    private static final String NO_LINE_START = "。，、；：！？）〕】》」』”’·%";

    private List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (line.length() > 0 && this.font.width(line.toString() + c) > maxWidth) {
                if (NO_LINE_START.indexOf(c) >= 0 && line.length() > 1) {
                    // 标点不能起头：把上一行最后一个字也带下来
                    char last = line.charAt(line.length() - 1);
                    line.setLength(line.length() - 1);
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(last);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                }
            }
            line.append(c);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private void confirmButton(GuiGraphicsExtractor g, R r, String label, boolean primary, int mouseX, int mouseY) {
        boolean hover = r.holds(mouseX, mouseY);
        int bg;
        if (primary) {
            bg = hover ? 0xFF2E7D32 : 0xFF1F5B24;
        } else {
            bg = hover ? 0xFF3A4658 : 0xFF232B36;
        }
        fillIn(g, r, bg);
        borderIn(g, r, primary ? 0xFF7FD18A : 0xFF4A586B);
        String text = fit(label, r.w());
        g.text(this.font, text, r.x() + (r.w() - this.font.width(text)) / 2,
                r.y() + Math.max(0, (r.h() - 8) / 2), 0xFFF2F6FB);
    }

    /**
     * 画确认框。调用点在 {@code extractRenderState} —— 那一趟在所有按钮之后跑，
     * 所以小窗一定盖在面板和按钮上面，不会被它们压住。
     */
    private void drawConfirm(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int w = Math.max(160, Math.min(320, this.width - MARGIN * 2));
        List<String> lines = wrap(pendingDetail, w - 24);
        int h = Math.min(this.height - MARGIN * 2, 30 + lines.size() * LINE_H + 34);
        R dlg = new R((this.width - w) / 2, (this.height - h) / 2, w, Math.max(1, h));

        g.fill(0, 0, this.width, this.height, 0xB4000000);
        fillIn(g, dlg, 0xF0141A24);
        borderIn(g, dlg, 0xFFE0B36A);

        Flow f = new Flow(dlg.inset(12, 10));
        R title = f.takeTop(LINE_H, 4);
        textIn(g, title, pendingTitle, 0xFFFFD98A);
        int btnH = 18;
        R btnRow = f.takeBottom(btnH, 6);
        R body = f.rest();
        if (body != null) {
            List<String> shown = lines.size() * LINE_H <= body.h() ? lines
                    : lines.subList(0, Math.max(0, body.h() / LINE_H));
            int y = body.y();
            for (String line : shown) {
                textIn(g, new R(body.x(), y, body.w(), LINE_H), line, 0xFFCFE0F5);
                y += LINE_H;
            }
        }
        if (btnRow != null) {
            int bw = Math.min(84, Math.max(56, (btnRow.w() - GAP) / 2));
            okBtn = new R(btnRow.right() - bw, btnRow.y(), bw, btnRow.h());
            noBtn = new R(okBtn.x() - GAP - bw, btnRow.y(), bw, btnRow.h());
            confirmButton(g, noBtn, "取消", false, mouseX, mouseY);
            confirmButton(g, okBtn, "确定", true, mouseX, mouseY);
        } else {
            okBtn = null;
            noBtn = null;
        }
    }

    private int parseCount() {
        try {
            int value = Integer.parseInt(countText.trim());
            if (value < 1) {
                return 1;
            }
            return Math.min(value, 4096);
        } catch (Exception ex) {
            return 1;
        }
    }

    /** 取货数量：默认 1，最多 4096（和服务端的 MAX_ORDER 一致）。 */
    private int parseOrderCount() {
        try {
            int value = Integer.parseInt(orderCountText.trim());
            if (value < 1) {
                return 1;
            }
            return Math.min(value, 4096);
        } catch (Exception ex) {
            return 1;
        }
    }

    /**
     * 名字整理：剥掉会破坏指令文本本身的字符，其余**原样保留**（含中文）。
     *
     * <p>以前这里只留 {@code [A-Za-z0-9_]}（因为 brigadier 的 {@code word()} 只认这些），
     * 结果中文仓库名会被整段剥成空串：既建不了中文仓库，服务端推下来的中文名在界面上
     * 也会变成空白、甚至被归进同一个 key。现在服务端用的是自定义的 {@code NameArgument}
     * （不带引号读到空白为止、带引号支持空格，见 {@code command/NameArgument.java}），
     * 所以这里只去掉引号和换行这类会截断指令的字符。
     *
     * <p>反过来，服务端发过来的名字（{@code entry.name} 等）也走这个函数，
     * 剥字符会让显示与指令都对不上，务必保持「不改内容」。
     */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' || c == '"' || c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /**
     * 把名字拼进指令文本：含空格时加引号（服务端 {@code NameArgument} 见到引号会整段读）。
     *
     * <p>{@link #clean(String)} 已经把引号剥掉了，所以这里只要决定加不加引号。
     */
    private static String q(String name) {
        if (name == null) {
            return "\"\"";
        }
        return name.indexOf(' ') >= 0 ? "\"" + name + "\"" : name;
    }

    // ------------------------------------------------------------------ 物品列表（含搜索过滤）

    /** 当前选中的仓库名（已经按 brigadier 的 word() 规则清洗过） */
    private String selectedRegion() {
        List<RegionCache.Entry> list = RegionCache.list();
        return list.isEmpty() || sel < 0 || sel >= list.size() ? "" : clean(list.get(sel).name);
    }

    /**
     * 当前仓库 + 当前搜索词下的物品列表。
     *
     * <p>结果缓存起来：一是别每帧重建（列表可能上千行），二是保证 drawInfo() 与 mouseScrolled()
     * 拿到的是**同一份**数据 —— 否则滚动上限会和实际行数对不上（出现空白行或翻不动）。
     */
    private List<ClientSnapshot.Item> itemsOf(String region) {
        String q = searchText.trim().toLowerCase(Locale.ROOT);
        String key = region + "\u0000" + q;
        long stamp = ClientSnapshot.receivedAt();
        if (key.equals(viewKey) && stamp == viewStamp) {
            return viewItems;
        }
        ClientSnapshot.Region view = ClientSnapshot.find(region);
        List<ClientSnapshot.Item> src = view == null ? List.of() : view.items;
        if (q.isEmpty()) {
            viewItems = src;
        } else {
            List<ClientSnapshot.Item> out = new ArrayList<>();
            for (ClientSnapshot.Item it : src) {
                if (matches(it, q)) {
                    out.add(it);
                }
            }
            viewItems = out;
        }
        viewKey = key;
        viewStamp = stamp;
        return viewItems;
    }

    // ------------------------------------------------------------ 行数（画 / 滚动 / 点击共用）

    /** 一个区域里能放下几行 h 高的行 */
    private static int rowsVisible(R box, int h) {
        return box == null || h <= 0 ? 0 : Math.max(0, box.h() / h);
    }

    /** 行区里第 i 行的矩形（i 从 0 开始） */
    private static R rowRect(R box, int i, int h) {
        return new R(box.x(), box.y() + i * h, box.w(), h);
    }

    /** 「这个仓库里有什么」能画几行（页码行占一行；统计数字在标题带里时不占物品框的行） */
    private int infoRows() {
        if (infoBox == null) {
            return 0;
        }
        int head = infoCapRight == null || infoCapRight.empty() ? LINE_H + 4 : 1;
        return Math.max(0, (infoBox.h() - head - (LINE_H + 2)) / LINE_H);
    }

    /** 「取货」清单能画几行（顶部表头占一行） */
    private int pickRows() {
        return pickBox == null ? 0 : Math.max(0, (pickBox.h() - (LINE_H + 4)) / LINE_H);
    }

    // ------------------------------------------------------------------ 每 tick

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        if (refreshAt > 0L && now >= refreshAt) {
            refreshAt = 0L;
            RegionCache.refresh(true);
        }
        RegionCache.refresh(false);
        clampSel();
        // 仓库个数变了就重建第 0 页：列表框的高度是按「有几个仓库」算出来的
        int ls = RegionCache.list().size();
        if (ls != listSig) {
            listSig = ls;
            if (tab == 0) {
                rebuildWidgets();
                return;
            }
        }
        // 名册 / 账号变了就重建当前页：每行的按钮数量与文案是跟着名单走的
        String bs = botSig();
        if (!bs.equals(botSig)) {
            botSig = bs;
            if (tab == 2 && admin) {
                rebuildWidgets();
                return;
            }
        }
        String as = accSig();
        if (!as.equals(accSig)) {
            accSig = as;
            if (tab == 3 && admin) {
                rebuildWidgets();
                return;
            }
        }
        if (dirBtn != null) {
            dirBtn.setMessage(Component.literal("方向：" + DIR_NAMES[dir]));
        }
        if (borderBtn != null) {
            borderBtn.setMessage(Component.literal(borderLabel()));
            // 优化9/10：边界按仓库开关，选中项一变（含切到「全部仓库」）按钮状态就要跟着变
            borderBtn.active = !selectedRegion().isEmpty();
        }
    }

    /**
     * 把选中项夹回合法范围（0.22.0 · 优化10）。
     *
     * <p>关键：{@code sel == -1} 是「全部仓库」，**必须原样保留** —— 仓库列表为空时也是 -1
     * （旧代码在空列表时写回 0，于是「默认全部仓库」一有仓库就变成「默认选中第一个」）。
     * 这里只夹两件事：{@code sel} 太大时收到最后一项、小于 -1 的脏值时回到 -1。
     */
    private void clampSel() {
        List<RegionCache.Entry> list = RegionCache.list();
        if (list.isEmpty()) {
            sel = -1;
            scroll = 0;
            return;
        }
        if (sel >= list.size()) {
            sel = list.size() - 1;
        }
        if (sel < -1) {
            sel = -1;
        }
        // 列表比真实仓库多一行：第 0 行是合成的「全部仓库」
        int max = Math.max(0, list.size() + 1 - Math.max(1, rowsVisible(listBox, regionRowH)));
        scroll = Math.max(0, Math.min(scroll, max));
    }

    // ================================================================== 绘制

    /**
     * 画一圈 1 像素宽的矩形边框（左上角 + 右下角语义，与所有调用点一致）。
     *
     * <p>⚠️ 26.2 的 {@code GuiGraphicsExtractor.outline(x, y, width, height, color)} 收的是
     * <b>宽和高</b>，不是右下角坐标 —— 历史版本按「左上/右下」传参，于是每条边都被多加了一次起点坐标，
     * 直接画到面板外（这就是「蓝灰色线越界」的根因）。这里统一用 {@code fill} 的两个角语义自己画四条边。
     * 另外：矩形是退化的（宽或高不足 2）时<b>什么都不画</b>，绝不退化成一条画在错误位置的线。
     */
    private static void border(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        if (x1 - x0 < 2 || y1 - y0 < 2) {
            return;
        }
        g.fill(x0, y0, x1, y0 + 1, color);          // 上边
        g.fill(x0, y1 - 1, x1, y1, color);          // 下边
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);  // 左边
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);  // 右边
    }

    private static void fillIn(GuiGraphicsExtractor g, R r, int color) {
        if (r != null && !r.empty()) {
            g.fill(r.x(), r.y(), r.right(), r.bottom(), color);
        }
    }

    private static void borderIn(GuiGraphicsExtractor g, R r, int color) {
        if (r != null && !r.empty()) {
            border(g, r.x(), r.y(), r.right(), r.bottom(), color);
        }
    }

    /**
     * 默认对齐：文字在**自己所属的矩形里水平 + 垂直居中**（不是相对整个 Screen 居中）。
     * <p>
     * 顺序是固定的：先按可用宽度截断（必要时带省略号）→ 用**截断后**的实际宽高算居中位置 →
     * 整段包在 scissor 里画。所以「改了文案 / 换了窗口大小 / 换了 GUI Scale」都不会让字越出自己的区域。
     * <p>
     * 只有「长文本说明、日志、多行描述、清单与搜索结果、用户输入、最近结果、调试信息」这些
     * 读起来更适合左对齐的，才显式用 {@link #textLeft}；多行整块居中用 {@link #textCenterLines}。
     */
    private void textCenter(GuiGraphicsExtractor g, R r, String s, int color) {
        if (r == null || r.empty() || s == null || s.isEmpty()) {
            return;
        }
        String t = fitChecked(s, r.w(), r);
        int x = r.x() + Math.max(0, (r.w() - this.font.width(t)) / 2);
        int y = r.y() + Math.max(0, (r.h() - this.font.lineHeight) / 2);
        clipped(g, r, gg -> gg.text(this.font, t, x, y, color));
    }

    /** 多行文字：**整块**在矩形里居中（不是每行各自居中），行数过多时同样按矩形裁剪。 */
    private void textCenterLines(GuiGraphicsExtractor g, R r, List<String> lines, int color) {
        if (r == null || r.empty() || lines == null || lines.isEmpty()) {
            return;
        }
        int lh = this.font.lineHeight;
        int y0 = r.y() + Math.max(0, (r.h() - lines.size() * lh) / 2);
        clipped(g, r, gg -> {
            for (int i = 0; i < lines.size(); i++) {
                String t = fitChecked(lines.get(i), r.w(), r);
                gg.text(this.font, t, r.x() + Math.max(0, (r.w() - this.font.width(t)) / 2), y0 + i * lh, color);
            }
        });
    }

    /** 一行文字：先按区域宽度截断，再按区域左边界画 —— 文字永远出不了这个矩形。 */
    private void textIn(GuiGraphicsExtractor g, R r, String s, int color) {
        textLeft(g, r, s, color);
    }

    /** 显式左对齐：长文本说明 / 日志 / 多行描述 / 清单与搜索结果 / 用户输入 / 最近结果 / 调试信息。 */
    private void textLeft(GuiGraphicsExtractor g, R r, String s, int color) {
        if (r == null || r.empty() || s == null || s.isEmpty()) {
            return;
        }
        clipped(g, r, gg -> gg.text(this.font, fitChecked(s, r.w(), r), r.x(), r.y(), color));
    }

    /** {@link #textCenter} 的旧名字（页签/标题这些一直用的就是它）。 */
    private void textMid(GuiGraphicsExtractor g, R r, String s, int color) {
        textCenter(g, r, s, color);
    }

    /** 右对齐的一行文字（仍然先截断到区域宽度） */
    private void textRight(GuiGraphicsExtractor g, R r, String s, int color) {
        if (r == null || r.empty() || s == null || s.isEmpty()) {
            return;
        }
        String t = fitChecked(s, r.w(), r);
        g.text(this.font, t, r.right() - this.font.width(t), r.y(), color);
    }

    /**
     * 在区域里裁剪绘制：body 里的内容无论怎么算都出不了这个矩形。
     * scissor 一定成对（异常也走 finally），不会把裁剪状态留给下一趟绘制。
     */
    private void clipped(GuiGraphicsExtractor g, R r, Consumer<GuiGraphicsExtractor> body) {
        if (r == null || r.empty()) {
            return;
        }
        g.enableScissor(r.x(), r.y(), r.right(), r.bottom());
        try {
            body.accept(g);
        } finally {
            g.disableScissor();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x8A000000);
        fillIn(g, panel, 0xF0141A24);
        borderIn(g, panel, 0xFF3D4757);

        // 标题（关闭按钮是控件，由 super.extractRenderState 画在这一层之上）
        textMid(g, headerTitle, this.title.getString(), 0xFFFFFFFF);
        drawTabs(g);
        drawSubTabs(g);
        textCenter(g, captionLeft, captionText(), 0xFF8FA0B8);

        switch (tab) {
            case 0 -> drawRegions(g, mouseX, mouseY);
            case 1 -> drawPick(g, mouseX, mouseY);
            case 2 -> drawPorter(g, mouseX, mouseY);
            default -> drawPerm(g, mouseX, mouseY);
        }
        drawStatus(g);
    }

    /** 二级页签那一行：当前子页高亮（比顶级页签矮一档、颜色暗一点，层级一眼能看出来） */
    private void drawSubTabs(GuiGraphicsExtractor g) {
        String[] names = subNames();
        if (names.length == 0 || subTabs == null || subTabs.empty()) {
            return;
        }
        for (int i = 0; i < names.length; i++) {
            R r = subRect(i);
            if (r == null || r.empty()) {
                continue;
            }
            boolean on = i == sub;
            fillIn(g, r, on ? 0xFF24405C : 0xFF161D29);
            borderIn(g, r, on ? 0xFF4E8BD8 : 0xFF262F3D);
            textMid(g, new R(r.x(), r.y(), r.w(), r.h()), subLabel(i), on ? 0xFFE8F1FF : 0xFF93A3B8);
        }
    }

    /**
     * 一个二级页签真正画出去的字：窄到放不下时先换短名（「名册值守」→「名册」），还放不下才截断。
     * 画（{@link #drawSubTabs}）和自检（{@link #layoutCheck}）用的是同一个方法，所以两边永远不会不一致。
     */
    private String subLabel(int i) {
        String[] names = subNames();
        if (i < 0 || i >= names.length) {
            return "";
        }
        String label = names[i];
        R r = subRect(i);
        if (r == null || r.empty()) {
            return label;
        }
        int room = Math.max(0, r.w() - 4);
        if (this.font.width(label) > room) {
            String brief = switch (label) {
                case "名册值守" -> "名册";
                case "扩建/缩小" -> "扩建/缩";   // 少一个字，窄窗口下更容易整块显示
                default -> label;
            };
            label = this.font.width(brief) <= room ? brief : fit(brief, room);
        }
        return label;
    }

    /** 页签那一行：当前页高亮 */
    private void drawTabs(GuiGraphicsExtractor g) {
        String[] names = tabNames();
        for (int i = 0; i < names.length; i++) {
            R r = tabRect(i);
            if (r == null || r.empty()) {
                continue;
            }
            boolean on = i == tab;
            fillIn(g, r, on ? 0xFF1D4E89 : 0xFF1A2230);
            borderIn(g, r, on ? 0xFF4E8BD8 : 0xFF2A3342);
            String label = names[i];
            if (i == 2) {
                label = label + " " + ClientSnapshot.bots().size();
            } else if (i == 3) {
                label = label + " " + ClientSnapshot.accounts().size();
            }
            textMid(g, new R(r.x() + 3, r.y(), Math.max(0, r.w() - 6), r.h()), label,
                    on ? 0xFFE8F1FF : 0xFF9FB0C6);
        }
    }

    /** 底部「最近结果」：标题 + 1~2 行正文，整块都在 statusBox 里 */
    private void drawStatus(GuiGraphicsExtractor g) {
        if (statusBox == null || statusBox.empty()) {
            return;
        }
        R cap = new R(statusBox.x(), statusBox.y(), statusBox.w(), CAP_H);
        textCenter(g, cap, "最近结果", 0xFF8FA0B8);
        String[] parts = statusText().split("\n");
        int room = Math.max(1, Math.min(statusLines(), parts.length));
        R body = new R(statusBox.x(), statusBox.y() + CAP_H, statusBox.w(), statusBox.h() - CAP_H);
        // 状态文字也是「能说完整就说完」：先按宽度折行，行数用满就停（不画半句 + 「…」）
        int y = body.y();
        int drawn = 0;
        for (int i = 0; i < parts.length && drawn < room; i++) {
            int color = i == 0 && drawn == 0 ? 0xFFCFE0F5 : 0xFF9FB3CC;
            for (String line : wrap(parts[i], body.w())) {
                if (drawn >= room || y + LINE_H > body.bottom()) {
                    return;
                }
                textIn(g, new R(body.x(), y, body.w(), LINE_H), line, color);
                y += LINE_H;
                drawn++;
            }
        }
    }

    /** 第 0 页「仓库」：左边仓库列表 + 这个仓库里有什么，右边「当前选中」与按钮网格 */
    private void drawRegions(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<RegionCache.Entry> list = RegionCache.list();

        // ---- 仓库列表（列表标题就是页面上方那条小标题带，不重复画）----
        fillIn(g, listBox, 0xFF0C1017);
        borderIn(g, listBox, 0xFF2A3342);
        int rows = Math.max(1, rowsVisible(listBox, regionRowH));
        if (listBox != null && !listBox.empty()) {
            // 0.22.0 · 优化10：列表第 0 行是合成的「全部仓库」（sel == -1），真实仓库是第 1..n 行
            int total = list.size() + 1;
            int max = Math.max(0, total - rows);
            scroll = Math.max(0, Math.min(scroll, max));
            clipped(g, listBox, gg -> {
                for (int i = 0; i < rows; i++) {
                    int idx = scroll + i;
                    if (idx >= total) {
                        break;
                    }
                    boolean all = idx == 0;
                    RegionCache.Entry entry = all ? null : list.get(idx - 1);
                    R row = new R(listBox.x() + 2, listBox.y() + 2 + i * regionRowH, Math.max(0, listBox.w() - 4),
                            regionRowH - 2);
                    boolean selected = all ? sel < 0 : idx - 1 == sel;
                    boolean hover = row.holds(mouseX, mouseY);
                    if (selected) {
                        fillIn(gg, row, 0xFF1D4E89);
                    } else if (hover) {
                        fillIn(gg, row, 0xFF1A2331);
                    }
                    // 名字一行 + 详情最多两行：整块在行里垂直居中，每行各自水平居中（都不会越出行框）
                    int metaW = Math.max(0, row.w() - 12);
                    List<String> meta = all ? allRegionsMeta(total - 1) : metaLines(entry, metaW);
                    int blockH = LINE_H * (1 + meta.size());
                    int top = row.y() + Math.max(2, (row.h() - blockH) / 2);
                    textCenter(gg, new R(row.x() + 6, top, metaW, LINE_H),
                            all ? "全部仓库" : entry.name, selected ? 0xFFFFFFFF : 0xFFD5DEEA);
                    for (int k = 0; k < meta.size(); k++) {
                        textCenter(gg, new R(row.x() + 6, top + (k + 1) * LINE_H, metaW, LINE_H),
                                meta.get(k), selected ? 0xFFD8E6F8 : 0xFF7C8CA1);
                    }
                }
                if (list.isEmpty()) {
                    R row = new R(listBox.x() + 6, listBox.y() + 6, Math.max(0, listBox.w() - 12), LINE_H);
                    textIn(gg, row, "还没有仓库", 0xFF93A3B8);
                    if (listBox.h() >= 56) {
                        textIn(gg, row.shift(0, 14), "站到一角 → 点1", 0xFF7F8EA3);
                        textIn(gg, row.shift(0, 26), "再站到对角 → 点2", 0xFF7F8EA3);
                        textIn(gg, row.shift(0, 38), "填写名字 → 新建仓库", 0xFF7F8EA3);
                    }
                }
            });
            if (total > rows) {
                textRight(g, captionRight,
                        "滚轮 " + (scroll + 1) + "~" + Math.min(total, scroll + rows) + " / " + total,
                        0xFF6E7E93);
            }
        }

        // ---- 子页分工：左栏那张仓库列表每个子页都有，右下/下半边看是哪个子页 ----
        String sub = subName();
        switch (sub) {
            case "物品" -> drawInfo(g, mouseX, mouseY);      // 整块物品清单 + 搜索框
            case "箱子" -> drawBoxes(g, mouseX, mouseY);     // 箱子总览
            default -> drawSelected(g);                      // 概览/新建/扩建/维护：右栏统计与选中详情
        }
    }

    // ================================================================== 批次 5：按需查询的四页

    /** 四页查询用的仓库名（空串 = 全部仓库；服务端把空串当「全部」） */
    private String queryRegion() {
        return clean(selectedRegion());
    }

    /** 分类下拉的候选：第 0 项固定「全部分类」，其余来自总览的 categories（总览没到就只有第 0 项） */
    private List<String> catList() {
        List<String> out = new ArrayList<>();
        out.add("全部分类");
        for (JsonObject c : QueryClient.arr(QueryClient.raw(ViewQueryPayload.KIND_OVERVIEW), "categories")) {
            String name = QueryClient.str(c, "name");
            if (!name.isEmpty() && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** 分类下拉选中的分类（「全部分类」= 空串，交给服务端当「不筛」） */
    private String catKey() {
        if (catDd == null || catDd.choice <= 0) {
            return "";
        }
        String name = catDd.entryName(catDd.choice);
        return "全部分类".equals(name) ? "" : name;
    }

    /** 排序参数（服务端口径见 ViewQueryService.sort） */
    private String itemSortKey() {
        return switch (itemSort) {
            case 1 -> "count_asc";
            case 2 -> "name";
            case 3 -> "refs";
            default -> "";
        };
    }

    private String itemSortLabel() {
        return switch (itemSort) {
            case 1 -> "排序：数量 ↑";
            case 2 -> "排序：名称";
            case 3 -> "排序：位置数";
            default -> "排序：数量 ↓";
        };
    }

    /** 物品列表查询（withPage 必须最后调：每个派生器都会把页码重置成 1） */
    private QueryClient.Request itemRequest(int page) {
        return QueryClient.Request.of(ViewQueryPayload.KIND_ITEMS, queryRegion())
                .withKeyword(searchText.trim())
                .withCategory(catKey())
                .withSort(itemSortKey())
                .withPage(page);
    }

    private QueryClient.Request itemDetailRequest() {
        return QueryClient.Request.of(ViewQueryPayload.KIND_ITEM, queryRegion()).withKey(itemDetailKey);
    }

    private QueryClient.Request boxRequest() {
        return QueryClient.Request.of(ViewQueryPayload.KIND_CONTAINERS, queryRegion()).withKeyword(boxFilter.trim());
    }

    private QueryClient.Request boxDetailRequest() {
        return QueryClient.Request.of(ViewQueryPayload.KIND_CONTAINER, queryRegion()).withKey(boxDetailKey);
    }

    /** 审计子页的查询：只按页码翻（关键字/分类/排序都不参与） */
    private QueryClient.Request auditRequest(int page) {
        return QueryClient.Request.of(ViewQueryPayload.KIND_AUDIT, "").withPage(page);
    }

    /** 翻页：页码先按服务端回的上限夹紧，真的变了才发请求 */
    private void goItemPage(int p) {
        JsonObject jo = QueryClient.raw(ViewQueryPayload.KIND_ITEMS);
        int pages = (int) Math.max(1L, QueryClient.num(jo, "pages"));
        int np = Math.max(1, Math.min(p, pages));
        if (np == itemPage) {
            return;
        }
        itemPage = np;
        infoScroll = 0;
        QueryClient.request(itemRequest(np));
    }

    /** 取一个字符串数组字段（容器的 regions 这种） */
    private static List<String> strArr(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o == null || !o.has(key)) {
            return out;
        }
        try {
            for (var e : o.getAsJsonArray(key)) {
                if (!e.isJsonNull()) {
                    out.add(e.getAsString());
                }
            }
        } catch (Throwable ignored) {
            // 形状不对就当作空
        }
        return out;
    }

    /** 「12,-60,4」→ {12,-60,4}（服务端只给坐标串，排序时自己拆） */    private int[] posOf(JsonObject c) {
        String[] parts = QueryClient.str(c, "pos").split(",");
        int[] out = {0, 0, 0};
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 物品页（kind=2 / kind=3）

    /** 物品页：列表态（kind=2）与详情态（kind=3）都画在 infoBox 里，行几何与 infoRows() 保持一致 */
    private void drawInfoQuery(GuiGraphicsExtractor g, int mouseX, int mouseY, String region) {
        boolean detail = !itemDetailKey.isEmpty();
        if (!detail && catDd != null) {
            // 每帧刷一次候选：总览数据到了以后分类才会出现在下拉里
            catDd.entries = catList();
            catDd.clamp();
        }
        JsonObject jo = QueryClient.raw(detail ? ViewQueryPayload.KIND_ITEM : ViewQueryPayload.KIND_ITEMS);
        if (jo == null) {
            // 还没有缓存（刚打开这一页 / 刚清过）：问一次，之后由 response 顶上
            QueryClient.open(detail ? itemDetailRequest() : itemRequest(itemPage));
        }
        String query = searchText.trim();
        String base = detail ? "物品详情" : "物品" + (region.isEmpty() ? "" : " · " + region);
        int capW = infoCapLeft == null ? 0 : infoCapLeft.w();
        String t0 = query.isEmpty() || detail ? base : base + " · 搜索「" + query + "」";
        String t1 = query.isEmpty() || detail ? base : base + " · 搜「" + query + "」";
        textCenter(g, infoCapLeft,
                this.font.width(t0) <= capW ? t0 : (this.font.width(t1) <= capW ? t1 : base), 0xFF8FA0B8);

        if (infoBox == null || infoBox.empty()) {
            // 窄屏下左栏可能没分到空间：宁可不画，也不能空指针崩客户端
            return;
        }
        fillIn(g, infoBox, 0xFF0C1017);
        borderIn(g, infoBox, 0xFF2A3342);
        if (jo == null) {
            clipped(g, infoBox, gg -> drawHintLines(gg, infoBox.x() + 6, infoBox.y() + 3,
                    Math.max(0, infoBox.w() - 12), List.of("正在查询…"), 0xFF93A3B8));
            return;
        }
        String error = QueryClient.str(jo, "error");
        if (!error.isEmpty()) {
            clipped(g, infoBox, gg -> drawHintLines(gg, infoBox.x() + 6, infoBox.y() + 3,
                    Math.max(0, infoBox.w() - 12), List.of(error), 0xFFE0B36A));
            return;
        }
        if (detail) {
            drawItemDetailQuery(g, mouseX, mouseY, jo);
        } else {
            drawItemListQuery(g, mouseX, mouseY, jo);
        }
    }

    private void drawItemListQuery(GuiGraphicsExtractor g, int mouseX, int mouseY, JsonObject jo) {
        List<JsonObject> rowsAll = QueryClient.arr(jo, "items");
        long total = QueryClient.num(jo, "total");
        int pages = (int) Math.max(1L, QueryClient.num(jo, "pages"));
        int page = (int) Math.max(1L, QueryClient.num(jo, "page"));
        itemPage = Math.min(Math.max(1, page), pages);
        boolean headInCap = infoCapRight != null && !infoCapRight.empty();
        R headBox = headInCap ? infoCapRight
                : new R(infoBox.x() + 6, infoBox.y() + 3, Math.max(0, infoBox.w() - 12), LINE_H);
        String stat = "共 " + total + " 种 · 第 " + itemPage + "/" + pages + " 页";
        textCenter(g, headBox, fitChecked(stat, headBox.w(), headBox), 0xFFB9C8DA);
        int top = headInCap ? infoBox.y() + 1 : infoBox.y() + 15;

        int rows = infoRows();
        int max = Math.max(0, rowsAll.size() - rows);
        infoScroll = Math.max(0, Math.min(infoScroll, max));
        String[] hoverInfo = {""};
        clipped(g, infoBox, gg -> {
            if (rowsAll.isEmpty()) {
                String msg = total > 0 ? "这一页没有内容。"
                        : (searchText.trim().isEmpty() && catKey().isEmpty()
                                ? (queryRegion().isEmpty() ? "所有仓库里都没有物品。" : "仓库内暂无物品。")
                                : "没有符合条件的物品。");
                drawHintLines(gg, infoBox.x() + 6, top + 2, Math.max(0, infoBox.w() - 12),
                        List.of(msg), 0xFF93A3B8);
                return;
            }
            int countW = 46;
            for (int i = 0; i < rows; i++) {
                int idx = infoScroll + i;
                if (idx >= rowsAll.size()) {
                    break;
                }
                JsonObject row = rowsAll.get(idx);
                R line = itemRowRect(i);
                boolean over = line.holds(mouseX, mouseY);
                if (over) {
                    fillIn(gg, line, 0xFF1A2331);
                }
                String name = queryItemName(row);
                String cat = QueryClient.str(row, "category");
                textIn(gg, new R(line.x() + 2, line.y() + 1, Math.max(0, line.w() - countW - 6), LINE_H),
                        name + (cat.isEmpty() ? "" : " · " + cat), over ? 0xFFFFFFFF : 0xFFD5DEEA);
                textRight(gg, new R(line.right() - countW, line.y() + 1, countW, LINE_H),
                        "×" + QueryClient.num(row, "count"), 0xFF8FB7E8);
                if (over) {
                    String stacks = QueryClient.str(row, "stacks");
                    hoverInfo[0] = cat.isEmpty() ? stacks : cat + " · " + stacks;
                }
            }
            R foot = new R(infoBox.x() + 6, infoBox.bottom() - LINE_H - 2, Math.max(0, infoBox.w() - 12), LINE_H);
            if (rowsAll.size() > rows) {
                textRight(gg, foot, (infoScroll + 1) + "~" + Math.min(rowsAll.size(), infoScroll + rows)
                        + " / " + rowsAll.size(), 0xFF6E7E93);
            } else if (!hoverInfo[0].isEmpty()) {
                textIn(gg, foot, hoverInfo[0], 0xFFE0B36A);
            }
        });
        // 翻页行中间那句页码（两边是原版按钮）
        if (pageRow != null && !pageRow.empty()) {
            int btnW = Math.min(48, Math.max(28, pageRow.w() / 6));
            R mid = new R(pageRow.x() + btnW + GAP, pageRow.y(),
                    Math.max(0, pageRow.w() - 2 * (btnW + GAP)), pageRow.h());
            textCenter(g, mid, pickFit(mid.w(), stat, "第 " + itemPage + "/" + pages + " 页",
                    String.valueOf(itemPage)), 0xFF8FA0B8);
        }
    }

    private void drawItemDetailQuery(GuiGraphicsExtractor g, int mouseX, int mouseY, JsonObject jo) {
        JsonObject item = jo.has("item") && jo.get("item").isJsonObject() ? jo.getAsJsonObject("item") : null;
        List<String> lines = new ArrayList<>();
        if (item != null) {
            String name = queryItemName(item);
            String cat = QueryClient.str(item, "category");
            String stacks = QueryClient.str(item, "stacks");
            lines.add(name + (cat.isEmpty() ? "" : " · " + cat) + (stacks.isEmpty() ? "" : " · " + stacks));
            List<JsonObject> locs = QueryClient.arr(item, "locations");
            for (JsonObject l : locs) {
                String dim = QueryClient.str(l, "dimensionName");
                lines.add(QueryClient.str(l, "pos") + " · 槽 " + QueryClient.num(l, "slot")
                        + " · ×" + QueryClient.num(l, "count")
                        + (dim.isEmpty() ? "" : " · " + dim));
            }
            if (locs.isEmpty()) {
                lines.add("索引里没有它的存放位置。");
            }
        } else {
            lines.add("索引中不存在该物品。");
        }
        boolean headInCap = infoCapRight != null && !infoCapRight.empty();
        long count = item == null ? 0L : QueryClient.num(item, "count");
        long refs = item == null ? 0L : QueryClient.num(item, "refs");
        R headBox = headInCap ? infoCapRight
                : new R(infoBox.x() + 6, infoBox.y() + 3, Math.max(0, infoBox.w() - 12), LINE_H);
        textCenter(g, headBox, fitChecked("共 " + count + " 个 · " + refs + " 处", headBox.w(), headBox), 0xFFB9C8DA);
        int top = headInCap ? infoBox.y() + 1 : infoBox.y() + 15;
        int rows = infoRows();
        int max = Math.max(0, lines.size() - rows);
        itemDetailScroll = Math.max(0, Math.min(itemDetailScroll, max));
        final int first = itemDetailScroll;
        clipped(g, infoBox, gg -> {
            for (int i = 0; i < rows; i++) {
                int idx = first + i;
                if (idx >= lines.size()) {
                    break;
                }
                R line = itemRowRect(i);
                boolean over = line.holds(mouseX, mouseY);
                if (over) {
                    fillIn(gg, line, 0xFF1A2331);
                }
                textIn(gg, new R(line.x() + 2, line.y() + 1, Math.max(0, line.w() - 4), LINE_H),
                        fitChecked(lines.get(idx), Math.max(0, line.w() - 4), line),
                        idx == 0 ? 0xFFFFFFFF : 0xFFD5DEEA);
            }
            if (lines.size() > rows) {
                R foot = new R(infoBox.x() + 6, infoBox.bottom() - LINE_H - 2, Math.max(0, infoBox.w() - 12), LINE_H);
                textRight(gg, foot, (first + 1) + "~" + Math.min(lines.size(), first + rows)
                        + " / " + lines.size(), 0xFF6E7E93);
            }
        });
    }

    /** 物品行矩形（列表态与点击判定共用同一套几何） */
    private R itemRowRect(int i) {
        boolean headInCap = infoCapRight != null && !infoCapRight.empty();
        int top = infoBox.y() + (headInCap ? 1 : 15);
        return new R(infoBox.x() + 4, top + i * LINE_H, Math.max(0, infoBox.w() - 8), LINE_H);
    }

    // ---------------------------------------------------------------- 箱子页（kind=4 / kind=5）

    /** 箱子总览（按需查询版）：列表走 kind=4，点一行进详情 kind=5 */
    private void drawBoxesQuery(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (boxBox == null || boxBox.empty()) {
            return;
        }
        boolean detail = !boxDetailKey.isEmpty();
        JsonObject jo = QueryClient.raw(detail ? ViewQueryPayload.KIND_CONTAINER : ViewQueryPayload.KIND_CONTAINERS);
        if (jo == null) {
            QueryClient.open(detail ? boxDetailRequest() : boxRequest());
        }
        fillIn(g, boxBox, 0xFF0C1017);
        borderIn(g, boxBox, 0xFF2A3342);
        if (jo == null) {
            clipped(g, boxBox, gg -> drawHintLines(gg, boxBox.x() + 6, boxBox.y() + 6,
                    Math.max(0, boxBox.w() - 12), List.of("正在查询…"), 0xFF93A3B8));
            return;
        }
        String error = QueryClient.str(jo, "error");
        if (!error.isEmpty()) {
            clipped(g, boxBox, gg -> drawHintLines(gg, boxBox.x() + 6, boxBox.y() + 6,
                    Math.max(0, boxBox.w() - 12), List.of(error), 0xFFE0B36A));
            return;
        }
        if (detail) {
            drawBoxDetailQuery(g, mouseX, mouseY, jo);
        } else {
            drawBoxListQuery(g, mouseX, mouseY, jo);
        }
    }

    private void drawBoxListQuery(GuiGraphicsExtractor g, int mouseX, int mouseY, JsonObject jo) {
        List<JsonObject> all = QueryClient.arr(jo, "containers");
        // 左下角那块空白放本页汇总（这一页不分两栏，物品框用不上）
        if (infoBox != null && !infoBox.empty()) {
            int empty = 0;
            int usedSlots = 0;
            int totalSlots = 0;
            for (JsonObject c : all) {
                if (QueryClient.num(c, "used") <= 0) {
                    empty++;
                }
                usedSlots += (int) Math.max(0L, QueryClient.num(c, "used"));
                totalSlots += (int) Math.max(0L, QueryClient.num(c, "size"));
            }
            int y = infoBox.y() + 4;
            int w = Math.max(0, infoBox.w() - 12);
            for (String line : List.of("共 " + all.size() + " 只箱子（空箱 " + empty + " 只）",
                    "已占 " + usedSlots + " / " + totalSlots + " 格")) {
                if (y + LINE_H > infoBox.bottom()) {
                    break;
                }
                textCenter(g, new R(infoBox.x() + 6, y, w, LINE_H), line, 0xFF8FA0B8);
                y += LINE_H;
            }
        }
        List<JsonObject> boxes = boxRowsQuery(jo);
        int rows = Math.max(1, rowsVisible(boxBox, BOX_ROW_H));
        int max = Math.max(0, boxes.size() - rows);
        boxScroll = Math.max(0, Math.min(boxScroll, max));
        String[] hoverTop = {""};
        clipped(g, boxBox, gg -> {
            if (boxes.isEmpty()) {
                textIn(gg, new R(boxBox.x() + 6, boxBox.y() + 6, Math.max(0, boxBox.w() - 12), LINE_H),
                        boxNonEmptyOnly ? "没有非空的箱子。" : "此仓库暂无箱子数据。", 0xFF93A3B8);
                if (!boxNonEmptyOnly) {
                    textIn(gg, new R(boxBox.x() + 6, boxBox.y() + 20, Math.max(0, boxBox.w() - 12), LINE_H),
                            "请先扫描仓库；若刚升级模组，请确认服务端也是同一版本。", 0xFF7F8EA3);
                }
                return;
            }
            for (int i = 0; i < rows; i++) {
                int idx = boxScroll + i;
                if (idx >= boxes.size()) {
                    break;
                }
                JsonObject box = boxes.get(idx);
                R row = boxRowRect(i);
                boolean hover = row.holds(mouseX, mouseY);
                if (hover) {
                    fillIn(gg, row, 0xFF1A2331);
                }
                long used = QueryClient.num(box, "used");
                long size = QueryClient.num(box, "size");
                int usedW = 66;
                int nameW = Math.max(0, row.w() - usedW - 10);
                String head = QueryClient.str(box, "pos") + " · " + QueryClient.str(box, "blockName")
                        + (QueryClient.flag(box, "doubleChest") ? " · 双联箱" : "");
                textIn(gg, new R(row.x() + 4, row.y() + 3, nameW, LINE_H),
                        fitChecked(head, nameW, row), hover ? 0xFFFFFFFF : 0xFFD5DEEA);
                textRight(gg, new R(row.right() - usedW, row.y() + 3, usedW, LINE_H),
                        used + "/" + size, used > 0 ? 0xFF8FB7E8 : 0xFF6E7E93);
                List<String> regions = strArr(box, "regions");
                String dim = QueryClient.str(box, "dimensionName");
                String second = used <= 0 ? "空箱" : used + " 格占用";
                if (!regions.isEmpty()) {
                    second = second + " · " + String.join(" / ", regions);
                } else if (!dim.isEmpty()) {
                    second = second + " · " + dim;
                } else {
                    second = second + " · 不在任何仓库区域内";
                }
                int secondW = Math.max(0, row.w() - 8);
                textIn(gg, new R(row.x() + 4, row.y() + 14, secondW, LINE_H),
                        fitChecked(second, secondW, row), hover ? 0xFFD8E6F8 : 0xFF7C8CA1);
                if (hover && used > 0) {
                    hoverTop[0] = used + "/" + size + " 格 · " + dim;
                }
            }
        });
        R foot = new R(boxBox.x() + 6, boxBox.bottom() - LINE_H - 2, Math.max(0, boxBox.w() - 12), LINE_H);
        if (boxes.size() > rows) {
            textRight(g, foot, (boxScroll + 1) + "~" + Math.min(boxes.size(), boxScroll + rows)
                    + " / " + boxes.size(), 0xFF6E7E93);
        } else if (!hoverTop[0].isEmpty()) {
            textIn(g, foot, fitChecked(hoverTop[0], foot.w(), foot), 0xFFE0B36A);
        }
    }

    private void drawBoxDetailQuery(GuiGraphicsExtractor g, int mouseX, int mouseY, JsonObject jo) {
        JsonObject c = jo.has("container") && jo.get("container").isJsonObject()
                ? jo.getAsJsonObject("container") : null;
        List<String> lines = new ArrayList<>();
        if (c != null) {
            long used = QueryClient.num(c, "used");
            long size = QueryClient.num(c, "size");
            long total = QueryClient.num(c, "totalItems");
            String dim = QueryClient.str(c, "dimensionName");
            lines.add(QueryClient.str(c, "pos") + " · " + QueryClient.str(c, "blockName")
                    + (QueryClient.flag(c, "doubleChest") ? " · 双联箱" : "")
                    + " · " + used + "/" + size + " 格 · 共 " + total + " 个"
                    + (dim.isEmpty() ? "" : " · " + dim));
            List<JsonObject> items = QueryClient.arr(c, "items");
            if (used <= 0 || items.isEmpty()) {
                lines.add("空箱");
            }
            for (JsonObject it : items) {
                String cat = QueryClient.str(it, "category");
                lines.add("槽 " + QueryClient.num(it, "slot") + " · " + queryItemName(it)
                        + " ×" + QueryClient.num(it, "count") + (cat.isEmpty() ? "" : " · " + cat)
                        + slotMeta(it));
            }
        } else {
            lines.add("索引中不存在该容器，可能刚重新扫描过。");
        }
        boolean headInCap = infoCapRight != null && !infoCapRight.empty();
        // 箱子页的布局里没有左栏 infoBox（清单用 boxBox），详情标题只能退回 boxBox ——
        // 直接用 infoBox 会在「箱子页点进容器详情」时空指针崩客户端（2026-10-01 实机崩过）。
        R host = infoBox != null && !infoBox.empty() ? infoBox
                : (boxBox != null && !boxBox.empty() ? boxBox : null);
        if (host == null) {
            return;
        }
        R headBox = headInCap ? infoCapRight
                : new R(host.x() + 6, host.y() + 3, Math.max(0, host.w() - 12), LINE_H);
        String stat = c == null ? "容器详情"
                : QueryClient.num(c, "used") + "/" + QueryClient.num(c, "size") + " 格";
        textCenter(g, headBox, fitChecked(stat, headBox.w(), headBox), 0xFFB9C8DA);
        // 标题画进 boxBox 里时必须给它留一行，否则第一行详情会和标题叠在一起（实机出现过）。
        boolean headInsideBox = !headInCap && host == boxBox;
        int rowPad = headInsideBox ? LINE_H : 0;
        int rows = Math.max(1, rowsVisible(boxBox, LINE_H) - (headInsideBox ? 1 : 0));
        int max = Math.max(0, lines.size() - rows);
        boxDetailScroll = Math.max(0, Math.min(boxDetailScroll, max));
        final int first = boxDetailScroll;
        clipped(g, boxBox, gg -> {
            for (int i = 0; i < rows; i++) {
                int idx = first + i;
                if (idx >= lines.size()) {
                    break;
                }
                int y = boxBox.y() + 2 + rowPad + i * LINE_H;
                R line = new R(boxBox.x() + 4, y, Math.max(0, boxBox.w() - 8), LINE_H);
                boolean over = line.holds(mouseX, mouseY);
                if (over) {
                    fillIn(gg, line, 0xFF1A2331);
                }
                textIn(gg, new R(line.x() + 2, line.y(), Math.max(0, line.w() - 4), LINE_H),
                        fitChecked(lines.get(idx), Math.max(0, line.w() - 4), line),
                        idx == 0 ? 0xFFFFFFFF : 0xFFD5DEEA);
            }
            if (lines.size() > rows) {
                R foot = new R(boxBox.x() + 6, boxBox.bottom() - LINE_H - 2, Math.max(0, boxBox.w() - 12), LINE_H);
                textRight(gg, foot, (first + 1) + "~" + Math.min(lines.size(), first + rows)
                        + " / " + lines.size(), 0xFF6E7E93);
            }
        });
    }

    /** 箱子行矩形（列表态与点击判定共用同一套几何） */
    private R boxRowRect(int i) {
        return new R(boxBox.x() + 2, boxBox.y() + 2 + i * BOX_ROW_H, Math.max(0, boxBox.w() - 4), BOX_ROW_H - 2);
    }

    /**
     * 箱子清单：客户端「只看非空」过滤 + 客户端排序（画与点共用同一份顺序，否则点到的不是看到的那只）。
     *
     * <p>协议里只有「已占格数」没有「箱内物品件数」，所以「排序：数量」这一档暂时按已占格数降序排。
     */
    private List<JsonObject> boxRowsQuery(JsonObject jo) {
        List<JsonObject> boxes = new ArrayList<>();
        for (JsonObject c : QueryClient.arr(jo, "containers")) {
            if (boxNonEmptyOnly && QueryClient.num(c, "used") <= 0) {
                continue;
            }
            boxes.add(c);
        }
        boxes.sort((a, b) -> {
            int[] pa = posOf(a);
            int[] pb = posOf(b);
            int c;
            if (boxSort == 1) {                        // 坐标
                c = Integer.compare(pa[0], pb[0]);
                if (c == 0) {
                    c = Integer.compare(pa[1], pb[1]);
                }
            } else {                                   // 占用（默认）/ 数量
                c = Long.compare(QueryClient.num(b, "used"), QueryClient.num(a, "used"));
            }
            if (c == 0) {
                c = Integer.compare(pa[1], pb[1]);
            }
            if (c == 0) {
                c = Integer.compare(pa[2], pb[2]);
            }
            if (c == 0) {
                c = Integer.compare(pa[0], pb[0]);
            }
            return c;
        });
        return boxes;
    }

    /** 物品详情一共几行（画与滚共用，免得两边算得不一样） */
    private int itemDetailLineCount(JsonObject jo) {
        JsonObject item = jo != null && jo.has("item") && jo.get("item").isJsonObject()
                ? jo.getAsJsonObject("item") : null;
        if (item == null) {
            return 1;
        }
        return 1 + Math.max(1, QueryClient.arr(item, "locations").size());
    }

    /** 容器详情一共几行（画与滚共用） */
    private int boxDetailLineCount(JsonObject jo) {
        JsonObject c = jo != null && jo.has("container") && jo.get("container").isJsonObject()
                ? jo.getAsJsonObject("container") : null;
        if (c == null) {
            return 1;
        }
        int items = QueryClient.arr(c, "items").size();
        return QueryClient.num(c, "used") <= 0 || items == 0 ? 2 : 1 + items;
    }

    // ---------------------------------------------------------------- 概览页的「索引状态」（kind=1）
    /**
     * 概览页左栏那段「索引状态」：扫描到哪儿、上次扫描耗时、磁盘恢复、容器/槽位总数。
     *
     * <p>右栏 {@code infoBlock} 已经被「当前选中 …」占满，这里画在左栏的 {@code infoBox} 里。
     */
    private void drawIndexStatus(GuiGraphicsExtractor g) {
        if (!QueryClient.supported()) {
            return;
        }
        JsonObject jo = QueryClient.raw(ViewQueryPayload.KIND_OVERVIEW);
        if (jo == null) {
            QueryClient.open(QueryClient.Request.of(ViewQueryPayload.KIND_OVERVIEW, queryRegion()));
        }
        R area = infoBox != null && !infoBox.empty() ? infoBox : null;
        if (area == null) {
            // 窄屏（427×240）左栏分不到 infoBox：退化成仓库列表下面的一行摘要，
            // 不画整块，更不能因为 infoBox 为空就直接崩。
            if (jo == null || listBox == null || listBox.empty()) {
                return;
            }
            R foot = new R(listBox.x() + 4, listBox.bottom() + 2,
                    Math.max(0, listBox.w() - 8), LINE_H);
            if (foot.bottom() > content.bottom()) {
                return;
            }
            String line = "索引 " + QueryClient.num(jo, "boxCount") + " 箱 · "
                    + QueryClient.num(jo, "itemKinds") + " 种 · "
                    + QueryClient.num(jo, "totalItems") + " 件";
            textIn(g, foot, fitChecked(line, foot.w(), foot), 0xFF8FA0B8);
            return;
        }
        fillIn(g, area, 0xFF0C1017);
        borderIn(g, area, 0xFF2A3342);
        List<String> lines = new ArrayList<>();
        if (jo == null) {
            lines.add("索引状态：正在查询…");
        } else {
            String scanRegion = QueryClient.str(jo, "lastScanRegion");
            long millis = QueryClient.num(jo, "lastScanMillis");
            lines.add("索引状态" + (scanRegion.isEmpty() ? "" : " · 最近扫描 " + scanRegion
                    + (millis > 0 ? " (" + millis + "ms)" : "")));
            if (QueryClient.flag(jo, "scannerRunning")) {
                String prog = QueryClient.str(jo, "scannerProgress");
                lines.add("正在扫描：" + (prog.isEmpty() ? "进行中…" : prog));
            } else {
                lines.add("已扫区块 " + QueryClient.num(jo, "scannedChunks")
                        + " · 跳过 " + QueryClient.num(jo, "skippedChunks"));
            }
            lines.add("容器 " + QueryClient.num(jo, "boxCount") + " 只（空箱 "
                    + QueryClient.num(jo, "emptyContainers") + " 只）· 槽位 "
                    + QueryClient.num(jo, "usedSlots") + "/" + QueryClient.num(jo, "capacity"));
            lines.add("物品种类 " + QueryClient.num(jo, "itemKinds") + " · 物品共 "
                    + QueryClient.num(jo, "totalItems") + " 个"
                    + (QueryClient.flag(jo, "restoredFromDisk") ? " · 来自磁盘缓存" : ""));
        }
        clipped(g, area, gg -> drawHintLines(gg, area.x() + 6, area.y() + 3,
                Math.max(0, area.w() - 12), lines, 0xFF93A3B8));
    }

    // ================================================================== 箱子总览（子页「箱子」）

    /**
     * 箱子总览：一只箱子一行（双联箱在服务端索引里本来就是一条记录，所以不会出现两行）。
     * 第一行＝坐标 · 方块名 · 双联箱标注，右端＝已占/总格；第二行＝物品总数 · 主要物品（空箱写「空箱」）。
     * 数据是服务端随快照一起下发的（{@code Region.boxes}），本页不做任何网络请求。
     */
    private void drawBoxes(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (boxBox == null || boxBox.empty()) {
            return;
        }
        // 批次 5：服务端支持面板查询就用 kind=4 / kind=5，否则退回旧的整包快照
        if (QueryClient.supported()) {
            drawBoxesQuery(g, mouseX, mouseY);
            return;
        }
        fillIn(g, boxBox, 0xFF0C1017);
        borderIn(g, boxBox, 0xFF2A3342);
        List<ClientSnapshot.Box> boxes = boxList();

        // 左下角那块空白（这一页不分两栏，物品框用不上）正好放本页汇总，免得看着像没画完
        if (infoBox != null && !infoBox.empty()) {
            List<ClientSnapshot.Box> all = boxesOf(selectedRegion());
            int empty = 0;
            int usedSlots = 0;
            int totalSlots = 0;
            long items = 0L;
            for (ClientSnapshot.Box b : all) {
                if (b == null) {
                    continue;
                }
                if (b.used <= 0) {
                    empty++;
                }
                usedSlots += Math.max(0, b.used);
                totalSlots += Math.max(0, b.size);
                items += Math.max(0L, b.items);
            }
            int y = infoBox.y() + 4;
            int w = Math.max(0, infoBox.w() - 12);
            for (String line : List.of(
                    "共 " + all.size() + " 只箱子（空箱 " + empty + " 只）",
                    "已占 " + usedSlots + " / " + totalSlots + " 格",
                    "箱内物品共 " + items + " 个")) {
                if (y + LINE_H > infoBox.bottom()) {
                    break;
                }
                textCenter(g, new R(infoBox.x() + 6, y, w, LINE_H), line, 0xFF8FA0B8);
                y += LINE_H;
            }
        }
        int rows = Math.max(1, rowsVisible(boxBox, BOX_ROW_H));
        int max = Math.max(0, boxes.size() - rows);
        boxScroll = Math.max(0, Math.min(boxScroll, max));
        String[] hoverTop = {""};
        clipped(g, boxBox, gg -> {
            if (!ClientSnapshot.hasData()) {
                textIn(gg, new R(boxBox.x() + 6, boxBox.y() + 6, Math.max(0, boxBox.w() - 12), LINE_H),
                        "正在接收服务器仓库数据…", 0xFF93A3B8);
                return;
            }
            if (boxes.isEmpty()) {
                textIn(gg, new R(boxBox.x() + 6, boxBox.y() + 6, Math.max(0, boxBox.w() - 12), LINE_H),
                        boxNonEmptyOnly ? "没有非空的箱子。" : "此仓库暂无箱子数据。", 0xFF93A3B8);
                if (!boxNonEmptyOnly) {
                    textIn(gg, new R(boxBox.x() + 6, boxBox.y() + 20, Math.max(0, boxBox.w() - 12), LINE_H),
                            "请先扫描仓库；若刚升级模组，请确认服务端也是同一版本。", 0xFF7F8EA3);
                }
                return;
            }
            for (int i = 0; i < rows; i++) {
                int idx = boxScroll + i;
                if (idx >= boxes.size()) {
                    break;
                }
                ClientSnapshot.Box box = boxes.get(idx);
                R row = new R(boxBox.x() + 2, boxBox.y() + 2 + i * BOX_ROW_H,
                        Math.max(0, boxBox.w() - 4), BOX_ROW_H - 2);
                boolean hover = row.holds(mouseX, mouseY);
                if (hover) {
                    fillIn(gg, row, 0xFF1A2331);
                }
                int usedW = 66;
                int nameW = Math.max(0, row.w() - usedW - 10);
                String name = boxName(box);
                // 原版双联箱的名字已经写明「大型箱子」，再加「· 双联箱」是废话；模组箱子（名字里没有大小）才标
                String dblTag = box.dbl && !"minecraft:chest".equals(box.block) ? " · 双联箱" : "";
                String head = box.x + "," + box.y + "," + box.z + " · " + name + dblTag;
                textIn(gg, new R(row.x() + 4, row.y() + 3, nameW, LINE_H),
                        fitChecked(head, nameW, row), hover ? 0xFFFFFFFF : 0xFFD5DEEA);
                textRight(gg, new R(row.right() - usedW, row.y() + 3, usedW, LINE_H),
                        box.used + "/" + box.size, box.used > 0 ? 0xFF8FB7E8 : 0xFF6E7E93);
                String top = topTextOf(box);
                // 批次 6 · D3：先报「这箱主要装什么」（服务端按非空槽数→件数挑的分类）
                String dom = box.dominant == null || box.dominant.isEmpty() ? ""
                        : "主 " + box.dominant + " " + box.domSlots + "格/" + box.domCount + "件";
                String second = box.items <= 0 ? "空箱"
                        : (dom.isEmpty() ? "" : dom + " · ") + box.items + " 个"
                                + (top.isEmpty() ? "" : " · " + top);
                int secondW = Math.max(0, row.w() - 8);
                textIn(gg, new R(row.x() + 4, row.y() + 14, secondW, LINE_H),
                        fitChecked(second, secondW, row), hover ? 0xFFD8E6F8 : 0xFF7C8CA1);
                if (hover && box.used > 0) {
                    hoverTop[0] = box.used + "/" + box.size + " 格 · " + box.items + " 个"
                            + (dom.isEmpty() ? "" : " · " + dom)
                            + (top.isEmpty() ? "" : " · " + top);
                }
            }
        });
        R foot = new R(boxBox.x() + 6, boxBox.bottom() - LINE_H - 2, Math.max(0, boxBox.w() - 12), LINE_H);
        if (boxes.size() > rows) {
            textRight(g, foot, (boxScroll + 1) + "~" + Math.min(boxes.size(), boxScroll + rows)
                    + " / " + boxes.size(), 0xFF6E7E93);
        } else if (!hoverTop[0].isEmpty()) {
            textIn(g, foot, fitChecked(hoverTop[0], foot.w(), foot), 0xFFE0B36A);
        }
    }

    /** 箱子总览的行（当前仓库 + 「只看非空」过滤 + 当前排序，都是纯客户端的） */
    private List<ClientSnapshot.Box> boxList() {
        List<ClientSnapshot.Box> list = new ArrayList<>();
        for (ClientSnapshot.Box b : boxesOf(selectedRegion())) {
            if (b == null) {
                continue;
            }
            if (boxNonEmptyOnly && b.used <= 0) {
                continue;
            }
            list.add(b);
        }
        list.sort((a, b) -> {
            int c;
            if (boxSort == 1) {                       // 坐标
                c = Integer.compare(a.x, b.x);
                if (c == 0) {
                    c = Integer.compare(a.y, b.y);
                }
            } else if (boxSort == 2) {                // 物品数
                c = Long.compare(b.items, a.items);
                if (c == 0) {
                    c = Integer.compare(b.used, a.used);
                }
            } else {                                  // 占用（默认）
                c = Integer.compare(b.used, a.used);
                if (c == 0) {
                    c = Long.compare(b.items, a.items);
                }
            }
            if (c == 0) {
                c = Integer.compare(a.y, b.y);
            }
            if (c == 0) {
                c = Integer.compare(a.z, b.z);
            }
            if (c == 0) {
                c = Integer.compare(a.x, b.x);
            }
            return c;
        });
        return list;
    }

    /** 某个仓库的箱子表（带缓存：仓库没换、快照没换就直接用上一次的） */
    private List<ClientSnapshot.Box> boxesOf(String region) {
        ClientSnapshot.Region view = ClientSnapshot.find(region);
        long stamp = ClientSnapshot.receivedAt();
        if (view != null && region.equals(boxKey) && stamp == boxStamp) {
            return boxView;
        }
        List<ClientSnapshot.Box> list = view == null || view.boxes == null ? List.of() : view.boxes;
        boxKey = region;
        boxStamp = stamp;
        boxView = list;
        return boxView;
    }

    /** 「排序」按钮上的字（点一下换一种，循环 占用 → 坐标 → 数量） */
    private String sortLabel() {
        return switch (boxSort) {
            case 1 -> "排序：坐标 ▸";
            case 2 -> "排序：数量 ▸";
            default -> "排序：占用 ▸";
        };
    }

    /** 「只看非空」按钮上的字（按钮只有 56 宽，写全「只看非空：关」会触发文字自检并缩成「只看非空…」） */
    private String boxFilterLabel() {
        return boxNonEmptyOnly ? "非空：开" : "非空：关";
    }

    /**
     * 物品区那一行**短**统计（给标题带右端用）。
     * 完整句子（「还没有东西（点“扫描仓库”或往箱子里放点东西）」这种）不放在窄条里——它会被切成半句话，
     * 看起来就像文字越界；长提示一律交给 {@link #infoHint} 画在物品框里（那里有整块的空白）。
     */
    private String infoStat(String region, String query, List<ClientSnapshot.Item> items, int maxW) {
        if (!ClientSnapshot.hasData()) {
            return "等数据…";
        }
        ClientSnapshot.Region view = ClientSnapshot.find(region);
        if (view == null) {
            return "无数据";
        }
        if (items.isEmpty()) {
            return query.isEmpty() ? "空" : "没匹配";
        }
        long sum = 0L;
        for (ClientSnapshot.Item it : items) {
            sum += it.count;
        }
        String full = query.isEmpty()
                ? items.size() + " 种 · 共 " + view.totalItems + " 个 · 占 " + view.usedSlots + "/" + view.capacity + " 格"
                : "命中 " + items.size() + " 种 · 共 " + sum + " 个（全仓 " + view.totalItems + " 个）";
        if (this.font.width(full) <= maxW) {
            return full;
        }
        String brief = query.isEmpty()
                ? items.size() + " 种/" + view.totalItems + " 个"
                : "命中 " + items.size() + " 种/" + sum + " 个";
        return brief;
    }

    /** 标题带右端要留多宽（按压缩过的短统计算，别为了塞长句子把左标题挤没） */
    private int infoStatWidth() {
        int room = infoCap == null ? 160 : Math.max(48, infoCap.w() - 76);
        return this.font.width(infoStat(selectedRegion(), searchText.trim(), itemsOf(selectedRegion()), room)) + 4;
    }

    /** 当前选中仓库 + 当前搜索词的短统计（不给宽度上限时按最长的那版算） */
    private String infoStat() {
        String region = selectedRegion();
        return infoStat(region, searchText.trim(), itemsOf(region), Integer.MAX_VALUE);
    }

    /**
     * 物品框里的多行提示：只在「一行物品都没有」时用。
     * 返回的每一行会再按框宽折行，绝不画到框外。
     */
    private List<String> infoHint(String region, String query, List<ClientSnapshot.Item> items) {
        if (!ClientSnapshot.hasData()) {
            return List.of("正在接收服务器仓库数据…", ClientSnapshot.note());
        }
        if (!items.isEmpty()) {
            return List.of();
        }
        if (!query.isEmpty()) {
            return List.of("此仓库中没有匹配「" + query + "」的物品。",
                    "请更换关键词，或清空上方搜索框查看全部。");
        }
        return List.of("此仓库暂无物品。",
                "请先把物品放入箱内，再点击右侧「扫描仓库」。",
                "也可点击「显示边界」，确认仓库范围是否圈定正确。");
    }

    /** 在物品框里把提示按宽度折行画完（超出框高的部分直接不画） */
    private void drawHintLines(GuiGraphicsExtractor gg, int x, int y, int w, List<String> lines, int color) {
        if (infoBox == null || w <= 0) {
            return;
        }
        int bottom = infoBox.bottom() - 2;
        for (String line : lines) {
            for (String part : wrap(line, w)) {
                if (y + LINE_H > bottom) {
                    return;
                }
                textIn(gg, new R(x, y, w, LINE_H), part, color);
                y += LINE_H;
            }
        }
    }

    /** 左下：选中的这个仓库里有什么东西（可滚动，内容一律裁在 infoBox 里） */
    private void drawInfo(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (infoBox == null || infoBox.empty()) {
            return;
        }
        // 批次 5：服务端支持面板查询就走按需查询（列表 kind=2 / 详情 kind=3），否则退回旧的整包快照路径
        if (QueryClient.supported()) {
            drawInfoQuery(g, mouseX, mouseY, selectedRegion());
            return;
        }
        String region = selectedRegion();
        String query = searchText.trim();
        // 标题也按宽度退化：宁可只写「物品 · main」，也不要被切成「物品 · main · 搜…」
        String base = "物品" + (region.isEmpty() ? "" : " · " + region);
        int capW = infoCapLeft == null ? 0 : infoCapLeft.w();
        String t0 = base + (query.isEmpty() ? "" : " · 搜索「" + query + "」");
        String t1 = base + (query.isEmpty() ? "" : " · 搜「" + query + "」");
        textCenter(g, infoCapLeft,
                this.font.width(t0) <= capW ? t0 : (this.font.width(t1) <= capW ? t1 : base), 0xFF8FA0B8);

        fillIn(g, infoBox, 0xFF0C1017);
        borderIn(g, infoBox, 0xFF2A3342);

        if (!ClientSnapshot.hasData()) {
            clipped(g, infoBox, gg -> drawHintLines(gg, infoBox.x() + 6, infoBox.y() + 3,
                    Math.max(0, infoBox.w() - 12), infoHint(region, query, List.of()), 0xFF93A3B8));
            return;
        }

        List<ClientSnapshot.Item> items = itemsOf(region);
        List<String> hint = infoHint(region, query, items);

        // 统计数字画在物品标题带的右端（物品框里于是全是物品行）；标题带放不下时退回框内第一行。
        // 注意这一句必须在 clipped(infoBox) 之外 —— 画在 clip 里会被剪掉。
        boolean headInCap = infoCapRight != null && !infoCapRight.empty();
        R headBox = headInCap ? infoCapRight
                : new R(infoBox.x() + 6, infoBox.y() + 3, Math.max(0, infoBox.w() - 12), LINE_H);
        textCenter(g, headBox, infoStat(region, query, items, headBox.w()), 0xFFB9C8DA);
        final int top = headInCap ? infoBox.y() + 1 : infoBox.y() + 15;

        String[] hoverLoc = {""};
        clipped(g, infoBox, gg -> {
            int rows = infoRows();
            int max = Math.max(0, items.size() - rows);
            infoScroll = Math.max(0, Math.min(infoScroll, max));
            int countW = 46;
            for (int i = 0; i < rows; i++) {
                int idx = infoScroll + i;
                if (idx >= items.size()) {
                    break;
                }
                ClientSnapshot.Item row = items.get(idx);
                int y = top + i * LINE_H;
                R line = new R(infoBox.x() + 4, y, Math.max(0, infoBox.w() - 8), LINE_H);
                boolean over = line.holds(mouseX, mouseY);
                if (over) {
                    fillIn(gg, line, 0xFF1A2331);
                }
                textIn(gg, new R(line.x() + 2, y + 1, Math.max(0, line.w() - countW - 6), LINE_H),
                        itemName(row), over ? 0xFFFFFFFF : 0xFFD5DEEA);
                textRight(gg, new R(line.right() - countW, y + 1, countW, LINE_H), "×" + row.count, 0xFF8FB7E8);
                if (over && row.loc != null && !row.loc.isEmpty()) {
                    hoverLoc[0] = row.loc;
                }
            }
            R foot = new R(infoBox.x() + 6, infoBox.bottom() - LINE_H - 2, Math.max(0, infoBox.w() - 12), LINE_H);
            if (items.size() > rows) {
                textRight(gg, foot, (infoScroll + 1) + "~" + Math.min(items.size(), infoScroll + rows)
                        + " / " + items.size(), 0xFF6E7E93);
            } else if (!hoverLoc[0].isEmpty()) {
                textIn(gg, foot, hoverLoc[0], 0xFFE0B36A);
            } else if (items.isEmpty() && rows > 0) {
                // 一行物品都没有：整块空白正好用来把话说完整（长提示绝不塞进窄条里切一半）
                drawHintLines(gg, infoBox.x() + 6, top + 2, Math.max(0, infoBox.w() - 12), hint, 0xFF93A3B8);
            } else if (items.size() > 0 && rows <= 0) {
                textIn(gg, foot, pickFit(foot.w(), "窗口过小：滚轮翻动，或用指令查看", "窗口过小", "过小"), 0xFFE0B36A);
            }
        });
    }

    /** 右上：「当前选中 …」那几行（普通玩家是只读说明） */
    private void drawSelected(GuiGraphicsExtractor g) {
        if (infoBlock == null || infoBlock.empty()) {
            return;
        }
        // 批次 5：概览页在左栏空白里补一段「索引状态」（数据走按需查询 kind=1）
        if ("概览".equals(subName())) {
            drawIndexStatus(g);
        }
        List<RegionCache.Entry> list = RegionCache.list();
        if (!admin) {
            int w = infoBlock.w();
            textIn(g, new R(infoBlock.x(), infoBlock.y(), w, LINE_H),
                    pickFit(w, "普通玩家模式：可取货、入库、查看仓库。", "普通玩家：可取货、入库", "普通玩家"),
                    0xFFE0B36A);
            textIn(g, new R(infoBlock.x(), infoBlock.y() + LINE_H, w, LINE_H),
                    pickFit(w, "修改仓库设置 / 指挥搬运工需要管理员（OP）权限。",
                            "修改设置 / 指挥搬运工需要 OP", "需要 OP 权限"),
                    0xFF8FA0B8);
            return;
        }
        R r0 = new R(infoBlock.x(), infoBlock.y(), infoBlock.w(), LINE_H);
        if (!list.isEmpty() && sel >= 0 && sel < list.size()) {
            RegionCache.Entry entry = list.get(sel);
            textCenter(g, r0, "当前选中 " + entry.name, 0xFFFFFFFF);
            textCenter(g, new R(r0.x(), r0.y() + LINE_H, r0.w(), LINE_H), entry.cornerText(), 0xFFCFE0F5);
            if (infoBlock.h() >= 3 * LINE_H + 2) {
                // 同样是拼出来的信息：窄就整段丢掉尾巴（第一段「尺寸」永远留着）
                List<String> seg = new ArrayList<>();
                seg.add("尺寸");
                for (String part : entry.sizeText().split(" · ")) {
                    seg.add(part);
                }
                seg.add(entry.volume() + " 格");
                seg.add(entry.dimensionText());
                String s = String.join(" · ", seg);
                while (seg.size() > 1 && this.font.width(s) > r0.w()) {
                    seg.remove(seg.size() - 1);
                    s = String.join(" · ", seg);
                }
                textCenter(g, new R(r0.x(), r0.y() + 2 * LINE_H, r0.w(), LINE_H), s, 0xFF8FA0B8);
            }
            if (gridTruncated) {
                textLeft(g, new R(r0.x(), r0.y() + 3 * LINE_H, r0.w(), LINE_H),
                        pickFit(r0.w(), "窗口过小：其余按钮已省略，可用 /warehouse 指令查看",
                                "窗口过小：其余按钮已省略", "其余按钮已省略"), 0xFFE0B36A);
            }
        } else {
            // 优化10：sel == -1 是「全部仓库」，明确写出来（旧文案是「（还没有仓库）」，会误导）
            if (list.isEmpty()) {
                textCenter(g, r0, "（还没有仓库）", 0xFF7F8EA3);
            } else {
                textCenter(g, r0, "当前范围 全部仓库", 0xFFFFFFFF);
                textCenter(g, new R(r0.x(), r0.y() + LINE_H, r0.w(), LINE_H),
                        "左栏第 0 行 · 跨 " + list.size() + " 个仓库统计", 0xFFCFE0F5);
                if (infoBlock.h() >= 3 * LINE_H + 2) {
                    textCenter(g, new R(r0.x(), r0.y() + 2 * LINE_H, r0.w(), LINE_H),
                            "边界显示 / 扩建 / 缩小需要指定单个仓库", 0xFF8FA0B8);
                }
            }
        }
    }

    /** 第 1 页「取货」：从当前仓库里挑一样东西，或者自己写名字，点取货让搬运工送来 */
    private void drawPick(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String region = pickScope();
        List<ClientSnapshot.Item> items = pickItems();
        String tail = pickQuery.isBlank() ? "" : "（筛选「" + pickQuery + "」）";
        textCenter(g, pickCap, region + " 里有 " + items.size() + " 种物品" + tail, 0xFF8FA0B8);
        if (pickBox == null || pickBox.empty()) {
            return;
        }
        fillIn(g, pickBox, 0xFF0C1017);
        borderIn(g, pickBox, 0xFF2A3342);

        int rows = pickRows();
        int max = Math.max(0, items.size() - rows);
        pickScroll = Math.max(0, Math.min(pickScroll, max));

        int locW = Math.max(0, pickBox.w() - 300);
        boolean withLoc = pickBox.w() >= 320;
        clipped(g, new R(pickBox.x() + 1, pickBox.y() + 1, Math.max(0, pickBox.w() - 2), Math.max(0, pickBox.h() - 2)),
                gg -> {
                    String head = region + " · 点击一行填入下方输入框";
                    if (items.size() > rows) {
                        head = head + "（滚轮 " + (pickScroll + 1) + "~"
                                + Math.min(items.size(), pickScroll + rows) + " / " + items.size() + "）";
                    }
                    R headRow = new R(pickBox.x() + 6, pickBox.y() + 3,
                            Math.max(0, pickBox.w() - 12 - 90), LINE_H);
                    textIn(gg, headRow, head, 0xFF8FA0B8);
                    int top = pickBox.y() + LINE_H + 4;
                    for (int i = 0; i < rows; i++) {
                        int idx = pickScroll + i;
                        if (idx >= items.size()) {
                            break;
                        }
                        ClientSnapshot.Item it = items.get(idx);
                        R line = new R(pickBox.x() + 2, top + i * LINE_H, Math.max(0, pickBox.w() - 4), LINE_H);
                        boolean hover = line.holds(mouseX, mouseY);
                        if (hover) {
                            fillIn(gg, line, 0xFF1A2331);
                        }
                        int countW = 52;
                        int nameW = Math.max(40, line.w() * 46 / 100);
                        textIn(gg, new R(line.x() + 4, line.y(), nameW, LINE_H), itemName(it),
                                hover ? 0xFFFFFFFF : 0xFFD5DEEA);
                        if (withLoc && locW > 40) {
                            textIn(gg, new R(line.x() + 8 + nameW, line.y(), locW, LINE_H), it.loc, 0xFF6E7E93);
                        }
                        textRight(gg, new R(line.right() - countW, line.y(), countW, LINE_H),
                                "×" + it.count, 0xFF9FB3CC);
                    }
                    if (items.isEmpty()) {
                        String empty = region.equals("全部仓库") ? "所有仓库里都没有物品"
                                : region.equals("未选仓库") ? "请先到「仓库」页选择一个仓库" : "仓库内暂无物品";
                        textIn(gg, new R(pickBox.x() + 6, pickBox.y() + LINE_H + 8,
                                        Math.max(0, pickBox.w() - 12), LINE_H),
                                empty, 0xFF93A3B8);
                    }
                });
    }

    /** 第 2 页「搬运工」：一个假人一行，右边是「派到这仓 / 设在这里 / 上岗收回 / 停下」 */
    private void drawPorter(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!admin) {
            textIn(g, captionLeft, "仅管理员（OP）可指挥搬运工。", 0xFFE0B36A);
            return;
        }
        // 子页「整理」：不画名册，只把「整理」做什么说清楚（动作按钮在下面那排）
        if ("整理".equals(subName())) {
            if (notesBox != null && !notesBox.empty()) {
                String region = taskRegion();
                // 说明文字按框宽折行：宁可少说一句，也不要被截成半句 + 「…」
                int y = notesBox.y();
                int bottom = notesBox.bottom();
                for (String line : List.of(
                        "整理：把箱内物品压实、按顺序排好，并把放错箱子的物品搬回它所属的箱子。",
                        "开始前会再弹一次确认框；进行中请勿再往箱子里放东西。"
                                + (region.isEmpty() ? "当前范围：全部仓库（每名搬运工各管自己的值守仓库）。"
                                        : "当前仓库：" + region))) {
                    int color = y == notesBox.y() ? 0xFF8FA0B8 : 0xFF6E7E93;
                    for (String part : wrap(line, notesBox.w())) {
                        if (y + LINE_H > bottom) {
                            return;
                        }
                        textIn(g, new R(notesBox.x(), y, notesBox.w(), LINE_H), part, color);
                        y += LINE_H;
                    }
                }
            }
            return;
        }
        List<ClientSnapshot.Bot> bots = ClientSnapshot.bots();
        // 小标题带那句已经由 captionText() 画过了（含「只显示前 N 个」提示），这里不重复画
        if (rowsBox == null || rowsBox.empty() || rowH <= 0) {
            return;
        }
        if (bots.isEmpty()) {
            textIn(g, new R(rowsBox.x(), rowsBox.y() + 4, rowsBox.w(), LINE_H),
                    "还没有搬运工。请点击下方的「＋新增搬运工」新增一个。", 0xFF93A3B8);
            return;
        }
        int visible = rowsVisible(rowsBox, rowH);
        int widestName = 0;
        for (ClientSnapshot.Bot b : bots) {
            widestName = Math.max(widestName, this.font.width(b.shown()) + 2);
        }
        final int needName = widestName;
        clipped(g, rowsBox, gg -> {
            // 这一块只是为了拿按钮宽度，和 init 里用的是同一个方法
            for (int i = 0; i < Math.min(bots.size(), visible); i++) {
                R row = rowRect(rowsBox, i, rowH);
                R[] b = botButtons(row);
                int textW = Math.max(0, b[0].x() - GAP - row.x());
                R text = new R(row.x(), row.y(), textW, row.h() - 2);
                ClientSnapshot.Bot bot = bots.get(i);
                // 选中的那一行给个底色（「确定」是按选中的这行发指令的）
                if (bot.name.equals(pickBot)) {
                    fillIn(gg, new R(row.x(), row.y(), Math.max(0, row.w()), Math.max(6, row.h() - 2)), 0xFF1D3350);
                } else if (text.holds(mouseX, mouseY)) {
                    fillIn(gg, text, 0xFF161E2A);
                }
                // 一行里要放：名字 / 值守仓库 / 值守点 / 在忙什么。名字是身份，优先给足；
                // 剩下的宽度按可用空间分档，装不下就整段不画（宁可少一项，也不画成省略号糊在一起）。
                int nameW = Math.min(Math.max(46, needName + 4), Math.max(46, text.w() - 40));
                int rest = Math.max(0, text.w() - nameW);
                int regW;
                int spotW;
                int busyW;
                if (rest >= 150) {
                    regW = Math.max(46, rest * 30 / 100);
                    spotW = Math.max(60, rest * 38 / 100);
                    busyW = Math.max(0, rest - regW - spotW);
                } else if (rest >= 96) {
                    regW = Math.max(46, rest / 3);
                    spotW = rest - regW;
                    busyW = 0;
                } else {
                    regW = rest;
                    spotW = 0;
                    busyW = 0;
                }
                int x = text.x();
                // 显示名（优化7）：设了就画自定义名，没设画注册名 —— 选中的判据仍然是注册名
                boolean nameClipped = this.font.width(bot.shown()) > nameW;
                textIn(gg, new R(x, text.y() + 3, nameW, LINE_H), bot.shown(), 0xFFFFFFFF);
                x += nameW;
                String reg = bot.region == null || bot.region.isEmpty()
                        ? (regW >= 72 ? "（没派仓库）" : "没派")
                        : bot.region;
                if (regW > 0) {
                    // 名字被截断时留一个空格，免得「WarehouseB..」和仓库名糊成一串
                    textIn(gg, new R(x, text.y() + 3, regW, LINE_H),
                            (nameClipped ? " " : "") + (regW >= 56 ? "值守：" : "") + reg, 0xFFCFE0F5);
                    x += regW;
                }
                if (spotW > 0) {
                    String spot = bot.spot == null || bot.spot.isEmpty() ? "自动" : bot.spot;
                    textIn(gg, new R(x, text.y() + 3, spotW, LINE_H),
                            (spotW >= 66 ? "值守点：" : "") + spot, 0xFF9FB3CC);
                    x += spotW;
                }
                if (busyW >= 24) {
                    String busy = bot.busy == null || bot.busy.isEmpty() ? (bot.present ? "在岗" : "不在") : bot.busy;
                    textIn(gg, new R(x, text.y() + 3, busyW, LINE_H), busy, 0xFF8FA0B8);
                }
            }
        });
        // 分配那一行的说明文字：说清楚「确定」会作用到谁身上
        if (assignLabel != null && !assignLabel.empty()) {
            textCenter(g, new R(assignLabel.x(), assignLabel.y(), assignLabel.w(), assignLabel.h()),
                    pickBot.isEmpty() ? "先点一行选中搬运工" : "把「" + botLabel(pickBot) + "」派到",
                    pickBot.isEmpty() ? 0xFFE0B36A : 0xFFCFE0F5);
        }
    }

    /**
     * 展开的仓库清单（弹出层）：画在所有控件之上、确认框之下。
     * 清单本身带裁剪，条目再多也不会画到框外。
     */
    private void drawAssignList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (assignList == null || assignList.empty()) {
            return;
        }
        List<RegionCache.Entry> list = RegionCache.list();
        fillIn(g, assignList, 0xFF141B26);
        borderIn(g, assignList, 0xFF4E8BD8);
        int lineH = LINE_H + 3;
        final int first = assignScroll;
        final int rows = assignRows;
        clipped(g, assignList, gg -> {
            for (int i = 0; i < rows; i++) {
                int idx = first + i;
                if (idx >= list.size()) {
                    break;
                }
                String name = list.get(idx).name;
                R line = new R(assignList.x() + 2, assignList.y() + 2 + i * lineH, Math.max(0, assignList.w() - 4), lineH);
                boolean chosen = name.equals(pickRegion);
                if (chosen) {
                    fillIn(gg, line, 0xFF1D3350);
                } else if (line.holds(mouseX, mouseY)) {
                    fillIn(gg, line, 0xFF24313F);
                }
                textIn(gg, new R(line.x() + 3, line.y() + 2, Math.max(0, line.w() - 6), LINE_H),
                        (chosen ? "✓ " : "") + name, chosen ? 0xFFFFFFFF : 0xFFCFE0F5);
            }
        });
        if (list.size() > rows) {
            // 条目多于可见行时，右下角给个「滚轮」提示（清单可滚动）
            String hint = (first + 1) + "~" + Math.min(list.size(), first + rows) + " / " + list.size();
            textRight(g, new R(assignList.x(), assignList.bottom() - LINE_H - 2, assignList.w() - 3, LINE_H),
                    hint, 0xFF6E7E93);
        }
    }

    /** 第 3 页「权限」：子页 0 = 三项开关（就是 /warehouse user perm 那三个），子页 1 = 操作日志 */
    private void drawPerm(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!admin) {
            textIn(g, captionLeft, "仅管理员（OP）可修改权限。", 0xFFE0B36A);
            return;
        }
        if (sub == 1) {
            drawAudit(g, mouseX, mouseY);
            return;
        }
        auditPrev = null;
        auditNext = null;
        List<ClientSnapshot.Account> accounts = ClientSnapshot.accounts();
        // 小标题带那句由 captionText() 画（会按宽度退化），这里不重复画
        if (rowsBox != null && !rowsBox.empty() && rowH > 0) {
            if (accounts.isEmpty()) {
                textIn(g, new R(rowsBox.x(), rowsBox.y() + 4, rowsBox.w(), LINE_H),
                        "还没有玩家有权限。用 /warehouse user perm <玩家名> take|bot|tidy on 添加，或用 /warehouse user migrate 从旧网页账号库导入。", 0xFF93A3B8);
            } else {
                int visible = rowsVisible(rowsBox, rowH);
                clipped(g, rowsBox, gg -> {
                    for (int i = 0; i < Math.min(accounts.size(), visible); i++) {
                        R row = rowRect(rowsBox, i, rowH);
                        R[] b = permButtons(row);
                        ClientSnapshot.Account acc = accounts.get(i);
                        R text = new R(row.x(), row.y(), Math.max(0, b[0].x() - GAP - row.x()), row.h() - 2);
                        if (text.holds(mouseX, mouseY)) {
                            fillIn(gg, text, 0xFF161E2A);
                        }
                        textIn(gg, new R(text.x(), text.y() + 3, text.w(), LINE_H), acc.name, 0xFFFFFFFF);
                    }
                });
            }
        }
        if (notesBox != null && !notesBox.empty()) {
            // 说明文字按框宽折行：宁可少说一句，也不要被截成半句 + 「…」
            int y = notesBox.y();
            int bottom = notesBox.bottom();
            for (String line : List.of(
                    "「取货」= 可用面板取货；「假人」= 可指挥搬运工；「整理」= 可整理仓库并刷新索引。",
                    "操作日志在「审计」子页。")) {
                int color = y == notesBox.y() ? 0xFF8FA0B8 : 0xFF6E7E93;
                for (String part : wrap(line, notesBox.w())) {
                    if (y + LINE_H > bottom) {
                        return;
                    }
                    textIn(g, new R(notesBox.x(), y, notesBox.w(), LINE_H), part, color);
                    y += LINE_H;
                }
            }
        }
    }

    /**
     * 「权限 → 审计」子页：最近的操作日志。
     *
     * <p>服务端最多给最近 500 条（{@code ViewQueryService.AUDIT_MAX}），每页 50 条；非管理员只回一句 error。
     * 翻页按钮不走控件，直接在绘制时算矩形、点击时命中，和箱子页的行几何一个套路。
     */
    private void drawAudit(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        auditPrev = null;
        auditNext = null;
        JsonObject jo = QueryClient.raw(ViewQueryPayload.KIND_AUDIT);
        if (jo == null) {
            QueryClient.open(auditRequest(auditPage));
            hintIn(g, "正在查询操作日志…");
            return;
        }
        String error = QueryClient.str(jo, "error");
        if (!error.isEmpty()) {
            hintIn(g, error);
            return;
        }
        List<JsonObject> rows = QueryClient.arr(jo, "entries");
        int page = (int) QueryClient.num(jo, "page");
        int pages = (int) Math.max(1L, QueryClient.num(jo, "pages"));
        long total = QueryClient.num(jo, "total");
        long allTotal = QueryClient.num(jo, "allTotal");
        if (page >= 1 && page != auditPage) {
            auditPage = Math.min(pages, page);
        }
        if (rowsBox != null && !rowsBox.empty() && rowH > 0) {
            if (rows.isEmpty()) {
                hintIn(g, "暂无操作记录。");
            } else {
                int visible = rowsVisible(rowsBox, rowH);
                clipped(g, rowsBox, gg -> {
                    for (int i = 0; i < Math.min(rows.size(), visible); i++) {
                        R row = rowRect(rowsBox, i, rowH);
                        if (row.holds(mouseX, mouseY)) {
                            fillIn(gg, row, 0xFF161E2A);
                        }
                        JsonObject e = rows.get(i);
                        String detail = QueryClient.str(e, "detail");
                        String head = QueryClient.str(e, "time") + "  "
                                + QueryClient.str(e, "who") + "：" + QueryClient.str(e, "action");
                        String text = detail.isEmpty() ? head : head + " · " + detail;
                        int w = Math.max(0, row.w() - 8);
                        textIn(gg, new R(row.x() + 4, row.y() + 3, w, LINE_H),
                                fitChecked(text, w, row), 0xFFD5E2F0);
                    }
                });
            }
        }
        // 页码 + 翻页按钮：优先占用下面的说明区，没有就压到行区底部
        R bar = notesBox != null && !notesBox.empty()
                ? new R(notesBox.x(), notesBox.y(), notesBox.w(), LINE_H)
                : (rowsBox != null && !rowsBox.empty()
                ? new R(rowsBox.x(), Math.max(rowsBox.y(), rowsBox.bottom() - LINE_H), rowsBox.w(), LINE_H)
                : null);
        if (bar == null || bar.w() <= 0) {
            return;
        }
        int btnW = Math.max(18, Math.min(40, bar.w() / 5));
        R prev = new R(bar.x(), bar.y(), btnW, bar.h());
        R next = new R(bar.right() - btnW, bar.y(), btnW, bar.h());
        boolean canPrev = auditPage > 1;
        boolean canNext = auditPage < pages;
        drawFlatBtn(g, prev, "◀", canPrev, canPrev && prev.holds(mouseX, mouseY));
        drawFlatBtn(g, next, "▶", canNext, canNext && next.holds(mouseX, mouseY));
        if (canPrev) {
            auditPrev = prev;
        }
        if (canNext) {
            auditNext = next;
        }
        String stat = "第 " + auditPage + "/" + pages + " 页 · 共 " + total + " 条";
        if (allTotal > total) {
            stat = stat + "（日志共 " + allTotal + " 条，面板最多看最近 500 条）";
        }
        R mid = new R(prev.right() + 4, bar.y(), Math.max(0, next.x() - prev.right() - 8), bar.h());
        textCenter(g, mid, fitChecked(stat, mid.w(), mid), 0xFF8FA0B8);
    }

    /** 审计页的提示文字（画在行区第一行） */
    private void hintIn(GuiGraphicsExtractor g, String text) {
        if (rowsBox != null && !rowsBox.empty()) {
            textIn(g, new R(rowsBox.x(), rowsBox.y() + 4, rowsBox.w(), LINE_H), text, 0xFF93A3B8);
        }
    }

    /** 审计页的自绘小按钮（不走控件，点它只改页码 + 重发一次查询） */
    private void drawFlatBtn(GuiGraphicsExtractor g, R r, String label, boolean enabled, boolean hover) {
        fillIn(g, r, hover && enabled ? 0xFF22303F : 0xFF151C26);
        borderIn(g, r, enabled ? 0xFF3A4757 : 0xFF232C38);
        textCenter(g, r, label, enabled ? 0xFFD5E2F0 : 0xFF556070);
    }

    /** 审计页翻页 */
    private void goAuditPage(int p) {
        int want = Math.max(1, p);
        if (want == auditPage) {
            return;
        }
        auditPage = want;
        QueryClient.invalidate(ViewQueryPayload.KIND_AUDIT);
        QueryClient.request(auditRequest(auditPage));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        // 输入框的占位提示画在控件之后（画在 background 那趟会被输入框自己的底色盖掉）
        for (EditHint hint : editHints) {
            drawEditHint(g, hint.box(), hint.hint());
        }
        if (pickOpen) {
            drawAssignList(g, mouseX, mouseY);
        }
        if (catDd != null && catDd.open) {
            // 「物品」页的分类下拉：画在控件之上
            catDd.draw(g, mouseX, mouseY);
        }
        if (pickDd != null && pickDd.open) {
            pickDd.draw(g, mouseX, mouseY);
        }
        if (taskDd != null && taskDd.open) {
            taskDd.draw(g, mouseX, mouseY);
        }
        if (pending != null) {
            drawConfirm(g, mouseX, mouseY);
        }
    }

    private String statusText() {
        String base;
        if (System.currentTimeMillis() - ClientFeedback.textAt() < 10000L && !ClientFeedback.text().isEmpty()) {
            base = ClientFeedback.text();
        } else {
            base = status;
        }
        if (tab == 0 && !QueryClient.supported()) {
            // 「仓库」页那几页要靠服务端的按需查询：老服务端没这个通道，数据退回整包快照
            return base == null || base.isEmpty() ? QUERY_UNSUPPORTED : base + "\n" + QUERY_UNSUPPORTED;
        }
        return base;
    }

    private String fit(String text, int max) {
        String value = text == null ? "" : text;
        if (max <= 0) {
            return "";
        }
        if (this.font.width(value) <= max) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (this.font.width(sb.toString() + value.charAt(i) + "…") > max) {
                break;
            }
            sb.append(value.charAt(i));
        }
        return sb + "…";
    }

    /**
     * 跟 {@link #fit} 一样，但顺手做一次**文字自检**：截断之后还比区域宽就写日志。
     * 正常情况下永远不触发（这就是「文字不出区」的可验证证据）；一旦触发，说明有区域宽度被算成 0 或负数。
     */
    private String fitChecked(String text, int max, R where) {
        String t = fit(text, max);
        int w = this.font.width(t);
        if (w > Math.max(0, max)) {
            WarehouseMod.LOGGER.warn("[warehouse-keeper] 文字自检：宽 {} > 区域宽 {} 的文字「{}」（界面 {}x{}，页签 {}）",
                    w, max, text, this.width, this.height, tab);
        }
        return t;
    }

    // ================================================================== 输入

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubleClick) {
        // 确认框开着的时候，只认它自己的两个按钮，别的点击一律吃掉
        if (pending != null) {
            if (ev.button() == 0) {
                if (okBtn != null && okBtn.holds(ev.x(), ev.y())) {
                    Runnable action = pending;
                    pending = null;
                    action.run();
                    return true;
                }
                if (noBtn != null && noBtn.holds(ev.x(), ev.y())) {
                    cancelAsk();
                    return true;
                }
            }
            return true;
        }
        if (ev.button() == 0 && tab == 3 && sub == 1 && admin) {
            // 「权限 → 审计」子页：行区/说明区里的点击一律吃掉（翻页按钮也画在这两块里），
            // 免得落到下面的行或列表上；页签不在这两块里，不会被误吃。
            boolean inArea = (rowsBox != null && rowsBox.holds(ev.x(), ev.y()))
                    || (notesBox != null && notesBox.holds(ev.x(), ev.y()));
            if (inArea) {
                if (auditPrev != null && auditPrev.holds(ev.x(), ev.y())) {
                    goAuditPage(auditPage - 1);
                } else if (auditNext != null && auditNext.holds(ev.x(), ev.y())) {
                    goAuditPage(auditPage + 1);
                }
                return true;
            }
        }
        if (ev.button() == 0 && pickDd != null && pickDd.open && pickDd.click(ev.x(), ev.y())) {
            // 「取货」页的仓库下拉展开着：点条目就换范围，点别处就收起来（两种情况都吃掉这次点击）
            return true;
        }
        if (ev.button() == 0 && taskDd != null && taskDd.open && taskDd.click(ev.x(), ev.y())) {
            // 「整理」页的仓库下拉同理
            return true;
        }
        if (ev.button() == 0 && catDd != null && catDd.open) {
            // 「物品」页的分类下拉：选中条目才按新分类重查（第 0 条 = 全部分类）；点别处只是收起来
            int before = catDd.choice;
            if (catDd.click(ev.x(), ev.y())) {
                if (catDd.choice != before) {
                    itemPage = 1;
                    infoScroll = 0;
                    QueryClient.request(itemRequest(1));
                }
                return true;
            }
        }
        if (ev.button() == 0 && pickOpen) {
            // 仓库清单展开着：点条目就选中它，点别处就收起来（两种情况都吃掉这次点击）
            List<RegionCache.Entry> list = RegionCache.list();
            if (assignList != null && assignList.holds(ev.x(), ev.y())) {
                int lineH = LINE_H + 3;
                int i = (int) ((ev.y() - assignList.y() - 2) / lineH);
                int idx = assignScroll + Math.max(0, i);
                if (idx >= 0 && idx < list.size()) {
                    pickRegion = list.get(idx).name;
                }
            }
            pickOpen = false;
            layoutAssignList();
            rebuildWidgets();
            return true;
        }
        if (ev.button() == 0) {
            // 先看页签：点中就切页（切页 = 重新 init 一次，只建当前页的控件）
            int hit = tabAt(ev.x(), ev.y());
            if (hit >= 0) {
                if (hit != tab) {
                    openTab(hit);
                }
                return true;
            }
            // 再看二级页签：它是当前页的东西，命中优先级高于页面里的内容
            int s = subAt(ev.x(), ev.y());
            if (s >= 0) {
                if (s != sub) {
                    openSub(s);
                }
                return true;
            }
        }
        if (ev.button() == 0 && tab == 2 && admin && rowsBox != null && !rowsBox.empty() && rowH > 0) {
            // 点一行的文字部分 = 选中这个搬运工（行里的三个按钮由控件自己处理）
            List<ClientSnapshot.Bot> bots = ClientSnapshot.bots();
            int visible = rowsVisible(rowsBox, rowH);
            for (int i = 0; i < Math.min(bots.size(), visible); i++) {
                R row = rowRect(rowsBox, i, rowH);
                R[] bb = botButtons(row);
                R text = new R(row.x(), row.y(), Math.max(0, bb[0].x() - GAP - row.x()), row.h());
                if (text.holds(ev.x(), ev.y())) {
                    ClientSnapshot.Bot bot = bots.get(i);
                    pickBot = bot.name;
                    pickRegion = bot.region == null ? "" : bot.region;
                    pickOpen = false;
                    rebuildWidgets();
                    return true;
                }
            }
        }
        if (ev.button() == 0 && tab == 0 && listBox != null && !listBox.empty()) {
            List<RegionCache.Entry> list = RegionCache.list();
            int rows = Math.max(1, rowsVisible(listBox, regionRowH));
            int total = list.size() + 1;   // 第 0 行是合成的「全部仓库」
            for (int i = 0; i < rows; i++) {
                int idx = scroll + i;
                if (idx >= total) {
                    break;
                }
                R row = new R(listBox.x() + 2, listBox.y() + 2 + i * regionRowH, Math.max(0, listBox.w() - 4),
                        regionRowH - 2);
                if (row.holds(ev.x(), ev.y())) {
                    // 优化10：点第 0 行 = 「全部仓库」（sel = -1），**不碰** RegionBorder —— 边界是逐个仓库的
                    if (idx == 0) {
                        sel = -1;
                    } else {
                        sel = idx - 1;
                        RegionBorder.touch(list.get(sel));
                    }
                    // 换了范围：四页要查的东西全变了（页码/滚动/详情都归零，再各查一次）
                    itemPage = 1;
                    infoScroll = 0;
                    boxScroll = 0;
                    itemDetailKey = "";
                    boxDetailKey = "";
                    if (QueryClient.supported()) {
                        QueryClient.request(itemRequest(1));
                        QueryClient.request(boxRequest());
                        QueryClient.open(QueryClient.Request.of(ViewQueryPayload.KIND_OVERVIEW, queryRegion()));
                    }
                    return true;
                }
            }
        }
        if (ev.button() == 0 && QueryClient.supported() && tab == 0 && "物品".equals(subName())
                && itemDetailKey.isEmpty() && infoBox != null && !infoBox.empty()) {
            // 点物品行 = 进「物品详情」（kind=3）
            List<JsonObject> rowsAll = QueryClient.arr(QueryClient.raw(ViewQueryPayload.KIND_ITEMS), "items");
            int rows = infoRows();
            for (int i = 0; i < rows; i++) {
                int idx = infoScroll + i;
                if (idx >= rowsAll.size()) {
                    break;
                }
                if (itemRowRect(i).holds(ev.x(), ev.y())) {
                    String id = QueryClient.str(rowsAll.get(idx), "id");
                    if (!id.isEmpty()) {
                        itemDetailKey = id;
                        itemDetailScroll = 0;
                        QueryClient.request(itemDetailRequest());
                        rebuildWidgets();
                    }
                    return true;
                }
            }
        }
        if (ev.button() == 0 && QueryClient.supported() && tab == 0 && "箱子".equals(subName())
                && boxDetailKey.isEmpty() && boxBox != null && !boxBox.empty()) {
            // 点箱子行 = 进「容器详情」（kind=5）
            List<JsonObject> boxes = boxRowsQuery(QueryClient.raw(ViewQueryPayload.KIND_CONTAINERS));
            int rows = Math.max(1, rowsVisible(boxBox, BOX_ROW_H));
            for (int i = 0; i < rows; i++) {
                int idx = boxScroll + i;
                if (idx >= boxes.size()) {
                    break;
                }
                if (boxRowRect(i).holds(ev.x(), ev.y())) {
                    String key = QueryClient.str(boxes.get(idx), "key");
                    if (!key.isEmpty()) {
                        boxDetailKey = key;
                        boxDetailScroll = 0;
                        QueryClient.request(boxDetailRequest());
                        rebuildWidgets();
                    }
                    return true;
                }
            }
        }
        if (ev.button() == 0 && tab == 1 && pickBox != null && !pickBox.empty()) {
            // 点清单里的一行 = 把物品名填进上面的框（不用手打）
            List<ClientSnapshot.Item> items = pickItems();
            int rows = pickRows();
            int top = pickBox.y() + LINE_H + 4;
            for (int i = 0; i < rows; i++) {
                int idx = pickScroll + i;
                if (idx >= items.size()) {
                    break;
                }
                R line = new R(pickBox.x() + 2, top + i * LINE_H, Math.max(0, pickBox.w() - 4), LINE_H);
                if (line.holds(ev.x(), ev.y())) {
                    ClientSnapshot.Item it = items.get(idx);
                    // 填什么由 orderFill 决定：同一语言就填名字（好看），服务端语言不同就填 id（一定解析得到）
                    String fill = orderFill(it);
                    orderText = fill;
                    if (orderBox != null) {
                        orderBox.setValue(fill);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(ev, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (pending != null) {
            return true;   // 弹窗时不让滚轮动到底下的列表
        }
        int step = -(int) Math.signum(dy);
        if (pickDd != null && pickDd.open) {
            // 「取货」页的仓库下拉展开着：滚它就是滚仓库清单，别动底下的物品清单
            if (pickDd.scrollBy(x, y, dy)) {
                return true;
            }
            return true;
        }
        if (taskDd != null && taskDd.open) {
            if (taskDd.scrollBy(x, y, dy)) {
                return true;
            }
            return true;
        }
        if (catDd != null && catDd.open) {
            // 「物品」页的分类下拉展开着：滚它，别动底下的物品清单
            if (catDd.scrollBy(x, y, dy)) {
                return true;
            }
            return true;
        }
        if (pickOpen) {
            // 展开的仓库清单滚动（条目多于可见行时才有意义）
            if (assignList != null && assignList.holds(x, y)) {
                int max = Math.max(0, RegionCache.list().size() - assignRows);
                assignScroll = Math.max(0, Math.min(assignScroll + step, max));
            }
            return true;
        }
        if (tab == 1) {
            // 「取货」页：滚的是那份可点的物品清单
            if (pickBox != null && pickBox.holds(x, y)) {
                int max = Math.max(0, pickItems().size() - pickRows());
                pickScroll = Math.max(0, Math.min(pickScroll + step, max));
            }
            return true;
        }
        if (tab != 0) {
            return true;   // 搬运工 / 权限页没有可滚的列表
        }
        if (tab == 0 && "箱子".equals(subName())) {
            // 「箱子」子页：滚的是那份箱子总览（排序/过滤都是纯客户端的，直接重算行数）
            if (boxBox != null && boxBox.holds(x, y)) {
                if (QueryClient.supported()) {
                    int n = boxDetailKey.isEmpty()
                            ? boxRowsQuery(QueryClient.raw(ViewQueryPayload.KIND_CONTAINERS)).size()
                            : boxDetailLineCount(QueryClient.raw(ViewQueryPayload.KIND_CONTAINER));
                    int unit = boxDetailKey.isEmpty() ? BOX_ROW_H : LINE_H;
                    int max = Math.max(0, n - Math.max(1, rowsVisible(boxBox, unit)));
                    if (boxDetailKey.isEmpty()) {
                        boxScroll = Math.max(0, Math.min(boxScroll + step, max));
                    } else {
                        boxDetailScroll = Math.max(0, Math.min(boxDetailScroll + step, max));
                    }
                } else {
                    int max = Math.max(0, boxList().size() - Math.max(1, rowsVisible(boxBox, BOX_ROW_H)));
                    boxScroll = Math.max(0, Math.min(boxScroll + step, max));
                }
            }
            return true;
        }
        if (infoBox != null && infoBox.holds(x, y)) {
            if (QueryClient.supported()) {
                if (!itemDetailKey.isEmpty()) {
                    int max = Math.max(0, itemDetailLineCount(QueryClient.raw(ViewQueryPayload.KIND_ITEM)) - infoRows());
                    itemDetailScroll = Math.max(0, Math.min(itemDetailScroll + step, max));
                } else {
                    int n = QueryClient.arr(QueryClient.raw(ViewQueryPayload.KIND_ITEMS), "items").size();
                    int max = Math.max(0, n - infoRows());
                    infoScroll = Math.max(0, Math.min(infoScroll + step, max));
                }
            } else {
                int max = Math.max(0, itemsOf(selectedRegion()).size() - infoRows());
                infoScroll = Math.max(0, Math.min(infoScroll + step, max));
            }
            return true;
        }
        if (listBox != null && listBox.holds(x, y)) {
            // 行数 = 真实仓库 + 1（合成的「全部仓库」）
            int max = Math.max(0, RegionCache.list().size() + 1 - Math.max(1, rowsVisible(listBox, regionRowH)));
            scroll = Math.max(0, Math.min(scroll + step, max));
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent ev) {
        if (pending != null) {
            // Esc = 取消；其它按键一概不透传，免得回车/空格把底下的按钮也按了
            if (ev.key() == 256) {
                cancelAsk();
            }
            return true;
        }
        if (ev.key() == 256 && ((pickDd != null && pickDd.open) || (taskDd != null && taskDd.open)
                || (catDd != null && catDd.open))) {
            // 页内的仓库/分类下拉：Esc 先收下拉，不退出界面
            if (pickDd != null) {
                pickDd.close();
            }
            if (taskDd != null) {
                taskDd.close();
            }
            if (catDd != null) {
                catDd.close();
            }
            return true;
        }
        if (ev.key() == 256 && tab == 0 && (!itemDetailKey.isEmpty() || !boxDetailKey.isEmpty())) {
            // 详情态：Esc 先回列表，不退出界面
            if (!itemDetailKey.isEmpty()) {
                itemDetailKey = "";
                itemDetailScroll = 0;
                if (QueryClient.supported()) {
                    QueryClient.request(itemRequest(itemPage));
                }
            }
            if (!boxDetailKey.isEmpty()) {
                boxDetailKey = "";
                boxDetailScroll = 0;
                if (QueryClient.supported()) {
                    QueryClient.request(boxRequest());
                }
            }
            rebuildWidgets();
            return true;
        }
        if (pickOpen && ev.key() == 256) {
            pickOpen = false;
            layoutAssignList();
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(ev);
    }

    @Override
    public boolean isPauseScreen() {
        // 开着界面时世界继续跑，扫描和指令才能立刻生效。
        return false;
    }
}
