package com.rate.stevehud.mod.client.layout;

import com.rate.stevehud.protocol.model.Layout;

/**
 * Where a piece of the package sits on screen.
 *
 * <p>Each element is pinned to a corner or edge rather than positioned by an
 * absolute coordinate, and the scale is applied per element about its own pinned
 * corner. That is the difference between "make the artwork bigger" and "zoom the
 * whole interface": a top-right element stays in the top-right when it grows,
 * instead of drifting toward the centre as it would if everything were scaled
 * from the screen origin.
 *
 * <p>The arithmetic is deliberately separated from the drawing. {@link #place}
 * answers "where is this element's top-left", and the caller then divides by the
 * scale before drawing, because a matrix transform scales about the origin.
 * Keeping those two steps apart is what lets a panel be positioned correctly
 * without its own layout code knowing anything about scale.
 *
 * <p>This lives in the Minecraft-free half of the mod for one reason: the browser
 * overlay implements the same formula in JavaScript, and the two renderers
 * disagreeing about where an element goes is the bug this whole document model
 * exists to prevent. Here it can at least be tested.
 *
 * <p>The formula is stated in whatever space the caller passes in. Passing the
 * design canvas gives design coordinates, which is what the browser works in;
 * passing the scaled window gives screen coordinates, which is what the in-game
 * HUD works in. Both are the same arithmetic.
 */
public enum Anchor {

    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    CENTER;

    /** The anchor named by a layout document, or {@link #TOP_LEFT} if unrecognised. */
    public static Anchor of(String id) {
        if (id != null) {
            for (Anchor anchor : values()) {
                if (anchor.id().equals(id)) {
                    return anchor;
                }
            }
        }
        return TOP_LEFT;
    }

    /** This anchor's name in a layout document. */
    public String id() {
        return switch (this) {
            case TOP_LEFT -> Layout.ANCHOR_TOP_LEFT;
            case TOP_RIGHT -> Layout.ANCHOR_TOP_RIGHT;
            case BOTTOM_LEFT -> Layout.ANCHOR_BOTTOM_LEFT;
            case BOTTOM_RIGHT -> Layout.ANCHOR_BOTTOM_RIGHT;
            case CENTER -> Layout.ANCHOR_CENTER;
        };
    }

    public boolean isRight() {
        return this == TOP_RIGHT || this == BOTTOM_RIGHT;
    }

    public boolean isBottom() {
        return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
    }

    /**
     * The element's top-left corner, after scaling.
     *
     * @param width    the element's natural (unscaled) width
     * @param height   the element's natural (unscaled) height
     * @param scale    the scale the element will be drawn at
     * @param canvasW  the width of the space being laid out in
     * @param canvasH  the height of that space
     * @param marginX  gap from the left or right edge, in the same units as the space
     * @param marginY  gap from the top or bottom edge. Separate from {@code marginX}
     *                 because the bottom edge already carries the ticker, and a
     *                 timer sitting on top of it would be the kind of overlap this
     *                 package must not have.
     * @return {@code {x, y}}, which the drawer divides by {@code scale} before
     *         drawing inside a transform, and which the browser uses directly
     */
    public int[] place(int width, int height, float scale,
                       int canvasW, int canvasH, int marginX, int marginY) {
        int scaledW = Math.round(width * scale);
        int scaledH = Math.round(height * scale);

        int x;
        int y;
        if (this == CENTER) {
            // Centred, with the margins acting as an offset from the centre. That is
            // what makes the announcement element's negative y mean "above centre"
            // without a second anchor for every intermediate position.
            x = (canvasW - scaledW) / 2 + marginX;
            y = (canvasH - scaledH) / 2 + marginY;
        } else {
            x = isRight() ? canvasW - marginX - scaledW : marginX;
            y = isBottom() ? canvasH - marginY - scaledH : marginY;
        }
        return new int[]{x, y};
    }

    /** Horizontal centre of the element, for centre-aligned content. */
    public int centreX(int width, float scale, int canvasW, int margin) {
        int scaledW = Math.round(width * scale);
        if (this == CENTER) {
            return canvasW / 2 + margin;
        }
        return isRight() ? canvasW - margin - scaledW / 2 : margin + scaledW / 2;
    }
}
