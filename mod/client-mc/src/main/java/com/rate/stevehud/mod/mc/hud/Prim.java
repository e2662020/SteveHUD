package com.rate.stevehud.mod.mc.hud;

import net.minecraft.client.gui.DrawContext;

/**
 * Drawing primitives for the broadcast package.
 *
 * <p>Built only from {@code fill} and {@code fillGradient}, both of which are
 * byte-identical across every Minecraft version we target, so this whole file is
 * one copy shared by all four builds. Anything that would have needed a
 * version-specific call — sprite blits, render pipelines — is absent on purpose.
 *
 * <p>The visual language is deliberately "broadcast overlay" rather than "debug
 * panel": a drop shadow for separation from the world, a vertical gradient for
 * depth, corner brackets for framing, and a left accent bar carrying the
 * competitor's colour. None of it costs more than a few fills.
 */
public final class Prim {

    // ---- palette ------------------------------------------------------------

    public static final int SHADOW = 0x80000000;
    public static final int PANEL_TOP = 0xF01A2030;
    public static final int PANEL_BOTTOM = 0xF00C1018;
    public static final int BORDER = 0x40FFFFFF;
    public static final int EDGE_HIGHLIGHT = 0x28FFFFFF;
    public static final int ACCENT = 0xFFFFC24B;
    public static final int TEXT = 0xFFF2F2F2;
    public static final int TEXT_DIM = 0xFF9AA4B2;
    public static final int OUTLINE = 0xFF000000;
    public static final int LIVE = 0xFFFF4D4D;
    public static final int HOME = 0xFF4C9AFF;
    public static final int AWAY = 0xFFFF6B6B;
    public static final int TRACK = 0x30FFFFFF;
    public static final int HEAD_FRAME = 0x60FFFFFF;

    private Prim() {
    }

    // ---- composites ---------------------------------------------------------

    /**
     * The panel body: shadow, gradient, border, a top highlight and a left accent
     * bar. Returns nothing; the caller keeps drawing its own content on top.
     */
    public static void panel(DrawContext ctx, int x, int y, int width, int height,
                             int accentColor, float alpha) {
        // Shadow first, offset down-right so the panel reads as lifted off the world.
        ctx.fill(x + 2, y + 3, x + width + 2, y + height + 3, scaled(SHADOW, alpha));
        ctx.fillGradient(x, y, x + width, y + height,
                scaled(PANEL_TOP, alpha), scaled(PANEL_BOTTOM, alpha));
        border(ctx, x, y, width, height, scaled(BORDER, alpha));
        // A single brighter row along the top edge, the way glass catches light.
        ctx.fill(x + 1, y + 1, x + width - 1, y + 2, scaled(EDGE_HIGHLIGHT, alpha));
        // The competitor's colour down the left edge.
        ctx.fill(x, y, x + 3, y + height, scaled(accentColor, alpha));
    }

    public static void border(DrawContext ctx, int x, int y, int width, int height, int color) {
        ctx.fill(x, y, x + width, y + 1, color);
        ctx.fill(x, y + height - 1, x + width, y + height, color);
        ctx.fill(x, y + 1, x + 1, y + height - 1, color);
        ctx.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /**
     * Four L-shaped corner brackets, drawn just inside the panel.
     *
     * <p>{@code spread} animates them outward on entry, which is the whole reason
     * they exist: they make the panel look composed rather than pasted on.
     */
    public static void cornerBrackets(DrawContext ctx, int x, int y, int width, int height,
                                      int color, int length, int spread) {
        int l = length;
        int s = spread;
        int x0 = x + s;
        int y0 = y + s;
        int x1 = x + width - 1 - s;
        int y1 = y + height - 1 - s;

        // top-left
        ctx.fill(x0, y0, x0 + l, y0 + 1, color);
        ctx.fill(x0, y0, x0 + 1, y0 + l, color);
        // top-right
        ctx.fill(x1 - l, y0, x1, y0 + 1, color);
        ctx.fill(x1, y0, x1 + 1, y0 + l, color);
        // bottom-left
        ctx.fill(x0, y1 - 1, x0 + l, y1, color);
        ctx.fill(x0, y1 - l, x0 + 1, y1, color);
        // bottom-right
        ctx.fill(x1 - l, y1 - 1, x1, y1, color);
        ctx.fill(x1, y1 - l, x1 + 1, y1, color);
    }

    /**
     * A horizontal bar with its own track, filled to {@code progress}.
     *
     * @param progress 0..1
     */
    public static void bar(DrawContext ctx, int x, int y, int width, int height,
                           float progress, int trackColor, int fillColor) {
        ctx.fill(x, y, x + width, y + height, trackColor);
        int filled = Math.round(width * clamp01(progress));
        if (filled > 0) {
            ctx.fill(x, y, x + filled, y + height, fillColor);
        }
    }

    /**
     * A light band sweeping left to right across a region.
     *
     * <p>Drawn as a series of thin vertical strips whose alpha follows a triangular
     * falloff, because vanilla's gradient helper runs vertically and there is no
     * portable horizontal one. The strip count is fixed so the cost per frame is
     * constant and small.
     *
     * @param phase 0..1 position of the sweep
     */
    public static void shimmer(DrawContext ctx, int x, int y, int width, int height,
                               float phase, int color) {
        final int strips = 20;
        float centre = clamp01(phase) * (width + height);
        float reach = height * 1.6f;

        for (int i = 0; i < strips; i++) {
            int stripX = x + (width * i / strips);
            int stripW = Math.max(1, width / strips);
            float distance = Math.abs((stripX + stripW * 0.5f) - x - centre);
            if (distance > reach) {
                continue;
            }
            float intensity = 1f - distance / reach;
            ctx.fill(stripX, y, stripX + stripW, y + height, scaled(color, intensity * intensity));
        }
    }

    /** A dashed outline, used for a slot that has nothing in it yet. */
    public static void dashedRect(DrawContext ctx, int x, int y, int width, int height,
                                  int color, int dash) {
        int step = Math.max(2, dash * 2);
        for (int i = 0; i < width; i += step) {
            int end = Math.min(i + dash, width);
            ctx.fill(x + i, y, x + end, y + 1, color);
            ctx.fill(x + i, y + height - 1, x + end, y + height, color);
        }
        for (int i = 0; i < height; i += step) {
            int end = Math.min(i + dash, height);
            ctx.fill(x, y + i, x + 1, y + end, color);
            ctx.fill(x + width - 1, y + i, x + width, y + end, color);
        }
    }

    // ---- helpers ------------------------------------------------------------

    /** Multiplies a colour's alpha by {@code factor} (0..1), leaving RGB alone. */
    public static int scaled(int argb, float factor) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * clamp01(factor));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Black or white, whichever is legible on {@code background}.
     *
     * <p>Luminance is computed on linearised channels and the decision uses a
     * contrast comparison rather than a brightness threshold, because a flat
     * threshold puts white text on mid-tone team colours such as a bright yellow —
     * which is both the most common and the most obvious broadcast legibility bug.
     */
    public static int ink(int background) {
        double r = linear(((background >>> 16) & 0xFF) / 255.0);
        double g = linear(((background >>> 8) & 0xFF) / 255.0);
        double b = linear((background & 0xFF) / 255.0);
        double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;

        double contrastWithWhite = 1.05 / (luminance + 0.05);
        double contrastWithBlack = (luminance + 0.05) / 0.05;
        return contrastWithWhite >= contrastWithBlack ? 0xFFFFFFFF : 0xFF000000;
    }

    private static double linear(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    /**
     * Blends one colour toward another.
     *
     * @param amount 0 keeps {@code from}, 1 is {@code to}
     */
    public static int mix(int from, int to, float amount) {
        return com.rate.stevehud.mod.client.anim.Anim.lerpColor(from, to, clamp01(amount));
    }

    private static float clamp01(float t) {
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }
}
