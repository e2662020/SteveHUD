package com.rate.stevehud.protocol.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loading, validating and repairing {@link Layout} documents.
 *
 * <p>Three jobs, and the third is the one that matters:
 *
 * <ol>
 *   <li>Read the built-in packages shipped as JSON resources.</li>
 *   <li>Serialise a document for the editor and for the file on disk.</li>
 *   <li><b>Normalise.</b> Make any document safe to render, whatever it contains.</li>
 * </ol>
 *
 * <p>Normalisation is not defensive padding. The layout document is edited by hand
 * and by a browser, and it drives a live broadcast: one malformed value in it must
 * degrade that one element, not blank the package. So every field is clamped into a
 * range a renderer can draw, an unparsable colour falls back to the theme's, an
 * unknown element type is dropped, and duplicate ids are made unique rather than
 * being allowed to make {@link Layout#element} ambiguous.
 *
 * <p>The built-in packages live in {@code stevehud/layout/*.json} rather than in
 * code so that the browser preview server can read exactly the same files the mod
 * ships. {@link #fallback} exists only for the case where a resource is missing
 * from the jar, which would otherwise leave the package with nothing to draw.
 */
public final class Layouts {

    /** The package a fresh install starts with: dark, dense, cyan. */
    public static final String PRESET_ARENA = "arena";
    /** Light panels, gold and deep blue, information-heavy. Olympic in feel. */
    public static final String PRESET_OLYMPIA = "olympia";
    /** The least furniture that is still a package. Leaves the frame to the match. */
    public static final String PRESET_CLEAN = "clean";

    private static final List<String> PRESETS = List.of(
            PRESET_ARENA, PRESET_OLYMPIA, PRESET_CLEAN);

    private static final String RESOURCE_DIR = "/stevehud/layout/";

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();
    private static final Gson PRETTY = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private Layouts() {
    }

    /** The packages that ship with the mod, in the order the editor lists them. */
    public static List<String> presetNames() {
        return PRESETS;
    }

    /** A display name for a preset, for command feedback and the editor's list. */
    public static String presetLabel(String preset) {
        return switch (preset == null ? "" : preset) {
            case PRESET_ARENA -> "竞技场";
            case PRESET_OLYMPIA -> "奥林匹亚";
            case PRESET_CLEAN -> "边线";
            default -> preset == null ? "" : preset;
        };
    }

    /** True when {@code name} is one of the shipped packages. */
    public static boolean isPreset(String name) {
        return name != null && PRESETS.contains(name);
    }

    /**
     * Reads a built-in package.
     *
     * @return the normalised document, or {@link #fallback()} if the resource is
     *         missing or unreadable. Never null, never throws: the overlay is
     *         allowed to be wrong, but it is not allowed to have nothing to draw.
     */
    public static Layout load(String preset) {
        String name = isPreset(preset) ? preset : PRESET_ARENA;
        try (InputStream in = Layouts.class.getResourceAsStream(RESOURCE_DIR + name + ".json")) {
            if (in == null) {
                return fallback();
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Layout layout = fromJson(json);
            return layout == null ? fallback() : normalize(layout);
        } catch (IOException e) {
            return fallback();
        }
    }

    /**
     * Parses a document.
     *
     * @return null when the text is not a JSON object; the caller decides whether
     *         that is worth reporting
     */
    public static Layout fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Layout layout = GSON.fromJson(json, Layout.class);
            // Gson returns the literal null for the JSON text "null".
            return layout == null ? null : normalize(layout);
        } catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }
    }

    public static String toJson(Layout layout) {
        return GSON.toJson(layout);
    }

    /** Indented, for the file on disk and for a human reading the editor. */
    public static String toPrettyJson(Layout layout) {
        return PRETTY.toJson(layout);
    }

    // ---- normalisation ------------------------------------------------------

    /**
     * Makes a document safe to render, in place.
     *
     * <p>Idempotent: normalising an already-normalised document changes nothing,
     * which is what lets it run on every load, every save and every edit without
     * values drifting a little further on each pass.
     *
     * @return the same instance, for chaining
     */
    public static Layout normalize(Layout layout) {
        if (layout == null) {
            return fallback();
        }
        if (layout.canvas == null) {
            layout.canvas = new Layout.Canvas();
        }
        layout.canvas.width = Math.clamp(layout.canvas.width, 320, 16384);
        layout.canvas.height = Math.clamp(layout.canvas.height, 240, 16384);

        if (layout.theme == null) {
            layout.theme = new Layout.Theme();
        }
        Layout.Theme defaults = new Layout.Theme();
        layout.theme.accent = color(layout.theme.accent, defaults.accent);
        layout.theme.live = color(layout.theme.live, defaults.live);
        layout.theme.ink = color(layout.theme.ink, defaults.ink);
        layout.theme.inkDim = color(layout.theme.inkDim, defaults.inkDim);
        layout.theme.panelTop = color(layout.theme.panelTop, defaults.panelTop);
        layout.theme.panelBottom = color(layout.theme.panelBottom, defaults.panelBottom);
        layout.theme.border = color(layout.theme.border, defaults.border);
        layout.theme.home = color(layout.theme.home, defaults.home);
        layout.theme.away = color(layout.theme.away, defaults.away);
        layout.theme.font = layout.theme.font == null ? "" : layout.theme.font.trim();

        if (layout.safe == null) {
            layout.safe = new Layout.Safe();
        }
        layout.safe.x = Math.clamp(layout.safe.x, 0, layout.canvas.width / 3);
        layout.safe.y = Math.clamp(layout.safe.y, 0, layout.canvas.height / 3);

        if (layout.name == null) {
            layout.name = "";
        }
        layout.version = Layout.VERSION;

        if (layout.elements == null) {
            layout.elements = new ArrayList<>();
        }
        List<Layout.Element> kept = new ArrayList<>(layout.elements.size());
        Set<String> used = new LinkedHashSet<>();
        for (Layout.Element element : layout.elements) {
            if (element == null || element.type == null || !Layout.TYPES.contains(element.type)) {
                continue;
            }
            normalizeElement(element, used);
            kept.add(element);
        }
        layout.elements = kept;
        return layout;
    }

    private static void normalizeElement(Layout.Element element, Set<String> used) {
        element.id = uniqueId(element.id, element.type, used);
        used.add(element.id);
        element.label = element.label == null ? "" : element.label;
        element.anchor = Layout.ANCHORS.contains(element.anchor)
                ? element.anchor : Layout.ANCHOR_TOP_LEFT;
        // Offsets are unbounded on purpose: an operator may want an element to hang
        // off the edge, and clamping them to the canvas would make that impossible.
        element.width = Math.max(0, Math.min(element.width, 32768));
        element.height = Math.max(0, Math.min(element.height, 32768));
        element.scale = clampFloat(element.scale, 1f, 0.05f, 8f);
        element.opacity = clampFloat(element.opacity, 1f, 0f, 1f);

        if (element.scenes == null) {
            element.scenes = new ArrayList<>();
        }
        List<String> scenes = new ArrayList<>(element.scenes.size());
        for (String scene : element.scenes) {
            if (scene != null && Layout.SCENES.contains(scene) && !scenes.contains(scene)) {
                scenes.add(scene);
            }
        }
        element.scenes = scenes;

        if (element.style == null) {
            element.style = new Layout.Style();
        }
        // null stays null: "inherit from the theme" is a real state, and collapsing
        // it to a concrete colour here would freeze every element against a theme
        // change.
        element.style.accent = colorOrNull(element.style.accent);
        element.style.panel = colorOrNull(element.style.panel);
        element.style.ink = colorOrNull(element.style.ink);
        element.style.fontScale = clampFloat(element.style.fontScale, 1f, 0.25f, 4f);

        if (element.anim == null) {
            element.anim = new Layout.Motion();
        }
        element.anim.enter = Layout.ENTERS.contains(element.anim.enter)
                ? element.anim.enter : Layout.ENTER_FADE;
        element.anim.update = Layout.UPDATES.contains(element.anim.update)
                ? element.anim.update : "pop";
        element.anim.easing = Layout.EASINGS.contains(element.anim.easing)
                ? element.anim.easing : "outCubic";
        element.anim.durationMs = Math.clamp(element.anim.durationMs, 0, 4000);

        element.text = element.text == null ? "" : element.text;
        element.binding = element.binding == null ? "" : element.binding.trim();

        // Options are an open schema owned by the renderer, so this only enforces
        // the two things a document edited by hand gets wrong: a null key, and a
        // value that is not a string. Bounded so one runaway document cannot make
        // every broadcast message megabytes wide.
        if (element.options == null) {
            element.options = new LinkedHashMap<>();
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : element.options.entrySet()) {
            if (entry.getKey() == null || options.size() >= MAX_OPTIONS) {
                continue;
            }
            String key = entry.getKey().trim();
            if (key.isEmpty() || key.length() > MAX_OPTION_KEY) {
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            options.put(key, value.length() > MAX_OPTION_VALUE
                    ? value.substring(0, MAX_OPTION_VALUE) : value);
        }
        element.options = options;
    }

    /** Enough for every board to be configured several times over. */
    private static final int MAX_OPTIONS = 48;
    private static final int MAX_OPTION_KEY = 48;
    private static final int MAX_OPTION_VALUE = 512;

    /**
     * A non-empty id no other element is using. Ids have to be unique because the
     * editor addresses elements by them, and two "text" elements dragged in from a
     * palette would otherwise collide.
     */
    private static String uniqueId(String id, String type, Set<String> used) {
        String base = id == null ? "" : id.trim();
        if (base.isEmpty()) {
            base = type;
        }
        if (!used.contains(base)) {
            return base;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + "-" + suffix;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return base + "-" + System.identityHashCode(base);
    }

    private static float clampFloat(float value, float fallback, float min, float max) {
        if (Float.isNaN(value)) {
            return fallback;
        }
        return value < min ? min : (value > max ? max : value);
    }

    /** {@code value} when it parses as a colour, {@code fallback} otherwise. */
    private static String color(String value, String fallback) {
        return isColor(value) ? value.trim() : fallback;
    }

    /** As {@link #color}, but null passes through: null means "inherit". */
    private static String colorOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return isColor(value) ? value.trim() : null;
    }

    private static final Set<Character> HEX = hexDigits();

    /** Accepts {@code #RGB}, {@code #RRGGBB} and {@code #RRGGBBAA}. */
    public static boolean isColor(String value) {
        if (value == null) {
            return false;
        }
        String hex = value.trim();
        if (hex.isEmpty() || hex.charAt(0) != '#') {
            return false;
        }
        int digits = hex.length() - 1;
        if (digits != 3 && digits != 6 && digits != 8) {
            return false;
        }
        for (int i = 1; i < hex.length(); i++) {
            if (!HEX.contains(Character.toLowerCase(hex.charAt(i)))) {
                return false;
            }
        }
        return true;
    }

    private static Set<Character> hexDigits() {
        Set<Character> digits = new LinkedHashSet<>(16);
        for (char c : "0123456789abcdef".toCharArray()) {
            digits.add(c);
        }
        return digits;
    }

    /**
     * A usable package when no preset resource could be read.
     *
     * <p>Deliberately short. Its job is to leave the package drawing something
     * honest — a bug and a clock — while making clear from its name that the real
     * presets are missing, rather than to reimplement the flagship package in code
     * and have two definitions to keep in step.
     */
    public static Layout fallback() {
        Layout layout = new Layout();
        layout.name = "fallback";
        layout.elements.add(new Layout.Element("bug", Layout.TYPE_MATCH_BUG, "比赛角标")
                .at(Layout.ANCHOR_TOP_LEFT, 26, 22).size(346, 0)
                .entering(Layout.ENTER_SLIDE_DOWN));
        layout.elements.add(new Layout.Element("timer", Layout.TYPE_TIMER, "计时器")
                .at(Layout.ANCHOR_BOTTOM_RIGHT, 26, 26).size(0, 0)
                .entering(Layout.ENTER_FADE));
        return normalize(layout);
    }
}
