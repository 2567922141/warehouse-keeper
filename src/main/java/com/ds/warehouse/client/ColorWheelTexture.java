package com.ds.warehouse.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * 取色盘的色轮贴图。
 *
 * <p>以前用「96 段 × 16 环小方块」拼出色轮，块与块之间能看到明显台阶（颜色过渡一格一格）。
 * 这里改成一次性生成 256×256 的 RGBA 贴图，每个像素按极坐标算色相/饱和度，边缘做抗锯齿，
 * 之后每帧只用一次 {@code blit} 画出来 —— 渐变连续、也不再有几千次 fill 调用的开销。
 *
 * <p>贴图按「明度 100%」烘焙；明度由右侧竖条单独调，和常见取色器一致。生成失败时调用方
 * 会退回旧的方块画法（不影响功能）。
 */
final class ColorWheelTexture {

    private static final int SIZE = 256;

    private static Identifier id;
    private static boolean failed;

    private ColorWheelTexture() {
    }

    /** 贴图注册名；首次调用时生成并上传，失败返回 {@code null}。 */
    static Identifier id() {
        if (id != null) {
            return id;
        }
        if (failed) {
            return null;
        }
        try {
            NativeImage image = new NativeImage(SIZE, SIZE, true);
            double radius = SIZE / 2.0D;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    double dx = x + 0.5D - radius;
                    double dy = y + 0.5D - radius;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist > radius) {
                        image.setPixelABGR(x, y, 0);
                        continue;
                    }
                    int hue = (int) Math.round(Math.toDegrees(Math.atan2(dy, dx)));
                    hue = Math.floorMod(hue, 360);
                    int sat = (int) Math.round(Math.min(1.0D, dist / radius) * 100.0D);
                    int rgb = hsv(hue, sat, 100);
                    int alpha = 255;
                    if (dist > radius - 2.0D) {
                        alpha = (int) Math.round(255.0D * (radius - dist) / 2.0D);
                        alpha = Math.max(0, Math.min(255, alpha));
                    }
                    // NativeImage 存的是 ABGR：A<<24 | B<<16 | G<<8 | R
                    image.setPixelABGR(x, y,
                            (alpha << 24) | ((rgb & 0xFF) << 16) | (((rgb >> 8) & 0xFF) << 8) | ((rgb >> 16) & 0xFF));
                }
            }
            DynamicTexture texture = new DynamicTexture(() -> "warehouse-keeper-color-wheel", image);
            Identifier name = Identifier.parse("warehouse-keeper:color_wheel");
            Minecraft.getInstance().getTextureManager().register(name, texture);
            try {
                texture.upload();
            } catch (Throwable ignored) {
                // 上传失败不致命：下次绑定这张贴图时还会再尝试
            }
            id = name;
            return id;
        } catch (Throwable error) {
            failed = true;
            return null;
        }
    }

    /** HSV → RGB（H 0..359、S/V 0..100），只保留 RGB 24 位。 */
    private static int hsv(int hue, int sat, int val) {
        int h = Math.floorMod(hue, 360);
        double s = Math.max(0, Math.min(100, sat)) / 100.0D;
        double v = Math.max(0, Math.min(100, val)) / 100.0D;
        double sector = h / 60.0D;
        int i = (int) Math.floor(sector);
        double f = sector - i;
        double p = v * (1.0D - s);
        double q = v * (1.0D - s * f);
        double t = v * (1.0D - s * (1.0D - f));
        double r;
        double g;
        double b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return ((int) Math.round(r * 255.0D) << 16)
                | ((int) Math.round(g * 255.0D) << 8)
                | (int) Math.round(b * 255.0D);
    }
}
