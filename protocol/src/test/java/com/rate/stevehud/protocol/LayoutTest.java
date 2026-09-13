package com.rate.stevehud.protocol;

import com.rate.stevehud.protocol.model.Layout;
import com.rate.stevehud.protocol.model.Layouts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout document is the contract between the browser overlay, the in-game HUD
 * and the editor. These tests cover the two ways it can go wrong: a shipped preset
 * that a renderer cannot draw, and an edited document that a renderer must survive.
 */
class LayoutTest {

    @ParameterizedTest
    @ValueSource(strings = {"esports", "olympic", "minimal"})
    @DisplayName("every shipped preset loads and is drawable")
    void presetsLoad(String preset) {
        Layout layout = Layouts.load(preset);

        assertNotNull(layout, preset + " produced no document");
        assertFalse(layout.name.isBlank(), preset + " has no display name");
        assertFalse(layout.elements.isEmpty(), preset + " has no elements");
        assertEquals(Layout.VERSION, layout.version);

        for (Layout.Element element : layout.elements) {
            assertTrue(Layout.TYPES.contains(element.type),
                    preset + ": unknown type " + element.type);
            assertTrue(Layout.ANCHORS.contains(element.anchor),
                    preset + ": unknown anchor " + element.anchor);
            assertTrue(Layout.ENTERS.contains(element.anim.enter),
                    preset + ": unknown entrance " + element.anim.enter);
            assertFalse(element.id.isBlank(), preset + ": an element has no id");
            assertTrue(Layouts.isColor(layout.theme.accent), "accent is not a colour");
        }
    }

    @Test
    @DisplayName("the esports preset carries the whole package, not just a corner of it")
    void esportsCoversTheViewport() {
        Layout layout = Layouts.load(Layouts.PRESET_ESPORTS);

        for (String type : List.of(Layout.TYPE_MATCH_BUG, Layout.TYPE_EVENT_INFO,
                Layout.TYPE_TIMER, Layout.TYPE_LOWER_THIRD, Layout.TYPE_TICKER,
                Layout.TYPE_ANNOUNCEMENT, Layout.TYPE_FRAME)) {
            assertNotNull(layout.first(type), "esports is missing " + type);
        }

        // The package has to reach all four corners, which is the property that
        // stops it looking like a debug overlay bolted into the top-left.
        assertTrue(layout.elements.stream().anyMatch(e -> e.anchor.equals(Layout.ANCHOR_TOP_LEFT)));
        assertTrue(layout.elements.stream().anyMatch(e -> e.anchor.equals(Layout.ANCHOR_TOP_RIGHT)));
        assertTrue(layout.elements.stream().anyMatch(e -> e.anchor.equals(Layout.ANCHOR_BOTTOM_LEFT)));
        assertTrue(layout.elements.stream().anyMatch(e -> e.anchor.equals(Layout.ANCHOR_BOTTOM_RIGHT)));
        assertTrue(layout.elements.stream().anyMatch(e -> e.anchor.equals(Layout.ANCHOR_CENTER)));
    }

    @Test
    @DisplayName("the olympic preset uses a text element bound to the match state")
    void olympicExercisesBindings() {
        Layout layout = Layouts.load(Layouts.PRESET_OLYMPIC);
        Layout.Element caption = layout.first(Layout.TYPE_TEXT);

        assertNotNull(caption, "olympic has no discipline caption");
        assertEquals("event.stage", caption.binding);
        // A preset that ships no frame element is the case that catches a renderer
        // which assumes one is always present.
        assertNull(layout.first(Layout.TYPE_FRAME), "olympic is meant to have no frame");
    }

    @Test
    @DisplayName("a partial document keeps the defaults it did not mention")
    void partialDocumentKeepsDefaults() {
        Layout layout = Layouts.fromJson("{\"elements\":[{\"id\":\"a\",\"type\":\"timer\"}]}");

        assertNotNull(layout);
        assertEquals(1920, layout.canvas.width);
        assertEquals("#FFC24B", layout.theme.accent);
        assertEquals(1, layout.elements.size());
        assertEquals(Layout.ANCHOR_TOP_LEFT, layout.elements.get(0).anchor);
        assertEquals(1f, layout.elements.get(0).scale);
    }

    @Test
    @DisplayName("junk in an edited document degrades one element, not the package")
    void junkIsContained() {
        Layout layout = Layouts.fromJson("""
                {
                  "name": "edited",
                  "theme": { "accent": "not-a-colour" },
                  "elements": [
                    { "id": "ok", "type": "timer" },
                    { "id": "nope", "type": "hologram" },
                    { "id": "bad", "type": "matchBug", "anchor": "middle-ish",
                      "scale": 99, "opacity": -4,
                      "style": { "accent": "#GGGGGG", "fontScale": 400 },
                      "anim": { "enter": "teleport", "durationMs": 99999 },
                      "scenes": ["full", "scene-that-does-not-exist", "full"] },
                    { "id": "ok", "type": "text" }
                  ]
                }
                """);

        assertNotNull(layout);
        // An unusable colour falls back to the theme default rather than reaching a
        // renderer, where it would throw or paint nothing.
        assertEquals("#FFC24B", layout.theme.accent);
        // The unknown element type is dropped; everything else is kept.
        assertEquals(3, layout.elements.size());
        assertNull(layout.first("hologram"));

        Layout.Element bad = layout.element("bad");
        assertNotNull(bad);
        assertEquals(Layout.ANCHOR_TOP_LEFT, bad.anchor, "unknown anchor must fall back");
        assertEquals(8f, bad.scale, "scale must be clamped into a drawable range");
        assertEquals(0f, bad.opacity);
        assertNull(bad.style.accent, "an unparsable override must become 'inherit'");
        assertEquals(4f, bad.style.fontScale);
        assertEquals(Layout.ENTER_FADE, bad.anim.enter);
        assertEquals(4000, bad.anim.durationMs);
        // Deduplicated, and the unknown scene removed.
        assertEquals(List.of(Layout.SCENE_FULL), bad.scenes);

        // Duplicate ids would make element lookup ambiguous for the editor, so the
        // second "ok" is kept under a different id rather than dropped or merged.
        assertEquals(3, layout.ids().size());
        assertNotNull(layout.element("ok"));
        assertTrue(layout.elements.stream().anyMatch(e -> e.type.equals(Layout.TYPE_TEXT)
                        && !e.id.equals("ok")),
                "the duplicate-id element must survive under a fresh id");
    }

    @Test
    @DisplayName("normalising twice changes nothing the second time")
    void normalizeIsIdempotent() {
        Layout once = Layouts.load(Layouts.PRESET_ESPORTS);
        String first = Layouts.toJson(once);
        String second = Layouts.toJson(Layouts.normalize(Layouts.fromJson(first)));

        // If this ever fails it means normalisation is drifting values on every
        // pass, which would slowly deform a package that is saved repeatedly.
        assertEquals(first, second);
    }

    @Test
    @DisplayName("a document survives the round trip through JSON")
    void roundTrip() {
        Layout original = Layouts.load(Layouts.PRESET_ESPORTS);
        Layout copy = Layouts.fromJson(Layouts.toPrettyJson(original));

        assertNotNull(copy);
        assertEquals(original.name, copy.name);
        assertEquals(original.elements.size(), copy.elements.size());
        for (int i = 0; i < original.elements.size(); i++) {
            Layout.Element a = original.elements.get(i);
            Layout.Element b = copy.elements.get(i);
            assertEquals(a.id, b.id);
            assertEquals(a.type, b.type);
            assertEquals(a.anchor, b.anchor);
            assertEquals(a.x, b.x);
            assertEquals(a.y, b.y);
            assertEquals(a.width, b.width);
            assertEquals(a.scale, b.scale);
        }
    }

    @Test
    @DisplayName("text that is not a document decodes to null rather than throwing")
    void garbageDecodesToNull() {
        assertNull(Layouts.fromJson(null));
        assertNull(Layouts.fromJson(""));
        assertNull(Layouts.fromJson("   "));
        assertNull(Layouts.fromJson("not json at all"));
        assertNull(Layouts.fromJson("[1,2,3]"));
        assertNull(Layouts.fromJson("null"));
    }

    @Test
    @DisplayName("scene gating: empty means everywhere, unknown means the fullest scene")
    void sceneGating() {
        Layout.Element everywhere = new Layout.Element("a", Layout.TYPE_MATCH_BUG, "");
        assertTrue(Layout.showsIn(everywhere, Layout.SCENE_MINIMAL));

        Layout.Element fullOnly = new Layout.Element("b", Layout.TYPE_LOWER_THIRD, "")
                .scenesIn(Layout.SCENE_FULL);
        assertTrue(Layout.showsIn(fullOnly, Layout.SCENE_FULL));
        assertFalse(Layout.showsIn(fullOnly, Layout.SCENE_COMPACT));

        // A typo in a scene name must not be able to blank the package.
        assertTrue(Layout.showsIn(everywhere, "scene-typo"));

        fullOnly.visible = false;
        assertFalse(Layout.showsIn(fullOnly, Layout.SCENE_FULL));
        assertFalse(Layout.showsIn(null, Layout.SCENE_FULL));
    }

    @Test
    @DisplayName("the last-resort package is still a package")
    void fallbackIsUsable() {
        Layout layout = Layouts.fallback();

        assertFalse(layout.elements.isEmpty());
        assertNotNull(layout.first(Layout.TYPE_MATCH_BUG));
        assertNotNull(layout.first(Layout.TYPE_TIMER));
    }

    @Test
    @DisplayName("an unknown preset name loads the default rather than nothing")
    void unknownPresetFallsBackToEsports() {
        Layout layout = Layouts.load("no-such-package");

        assertEquals(Layouts.load(Layouts.PRESET_ESPORTS).elements.size(),
                layout.elements.size());
        assertFalse(Layouts.isPreset("no-such-package"));
    }

    @Test
    @DisplayName("colour validation accepts CSS 8-digit order and rejects the rest")
    void colourValidation() {
        assertTrue(Layouts.isColor("#fff"));
        assertTrue(Layouts.isColor("#4C9AFF"));
        assertTrue(Layouts.isColor("#1A2030E6"));
        assertFalse(Layouts.isColor("1A2030E6"));
        assertFalse(Layouts.isColor("#12345"));
        assertFalse(Layouts.isColor("#zzzzzz"));
        assertFalse(Layouts.isColor(null));
        assertFalse(Layouts.isColor("rgba(0,0,0,0.5)"));
    }
}
