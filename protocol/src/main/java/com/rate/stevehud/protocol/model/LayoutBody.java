package com.rate.stevehud.protocol.model;

/**
 * A broadcast package sent by the server for every client to draw.
 *
 * <p>Two scopes exist, and they are different in kind, not only in size:
 *
 * <ul>
 *   <li><b>local</b> — the document on one machine's disk. It drives that
 *       machine's OBS overlay and that machine's in-game HUD, and nothing else.
 *       This is the operator's own packaging work, so it needs no permission at
 *       all.</li>
 *   <li><b>server</b> — this message. The plugin owns one package and every
 *       connected client draws it instead of its own, which is what makes the
 *       graphics identical on every screen in the match. It changes what other
 *       people see, so only an operator can set it.</li>
 * </ul>
 *
 * <p>{@link #layout} being null is a real, meaningful state rather than a missing
 * value: it means "the server has no package, each client draws its own". That is
 * the default, deliberately — a server that forced a package on everyone by
 * default would make the local scope unreachable, and the local scope is where
 * OBS packaging actually happens.
 */
public final class LayoutBody {

    /** Only {@code "server"} exists today; the field is here so a future scope can be added. */
    public static final String SCOPE_SERVER = "server";

    public String scope = SCOPE_SERVER;

    /** Null means "no package; fall back to each client's own". */
    public Layout layout;

    /** A human-readable name for command feedback, e.g. "奥运转播". */
    public String label = "";

    /** Who set it, for feedback. Empty when nobody is being credited. */
    public String author = "";

    public LayoutBody() {
    }

    /** A package every client must draw. */
    public static LayoutBody of(Layout layout, String label, String author) {
        LayoutBody body = new LayoutBody();
        body.layout = layout;
        body.label = label == null ? "" : label;
        body.author = author == null ? "" : author;
        return body;
    }

    /** The instruction to go back to each client's own package. */
    public static LayoutBody cleared(String author) {
        LayoutBody body = new LayoutBody();
        body.layout = null;
        body.label = "";
        body.author = author == null ? "" : author;
        return body;
    }

    /** True when this message carries a package rather than clearing one. */
    public boolean present() {
        return layout != null && !layout.elements.isEmpty();
    }

    /**
     * What to call this package in chat: the label if there is one, else the
     * document's own name.
     *
     * <p>A proper noun, deliberately — never a sentence. The protocol module has no
     * business holding a translated string, so the wording around it is composed by
     * whoever is speaking, in their own language.
     */
    public String labelOrName() {
        if (!present()) {
            return "";
        }
        return label == null || label.isBlank() ? layout.name : label;
    }

    @Override
    public String toString() {
        return "LayoutBody[" + scope + ", " + (present() ? labelOrName() : "cleared") + "]";
    }
}
