package com.ds.warehouse.client;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

/**
 * 用 26.3 自带的 gizmo 在世界里画「开着边界的仓库」：整仓一个半透明彩色长方体 + 仓库名。
 *
 * <p>issue #8 优化②：原来每 8 tick 撒一次 {@code END_ROD} 粒子，太花；现在改成一次性提交的
 * {@code Gizmos.cuboid(...)}，填充透明度约 0.15、描边用仓库代表色、名字画在箱体上方。
 *
 * <p>三条纪律：
 * <ul>
 *   <li>只在 {@code LevelRenderEvents.BEFORE_GIZMOS} 里提交几何，别的阶段不要碰 {@link Gizmos}。</li>
 *   <li>只画同维度、玩家 128 格内的仓库：原版 gizmo 没有距离裁剪，远了会糊满屏幕。</li>
 *   <li>全高度仓库按「脚下那一圈往上 8 格」画：真按 -64~320 画，长方体有一半在天上/地底。</li>
 * </ul>
 */
public final class RegionGizmos {

    /** 只在玩家周围这个距离内画（格）。 */
    private static final double RANGE = 128.0;
    /** 全高度仓库的柱体高度（从最低点往上）：够看出范围，又不会糊满屏幕。 */
    private static final double FULL_HEIGHT_RISE = 8.0;
    /** 普通仓库最多画这么高：老数据/误操作存出世界高度的也不让它顶到天顶。 */
    private static final double MAX_RISE = 16.0;
    /** 有箱子时，在最高/最低箱子的外侧各留这么多格（用户要求：最高箱子上 8 格、最低箱子下 8 格）。 */
    private static final double PAD = 8.0;
    /** 填充透明度（0–255）：0.15 左右，能看出颜色又不挡住里面的东西。 */
    private static final int FILL_ALPHA = 38;
    private static final float STROKE_WIDTH = 2.0F;

    private static boolean registered;

    private RegionGizmos() {
    }

    /** 客户端初始化时调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> draw());
    }

    private static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        var entries = RegionBorder.enabledEntries();
        if (entries.isEmpty()) {
            return;
        }
        String dim = mc.level.dimension().identifier().toString();
        var eye = mc.player.position();
        for (RegionCache.Entry e : entries) {
            if (!dim.equals(e.dimension)) {
                continue;
            }
            AABB box = boxOf(e, eye.y, mc.level.getMinY(), mc.level.getMaxY(),
                    contentMinY(e), contentMaxY(e));
            if (box == null) {
                continue;
            }
            if (!box.inflate(RANGE).contains(eye)) {
                continue;
            }
            int rgb = e.color & 0xFFFFFF;
            Gizmos.cuboid(box, GizmoStyle.strokeAndFill(0xFF000000 | rgb, STROKE_WIDTH,
                    (FILL_ALPHA << 24) | rgb));
            Gizmos.billboardText(e.name,
                    net.minecraft.world.phys.Vec3.atCenterOf(
                            net.minecraft.core.BlockPos.containing(box.getCenter())).add(0.0, box.getYsize() / 2.0 + 0.5, 0.0),
                    net.minecraft.gizmos.TextGizmo.Style.forColorAndCentered(0xFFFFFFFF));
        }
    }

    /**
     * 仓库的箱体（只用于显示：不改区域定义、不影响取货）。
     *
     * <p>两条限制，保证「框选感」而不是「柱子」：
     * <ul>
     *   <li>全高度仓库：从仓库最低点往上 {@link #FULL_HEIGHT_RISE} 格；</li>
     *   <li>普通仓库：真实高度，但超过 {@link #MAX_RISE} 格时截到这么多；</li>
     *   <li>老数据里 y 可能存成世界上下界（-64..320），或者比玩家低太多：以玩家脚下为基准重新定底。</li>
     * </ul>
     */
    private static AABB boxOf(RegionCache.Entry e, double playerY, int worldMinY, int worldMaxY,
                              double contentMinY, double contentMaxY) {
        double minX = e.minX();
        double minZ = e.minZ();
        double maxX = e.maxX() + 1.0;
        double maxZ = e.maxZ() + 1.0;

        double bottom;
        double maxY;
        if (contentMaxY != -Double.MAX_VALUE) {
            // 仓库里已经有箱子：从最低的箱子往下、最高的箱子往上各留 PAD 格，
            // 这样「箱子在定义高度之外」也能被框住，而不是只画定义的那一段。
            bottom = contentMinY != Double.MAX_VALUE ? contentMinY - PAD : playerY - 1.0;
            maxY = contentMaxY + 1.0 + PAD;
            bottom = Math.max(bottom, worldMinY);
            maxY = Math.min(maxY, worldMaxY + 1.0);
            if (maxY <= bottom) {
                maxY = bottom + 1.0;
            }
            return new AABB(minX, bottom, minZ, maxX, maxY, maxZ);
        }

        double top = Math.min(e.maxY() + 1.0, worldMaxY + 1.0);
        bottom = Math.max(e.minY(), worldMinY);
        // 底在玩家脚下 48 格以外（老数据 / 世界边界）就当没存对：贴玩家脚下画
        if (bottom < playerY - 48.0 || bottom > playerY + 48.0) {
            bottom = Math.floor(playerY) - 1.0;
        }
        maxY = e.fullHeight ? bottom + FULL_HEIGHT_RISE : Math.min(top, bottom + MAX_RISE);
        if (maxY <= bottom) {
            maxY = bottom + 1.0;
        }
        return new AABB(minX, bottom, minZ, maxX, maxY, maxZ);
    }

    /** 该仓库里实际有容器的最高一层（没有就是 {@code -Double.MAX_VALUE}）。 */
    private static double contentMaxY(RegionCache.Entry e) {
        double best = -Double.MAX_VALUE;
        ClientSnapshot.Region r = ClientSnapshot.find(e.name);
        if (r != null) {
            for (ClientSnapshot.Box b : r.boxes) {
                best = Math.max(best, b.y);
            }
        }
        return best;
    }

    /** 该仓库里实际有容器的最低一层（没有就是 {@code Double.MAX_VALUE}）。 */
    private static double contentMinY(RegionCache.Entry e) {
        double best = Double.MAX_VALUE;
        ClientSnapshot.Region r = ClientSnapshot.find(e.name);
        if (r != null) {
            for (ClientSnapshot.Box b : r.boxes) {
                best = Math.min(best, b.y);
            }
        }
        return best;
    }
}

