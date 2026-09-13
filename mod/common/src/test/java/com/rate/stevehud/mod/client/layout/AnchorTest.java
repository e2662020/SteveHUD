package com.rate.stevehud.mod.client.layout;

import com.rate.stevehud.protocol.model.Layout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The anchor arithmetic is implemented twice — here and in the browser overlay —
 * so it is worth pinning down the properties the two have to agree on, rather than
 * only the numbers.
 */
class AnchorTest {

    private static final int W = 1920;
    private static final int H = 1080;

    @Test
    @DisplayName("a corner anchor insets from its own corner")
    void cornersInsetFromTheirOwnCorner() {
        // width 300, height 60, margin 26/22
        assertArrayEquals(new int[]{26, 22},
                Anchor.TOP_LEFT.place(300, 60, 1f, W, H, 26, 22));
        assertArrayEquals(new int[]{W - 26 - 300, 22},
                Anchor.TOP_RIGHT.place(300, 60, 1f, W, H, 26, 22));
        assertArrayEquals(new int[]{26, H - 22 - 60},
                Anchor.BOTTOM_LEFT.place(300, 60, 1f, W, H, 26, 22));
        assertArrayEquals(new int[]{W - 26 - 300, H - 22 - 60},
                Anchor.BOTTOM_RIGHT.place(300, 60, 1f, W, H, 26, 22));
    }

    @Test
    @DisplayName("scaling keeps each element pinned to its own corner")
    void scalingKeepsCornersPinned() {
        // This is the requirement stated as a test: 左上角还在左上角，右上角还在右上角.
        // A whole-UI zoom would move every element toward the centre as it grew.
        int[] small = Anchor.TOP_RIGHT.place(300, 60, 1f, W, H, 26, 22);
        int[] large = Anchor.TOP_RIGHT.place(300, 60, 2f, W, H, 26, 22);

        assertEquals(W - 26, small[0] + 300, "right edge at margin");
        assertEquals(W - 26, large[0] + 600, "right edge still at margin when doubled");
        assertEquals(22, small[1], "top edge stays at the top");
        assertEquals(22, large[1]);

        int[] bottomLeft = Anchor.BOTTOM_LEFT.place(300, 60, 2f, W, H, 26, 22);
        assertEquals(26, bottomLeft[0], "left edge stays at the left");
        assertEquals(H - 22, bottomLeft[1] + 120, "bottom edge stays at the margin");
    }

    @Test
    @DisplayName("the centre anchor offsets from the centre, not from an edge")
    void centreOffsetsFromTheCentre() {
        // The announcement sits above the crosshair, which is what its negative y is.
        assertArrayEquals(new int[]{(W - 300) / 2, (H - 60) / 2},
                Anchor.CENTER.place(300, 60, 1f, W, H, 0, 0));
        assertArrayEquals(new int[]{(W - 300) / 2, (H - 60) / 2 - 130},
                Anchor.CENTER.place(300, 60, 1f, W, H, 0, -130));
    }

    @Test
    @DisplayName("centreX follows the anchor")
    void centreXFollowsTheAnchor() {
        assertEquals(26 + 150, Anchor.TOP_LEFT.centreX(300, 1f, W, 26));
        assertEquals(W - 26 - 150, Anchor.TOP_RIGHT.centreX(300, 1f, W, 26));
        assertEquals(W / 2, Anchor.CENTER.centreX(300, 1f, W, 0));
        // Half of a doubled element, measured from the same edge.
        assertEquals(W - 26 - 300, Anchor.TOP_RIGHT.centreX(300, 2f, W, 26));
    }

    @Test
    @DisplayName("anchor names survive the round trip through a document")
    void namesRoundTrip() {
        for (Anchor anchor : Anchor.values()) {
            assertEquals(anchor, Anchor.of(anchor.id()), anchor + " did not survive");
            // And every name is one the document model considers valid, which is what
            // stops normalize() from silently rewriting a saved document's anchors.
            org.junit.jupiter.api.Assertions.assertTrue(Layout.ANCHORS.contains(anchor.id()),
                    anchor.id() + " is not an anchor the document model accepts");
        }
    }

    @Test
    @DisplayName("an unknown anchor name falls back rather than throwing")
    void unknownNameFallsBack() {
        assertEquals(Anchor.TOP_LEFT, Anchor.of("middle-ish"));
        assertEquals(Anchor.TOP_LEFT, Anchor.of(null));
        assertEquals(Anchor.TOP_LEFT, Anchor.of(""));
    }
}
