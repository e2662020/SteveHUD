package com.rate.stevehud.mod.client.layout;

import com.rate.stevehud.protocol.model.Layout;

import java.util.Map;

/**
 * The size each element type is designed at, and how a document's box compares.
 *
 * <p>This exists because of one requirement: <b>the text follows the box</b>. A
 * package whose panels are resized but whose type stays the same size looks
 * broken — a wide box full of small text, or a narrow one with text spilling out
 * of it. The fix is that every length inside an element is measured in a unit
 * derived from the ratio between the box the document asks for and the box the
 * type was drawn for.
 *
 * <p>Which makes this table load-bearing, and it is used by two renderers:
 * the in-game HUD (through {@code MatchHud}s transform) and the browser overlay
 * (through its {@code --k} custom property). The Java copy lives here, in the
 * Minecraft-free half, so it can be tested — including against the shipped
 * packages, which is what stops the numbers and the documents from drifting
 * apart. The JavaScript copy is unavoidable without a build step, so it carries a
 * comment pointing back here.
 */
public final class ElementMetrics {

    /** Below this a box would be unreadable; above it, a scoreboard eats the screen. */
    public static final float MIN_RATIO = 0.2f;
    public static final float MAX_RATIO = 5f;

    /**
     * Width in design pixels.
     *
     * <p>Zero means "content-sized, there is no reference" — a text element, the
     * centre announcement, and the viewport frame all take their size from what
     * they contain or from the viewport, so there is no box to fit into and the
     * ratio is 1.
     */
    private static final Map<String, Integer> REFERENCE_WIDTH = Map.ofEntries(
            Map.entry(Layout.TYPE_MATCH_BUG, 348),
            Map.entry(Layout.TYPE_EVENT_INFO, 432),
            Map.entry(Layout.TYPE_TIMER, 264),
            Map.entry(Layout.TYPE_LOWER_THIRD, 768),
            Map.entry(Layout.TYPE_TICKER, 1920),
            Map.entry(Layout.TYPE_ANNOUNCEMENT, 0),
            Map.entry(Layout.TYPE_FRAME, 0),
            Map.entry(Layout.TYPE_TEXT, 0),
            // Data boards. These must equal REFERENCE_WIDTH in the browser
            // overlay, which is what stops a box resized in the editor from
            // scaling its type in one renderer and not the other.
            Map.entry(Layout.TYPE_STAT_COMPARE, 420),
            Map.entry(Layout.TYPE_LEADER_BOARD, 360),
            Map.entry(Layout.TYPE_SERIES_CHART, 430),
            Map.entry(Layout.TYPE_KPI_TILES, 480),
            Map.entry(Layout.TYPE_ROSTER_CARD, 520),
            Map.entry(Layout.TYPE_SERIES_SCORE, 360),
            Map.entry(Layout.TYPE_TIMELINE, 380),
            Map.entry(Layout.TYPE_HEAD_TO_HEAD, 560));

    private ElementMetrics() {
    }

    /** The width this type is designed at, in design pixels. 0 when content-sized. */
    public static int referenceWidth(String type) {
        Integer width = REFERENCE_WIDTH.get(type == null ? "" : type);
        return width == null ? 0 : width;
    }

    /**
     * How much bigger or smaller a document's box is than the type's reference.
     *
     * @param width the document's width for the element; 0 for content-sized
     * @return 1 when there is nothing to fit into, otherwise the clamped ratio
     */
    public static float boxRatio(String type, int width) {
        int reference = referenceWidth(type);
        if (reference <= 0 || width <= 0) {
            return 1f;
        }
        return Math.clamp(width / (float) reference, MIN_RATIO, MAX_RATIO);
    }

    /** {@link #boxRatio} for an element, tolerating a null element. */
    public static float boxRatio(Layout.Element element) {
        return element == null ? 1f : boxRatio(element.type, element.width);
    }
}
