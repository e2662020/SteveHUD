package com.rate.stevehud.mod.client.layout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Merges a partial update into a document.
 *
 * <p>One rule, applied everywhere a partial payload meets a fuller one — the local
 * preview override, and the browser pages' handling of a state update that mentions
 * only the fields it changed:
 *
 * <ul>
 *   <li>a key holding an <b>object</b> is merged one level deeper, so a message that
 *       says {@code {"announcement":{"visible":false}}} cannot blank the title it
 *       did not mention;</li>
 *   <li>everything else — arrays, numbers, strings, booleans, null — <b>replaces</b>,
 *       because a list that arrives is the whole list.</li>
 * </ul>
 *
 * <p>Stated as one rule rather than a list of keys that happen to hold objects
 * today. A key list has to be edited every time the state grows a field, and the
 * failure when someone forgets is silent: a partial update that erases a score.
 */
public final class JsonMerge {

    private JsonMerge() {
    }

    /**
     * @return a new object: {@code base} with {@code patch} applied. Neither input
     *         is modified, so a caller can keep the base for the next merge.
     */
    public static JsonObject merge(JsonObject base, JsonObject patch) {
        JsonObject out = new JsonObject();
        if (base != null) {
            for (Map.Entry<String, JsonElement> entry : base.entrySet()) {
                out.add(entry.getKey(), entry.getValue());
            }
        }
        if (patch == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            String key = entry.getKey();
            JsonElement incoming = entry.getValue();
            JsonElement existing = out.get(key);
            if (incoming != null && incoming.isJsonObject()
                    && existing != null && existing.isJsonObject()) {
                out.add(key, merge(existing.getAsJsonObject(), incoming.getAsJsonObject()));
            } else {
                out.add(key, incoming);
            }
        }
        return out;
    }
}
