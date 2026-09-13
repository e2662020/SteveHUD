package com.rate.stevehud.mod.mc.hud;

/**
 * Parses the colours that arrive from the server.
 *
 * <p>Competitor colours are authored as CSS hex strings because the same value has
 * to drive the web overlay, so the client parses them rather than assuming a fixed
 * palette. Anything unparseable falls back rather than throwing: a malformed colour
 * in a competition's config must not take the whole package off the screen.
 */
final class Colors {

    private Colors() {
    }

    /** Parses {@code #RGB}, {@code #RRGGBB} or {@code #AARRGGBB}; {@code fallback} on anything else. */
    static int parse(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        // A leading alpha is common in web-authored colours, so accept it and
        // reorder into the ARGB the game wants.
        try {
            return switch (hex.length()) {
                case 3 -> {
                    int r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
                    int g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
                    int b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
                    yield 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                case 6 -> 0xFF000000 | Integer.parseInt(hex, 16);
                case 8 -> {
                    int rgba = (int) Long.parseLong(hex, 16);
                    int alpha = rgba & 0xFF;
                    int rgb = rgba >>> 8;
                    yield (alpha << 24) | rgb;
                }
                default -> fallback;
            };
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
