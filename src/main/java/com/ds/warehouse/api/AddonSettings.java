package com.ds.warehouse.api;

import java.util.List;

/**
 * 附属模组把「自己的设置」交给主体面板渲染的接口。
 *
 * <p>用途：装了附属模组（第一个是 {@code warehouse-keeper-voice}）时，主体的「设置」页会自动多出一个二级菜单，
 * 用主体自己的行样式渲染这里描述的设置项 —— 附属模组不用跟着主体的界面改版，主体也不用认识任何具体设置项。
 *
 * <h2>附属模组侧怎么接</h2>
 * <ol>
 *   <li>实现本接口（零 Minecraft 依赖，纯数据 + 回调），例如 {@code VoiceAddonSettings}；</li>
 *   <li>在 {@code fabric.mod.json} 里声明自定义 entrypoint：
 *       {@code "entrypoints": { "warehouse-keeper:addon": ["com.ds.warehouse.voice.VoiceAddonSettings"] }}；</li>
 *   <li>主体用 {@code FabricLoader.getInstance().getEntrypoints("warehouse-keeper:addon", AddonSettings.class)} 读取。</li>
 * </ol>
 *
 * <p><b>契约（重要）</b>：
 * <ul>
 *   <li>{@link #rows()} 每次被调用都必须<b>重新读</b>当前值，不要缓存 —— 附属模组自己的界面与主体面板改的是
 *       同一份配置，谁后改谁生效，两边不能各记一份（否则会出现「面板显示开、文件里是关」）；</li>
 *   <li>面板会<b>反复</b>调用 {@link #rows()}（打开时、每次点击后、以及每秒几次的自动比对），所以它要够便宜；
 *       反过来这也意味着下载进度、下载完成后按钮从「取消」变「删除」这类**自己会变**的状态不用附属模组主动通知，
 *       变化会在下一秒自动画出来；</li>
 *   <li>实现必须可被重复调用、不产生副作用；{@link Row#act()} 抛异常会被主体吞掉（只写日志），
 *       {@link #rows()} 抛异常则只是这个菜单不出现，面板照常；</li>
 *   <li>{@link #title()} 会直接画在二级页签上，建议 ≤4 个汉字。</li>
 * </ul>
 *
 * <p>本接口自 <b>1.2.1</b> 起提供。老版本主体不读这个 entrypoint，声明了也没有副作用（附属模组自己的设置界面照旧）。
 */
public interface AddonSettings {

    /** 稳定 id：去重、日志、兼容判断都用它，例如 {@code "warehouse-keeper-voice"}。 */
    String id();

    /** 二级菜单标题，例如「语音取货」。 */
    String title();

    /** 一行简介，显示在「设置 · 常规」的入口行上。 */
    String summary();

    /** 当前所有设置项。每次打开页面、每次点击后都会重新调用，必须直接反映最新状态。 */
    List<Row> rows();

    /**
     * 一行设置项。
     *
     * <p>面板的画法：左边 {@code label}（正文色）+ 灰色 {@code note}，右边一个按钮显示 {@code value}。
     * 三个例外：{@link Kind#INFO} 不建按钮（值直接画在右边）；{@link Kind#TEXT} 右边是一个**输入框**，
     * {@code value} 只当输入框的占位提示（例如「未填」/「已填 sk-1****」），玩家敲完回车或离开这一页时
     * 回调 {@link Row#text(String)}；{@link Row#header()} 是**分组小标题**，面板画成一条标题加分隔线。
     *
     * <p>要在几个固定选项里选一个（模型、下载源、服务商）就用 {@link Row#options()} +
     * {@link Row#pick(int)}：面板会画一个真正的下拉菜单，而不是「点一下换下一个」。这两个方法是
     * 1.2.1 起有的，**老面板不认识**（它看到 {@link Kind#CYCLE} 还是会一次一下地循环），所以附属模组
     * 最好把 {@code pick} 与循环那套都留着，见 {@code SimpleRow.choice}。
     */
    interface Row {

        /** 设置项名字，例如「主模型」。 */
        String label();

        /** 当前值（按钮上的字），例如「SenseVoice（推荐）」。 */
        String value();

        /** 灰色说明，可为空。 */
        String note();

        /** 这一行怎么交互。 */
        Kind kind();

        /** false = 灰掉不可点（例如模型还没下载时的「删除模型」）。 */
        boolean enabled();

        /**
         * 点击回调（{@link Kind#INFO} / {@link Kind#TEXT} 不会被调用）：
         * 改完自己的状态即可，面板会重新问一遍 {@link AddonSettings#rows()} 并重画。
         */
        void act();

        /**
         * {@link Kind#TEXT} 行提交时调用：玩家在面板里敲完 / 粘贴完，按回车、或者离开这一页的时候。
         *
         * <p>只有内容**非空、且和上一次提交的不同**才会调 —— 空输入不调（想清空就给一行
         * {@link Kind#ACTION} 的「清除」行，免得玩家点一下输入框又点到别处，就把已有的 Key 洗掉了）。
         * 面板提交完会把输入框清空，让 {@link Row#value()} 那行状态重新露出来。
         *
         * <p>其它 Kind 不会调用。**默认空实现**，所以老实现类（1.2.1 之前写的）不用改也不会炸。
         */
        default void text(String value) {
        }

        /**
         * {@link Kind#TEXT} 行是不是敏感内容：true 时面板里输入的字一边敲一边画成星号
         * （屏幕/录屏上看不到原文），Key 这类用它。默认 false。
         */
        default boolean secret() {
            return false;
        }

        /**
         * true = 这一行是**分组小标题**（例如「识别模型」「模型下载」「AI 功能」）：面板画成一条浅底标题
         * 加一道分隔线，不建按钮也不可点，用来把一页里十几行按功能分开。
         *
         * <p>标题行的 {@link Row#note()} 与 {@link Row#value()} 都不画。默认 false —— 1.2.1 之前写的
         * 实现类不用改，它们照样是一行普通设置项。
         */
        default boolean header() {
            return false;
        }

        /**
         * 非空 = 这一行是**下拉选择**：面板画一个下拉菜单，条目就是这里的中文名，当前选中第
         * {@link Row#optionIndex()} 条，玩家选了就回调 {@link Row#pick(int)}。
         *
         * <p>空表（默认）表示这不是下拉行，按 {@link Row#kind()} 原本的画法走。
         */
        default java.util.List<String> options() {
            return java.util.List.of();
        }

        /** 下拉里当前选中第几条（没有下拉时恒 0）。 */
        default int optionIndex() {
            return 0;
        }

        /**
         * 下拉里选了第几条（下标对应 {@link Row#options()}）。
         *
         * <p>**默认空实现**：老实现类不会因为这个新方法而编不过。注意面板回读新状态下一次
         * {@link AddonSettings#rows()} 时会把 {@link Row#optionIndex()} 一起读走，所以这里只要改自己的
         * 状态、落盘即可，不用告诉面板「选的是第几条」。
         */
        default void pick(int index) {
        }
    }

    /** 一行的交互方式。 */
    enum Kind {
        /** 点击循环切换（主模型、下载镜像这类）。 */
        CYCLE,
        /** 开 / 关。 */
        TOGGLE,
        /** 点一下做一件事（下载、删除、打开页面这类）；{@code value} 显示当前状态或动作名。 */
        ACTION,
        /** 文本输入（API Key、地址、模型名这类）：面板给一个**真的输入框**，回车 / 离开这一页时回调
         *  {@link Row#text(String)}；敏感内容加 {@link Row#secret()}。 */
        TEXT,
        /** 只读信息行（不建按钮，只画 value）。 */
        INFO
    }
}
