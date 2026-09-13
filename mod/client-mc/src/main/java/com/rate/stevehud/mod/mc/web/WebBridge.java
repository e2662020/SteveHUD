package com.rate.stevehud.mod.mc.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rate.stevehud.mod.SteveHudMod;
import com.rate.stevehud.mod.client.SteveHudLink;
import com.rate.stevehud.mod.client.layout.JsonMerge;
import com.rate.stevehud.mod.client.layout.LayoutSource;
import com.rate.stevehud.mod.client.layout.LayoutStore;
import com.rate.stevehud.mod.client.web.LocalGraphicsServer;
import com.rate.stevehud.mod.mc.SteveHudClient;
import com.rate.stevehud.mod.mc.config.Configs;
import com.rate.stevehud.mod.mc.hud.HudState;
import com.rate.stevehud.protocol.model.Layout;
import com.rate.stevehud.protocol.model.LayoutBody;
import com.rate.stevehud.protocol.model.Layouts;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Connects the mod to its local graphics server.
 *
 * <p>Jobs, all of them about keeping a broadcast on air:
 *
 * <ol>
 *   <li><b>Unpack the web pages to disk</b> on first run and serve them from there,
 *       rather than from inside the jar. That is what makes the overlay editable:
 *       the operator can open the files, change them, and reload the browser, with
 *       no rebuild and no game restart.</li>
 *   <li><b>Resolve which package is on air</b> — see {@link LayoutSource} — and hand
 *       the same answer to the in-game HUD, the browser overlay and the command
 *       feedback, so all three describe one situation rather than three.</li>
 *   <li><b>Publish the state and the layout</b> to every connected browser whenever
 *       either changes.</li>
 *   <li><b>Apply a local preview override</b>, so a package can be laid out against
 *       representative numbers with no match running. It never leaves this machine
 *       and it never touches what the server sent.</li>
 *   <li><b>Keep the server alive.</b> The graphics live on the client, so if this
 *       service dies the stream loses its picture. It is therefore watched and
 *       restarted rather than started once and trusted.</li>
 * </ol>
 *
 * <h2>Two scopes</h2>
 *
 * <p>{@link #layout()} is what is on air: the server's package while an operator has
 * set one, otherwise this machine's own. {@link #localLayout()} is always this
 * machine's file, and is what the editor edits. Keeping them apart is what makes the
 * local scope usable during a match rather than only when no server package exists.
 */
public final class WebBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("SteveHUD");

    /** Where the pages are unpacked, relative to the game's config directory. */
    private static final String WEB_DIR = "stevehud/web";

    /** Where the local layout document is kept, likewise. */
    private static final String LAYOUT_FILE = "stevehud/layout.json";

    /** How long to wait before retrying a failed bind, so a bad port cannot spin. */
    private static final long RETRY_BACKOFF_MS = 5_000L;

    /** How often to look for a hand-edit of the layout file, at most. */
    private static final long FILE_POLL_MS = 1_000L;

    private static final Gson GSON = new Gson();

    private static LocalGraphicsServer server;
    /** Resolves the local file and the server's package into what is on air. */
    private static LayoutSource layouts;
    private static Path webRoot;
    private static int wantedPort = -1;
    private static boolean wantedLoopback = true;
    private static long nextRetryAtMs;
    private static long nextFilePollAtMs;

    /** A locally invented state, merged over the real one. Null when not previewing. */
    private static volatile JsonObject previewOverride;

    private WebBridge() {
    }

    /** Unpacks the pages, loads the layout, and starts the server if the settings ask. */
    public static void initialize() {
        try {
            webRoot = extractPages();
        } catch (IOException e) {
            LOGGER.error("Could not unpack the web pages; the overlay will not be available", e);
            return;
        }

        LayoutStore local = new LayoutStore(
                FabricLoader.getInstance().getConfigDir().resolve(LAYOUT_FILE),
                message -> LOGGER.info("layout: {}", message));
        local.load();
        layouts = new LayoutSource(local);

        server = new LocalGraphicsServer(webRoot, message -> LOGGER.info("web: {}", message));
        // The server has no idea what a layout document is; the store is the only
        // thing that does. These hooks are the whole of the contract.
        server.setLayoutSink(new LocalGraphicsServer.LayoutSink() {

            @Override
            public String accept(String json) {
                Layout accepted = layouts.store().accept(json);
                return accepted == null ? null : Layouts.toJson(accepted);
            }

            @Override
            public String preset(String name) {
                Layout switched = layouts.store().applyPreset(name);
                return switched == null ? null : Layouts.toJson(switched);
            }

            @Override
            public String presetsJson() {
                JsonArray names = new JsonArray();
                JsonObject labels = new JsonObject();
                JsonObject themes = new JsonObject();
                for (String name : Layouts.presetNames()) {
                    names.add(name);
                    labels.addProperty(name, Layouts.presetLabel(name));
                    // Each package's palette, read WITHOUT applying it.
                    //
                    // The editor paints a row of swatches per package, and the
                    // obvious way to get those colours is GET /api/layout?preset=.
                    // That request is a write — it switches the package on air —
                    // so opening the editor would have walked the live broadcast
                    // through every preset in the list. Reading the resource
                    // directly is the fix; the swatch is not worth a scene change.
                    themes.add(name, GSON.toJsonTree(Layouts.load(name).theme));
                }
                JsonObject root = new JsonObject();
                root.add("presets", names);
                root.add("labels", labels);
                root.add("themes", themes);
                root.addProperty("current", layouts.store().preset());
                // Which scope is on air, so the editor can say plainly that a server
                // package is winning and that editing the local one will not show.
                root.addProperty("scope", layouts.scope());
                return GSON.toJson(root);
            }
        });
        server.setPreviewSink(WebBridge::acceptPreview);
        server.publishLayout(effectiveJson());

        syncWithConfig();

        // Publish once so a browser opened before the first match message still has
        // something to render.
        publish();
    }

    /**
     * Called on the client tick.
     *
     * <p>Picks up setting changes, notices a hand-edit of the layout file, and, if
     * the server has stopped for any reason, brings it back. All three are checked
     * cheaply and act only on a real difference.
     */
    public static void tick() {
        if (server == null) {
            return;
        }
        syncWithConfig();
        pollLayoutFile();
    }

    /**
     * Watches for an operator editing the layout document by hand.
     *
     * <p>Polled rather than watched through a {@code WatchService}: a directory watch
     * holds a thread and a native handle for the life of the game to catch an event
     * that happens a few times a session, and on Windows it misses an editor that
     * writes through a temporary file, which is most of them.
     */
    private static void pollLayoutFile() {
        long now = System.nanoTime() / 1_000_000L;
        if (now < nextFilePollAtMs) {
            return;
        }
        nextFilePollAtMs = now + FILE_POLL_MS;
        if (layouts != null && layouts.store().fileChangedSinceRead()) {
            LOGGER.info("The layout file changed on disk; reloading it");
            publishLayout();
        }
    }

    private static void syncWithConfig() {
        var config = Configs.get();
        boolean wanted = config.localServerEnabled;
        int port = Math.clamp(config.localServerPort, 1024, 65535);
        boolean loopback = config.bindLoopbackOnly;

        if (server.isRunning() && (port != wantedPort || loopback != wantedLoopback)) {
            LOGGER.info("Restarting the local graphics server: settings changed");
            server.stop();
        }
        if (server.isRunning() && !wanted) {
            server.stop();
            return;
        }
        if (!wanted || server.isRunning()) {
            return;
        }

        long now = System.nanoTime() / 1_000_000L;
        if (now < nextRetryAtMs) {
            return;
        }
        if (server.start(port, loopback)) {
            wantedPort = port;
            wantedLoopback = loopback;
            nextRetryAtMs = 0L;
            // A freshly started server has published nothing yet.
            server.publishLayout(effectiveJson());
        } else {
            // Port taken, most likely by another instance. Back off rather than
            // retrying every tick and flooding the log.
            nextRetryAtMs = now + RETRY_BACKOFF_MS;
        }
    }

    /** Sends the current state to every connected browser. */
    public static void publish() {
        if (server == null || !server.isRunning()) {
            return;
        }
        server.publish(buildJson());
    }

    /** Sends the document that is actually on air to every connected browser. */
    public static void publishLayout() {
        if (server == null || !server.isRunning() || layouts == null) {
            return;
        }
        server.publishLayout(effectiveJson());
    }

    /** The effective document as compact JSON, or an empty object before setup. */
    private static String effectiveJson() {
        return layouts == null ? "{}" : Layouts.toJson(layouts.effective());
    }

    public static void shutdown() {
        if (server != null) {
            server.stop();
        }
    }

    // ---- what the rest of the mod asks about ---------------------------------

    /** The package on air: the server's while one is set, otherwise this machine's. */
    public static Layout layout() {
        return layouts == null ? Layouts.fallback() : layouts.effective();
    }

    /**
     * The document on this machine's disk, whatever is on air.
     *
     * <p>This is what the editor edits. Deliberately not {@link #layout()}: the
     * operator's own packaging work has to stay editable while a server package is in
     * effect, or the local scope would be unusable for the whole of a match.
     */
    public static Layout localLayout() {
        return layouts == null ? Layouts.fallback() : layouts.local();
    }

    /** A name for the package on air, for command feedback. */
    public static String onAirName() {
        return layouts == null ? "" : layouts.onAirName();
    }

    /** Who set the server's package, or empty when nobody is being credited. */
    public static String onAirAuthor() {
        return layouts == null ? "" : layouts.serverAuthor();
    }

    /** True while a locally invented state is overriding the live one. */
    public static boolean previewing() {
        return previewOverride != null;
    }

    /** {@code "server"} or {@code "local"}: which scope every screen is drawing. */
    public static String layoutScope() {
        return layouts == null ? LayoutSource.SCOPE_LOCAL : layouts.scope();
    }

    /** True while the server's package is what every client is drawing. */
    public static boolean serverLayoutActive() {
        return layouts != null && layouts.serverActive();
    }

    /** Which built-in package the local document came from. */
    public static String preset() {
        return layouts == null ? Layouts.PRESET_ARENA : layouts.store().preset();
    }

    /** Switches the local package, or returns false when {@code name} is not one that ships. */
    public static boolean applyPreset(String name) {
        if (layouts == null || layouts.store().applyPreset(name) == null) {
            return false;
        }
        // Only republishes when the local package is the one on air. Switching the
        // local preset while a server package is in effect changes nothing on screen,
        // and republishing would replace what the browsers are drawing with a document
        // that is not on air.
        if (!layouts.serverActive()) {
            publishLayout();
        }
        return true;
    }

    /** Re-reads the local layout from disk. */
    public static void reloadLayout() {
        if (layouts == null) {
            return;
        }
        layouts.store().reload();
        if (!layouts.serverActive()) {
            publishLayout();
        }
    }

    /** Where the local layout document lives, for a command that wants to name it. */
    public static Path layoutFile() {
        return layouts == null ? null : layouts.store().file();
    }

    /**
     * Applies a package the server pushed, or clears it when the server sent none.
     *
     * <p>Republishes when the precedence changed, because the browsers are drawing
     * the old document until they are told otherwise.
     *
     * @param body the message body, or null to clear
     * @return true when what is on air changed
     */
    public static boolean applyServerLayout(LayoutBody body) {
        if (layouts == null) {
            return false;
        }
        boolean cleared = body == null || !body.present();
        boolean changed = cleared
                ? layouts.clearServer()
                : layouts.acceptServer(body.layout, body.label, body.author);
        if (changed) {
            LOGGER.info("Server package {}", cleared
                    ? "cleared; this machine is back on its own"
                    : "in effect: " + layouts.onAirName() + " by " + layouts.serverAuthor());
            publishLayout();
        }
        return changed;
    }

    /**
     * Forgets the server's package, as on disconnect.
     *
     * <p>It belonged to that server. Carrying it into the next session would silently
     * restyle a server that never asked for it.
     *
     * @return true when there was one to forget
     */
    public static boolean clearServerLayout() {
        if (layouts == null || !layouts.serverActive()) {
            return false;
        }
        layouts.clearServer();
        publishLayout();
        return true;
    }

    /** Where the web pages were unpacked, for the command that opens them. */
    public static Path webRoot() {
        return webRoot;
    }

    public static boolean isServing() {
        return server != null && server.isRunning();
    }

    public static int port() {
        return server == null ? -1 : server.port();
    }

    public static int listeners() {
        return server == null ? 0 : server.clientCount();
    }

    // ---- the preview override ------------------------------------------------

    /**
     * Applies a partial state over the live one, on this machine only.
     *
     * <p>The point is laying a package out before the event: an operator needs to see
     * a long team name, a four-digit score and a stopped clock to know whether the
     * design holds, and waiting for a real match to supply those is not a design
     * loop. {@code null} clears it and hands the state back to the server.
     *
     * @return the state to publish, or null when the body was unusable
     */
    private static String acceptPreview(String json) {
        if (json == null) {
            previewOverride = null;
            LOGGER.info("Preview override cleared; the live state is back");
            return buildJson();
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return null;
        }
        if (!parsed.isJsonObject()) {
            return null;
        }
        previewOverride = JsonMerge.merge(previewOverride, parsed.getAsJsonObject());
        LOGGER.info("Preview override applied ({} fields)", previewOverride.size());
        return buildJson();
    }

    /**
     * The state the web pages consume: the match package as the server sent it, plus
     * the things only this client knows — the revision it has reached, the health of
     * its link, which layout scope is on air, and whether what is on screen is real.
     */
    private static String buildJson() {
        JsonObject root;
        try {
            root = GSON.toJsonTree(HudState.state()).getAsJsonObject();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not serialise the broadcast state for the web", e);
            root = new JsonObject();
        }

        JsonObject override = previewOverride;
        if (override != null) {
            root = JsonMerge.merge(root, override);
        }

        SteveHudLink link = SteveHudClient.LINK;
        // A preview that did not look like one would be the worst outcome here: an
        // operator could mistake invented numbers for a live feed.
        root.addProperty("preview", override != null);
        root.addProperty("rev", Math.max(link.revision(), 0));
        root.addProperty("layoutName", layout().name);
        root.addProperty("layoutPreset", preset());
        root.addProperty("layoutScope", layoutScope());
        root.addProperty("layoutAuthor", onAirAuthor());

        JsonObject linkInfo = new JsonObject();
        linkInfo.addProperty("connected", link.ready());
        linkInfo.addProperty("millisSinceLastMessage", link.millisSinceLastMessage());
        root.add("link", linkInfo);

        return GSON.toJson(root);
    }

    // ---- unpacking ----------------------------------------------------------

    /**
     * Copies the bundled pages into the config directory.
     *
     * <p>Existing files are left alone so that edits survive, but a page that has
     * never been written is copied in. A user who deletes one gets it back; a user
     * who customises one keeps their version.
     */
    private static Path extractPages() throws IOException {
        Path target = FabricLoader.getInstance().getConfigDir().resolve(WEB_DIR);
        Files.createDirectories(target);

        var container = FabricLoader.getInstance().getModContainer(SteveHudMod.MOD_ID);
        if (container.isEmpty()) {
            throw new IOException("own mod container not found; cannot locate the web pages");
        }
        Path source = container.get().findPath("stevehud/web")
                .orElseThrow(() -> new IOException("stevehud/web is not present in the jar"));

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path destination = target.resolve(source.relativize(file).toString());
                if (!Files.exists(destination)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination);
                    LOGGER.info("Unpacked {}", destination);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return target;
    }
}
