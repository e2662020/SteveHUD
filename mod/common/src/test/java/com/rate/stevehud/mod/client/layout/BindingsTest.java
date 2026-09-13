package com.rate.stevehud.mod.client.layout;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The binding grammar is implemented twice — here and in the browser overlay — so
 * every case below is a case the two implementations have to answer identically.
 */
class BindingsTest {

    private static final JsonElement STATE = JsonParser.parseString("""
            {
              "rev": 12,
              "scene": "full",
              "event": { "name": "春季联赛 决赛", "stage": "BO3 · 第 1 局" },
              "sides": [
                { "id": "home", "short": "HOM", "score": 3,
                  "competitors": [ { "name": "选手一", "number": "07" } ] },
                { "id": "away", "short": "AWY", "score": 2, "competitors": [] }
              ],
              "clock": { "label": "比赛计时", "value": "12:34", "running": true },
              "announcement": { "title": "ROUND 3", "visible": false },
              "nested": [ [ { "deep": "yes" } ] ]
            }
            """);

    @Test
    @DisplayName("reads plain and nested fields")
    void readsFields() {
        assertEquals("full", Bindings.resolve(STATE, "scene"));
        assertEquals("春季联赛 决赛", Bindings.resolve(STATE, "event.name"));
        assertEquals("BO3 · 第 1 局", Bindings.resolve(STATE, "event.stage"));
        assertEquals("12:34", Bindings.resolve(STATE, "clock.value"));
    }

    @Test
    @DisplayName("reads array indices")
    void readsIndices() {
        assertEquals("HOM", Bindings.resolve(STATE, "sides[0].short"));
        assertEquals("AWY", Bindings.resolve(STATE, "sides[1].short"));
        assertEquals("选手一", Bindings.resolve(STATE, "sides[0].competitors[0].name"));
        assertEquals("3", Bindings.resolve(STATE, "sides[0].score"));
    }

    @Test
    @DisplayName("numbers read as text without a decimal point or separator")
    void numbersReadAsText() {
        // These end up on a broadcast graphic, so "3" is right and "3.0" is not.
        assertEquals("3", Bindings.resolve(STATE, "sides[0].score"));
        assertEquals("12", Bindings.resolve(STATE, "rev"));
    }

    @Test
    @DisplayName("booleans read as words")
    void booleansReadAsWords() {
        assertEquals("true", Bindings.resolve(STATE, "clock.running"));
        assertEquals("false", Bindings.resolve(STATE, "announcement.visible"));
    }

    @Test
    @DisplayName("a path that does not resolve reads as blank, never as an error")
    void missingPathsAreBlank() {
        // A typo in an authored binding must blank one label, not stop the package.
        assertEquals("", Bindings.resolve(STATE, "event.nope"));
        assertEquals("", Bindings.resolve(STATE, "nope.at.all"));
        assertEquals("", Bindings.resolve(STATE, "sides[9].short"), "an index past the end");
        assertEquals("", Bindings.resolve(STATE, "sides[0].nope"));
        assertEquals("", Bindings.resolve(STATE, ""));
        assertEquals("", Bindings.resolve(STATE, null));
        assertEquals("", Bindings.resolve(null, "scene"));
        assertNull(Bindings.walk(STATE, "event.nope"));
    }

    @Test
    @DisplayName("a container reads as blank rather than as printed JSON")
    void containersAreBlank() {
        // Nobody means to put Gson's output on air, so this reads as "no value".
        assertEquals("", Bindings.resolve(STATE, "sides"));
        assertEquals("", Bindings.resolve(STATE, "event"));
        assertNotNull(Bindings.walk(STATE, "sides"), "but the element is still reachable");
    }

    @Test
    @DisplayName("a null field reads as blank")
    void nullReadsAsBlank() {
        JsonElement withNull = JsonParser.parseString("{\"a\":null}");
        assertEquals("", Bindings.resolve(withNull, "a"));
    }

    @Test
    @DisplayName("indices can be chained")
    void chainedIndices() {
        assertEquals("yes", Bindings.resolve(STATE, "nested[0][0].deep"));
    }

    @Test
    @DisplayName("resolveInt falls back instead of throwing")
    void resolveIntFallsBack() {
        assertEquals(3, Bindings.resolveInt(STATE, "sides[0].score", -1));
        assertEquals(12, Bindings.resolveInt(STATE, "rev", -1));
        assertEquals(-1, Bindings.resolveInt(STATE, "event.name", -1), "text is not a number");
        assertEquals(-1, Bindings.resolveInt(STATE, "missing", -1));
        assertEquals(-1, Bindings.resolveInt(STATE, "sides", -1));
    }
}
