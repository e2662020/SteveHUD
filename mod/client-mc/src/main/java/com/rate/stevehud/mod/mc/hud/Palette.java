package com.rate.stevehud.mod.mc.hud;

import com.rate.stevehud.protocol.model.Layout;

import java.util.Locale;

/**
 * The colours and type treatment one element draws with.
 *
 * <p>Resolved once per element from the document's theme and that element's own
 * overrides, so the painters below never ask where a colour came from and an
 * element that sets nothing simply inherits. That inheritance is the point: a
 * package can be restyled by editing the theme alone, which is only true if an
 * unset override stays distinguishable from one that happens to match.
 *
 * <p>Public final fields rather than a record: the painters read these several
 * times each, and a record's components are private, so every read would need an
 * accessor call for no gain. This is a plain value holder, and matches how the
 * layout document itself is written.
 */
final class Palette {

    /** Highlights, rules, the team block's edge. */
    final int accent;
    /** The LIVE badge and the running indicator. */
    final int live;
    /** Primary text. */
    final int ink;
    /** Secondary text. */
    final int inkDim;
    /** Panel gradient, top. */
    final int panelTop;
    /** Panel gradient, bottom. */
    final int panelBottom;
    /** Panel outline. */
    final int border;
    /** Fallback colour for the first side. */
    final int home;
    /** Fallback colour for the second side. */
    final int away;
    /** Multiplies the element's own size. */
    final float fontScale;
    /** Whether text is set in capitals. */
    final boolean uppercase;

    private Palette(int accent, int live, int ink, int inkDim,
                    int panelTop, int panelBottom, int border,
                    int home, int away, float fontScale, boolean uppercase) {
        this.accent = accent;
        this.live = live;
        this.ink = ink;
        this.inkDim = inkDim;
        this.panelTop = panelTop;
        this.panelBottom = panelBottom;
        this.border = border;
        this.home = home;
        this.away = away;
        this.fontScale = fontScale;
        this.uppercase = uppercase;
    }

    /** Builds a palette from a document's theme, with one element's overrides applied. */
    static Palette of(Layout.Theme theme, Layout.Element element) {
        Layout.Theme fallback = new Layout.Theme();
        String accent = theme == null ? null : theme.accent;
        int accentArgb = Colors.parse(accent, Colors.parse(fallback.accent, Prim.ACCENT));

        Layout.Style style = element == null ? null : element.style;
        return new Palette(
                Colors.parse(pick(style == null ? null : style.accent, accent), accentArgb),
                Colors.parse(theme == null ? null : theme.live, Prim.LIVE),
                Colors.parse(pick(style == null ? null : style.ink, theme == null ? null : theme.ink),
                        Prim.TEXT),
                Colors.parse(theme == null ? null : theme.inkDim, Prim.TEXT_DIM),
                Colors.parse(theme == null ? null : theme.panelTop, Prim.PANEL_TOP),
                Colors.parse(theme == null ? null : theme.panelBottom, Prim.PANEL_BOTTOM),
                Colors.parse(theme == null ? null : theme.border, Prim.BORDER),
                Colors.parse(theme == null ? null : theme.home, Prim.HOME),
                Colors.parse(theme == null ? null : theme.away, Prim.AWAY),
                style == null ? 1f : style.fontScale,
                style != null && style.uppercase);
    }

    /** The element's override when it has one, the theme's value otherwise. */
    private static String pick(String override, String inherited) {
        return override == null || override.isBlank() ? inherited : override;
    }

    /** Applies the package's capitalisation to a value about to be drawn. */
    String text(String value) {
        if (value == null) {
            return "";
        }
        // Locale.ROOT: a Turkish locale would otherwise map 'i' to a dotted capital,
        // which is a real way for a team name to come out wrong on air.
        return uppercase ? value.toUpperCase(Locale.ROOT) : value;
    }
}
