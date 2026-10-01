package com.ds.warehouse.client;

import java.util.ArrayList;
import java.util.List;
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

    private static RegionCache.Entry region;
    private static boolean on;
    private static int counter;

    private RegionBorder() {
    }

    public static boolean isOn() {
        return on;
    }

    public static RegionCache.Entry region() {
        return region;
    }

    public static void select(RegionCache.Entry entry) {
        region = entry;
    }

    public static void setOn(boolean value) {
        on = value;
        counter = 0;
        if (!value) {
            clearLive();
        }
    }

    /** 选中区域没了（被删掉）就把边界关掉。 */
    public static void validate() {
        if (region == null) {
            return;
        }
        for (RegionCache.Entry entry : RegionCache.list()) {
            if (entry == region || (entry.name != null && entry.name.equals(region.name))) {
                region = entry;
                return;
            }
        }
        region = null;
        on = false;
        clearLive();
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
        if (!on || region == null) {
            clearLive();
            return;
        }
        if (++counter % PERIOD != 0) {
            prune();
            return;
        }
        String here = mc.level.dimension().identifier().toString();
        if (!here.equals(region.dimension)) {
            clearLive();
            return;
        }

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
