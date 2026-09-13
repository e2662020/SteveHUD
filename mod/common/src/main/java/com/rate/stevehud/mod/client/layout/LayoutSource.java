package com.rate.stevehud.mod.client.layout;

import com.rate.stevehud.protocol.model.Layout;
import com.rate.stevehud.protocol.model.Layouts;

/**
 * Which package is actually on air, resolved from the two scopes.
 *
 * <p>There are two documents in play and it matters which one wins:
 *
 * <ul>
 *   <li>the <b>local</b> one on disk, which drives this machine's OBS overlay and
 *       this machine's HUD — the operator's own packaging work, needing no
 *       permission;</li>
 *   <li>the <b>server</b> one, pushed by an operator so that every screen in the
 *       match draws the same package.</li>
 * </ul>
 *
 * <p>The server's wins while it is set, because that is the whole point of setting
 * it: if a client could quietly keep drawing its own package, "the same graphics
 * on every screen" would be a hope rather than a property. Clearing it hands
 * control back to each client.
 *
 * <p>This class exists in the Minecraft-free half so the precedence rule can be
 * tested. It is also the only place that answers "what is on air", which keeps
 * that question from being answered differently in the HUD, the web overlay and
 * the command feedback.
 */
public final class LayoutSource {

    /** No server package: each client draws its own. */
    public static final String SCOPE_LOCAL = "local";
    /** The server's package is in effect everywhere. */
    public static final String SCOPE_SERVER = "server";

    private final LayoutStore local;

    /** The server's package as last received; null when it sent none. */
    private volatile Layout server;
    private volatile String serverLabel = "";
    private volatile String serverAuthor = "";

    public LayoutSource(LayoutStore local) {
        this.local = local;
    }

    /**
     * Applies a package from the server.
     *
     * @param layout the document, or null to go back to each client's own
     * @return true when this changed what is on air, so a caller knows whether to
     *         republish
     */
    public synchronized boolean acceptServer(Layout layout, String label, String author) {
        Layout normalised = layout == null ? null : Layouts.normalize(layout);
        if (normalised != null && normalised.elements.isEmpty()) {
            // A package with nothing in it would blank every screen in the match. Read
            // as "no package" here rather than only at the call site, so the rule holds
            // whichever path the document arrives by — this is the last place before it
            // reaches a renderer, which is where the invariant belongs.
            normalised = null;
        }
        boolean changed = (server == null) != (normalised == null)
                || (normalised != null && !Layouts.toJson(normalised).equals(Layouts.toJson(server)));
        server = normalised;
        serverLabel = label == null ? "" : label;
        serverAuthor = author == null ? "" : author;
        return changed;
    }

    /**
     * Forgets the server's package, as on disconnect: it belonged to that server.
     *
     * @return true when there was one to forget, so the caller knows whether what is
     *         on air just changed
     */
    public synchronized boolean clearServer() {
        if (server == null) {
            return false;
        }
        server = null;
        serverLabel = "";
        serverAuthor = "";
        return true;
    }

    /** True while the server's package is what every client is drawing. */
    public boolean serverActive() {
        return server != null;
    }

    /** {@link #SCOPE_SERVER} or {@link #SCOPE_LOCAL}. */
    public String scope() {
        return serverActive() ? SCOPE_SERVER : SCOPE_LOCAL;
    }

    public String serverLabel() {
        return serverLabel;
    }

    public String serverAuthor() {
        return serverAuthor;
    }

    /**
     * The document to draw.
     *
     * <p>Never null: with no server package and no readable local file this is the
     * built-in fallback, because a renderer with nothing to draw is not an
     * acceptable state for a broadcast.
     */
    public Layout effective() {
        Layout fromServer = server;
        return fromServer != null ? fromServer : local.current();
    }

    /** The local document, which is what the editor edits regardless of scope. */
    public Layout local() {
        return local.current();
    }

    public LayoutStore store() {
        return local;
    }

    /**
     * A name for what is on air, for command output and the editor banner.
     *
     * <p>In the server scope this is the server's label; in the local scope, the
     * local document's name. A proper noun rather than a sentence: this module holds
     * no translated text, so each caller composes its own wording around it — which
     * is also what lets the command answer in the player's own language.
     */
    public String onAirName() {
        Layout fromServer = server;
        if (fromServer == null) {
            return local.current().name;
        }
        return serverLabel.isBlank() ? fromServer.name : serverLabel;
    }
}
