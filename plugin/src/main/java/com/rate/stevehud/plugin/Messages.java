package com.rate.stevehud.plugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player message lookup.
 *
 * <p>The server has to answer each player in that player's own language, because
 * a single server hosts players on differently localised clients. Bukkit exposes
 * the client locale through {@link Player#getLocale()}, so messages come from a
 * small JSON bundle per locale, falling back to English and then to the key
 * itself — a missing translation should be visible, not silent.
 */
final class Messages {

    private static final String FALLBACK_LOCALE = "en_us";

    private final Plugin plugin;
    private final Gson gson = new Gson();
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    Messages(Plugin plugin) {
        this.plugin = plugin;
    }

    String get(Player player, String key, Object... args) {
        String pattern = bundleFor(player).get(key);
        if (pattern == null) {
            // Showing the raw key makes a missing translation obvious in game
            // rather than silently dropping the message.
            return key;
        }
        return args.length == 0 ? pattern : String.format(pattern, args);
    }

    private Map<String, String> bundleFor(Player player) {
        String tag = player == null || player.getLocale() == null
                ? FALLBACK_LOCALE
                : player.getLocale().toLowerCase(Locale.ROOT);

        Map<String, String> bundle = cache.computeIfAbsent(tag, this::load);
        if (bundle.isEmpty()) {
            // e.g. "de_de" is not shipped; serve English rather than keys.
            bundle = cache.computeIfAbsent(FALLBACK_LOCALE, this::load);
        }
        return bundle;
    }

    private Map<String, String> load(String tag) {
        String path = "lang/" + tag + ".json";
        try (InputStream stream = plugin.getResource(path)) {
            if (stream == null) {
                return Map.of();
            }
            Map<String, String> parsed = gson.fromJson(
                    new InputStreamReader(stream, StandardCharsets.UTF_8),
                    new TypeToken<HashMap<String, String>>() {
                    }.getType());
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read " + path + ": " + e.getMessage());
            return Map.of();
        }
    }
}
