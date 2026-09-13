package com.rate.stevehud.mod.client.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Reads a value out of the match state by path.
 *
 * <p>This is what lets a {@code text} element in a package say something about the
 * match. A package that wants a second score readout, a discipline caption or a
 * sponsor line does not need new code in either renderer — it needs one element and
 * one path.
 *
 * <p>The grammar is deliberately tiny, because the browser overlay implements the
 * same one in JavaScript and two implementations of a rich grammar would drift:
 *
 * <pre>
 *   event.name                 an object field
 *   sides[0].score             an array index, then a field
 *   sides[0].competitors[0].name
 * </pre>
 *
 * <p>Segments are separated by dots; any segment may be followed by one or more
 * {@code [n]} indices. Nothing else — no wildcards, no expressions, no arithmetic.
 *
 * <p>Every failure returns the empty string rather than throwing or returning null.
 * A binding is a piece of authored content that can be typo'd, and a typo has to
 * mean "this label is blank", not "the package stops drawing".
 */
public final class Bindings {

    private Bindings() {
    }

    /**
     * The value at {@code path}, as display text.
     *
     * @return the value, or the empty string when the path does not resolve
     */
    public static String resolve(JsonElement root, String path) {
        JsonElement found = walk(root, path);
        if (found == null || found.isJsonNull()) {
            return "";
        }
        if (found.isJsonPrimitive()) {
            JsonPrimitive primitive = found.getAsJsonPrimitive();
            // A boolean reads as "true"/"false" rather than "1"/"0" because these end
            // up on screen next to words.
            return primitive.getAsString();
        }
        // An object or array has no sensible one-line rendering, and printing
        // Gson's JSON onto a broadcast graphic is never what the author meant.
        return "";
    }

    /** The numeric value at {@code path}, or {@code fallback} when there is none. */
    public static int resolveInt(JsonElement root, String path, int fallback) {
        JsonElement found = walk(root, path);
        if (found == null || !found.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return found.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    /**
     * The element at {@code path}.
     *
     * @return null when the path does not resolve, which the caller can tell apart
     *         from a JSON null only by checking {@link JsonElement#isJsonNull}
     */
    public static JsonElement walk(JsonElement root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        JsonElement current = root;
        for (String segment : split(path.trim())) {
            if (current == null) {
                return null;
            }
            current = step(current, segment);
        }
        return current;
    }

    /**
     * Splits a path into segments, keeping an index attached to the segment it
     * belongs to: {@code sides[0].name} becomes {@code ["sides[0]", "name"]}.
     */
    private static String[] split(String path) {
        return path.split("\\.");
    }

    /** One segment: a field name, optionally with {@code [n]} indices after it. */
    private static JsonElement step(JsonElement current, String segment) {
        int bracket = segment.indexOf('[');
        String field = bracket < 0 ? segment : segment.substring(0, bracket);

        if (!field.isEmpty()) {
            if (!current.isJsonObject()) {
                return null;
            }
            JsonObject object = current.getAsJsonObject();
            if (!object.has(field)) {
                return null;
            }
            current = object.get(field);
        }

        int cursor = bracket;
        while (cursor >= 0 && cursor < segment.length()) {
            int close = segment.indexOf(']', cursor);
            if (close < 0) {
                return null;
            }
            int index;
            try {
                index = Integer.parseInt(segment.substring(cursor + 1, close).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (!current.isJsonArray()) {
                return null;
            }
            JsonArray array = current.getAsJsonArray();
            if (index < 0 || index >= array.size()) {
                // Out of range reads as absent, so a package with a two-team layout
                // draws nothing for a third side rather than throwing.
                return null;
            }
            current = array.get(index);
            cursor = segment.indexOf('[', close);
        }
        return current;
    }
}
