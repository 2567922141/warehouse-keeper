package com.ds.warehouse.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleTypes;

/**
 * 用原版发光粒子在世界里勾出仓库区域的范围。
 *
 * <p>粒子只在本地客户端生成，服务端完全不知道它的存在，也不会写进存档。
 * 选粒子而不是自绘线条，是因为原版粒子机制与 Sodium / Iris 这类渲染模组零冲突。
 *
 * <p>三个刻意的设计：
 * <ul>
 *   <li><b>只画玩家周围 {@link #RANGE} 格</b>：先把线段裁到玩家附近再撒点，所以不论仓库多大都是实线，也不会把粒子撒到看不见的地方。</li>
 *   <li><b>全高度仓库只画脚下那一圈</b>：全高度意味着「整根柱子都算」，再画 384 格高的竖线只会糊满整个屏幕、还容易让人以为区域超出了范围。限定高度的仓库才画完整体块。</li>
 *   <li><b>开界面 / 暂停时把粒子收掉</b>：粒子是我们自己 add 进去的，句柄留着，所以能一条不剩地 remove()。否则单机暂停时它们会冻在半空，透过半透明面板看就像面板边框画歪了。</li>
 * </ul>
 *
 * <p><b>0.22.0 · 优化9：按仓库分别控制。</b>以前这里只有一个 {@code region} + 一个 {@code on}
 * 布尔（进程级单例），面板一换选中仓库就把边界「搬」过去 —— 所以看起来像「开一个仓库，
 * 其他仓库也一起开了」。现在状态是 {@code 名字 → 区域} 的表，每个仓库各自开关，可以同时显示多个；
 * 仍然<b>纯客户端内存态</b>：不落盘、不走网络，退出游戏就没了。
 */
public final class RegionBorder {

    /** 每多少客户端 tick 撒一次粒子。 */
    private static final int PERIOD = 8;
    /** 只画玩家周围这个距离之内的边（远了看不见、也没必要画）。 */
    private static final double RANGE = 44.0;
    /** 相邻两个粒子的间距（格）。 */
    private static final double STEP = 1.0;
    /** 每条边一次最多撒多少个粒子。 */
    private static final int MAX_PER_EDGE = 70;

    /** 我们自己撒出去的粒子（用于开界面/暂停时立刻回收）。 */
    private static final List<Particle> LIVE = new ArrayList<>();

    /**
     * 开着边界的仓库：{@code 区域名 → 区域}。
     *
     * <p>用 {@link LinkedHashMap} 是为了让撒粒子的顺序稳定（同一批区域每 8 tick 的观感一致）。
     * 值必须每次都换成 {@link RegionCache} 最新的那个 {@code Entry}：{@code RegionCache.refresh()}
     * 每次都会重建 Entry 对象，旧对象里的坐标可能已经过期（仓库被扩建/缩小过）。
     */
    private static final Map<String, RegionCache.Entry> ENABLED = new LinkedHashMap<>();

    private static int counter;

    private RegionBorder() {
    }

    /** 这个仓库的边界现在开着吗（{@code name} 为空 = 没有具体仓库 ⇒ false）。 */
    public static boolean isOn(String name) {
        return name != null && !name.isEmpty() && ENABLED.containsKey(name);
    }

    /** 当前开着边界的仓库名（按开启顺序，只读快照）。 */
    public static List<String> enabledNames() {
        return List.copyOf(ENABLED.keySet());
    }

    /** 打开 / 关闭某个仓库的边界；重复设置同一个值是无害的。 */
    public static void toggle(String name, boolean value) {
        if (name == null || name.isEmpty()) {
            return;
        }
        boolean changed;
        if (value) {
            RegionCache.Entry entry = find(name);
            if (entry == null) {
                // 列表里已经没有这个仓库了（刚被删）：直接忽略，别往表里塞一个空壳
                changed = ENABLED.remove(name) != null;
            } else {
                ENABLED.put(name, entry);
                changed = true;
            }
        } else {
            changed = ENABLED.remove(name) != null;
        }
        if (changed) {
            counter = 0;
            // 立刻把已撒出去的粒子收掉：关掉的那个仓库的线不该在屏幕上多留几秒；
            // 还开着的仓库下一个 tick 就会重新撒出来（counter 归零 ⇒ 立刻重画）。
            clearLive();
        }
    }

    /**
     * 记录「面板当前显示的仓库」。
     *
     * <p>只做一件事：如果这个仓库的边界正开着，就把表里那份换成最新的 {@code Entry}
     * （区域被改过之后坐标要跟着更新）。<b>不会顺手把没开的仓库打开</b> ——
     * 「点一下列表就点亮所有边框」正是玩家反馈的那个毛病。
     *
     * @return 传进来的 entry（便于链式使用）
     */
    public static RegionCache.Entry touch(RegionCache.Entry entry) {
        if (entry == null || entry.name == null || entry.name.isEmpty()) {
            return entry;
        }
        if (ENABLED.containsKey(entry.name)) {
            ENABLED.put(entry.name, entry);
        }
        return entry;
    }

    /** {@link #touch} 的旧名字（调用方语义没变：只是「现在在看哪个仓库」）。 */
    public static void select(RegionCache.Entry entry) {
        touch(entry);
    }

    /**
     * 当前显示的仓库（兼容旧调用：取表里第一个）。
     *
     * @deprecated 状态已经不是一个「当前区域」了，请用 {@link #enabledNames()} / {@link #isOn(String)}。
     */
    @Deprecated
    public static RegionCache.Entry region() {
        for (RegionCache.Entry entry : ENABLED.values()) {
            return entry;
        }
        return null;
    }

    /** 区域被删掉的仓库从表里remove（名字还在 RegionCache.list() 里的顺手换成最新对象）。 */
    public static void validate() {
        if (ENABLED.isEmpty()) {
            return;
        }
        Map<String, RegionCache.Entry> latest = new LinkedHashMap<>();
        for (RegionCache.Entry entry : RegionCache.list()) {
            if (entry != null && entry.name != null && !entry.name.isEmpty()) {
                latest.put(entry.name, entry);
            }
        }
        boolean removed = false;
        Iterator<Map.Entry<String, RegionCache.Entry>> it = ENABLED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RegionCache.Entry> e = it.next();
            RegionCache.Entry fresh = latest.get(e.getKey());
            if (fresh == null) {
                it.remove();
                removed = true;
            } else if (fresh != e.getValue()) {
                e.setValue(fresh);
            }
        }
        if (removed) {
            counter = 0;
            clearLive();
        }
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            clearLive();
            return;
        }
        // 打开任何界面（仓库管理面板、背包、Esc 菜单…）或者游戏暂停时：
        // 先把已经撒出去的粒子收干净，再停止撒新的。
        // 面板是半透明的，粒子会透过来；而单机暂停时粒子会冻在半空一直不去，
        // 看起来就像面板的边框画歪了、或者区域的边线跑到了不该在的地方。
        if ((mc.gui != null && mc.gui.screen() != null) || mc.isPaused()) {
            clearLive();
            return;
        }
        if (ENABLED.isEmpty()) {
            clearLive();
            return;
        }
        if (++counter % PERIOD != 0) {
            prune();
            return;
        }
        String here = mc.level.dimension().identifier().toString();
        boolean any = false;
        // 逐个仓库画：粒子已经按玩家半径裁剪过，多开几个的代价与一个仓库同量级
        for (RegionCache.Entry entry : ENABLED.values()) {
            if (entry == null || !here.equals(entry.dimension)) {
                continue;
            }
            any = true;
            draw(mc, entry);
        }
        if (!any) {
            // 开着的仓库全在别的维度：把残留粒子收掉（但**不清空**表，回到那个维度还会自己亮）
            clearLive();
        }
    }

    /** 画一个仓库的边界（原来写在 clientTick 里的那一段，原样搬过来）。 */
    private static void draw(Minecraft mc, RegionCache.Entry region) {
        double x0 = region.minX();
        double z0 = region.minZ();
        double x1 = region.maxX() + 1.0;
        double z1 = region.maxZ() + 1.0;
        boolean full = region.fullHeight;

        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        // 玩家所在高度那一圈：站在仓库里也能看清范围（全高度仓库只画这一圈）
        rect(mc, x0, x1, z0, z1, Math.floor(py) + 0.02, px, py, pz);

        if (!full) {
            // 限定高度的仓库：把整个体块画出来（四条竖边 + 上下底面）
            double yd0 = region.minY();
            double yd1 = region.maxY() + 1.0;
            vert(mc, x0, z0, yd0, yd1, px, py, pz);
            vert(mc, x0, z1, yd0, yd1, px, py, pz);
            vert(mc, x1, z0, yd0, yd1, px, py, pz);
            vert(mc, x1, z1, yd0, yd1, px, py, pz);
            rect(mc, x0, x1, z0, z1, yd0, px, py, pz);
            rect(mc, x0, x1, z0, z1, yd1, px, py, pz);
        }
    }

    /** 按名字在 {@link RegionCache} 里找最新的一份。 */
    private static RegionCache.Entry find(String name) {
        for (RegionCache.Entry entry : RegionCache.list()) {
            if (entry != null && name.equals(entry.name)) {
                return entry;
            }
        }
        return null;
    }

    /** 一条竖边。 */
    private static void vert(Minecraft mc, double x, double z, double y0, double y1,
                             double px, double py, double pz) {
        line(mc, x, y0, z, x, y1, z, px, py, pz);
    }

    /** 一个水平矩形（四条边）。 */
    private static void rect(Minecraft mc, double x0, double x1, double z0, double z1, double y,
                             double px, double py, double pz) {
        line(mc, x0, y, z0, x1, y, z0, px, py, pz);
        line(mc, x1, y, z0, x1, y, z1, px, py, pz);
        line(mc, x1, y, z1, x0, y, z1, px, py, pz);
        line(mc, x0, y, z1, x0, y, z0, px, py, pz);
    }

    /**
     * 把一条线段裁到玩家周围 {@link #RANGE} 格以内，然后每隔 {@link #STEP} 格撒一个粒子。
     * 这样无论区域多大，画出来的都是实线，而且只在看得见的地方画。
     */
    private static void line(Minecraft mc, double ax, double ay, double az,
                             double bx, double by, double bz,
                             double px, double py, double pz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double[] d = {dx, dy, dz};
        double[] a = {ax, ay, az};
        double[] p = {px, py, pz};
        double t0 = 0.0;
        double t1 = 1.0;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-6) {
                if (a[i] < p[i] - RANGE || a[i] > p[i] + RANGE) {
                    return;
                }
                continue;
            }
            double lo = (p[i] - RANGE - a[i]) / d[i];
            double hi = (p[i] + RANGE - a[i]) / d[i];
            if (lo > hi) {
                double tmp = lo;
                lo = hi;
                hi = tmp;
            }
            if (lo > t0) {
                t0 = lo;
            }
            if (hi < t1) {
                t1 = hi;
            }
            if (t0 > t1) {
                return;
            }
        }
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-6) {
            return;
        }
        int steps = (int) Math.floor((t1 - t0) * length / STEP);
        if (steps < 1) {
            steps = 1;
        }
        if (steps > MAX_PER_EDGE) {
            steps = MAX_PER_EDGE;
        }
        for (int i = 0; i <= steps; i++) {
            double t = t0 + (t1 - t0) * ((double) i / (double) steps);
            spawn(mc, ax + dx * t, ay + dy * t, az + dz * t);
        }
    }

    /** 撒一个粒子，并把句柄记下来（这样才收得回来）。 */
    private static void spawn(Minecraft mc, double x, double y, double z) {
        Particle particle = mc.particleEngine.createParticle(ParticleTypes.END_ROD, x, y, z, 0.0, 0.0, 0.0);
        if (particle == null) {
            return;
        }
        mc.particleEngine.add(particle);
        LIVE.add(particle);
    }

    /** 已经自然消失的粒子从表里划掉，免得表越攒越长。 */
    private static void prune() {
        if (LIVE.isEmpty()) {
            return;
        }
        LIVE.removeIf(p -> !p.isAlive());
    }

    /** 立刻把还活着的边界粒子全部收掉。 */
    private static void clearLive() {
        for (Particle particle : LIVE) {
            if (particle.isAlive()) {
                particle.remove();
            }
        }
        LIVE.clear();
    }
}
