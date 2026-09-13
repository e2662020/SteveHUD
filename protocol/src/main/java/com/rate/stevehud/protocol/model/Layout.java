package com.rate.stevehud.protocol.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The broadcast package: which graphics exist, where they sit, and how they look.
 *
 * <p>This document is the contract between three renderers that must agree with
 * each other:
 *
 * <ul>
 *   <li>the browser overlay, which is what OBS composites and what the audience sees</li>
 *   <li>the in-game HUD, so every player's screen carries the same package</li>
 *   <li>the layout editor, which is how an operator changes it</li>
 * </ul>
 *
 * <p>It is deliberately plain: public mutable fields, no records, no logic. Gson
 * fills it from JSON, the editor round-trips it, and the two renderers read it.
 * A partial document is legal — every field carries a usable default, and Gson
 * runs the field initialisers for anything the JSON omits, so
 * {@code {"elements":[...]}} is enough to describe a whole package.
 *
 * <h2>Coordinate system</h2>
 *
 * <p>Every measurement is in <b>design pixels</b> against {@link Canvas}, by
 * default 1920x1080. Nothing is stored relative to the actual screen. A renderer
 * computes one uniform factor from the real viewport and multiplies, which is
 * what keeps the package identical at 720p, 1080p and 4K rather than merely
 * similar.
 *
 * <p>An element is positioned by an anchor plus an offset, never by an absolute
 * coordinate. That is what makes the package resolution-independent <em>and</em>
 * what makes scaling behave: an element is scaled about its own pinned corner, so
 * a top-right panel stays in the top-right as it grows. Storing absolute
 * coordinates would make both properties the caller's problem.
 *
 * <h2>Anchors</h2>
 *
 * <p>{@link #ANCHOR_TOP_LEFT} and friends. The offset then runs inward from that
 * corner: {@code x} counts right from a left anchor and left from a right one,
 * {@code y} counts down from a top anchor and up from a bottom one. So
 * {@code "anchor":"topRight","x":26} means "26 design pixels in from the right
 * edge", and moving an element further from its edge is always a positive number
 * whichever corner it is pinned to.
 */
public final class Layout {

    /** Bumped when the shape changes in a way a reader has to know about. */
    public static final int VERSION = 1;

    // ---- element types ------------------------------------------------------
    // The set is deliberately fixed rather than open. Each type is a piece of
    // broadcast grammar with its own drawing code and its own meaning; opening it
    // up would make the editor able to produce documents neither renderer can draw.
    // Anything a package needs beyond these is a Layout.TEXT element with a binding.

    /** Live badge, wall clock and one band per side with its score. */
    public static final String TYPE_MATCH_BUG = "matchBug";
    /** What is being played: event name and stage. */
    public static final String TYPE_EVENT_INFO = "eventInfo";
    /** The match clock, at the largest type in the package. */
    public static final String TYPE_TIMER = "timer";
    /** Competitor introduction card that slides in from its edge. */
    public static final String TYPE_LOWER_THIRD = "lowerThird";
    /** Scrolling notices along the bottom edge. */
    public static final String TYPE_TICKER = "ticker";
    /** Centre card for round transitions and headlines. */
    public static final String TYPE_ANNOUNCEMENT = "announcement";
    /** Safe-area corner brackets. Frames the viewport; ignores width and height. */
    public static final String TYPE_FRAME = "frame";
    /** A single line of text, either literal or read from the match state. */
    public static final String TYPE_TEXT = "text";

    /** Every type a renderer is expected to draw. */
    public static final List<String> TYPES = List.of(
            TYPE_MATCH_BUG, TYPE_EVENT_INFO, TYPE_TIMER, TYPE_LOWER_THIRD,
            TYPE_TICKER, TYPE_ANNOUNCEMENT, TYPE_FRAME, TYPE_TEXT);

    // ---- anchors ------------------------------------------------------------

    public static final String ANCHOR_TOP_LEFT = "topLeft";
    public static final String ANCHOR_TOP_RIGHT = "topRight";
    public static final String ANCHOR_BOTTOM_LEFT = "bottomLeft";
    public static final String ANCHOR_BOTTOM_RIGHT = "bottomRight";
    public static final String ANCHOR_CENTER = "center";

    public static final List<String> ANCHORS = List.of(
            ANCHOR_TOP_LEFT, ANCHOR_TOP_RIGHT,
            ANCHOR_BOTTOM_LEFT, ANCHOR_BOTTOM_RIGHT, ANCHOR_CENTER);

    // ---- scenes -------------------------------------------------------------
    // A scene is how much of the package is on air. An element may be restricted
    // to some of them; an empty list means every scene.

    public static final String SCENE_FULL = "full";
    public static final String SCENE_COMPACT = "compact";
    public static final String SCENE_MINIMAL = "minimal";

    public static final List<String> SCENES = List.of(SCENE_FULL, SCENE_COMPACT, SCENE_MINIMAL);

    // ---- entrances ----------------------------------------------------------

    public static final String ENTER_NONE = "none";
    public static final String ENTER_FADE = "fade";
    public static final String ENTER_SLIDE_DOWN = "slideDown";
    public static final String ENTER_SLIDE_UP = "slideUp";
    public static final String ENTER_SLIDE_LEFT = "slideLeft";
    public static final String ENTER_SLIDE_RIGHT = "slideRight";
    public static final String ENTER_SCALE_IN = "scaleIn";
    public static final String ENTER_POP_IN = "popIn";

    public static final List<String> ENTERS = List.of(
            ENTER_NONE, ENTER_FADE, ENTER_SLIDE_DOWN, ENTER_SLIDE_UP,
            ENTER_SLIDE_LEFT, ENTER_SLIDE_RIGHT, ENTER_SCALE_IN, ENTER_POP_IN);

    /** How an element reacts to its data changing while it is already on screen. */
    public static final List<String> UPDATES = List.of("none", "pop", "flash");

    /** Easing names, matching {@code Anim.Tween.Curve} and the browser's curve table. */
    public static final List<String> EASINGS = List.of(
            "linear", "outCubic", "outQuint", "outBack", "inOutSine");

    // ---- document -----------------------------------------------------------

    public int version = VERSION;

    /** Human-readable name of the package, shown in the editor's preset list. */
    public String name = "";

    public Canvas canvas = new Canvas();
    public Theme theme = new Theme();
    public Safe safe = new Safe();

    /** Back to front: the first element in the list is drawn first. */
    public List<Element> elements = new ArrayList<>();

    public Layout() {
    }

    /** The element with this id, or null. Ids are unique after {@link Layouts#normalize}. */
    public Element element(String id) {
        if (id == null) {
            return null;
        }
        for (Element element : elements) {
            if (id.equals(element.id)) {
                return element;
            }
        }
        return null;
    }

    /** The first element of this type, or null. */
    public Element first(String type) {
        for (Element element : elements) {
            if (element != null && type.equals(element.type)) {
                return element;
            }
        }
        return null;
    }

    /** True when this element should be on air in {@code scene}. */
    public static boolean showsIn(Element element, String scene) {
        if (element == null || !element.visible) {
            return false;
        }
        List<String> scenes = element.scenes;
        if (scenes == null || scenes.isEmpty()) {
            return true;
        }
        // An unknown scene reads as the fullest one rather than as "nothing shows",
        // so a typo in a scene name cannot black out the entire package.
        String wanted = SCENES.contains(scene) ? scene : SCENE_FULL;
        return scenes.contains(wanted);
    }

    /** The design-space box everything is measured against. */
    public static final class Canvas {
        public int width = 1920;
        public int height = 1080;
    }

    /**
     * Package palette.
     *
     * <p>Colours are CSS notation exactly as a browser writes them: {@code #RGB},
     * {@code #RRGGBB}, or {@code #RRGGBBAA} with the alpha <em>last</em>. The same
     * strings drive the web overlay's custom properties and, through
     * {@code Colors.parse}, the in-game primitives, so there is one colour format
     * in the project rather than one per renderer. Getting the alpha order wrong
     * here is the kind of mistake that shows up as a panel that is subtly too
     * transparent in one renderer only, so it is stated rather than implied.
     */
    public static final class Theme {
        public String accent = "#FFC24B";
        public String live = "#FF4D4D";
        public String ink = "#F2F4F8";
        public String inkDim = "#9AA4B2";
        /** Near-opaque on purpose: in OBS the page is composited over gameplay. */
        public String panelTop = "#1A2030E6";
        public String panelBottom = "#0C1018F0";
        public String border = "#FFFFFF40";
        public String home = "#4C9AFF";
        public String away = "#FF6B6B";
        /** Empty means the platform's own UI font stack. */
        public String font = "";
    }

    /**
     * Space kept clear of the viewport edges, in design pixels.
     *
     * <p>This is what the safe-area frame draws and what the default offsets were
     * chosen against, so a package that respects it will not be cropped by a
     * broadcaster that overlays its own furniture.
     */
    public static final class Safe {
        public int x = 26;
        public int y = 22;
    }

    /**
     * One piece of the package.
     *
     * <p>{@code width} or {@code height} of 0 means "as large as the content
     * needs", which is how a panel keeps a background that matches its text
     * without anyone computing a height by hand.
     */
    public static final class Element {

        /** Stable identity, used by the editor and by the binding path of nothing else. */
        public String id = "";
        public String type = TYPE_TEXT;
        /** Shown in the editor's element list; never rendered. */
        public String label = "";
        public boolean visible = true;
        /** Scenes this element appears in. Empty means all of them. */
        public List<String> scenes = new ArrayList<>();

        public String anchor = ANCHOR_TOP_LEFT;
        /** Inward offset from the anchored corner, in design pixels. */
        public int x = 0;
        public int y = 0;
        /** 0 means content-sized. */
        public int width = 0;
        public int height = 0;
        public float scale = 1f;
        public float opacity = 1f;

        /** {@link Layout#TYPE_TEXT} only: literal text, used when {@link #binding} is empty. */
        public String text = "";
        /** {@link Layout#TYPE_TEXT} only: a path into the match state, e.g. {@code sides[0].score}. */
        public String binding = "";

        public Style style = new Style();
        public Motion anim = new Motion();

        public Element() {
        }

        public Element(String id, String type, String label) {
            this.id = id;
            this.type = type;
            this.label = label;
        }

        /** Fluent helper, so a preset reads as a list of configured elements. */
        public Element at(String anchor, int x, int y) {
            this.anchor = anchor;
            this.x = x;
            this.y = y;
            return this;
        }

        public Element size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Element scenesIn(String... scenes) {
            this.scenes = new ArrayList<>(new LinkedHashSet<>(List.of(scenes)));
            return this;
        }

        public Element entering(String enter) {
            this.anim.enter = enter;
            return this;
        }
    }

    /**
     * Per-element overrides of the theme.
     *
     * <p>Null means inherit. Keeping "unset" distinguishable from "set to the
     * theme's value" is what lets a package be restyled by changing the theme
     * alone, without every element that happens to agree with it being pinned to
     * the old colour.
     */
    public static final class Style {
        public String accent;
        public String panel;
        public String ink;
        /** Multiplies the type's own size. 1 means "as designed". */
        public float fontScale = 1f;
        public boolean uppercase = false;
    }

    /**
     * How an element arrives and how it reacts to change.
     *
     * <p>Durations are in milliseconds of wall clock, not ticks, because the two
     * renderers must agree on the timing and the browser has no ticks.
     */
    public static final class Motion {
        public String enter = ENTER_SLIDE_DOWN;
        public String update = "pop";
        public int durationMs = 420;
        public String easing = "outCubic";

        /** How long the entrance takes, clamped to something a broadcast can use. */
        public int safeDurationMs() {
            return Math.clamp(durationMs, 0, 4000);
        }
    }

    /** Element ids in this document, in draw order. */
    public Set<String> ids() {
        Set<String> out = new LinkedHashSet<>();
        for (Element element : elements) {
            if (element != null) {
                out.add(element.id);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "Layout[" + name + ", " + elements.size() + " elements]";
    }
}
