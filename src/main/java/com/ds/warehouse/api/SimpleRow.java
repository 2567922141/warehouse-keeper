package com.ds.warehouse.api;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * {@link AddonSettings.Row} 的通用实现。
 *
 * <p>附属模组只想给出一组行时直接用这里的静态工厂即可，不必自己写实现类：
 * <pre>{@code
 * return List.of(
 *         SimpleRow.cycle("主模型", currentModelName(), "点一下换下一个；改完要「保存并应用」", this::nextModel),
 *         SimpleRow.toggle("副模型对照", compareOn, "两套模型同时识别，用来对比结果", this::toggleCompare),
 *         SimpleRow.action("下载模型", installed ? "删除" : "下载", "点一下下载；已装好时点一下删除（会再确认一次）", this::downloadOrDelete),
 *         SimpleRow.info("模型目录", dir.toString(), "下载的模型都放在这里")
 * );
 * }</pre>
 */
public final class SimpleRow implements AddonSettings.Row {

    private final String label;
    private final String value;
    private final String note;
    private final AddonSettings.Kind kind;
    private final boolean enabled;
    private final Runnable action;
    /** TEXT 行：敲成星号显示（Key 这类） */
    private final boolean secret;
    /** TEXT 行：提交回调（回车 / 离开这一页），可为 null（那就只是个只读文本行） */
    private final java.util.function.Consumer<String> sink;
    /** 分组小标题行（不建按钮，只画标题与分隔线） */
    private final boolean header;
    /** 下拉候选（非空 = 这一行是下拉行）；值就是条目本身，面板直接画 */
    private final List<String> options;
    /** 下拉当前选中第几条（只有 {@link #options} 非空时有意义） */
    private final int optionIndex;
    /** 下拉选中回调；老面板上走了 {@link #act()} 那条循环路时也是它 */
    private final IntConsumer picker;

    public SimpleRow(String label, String value, String note, AddonSettings.Kind kind, boolean enabled, Runnable action) {
        this(label, value, note, kind, enabled, action, false, null, false, List.of(), 0, null);
    }

    public SimpleRow(String label, String value, String note, AddonSettings.Kind kind, boolean enabled,
                     Runnable action, boolean secret, java.util.function.Consumer<String> sink) {
        this(label, value, note, kind, enabled, action, secret, sink, false, List.of(), 0, null);
    }

    public SimpleRow(String label, String value, String note, AddonSettings.Kind kind, boolean enabled,
                     Runnable action, boolean secret, Consumer<String> sink, boolean header,
                     List<String> options, int optionIndex, IntConsumer picker) {
        this.label = label == null ? "" : label;
        this.value = value == null ? "" : value;
        this.note = note == null ? "" : note;
        this.kind = kind == null ? AddonSettings.Kind.INFO : kind;
        this.enabled = enabled;
        this.action = action;
        this.secret = secret;
        this.sink = sink;
        this.header = header;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.optionIndex = optionIndex;
        this.picker = picker;
    }

    /** 点击循环切换的一行。 */
    public static SimpleRow cycle(String label, String value, String note, Runnable act) {
        return new SimpleRow(label, value, note, AddonSettings.Kind.CYCLE, true, act);
    }

    /** 开 / 关的一行（值显示「开」或「关」）。 */
    public static SimpleRow toggle(String label, boolean on, String note, Runnable act) {
        return new SimpleRow(label, on ? "开" : "关", note, AddonSettings.Kind.TOGGLE, true, act);
    }

    /** 开 / 关的一行，但值用自定义文案（例如「预加载：已开启」）。 */
    public static SimpleRow toggle(String label, String value, String note, Runnable act) {
        return new SimpleRow(label, value, note, AddonSettings.Kind.TOGGLE, true, act);
    }

    /** 点一下执行一个动作（下载、删除、打开某个页面这类）；值显示当前状态或动作名。 */
    public static SimpleRow action(String label, String value, String note, Runnable act) {
        return new SimpleRow(label, value, note, AddonSettings.Kind.ACTION, true, act);
    }

    /** 动作行，但可以灰掉（例如模型正在下载时不让点、或者没装就没得删）。 */
    public static SimpleRow action(String label, String value, String note, boolean enabled, Runnable act) {
        return new SimpleRow(label, value, note, AddonSettings.Kind.ACTION, enabled, act);
    }

    /** 只读信息行。 */
    public static SimpleRow info(String label, String value, String note) {
        return new SimpleRow(label, value, note, AddonSettings.Kind.INFO, false, null);
    }

    /** 灰掉不可点的一行（例如模型还没下载时的「删除模型」）。 */
    public static SimpleRow disabled(String label, String value, String note) {
        return new SimpleRow(label, value, note, AddonSettings.Kind.TOGGLE, false, null);
    }

    /** 文本行：1.2.1 起面板画成**真的输入框**，逗号后面那个 setter 是提交回调（回车 / 离开这一页）。
     *  例：{@code SimpleRow.text("API 地址", nowUrl(), "换服务商时在这里改", this::setUrl)}。 */
    public static SimpleRow text(String label, String value, String note, java.util.function.Consumer<String> setter) {
        return new SimpleRow(label, value, note, AddonSettings.Kind.TEXT, true, null, false, setter);
    }

    /** 敏感文本行：敲进去的字在面板里画成星号（屏幕/录屏上看不到原文），Key 这类用它。 */
    public static SimpleRow secret(String label, String value, String note, java.util.function.Consumer<String> setter) {
        return new SimpleRow(label, value, note, AddonSettings.Kind.TEXT, true, null, true, setter);
    }

    /** 只读文本行：面板画成一行灰字（老版本主体的画法，附属模组没给 setter 时的兜底）。 */
    public static SimpleRow text(String label, String value, String note) {
        return new SimpleRow(label, value, note, AddonSettings.Kind.TEXT, false, null);
    }

    /**
     * 分组小标题：面板画成一条浅底标题 + 一道分隔线，不建按钮、点不了。
     *
     * <p>老面板（1.2.1 之前）不认识标题行，会把它当 {@link AddonSettings.Kind#INFO} 画成一行普通文字 ——
     * 还能看，只是少了那条分隔线。
     */
    public static SimpleRow header(String title) {
        return new SimpleRow(title, "", "", AddonSettings.Kind.INFO, false, null,
                false, null, true, List.of(), 0, null);
    }

    /**
     * 下拉选择行：新面板画一个真下拉菜单（条目就是 {@code options} 里的中文名），
     * 老面板（1.2.1 之前不认识 {@link AddonSettings.Row#options()}）退化成原来那套「点一下换下一个」。
     *
     * <p>{@code picker} 收到的是**下标**（对应 {@code options}）；老面板走 {@link #act()} 时，
     * 这里会自己把下标 +1 再回调，所以两种面板的行为一致。
     *
     * @param index 当前选中第几条（越界会被夹回来）
     */
    public static SimpleRow choice(String label, List<String> options, int index, String note, IntConsumer picker) {
        List<String> copy = options == null ? List.of() : List.copyOf(options);
        int at = copy.isEmpty() ? 0 : Math.max(0, Math.min(index, copy.size() - 1));
        String value = copy.isEmpty() ? "—" : copy.get(at);
        return new SimpleRow(label, value, note, AddonSettings.Kind.CYCLE, true, null,
                false, null, false, copy, at, picker);
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String value() {
        // 下拉行：值就是当前选中的那一条（面板每次都会重新问，所以直接在这里现取）
        if (!options.isEmpty()) {
            return options.get(optionIndex());
        }
        return value;
    }

    @Override
    public boolean header() {
        return header;
    }

    @Override
    public List<String> options() {
        return options;
    }

    @Override
    public int optionIndex() {
        if (options.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(optionIndex, options.size() - 1));
    }

    @Override
    public void pick(int index) {
        if (picker != null && index >= 0 && index < options.size()) {
            picker.accept(index);
        }
    }

    @Override
    public String note() {
        return note;
    }

    @Override
    public AddonSettings.Kind kind() {
        return kind;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void act() {
        // 下拉行在老面板上就是「点一下换下一个」：把下标 +1 再回调（新面板走的是 pick(int)）
        if (!options.isEmpty() && picker != null) {
            pick(optionIndex() + 1 >= options.size() ? 0 : optionIndex() + 1);
            return;
        }
        if (action != null) {
            action.run();
        }
    }

    @Override
    public boolean secret() {
        return secret;
    }

    @Override
    public void text(String value) {
        if (sink != null) {
            sink.accept(value);
        }
    }
}
