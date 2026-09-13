package com.rate.stevehud.mod.client.layout;

import com.rate.stevehud.protocol.model.Layout;
import com.rate.stevehud.protocol.model.Layouts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference widths are what "the text follows the box" is computed from, and
 * both renderers depend on them agreeing with the packages that ship. These tests
 * pin them to those packages rather than to a comment.
 */
class ElementMetricsTest {

    @Test
    @DisplayName("every explicitly sized element in a shipped package has a reference to fit into")
    void shippedPackagesAreFullyFitted() {
        // The failure this catches is silent: a type with no reference width that a
        // package sizes anyway stays at its designed type size inside a box the
        // document made twice as wide — a big panel full of small text, with nothing
        // anywhere reporting a problem. Reading the property out of the shipped
        // documents is what keeps the table anchored to real packages rather than to
        // numbers that agree with themselves.
        for (String preset : Layouts.presetNames()) {
            for (Layout.Element element : Layouts.load(preset).elements) {
                if (element.width <= 0) {
                    continue;
                }
                assertTrue(ElementMetrics.referenceWidth(element.type) > 0,
                        preset + " sizes " + element.id + " (" + element.type
                                + ") explicitly, but that type has no reference width, so"
                                + " its text would never follow its box");
            }
        }
    }

    @Test
    @DisplayName("a preset may size an element differently, and its type follows")
    void presetsMayDifferAndStillBeFitted() {
        // The reference is a baseline, not a constraint: a package built for a more
        // generous look sizes its panels up, and its type comes with it. What must not
        // happen is a ratio so extreme it is unreadable, which the clamp covers.
        Layout olympia = Layouts.load(Layouts.PRESET_OLYMPIA);
        Layout.Element bug = olympia.first(Layout.TYPE_MATCH_BUG);
        assertNotNull(bug);
        assertTrue(bug.width > ElementMetrics.referenceWidth(Layout.TYPE_MATCH_BUG),
                "the olympia package is the larger-format one");

        float ratio = ElementMetrics.boxRatio(bug);
        assertTrue(ratio > 1f && ratio < 1.5f,
                "a modest, deliberate size difference; was " + ratio);

        for (String preset : Layouts.presetNames()) {
            for (Layout.Element element : Layouts.load(preset).elements) {
                float each = ElementMetrics.boxRatio(element);
                assertTrue(each >= ElementMetrics.MIN_RATIO && each <= ElementMetrics.MAX_RATIO,
                        preset + " / " + element.id + " asks for an unusable ratio " + each);
            }
        }
    }

    @Test
    @DisplayName("every sized type has a reference, so nothing is silently unfitted")
    void sizedTypesHaveReferences() {
        // A typo'd 0 here would turn the feature off for that element with no other
        // symptom, which is exactly the kind of silence a test should break.
        for (String type : List.of(Layout.TYPE_MATCH_BUG, Layout.TYPE_EVENT_INFO,
                Layout.TYPE_TIMER, Layout.TYPE_LOWER_THIRD, Layout.TYPE_TICKER,
                Layout.TYPE_STAT_COMPARE, Layout.TYPE_LEADER_BOARD, Layout.TYPE_SERIES_CHART,
                Layout.TYPE_KPI_TILES, Layout.TYPE_ROSTER_CARD, Layout.TYPE_SERIES_SCORE,
                Layout.TYPE_TIMELINE, Layout.TYPE_HEAD_TO_HEAD)) {
            assertTrue(ElementMetrics.referenceWidth(type) > 0,
                    type + " has no reference width, so its text would never follow its box");
        }
    }

    @Test
    @DisplayName("content-sized types have no reference, so nothing is fitted")
    void contentSizedTypesHaveNoReference() {
        for (String type : List.of(Layout.TYPE_ANNOUNCEMENT, Layout.TYPE_FRAME, Layout.TYPE_TEXT)) {
            assertEquals(0, ElementMetrics.referenceWidth(type),
                    type + " takes its size from its content or the viewport");
            assertEquals(1f, ElementMetrics.boxRatio(type, 900),
                    type + " must not be fitted to a box");
        }
    }

    @Test
    @DisplayName("a content-sized element is not fitted either")
    void autoWidthIsNotFitted() {
        // width 0 means "as large as the content needs". There is no box to fit into,
        // so the ratio has to be 1 rather than a division by zero.
        assertEquals(1f, ElementMetrics.boxRatio(Layout.TYPE_MATCH_BUG, 0));
        assertEquals(1f, ElementMetrics.boxRatio(Layout.TYPE_MATCH_BUG, -10));
    }

    @Test
    @DisplayName("resizing a box scales the type with it")
    void resizingScalesTheType() {
        // Twice the box, twice the type. That is the whole requirement.
        //
        // Derived from the reference rather than written out, so moving the
        // reference does not silently turn this into an assertion about 1.99.
        int reference = ElementMetrics.referenceWidth(Layout.TYPE_MATCH_BUG);
        assertTrue(reference > 0, "the bug has to have a reference for this to mean anything");
        assertEquals(2f, ElementMetrics.boxRatio(Layout.TYPE_MATCH_BUG, reference * 2));
        assertEquals(0.5f, ElementMetrics.boxRatio(Layout.TYPE_MATCH_BUG, reference / 2));
        assertEquals(1f, ElementMetrics.boxRatio(Layout.TYPE_MATCH_BUG, reference),
                "the designed size is the identity");
    }

    @Test
    @DisplayName("an absurd box is clamped rather than obeyed")
    void absurdRatiosAreClamped() {
        // The document is edited by hand and by a browser. A stray extra digit must
        // not put a 900-pixel scoreboard on screen.
        assertEquals(ElementMetrics.MAX_RATIO, ElementMetrics.boxRatio(Layout.TYPE_TIMER, 999_999));
        assertEquals(ElementMetrics.MIN_RATIO, ElementMetrics.boxRatio(Layout.TYPE_TIMER, 1));
    }

    @Test
    @DisplayName("an unknown type is not fitted")
    void unknownTypeHasNoReference() {
        assertEquals(0, ElementMetrics.referenceWidth("hologram"));
        assertEquals(0, ElementMetrics.referenceWidth(null));
        assertEquals(1f, ElementMetrics.boxRatio("hologram", 500));
        assertEquals(1f, ElementMetrics.boxRatio((Layout.Element) null));
    }

    @Test
    @DisplayName("the JavaScript mirror in the overlay lists the same numbers")
    void theBrowserCopyAgrees() {
        // The overlay cannot import Java, so its table is a copy. A copy that is
        // never checked is a copy that drifts, so the numbers are read back out of
        // the page source and compared.
        String page = overlaySource();
        assertNotNull(page,
                "could not find the overlay page; this test needs the repository, not a jar");

        int start = page.indexOf("var REFERENCE_WIDTH = {");
        assertTrue(start > 0, "the overlay has no REFERENCE_WIDTH table");
        int end = page.indexOf("};", start);
        String table = page.substring(start, end);

        // Every type, not just the ones a preset happens to use: a board whose
        // reference was typo'd would otherwise only show up on the day someone
        // dragged a box in the editor.
        for (String type : Layout.TYPES) {
            int expected = ElementMetrics.referenceWidth(type);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\\b" + type + ":\\s*(\\d+)")
                    .matcher(table);
            assertTrue(matcher.find(), "the overlay table is missing " + type);
            assertEquals(expected, Integer.parseInt(matcher.group(1)),
                    "the overlay's reference width for " + type
                            + " disagrees with ElementMetrics");
        }
    }

    /**
     * The overlay page, found by walking up from the working directory.
     *
     * <p>The page lives in {@code mod/client-mc}, which is not a module of its own and
     * so is not on this module's classpath. Reading it off disk is the only way to
     * check the two tables against each other, and it is worth the small coupling:
     * this is the test that catches the two renderers drifting apart.
     */
    private static String overlaySource() {
        java.nio.file.Path at = java.nio.file.Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && at != null; up++) {
            java.nio.file.Path candidate = at.resolve(
                    "mod/client-mc/src/main/resources/stevehud/web/overlay/index.html");
            if (java.nio.file.Files.isRegularFile(candidate)) {
                try {
                    return java.nio.file.Files.readString(candidate);
                } catch (java.io.IOException e) {
                    return null;
                }
            }
            at = at.getParent();
        }
        return null;
    }
}
