package com.rate.stevehud.mod.mc.hud;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Text treatments that lift vanilla text to something that reads as broadcast
 * graphics.
 *
 * <p>Minecraft's font is a fixed bitmap face and cannot be replaced from a mod
 * without shipping a font resource. What can be done — and what actually accounts
 * for most of the difference between "debug readout" and "broadcast overlay" — is
 * how the glyphs are drawn around:
 *
 * <ul>
 *   <li><b>Outline.</b> Eight offset copies in a dark colour under the glyphs.
 *       Vanilla's drop shadow only covers one direction, which is why HUD text
 *       washes out against a bright sky.</li>
 *   <li><b>Tracking.</b> Extra letter spacing on labels, which is what makes an
 *       all-caps header look designed rather than typed.</li>
 *   <li><b>Scale.</b> Large numerals for scores, via the per-version matrix bridge
 *       in {@code impl.HudScale}.</li>
 * </ul>
 *
 * <p>Measurement and truncation live here too, beside the drawing, so a caller
 * cannot size a panel with one rule and draw text with another — the mismatch that
 * let an earlier version of this HUD paint text straight through its own
 * background.
 */
public final class TextFx {

    private TextFx() {
    }

    /**
     * Draws text with a full outline.
     *
     * @param outlineColor use {@link Prim#OUTLINE}, or a translucent variant to
     *                     soften the effect on small text
     */
    public static void outlined(DrawContext ctx, TextRenderer font, String text,
                               int x, int y, int color, int outlineColor) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) {
                    ctx.drawText(font, text, x + dx, y + dy, outlineColor, false);
                }
            }
        }
        ctx.drawText(font, text, x, y, color, false);
    }

    public static void outlinedRight(DrawContext ctx, TextRenderer font, String text,
                                     int rightX, int y, int color, int outlineColor) {
        outlined(ctx, font, text, rightX - font.getWidth(text), y, color, outlineColor);
    }

    public static void outlinedCentre(DrawContext ctx, TextRenderer font, String text,
                                      int centreX, int y, int color, int outlineColor) {
        outlined(ctx, font, text, centreX - font.getWidth(text) / 2, y, color, outlineColor);
    }

    /** Total width of {@code text} drawn with {@code tracking} extra pixels per glyph. */
    public static int width(TextRenderer font, String text, int tracking) {
        if (text.isEmpty()) {
            return 0;
        }
        return font.getWidth(text) + tracking * (codePointCount(text) - 1);
    }

    /**
     * Draws text with extra letter spacing, truncating it if it would exceed
     * {@code maxWidth}.
     *
     * <p>Truncation lives here rather than being left to the caller so that a panel
     * can never be overrun: whatever this draws is guaranteed to fit the budget it
     * was given.
     */
    public static void tracked(DrawContext ctx, TextRenderer font, String text,
                               int x, int y, int maxWidth, int color, int outlineColor, int tracking) {
        String fitted = fit(font, text, maxWidth, tracking);
        int cursor = x;
        for (int i = 0; i < fitted.length(); ) {
            int codePoint = fitted.codePointAt(i);
            String glyph = new String(Character.toChars(codePoint));
            outlined(ctx, font, glyph, cursor, y, color, outlineColor);
            cursor += font.getWidth(glyph) + tracking;
            i += Character.charCount(codePoint);
        }
    }

    /** {@link #tracked} aligned to the right edge at {@code rightX}. */
    public static void trackedRight(DrawContext ctx, TextRenderer font, String text,
                                    int rightX, int y, int maxWidth, int color,
                                    int outlineColor, int tracking) {
        String fitted = fit(font, text, maxWidth, tracking);
        tracked(ctx, font, fitted, rightX - width(font, fitted, tracking), y,
                maxWidth, color, outlineColor, tracking);
    }

    /**
     * The longest prefix of {@code text} that fits {@code maxWidth} when drawn with
     * {@code tracking}, with an ellipsis when anything had to be dropped.
     */
    public static String fit(TextRenderer font, String text, int maxWidth, int tracking) {
        if (maxWidth <= 0) {
            return "";
        }
        if (width(font, text, tracking) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int budget = maxWidth - font.getWidth(ellipsis);
        if (budget <= 0) {
            return font.trimToWidth(text, maxWidth);
        }

        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String glyph = new String(Character.toChars(codePoint));
            int advance = font.getWidth(glyph) + tracking;
            if (used + advance > budget) {
                break;
            }
            kept.append(glyph);
            used += advance;
            i += Character.charCount(codePoint);
        }
        return kept + ellipsis;
    }

    /** Plain single-colour text, truncated to {@code maxWidth}. */
    public static void clipped(DrawContext ctx, TextRenderer font, String text,
                               int x, int y, int maxWidth, int color, boolean shadow) {
        String fitted = font.getWidth(text) <= maxWidth ? text : font.trimToWidth(text, maxWidth);
        ctx.drawText(font, fitted, x, y, color, shadow);
    }

    private static int codePointCount(String text) {
        return text.codePointCount(0, text.length());
    }
}
