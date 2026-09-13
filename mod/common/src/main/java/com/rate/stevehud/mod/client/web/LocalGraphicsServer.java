package com.rate.stevehud.mod.client.web;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The local HTTP server the browser overlay and the layout editor connect to.
 *
 * <p>Written on a plain {@link ServerSocket} rather than on
 * {@code com.sun.net.httpserver}. That is a deliberate trade: the JDK's server is a
 * few hundred lines shorter, but it lives in a module the game's launcher may not
 * resolve, and it cannot be exercised without a running game. This one has no
 * module dependency at all, and it runs under a plain unit test — which for a
 * component that has to keep working through a live broadcast is worth the length.
 *
 * <p>Serves five things:
 * <ul>
 *   <li>static files out of a directory (the overlay and editor pages)</li>
 *   <li>{@code GET /api/state} — the current match state, once</li>
 *   <li>{@code GET /api/layout} — the current layout document, once</li>
 *   <li>{@code POST /api/layout} — a layout document to store and broadcast</li>
 *   <li>{@code GET /events} — a Server-Sent Events stream of updates</li>
 * </ul>
 *
 * <p>The stream carries two kinds of event. State updates arrive as the default
 * event, so a page that only listens with {@code onmessage} keeps working; layout
 * updates arrive as a named {@code layout} event, so a page that draws the package
 * can tell a restyle from a score change without diffing the payload. Both were
 * needed: the overlay redraws on either, but the editor must not treat an incoming
 * layout as "someone else is editing" and yank the document out from under a drag.
 *
 * <p>The server owns no knowledge of what a layout <em>is</em>. Storing and
 * validating one is delegated to a {@link LayoutSink}, which is the mod's job, so
 * this class stays testable with a two-line stub instead of a whole document model.
 *
 * <p>Server-Sent Events rather than WebSocket, again deliberately. The traffic is
 * one-way — state flows to the browser, and the editor's writes go over ordinary
 * POSTs — so SSE covers it with no handshake to implement, no framing, and no
 * dependency. It is also plain enough that a failure is diagnosable with curl.
 *
 * <p>No Minecraft types, so this is testable on its own.
 */
public final class LocalGraphicsServer implements AutoCloseable {

    /**
     * Accepts a layout document from a browser.
     *
     * <p>Implemented by the mod, which is the only thing that knows what a layout
     * document is, where it is stored, and what makes one valid.
     */
    public interface LayoutSink {

        /**
         * @param json the document as posted
         * @return the normalised document to keep and broadcast, or null when the
         *         body could not be read as a layout — which the server reports as
         *         a 400 rather than storing something it cannot vouch for
         */
        String accept(String json);

        /**
         * Loads one of the built-in packages.
         *
         * @return the document, or null when there is no package by that name
         */
        default String preset(String name) {
            return null;
        }

        /**
         * The available packages, as JSON.
         *
         * <p>Returned as an opaque body rather than as a list of names: the server
         * has no business knowing what a preset is, and the editor wants the display
         * labels too.
         */
        default String presetsJson() {
            return null;
        }
    }

    /**
     * Applies a local preview override to the match state.
     *
     * <p>This is how the editor's quick controls do something visible without a live
     * match: the override is merged over whatever the server last sent, on this
     * client only, and never leaves the machine.
     */
    public interface PreviewSink {

        /**
         * @param json a partial state to merge, or null to clear the override
         * @return the state to publish, or null when the body was unusable
         */
        String accept(String json);
    }

    /** Files are served from here; anything outside is refused. */
    private final Path root;

    private final Consumer<String> log;
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<SseClient> clients = new CopyOnWriteArrayList<>();
    private volatile String latestState = "{}";
    private volatile String latestLayout = "{}";
    private volatile LayoutSink layoutSink;
    private volatile PreviewSink previewSink;
    private volatile ServerSocket socket;
    private volatile Thread acceptThread;

    /**
     * @param root where the web pages live
     * @param log  where to report; the server never throws into a caller
     */
    public LocalGraphicsServer(Path root, Consumer<String> log) {
        this.root = root.toAbsolutePath().normalize();
        this.log = log;
    }

    public boolean isRunning() {
        return running.get();
    }

    public int port() {
        ServerSocket current = socket;
        return current == null ? -1 : current.getLocalPort();
    }

    /**
     * Binds and starts accepting.
     *
     * @param loopbackOnly restrict to 127.0.0.1. Off by default would expose the
     *                     match to the local network, so the caller decides.
     * @return true when listening
     */
    public synchronized boolean start(int port, boolean loopbackOnly) {
        if (running.get()) {
            return true;
        }
        try {
            InetAddress bind = loopbackOnly
                    ? InetAddress.getLoopbackAddress()
                    : InetAddress.getByName("0.0.0.0");
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(bind, port), 32);
            socket = server;
        } catch (IOException e) {
            log.accept("Could not bind port " + port + ": " + e.getMessage());
            return false;
        }

        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "SteveHUD-web");
        // Daemon, so a stuck server can never keep the game process alive.
        acceptThread.setDaemon(true);
        acceptThread.start();
        log.accept("Serving " + root + " on http://127.0.0.1:" + port + "/overlay/");
        return true;
    }

    public synchronized void stop() {
        running.set(false);
        for (SseClient client : clients) {
            client.close();
        }
        clients.clear();
        ServerSocket current = socket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Closing is best effort; the accept loop is guarded anyway.
            }
        }
        socket = null;
        acceptThread = null;
    }

    /** {@link #stop}, so a server can be held in a try-with-resources block. */
    @Override
    public void close() {
        stop();
    }

    /**
     * Publishes the match state to every connected browser.
     *
     * <p>Keeps the latest copy even with nobody listening, so the editor can fetch
     * it on load instead of showing an empty page until the next update.
     */
    public void publish(String json) {
        latestState = json == null ? "{}" : json;
        broadcast(null, latestState);
    }

    /**
     * Publishes the layout document.
     *
     * <p>Sent as a named event so a page can tell a restyle from a score change.
     * The layout changes far less often than the state and costs far more to
     * redraw, so conflating the two would make the overlay rebuild its whole
     * element tree on a routine score tick.
     */
    public void publishLayout(String json) {
        latestLayout = json == null ? "{}" : json;
        broadcast("layout", latestLayout);
    }

    /** The last layout published or accepted, as JSON. */
    public String layout() {
        return latestLayout;
    }

    /** Called by the mod so this server can store a document it cannot itself read. */
    public void setLayoutSink(LayoutSink sink) {
        this.layoutSink = sink;
    }

    /** Called by the mod so the editor's quick controls have somewhere to go. */
    public void setPreviewSink(PreviewSink sink) {
        this.previewSink = sink;
    }

    private void broadcast(String event, String json) {
        for (SseClient client : clients) {
            if (!client.send(event, json)) {
                clients.remove(client);
            }
        }
    }

    /** How many browsers are currently subscribed. */
    public int clientCount() {
        return clients.size();
    }

    // ---- request handling ---------------------------------------------------

    private void acceptLoop() {
        while (running.get()) {
            ServerSocket current = socket;
            if (current == null) {
                return;
            }
            try {
                Socket connection = current.accept();
                Thread worker = new Thread(() -> handle(connection), "SteveHUD-web-req");
                worker.setDaemon(true);
                worker.start();
            } catch (SocketException e) {
                // Expected when the socket is closed by stop().
                return;
            } catch (IOException e) {
                if (running.get()) {
                    log.accept("Accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handle(Socket connection) {
        try (connection) {
            connection.setSoTimeout(15_000);
            InputStream in = connection.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            // Read and discard headers, capturing the length of a body if present.
            int contentLength = 0;
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0 && header.substring(0, colon).trim()
                        .equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(header.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                        contentLength = 0;
                    }
                }
            }

            byte[] body = contentLength > 0 ? in.readNBytes(Math.min(contentLength, 1 << 20)) : new byte[0];
            route(connection, method, target, body);
        } catch (IOException e) {
            // A browser closing early is routine; only report while we are running.
            if (running.get()) {
                log.accept("Request failed: " + e.getMessage());
            }
        }
    }

    private void route(Socket connection, String method, String target, byte[] body) throws IOException {
        String path = target;
        String query = "";
        int marker = path.indexOf('?');
        if (marker >= 0) {
            query = path.substring(marker + 1);
            path = path.substring(0, marker);
        }
        path = java.net.URLDecoder.decode(path, StandardCharsets.UTF_8);

        if (path.equals("/events")) {
            serveEvents(connection);
            return;
        }
        if (path.equals("/api/state")) {
            send(connection, 200, "application/json; charset=utf-8",
                    latestState.getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        if (path.equals("/api/layout")) {
            handleLayout(connection, method, query, body);
            return;
        }
        if (path.equals("/api/presets")) {
            handlePresets(connection);
            return;
        }
        if (path.equals("/api/preview")) {
            handlePreview(connection, method, body);
            return;
        }
        if (path.equals("/") || path.isEmpty()) {
            send(connection, 302, "text/plain", new byte[0], Map.of("Location", "/overlay/"));
            return;
        }
        serveFile(connection, path);
    }

    /** The built-in packages, for the editor's preset list. */
    private void handlePresets(Socket connection) throws IOException {
        LayoutSink sink = layoutSink;
        String body = sink == null ? null : sink.presetsJson();
        if (body == null) {
            send(connection, 503, "text/plain; charset=utf-8",
                    "no layout store is attached yet\n".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        send(connection, 200, "application/json; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8), null);
    }

    /**
     * Reads and writes the layout document.
     *
     * <p>Storing is delegated: this server has no idea what a layout is. What it
     * does own is the ordering, which matters — the new document is published to
     * every listener <em>before</em> the response goes back, so the page that saved
     * cannot be the only one that sees the change. An earlier version of the editor
     * wrote a document to a file that nothing read, and the write silently did
     * nothing to the picture; that is the failure this ordering exists to prevent
     * from being invisible again.
     */
    private void handleLayout(Socket connection, String method, String query, byte[] body)
            throws IOException {
        LayoutSink sink = layoutSink;

        if (method.equals("GET")) {
            String preset = parameter(query, "preset");
            if (preset == null) {
                send(connection, 200, "application/json; charset=utf-8",
                        latestLayout.getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            // Switching package is a write, but it is a GET with a parameter so the
            // editor can put it behind a plain link and an operator can do it from
            // the address bar. It is loopback-only and it changes nothing outside
            // this machine's broadcast.
            if (sink == null) {
                send(connection, 503, "text/plain; charset=utf-8",
                        "the layout store is not attached yet\n".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            String switched = sink.preset(preset);
            if (switched == null) {
                send(connection, 404, "text/plain; charset=utf-8",
                        ("no such package: " + preset + "\n").getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            publishLayout(switched);
            send(connection, 200, "application/json; charset=utf-8",
                    switched.getBytes(StandardCharsets.UTF_8), null);
            return;
        }

        if (!method.equals("POST")) {
            send(connection, 405, "text/plain; charset=utf-8",
                    ("GET or POST a layout document here, not " + method + "\n")
                            .getBytes(StandardCharsets.UTF_8),
                    Map.of("Allow", "GET, POST"));
            return;
        }

        if (sink == null) {
            send(connection, 503, "text/plain; charset=utf-8",
                    "the layout store is not attached yet\n".getBytes(StandardCharsets.UTF_8), null);
            return;
        }

        String stored;
        try {
            stored = sink.accept(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            log.accept("Layout rejected: " + e);
            stored = null;
        }
        if (stored == null) {
            send(connection, 400, "text/plain; charset=utf-8",
                    "that body could not be read as a layout document\n"
                            .getBytes(StandardCharsets.UTF_8), null);
            return;
        }

        publishLayout(stored);
        log.accept("Layout stored and published (" + stored.length() + " chars)");
        send(connection, 200, "application/json; charset=utf-8",
                stored.getBytes(StandardCharsets.UTF_8), null);
    }

    /** The value of one query parameter, URL-decoded, or null when absent. */
    private static String parameter(String query, String name) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (pair.substring(0, equals).equals(name)) {
                String value = pair.substring(equals + 1);
                return value.isEmpty() ? null
                        : java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Applies or clears the local preview override on the match state.
     *
     * <p>DELETE clears it, which is how the editor hands the state back to the live
     * match data after laying a package out against made-up numbers.
     */
    private void handlePreview(Socket connection, String method, byte[] body) throws IOException {
        PreviewSink sink = previewSink;
        if (sink == null) {
            send(connection, 503, "text/plain; charset=utf-8",
                    "preview is not available\n".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        if (!method.equals("POST") && !method.equals("DELETE")) {
            send(connection, 405, "text/plain; charset=utf-8",
                    ("POST a partial state, or DELETE to clear it, not " + method + "\n")
                            .getBytes(StandardCharsets.UTF_8),
                    Map.of("Allow", "POST, DELETE"));
            return;
        }

        String result;
        try {
            result = sink.accept(method.equals("DELETE")
                    ? null : new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            log.accept("Preview rejected: " + e);
            result = null;
        }
        if (result == null) {
            send(connection, 400, "text/plain; charset=utf-8",
                    "that body could not be applied as state\n".getBytes(StandardCharsets.UTF_8), null);
            return;
        }

        publish(result);
        send(connection, 200, "application/json; charset=utf-8",
                result.getBytes(StandardCharsets.UTF_8), null);
    }

    private void serveFile(Socket connection, String path) throws IOException {
        Path target = root.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();
        // Path traversal guard: a request for ../../something must not escape the root.
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            send(connection, 404, "text/plain; charset=utf-8",
                    ("not found: " + path + "\n").getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        send(connection, 200, mimeType(target), Files.readAllBytes(target), null);
    }

    /**
     * Holds the connection open and streams state updates.
     *
     * <p>The socket deliberately outlives this method, so it is not closed by the
     * try-with-resources in {@link #handle}.
     */
    private void serveEvents(Socket connection) throws IOException {
        OutputStream raw = new BufferedOutputStream(connection.getOutputStream());

        // Registered before the response head is written. The other order leaves a
        // window in which an update published after the browser sees the headers but
        // before this client is on the list is lost, and the page then shows stale
        // data until something else changes.
        SseClient client = new SseClient(connection, raw);
        clients.add(client);

        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: text/event-stream; charset=utf-8\r\n")
                .append("Cache-Control: no-cache, no-store\r\n")
                .append("Connection: keep-alive\r\n")
                // A browser on another origin may read this stream; the server is
                // loopback-only by default, but the header keeps a local tool working.
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("\r\n");
        try {
            raw.write(headers.toString().getBytes(StandardCharsets.UTF_8));
            raw.flush();
        } catch (IOException e) {
            clients.remove(client);
            client.close();
            return;
        }

        log.accept("Overlay connected (" + clients.size() + " listening)");

        // Send the current state and layout immediately, so a page that loads
        // mid-match has something to draw before anything happens to change.
        client.send(null, latestState);
        client.send("layout", latestLayout);

        // Keep the thread parked while the client stays connected; the write path is
        // driven by publish() from whichever thread has news.
        while (running.get() && client.isOpen()) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        client.close();
        clients.remove(client);
        log.accept("Overlay disconnected (" + clients.size() + " listening)");
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\n') {
                break;
            }
            if (read != '\r') {
                buffer.write(read);
            }
            if (buffer.size() > 8192) {
                break;
            }
        }
        if (read == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void send(Socket connection, int status, String contentType,
                             byte[] body, Map<String, String> extraHeaders) throws IOException {
        OutputStream out = new BufferedOutputStream(connection.getOutputStream());
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                // The editor posts here, and a page served from this same origin is
                // not a cross-origin request, but a local tool may be.
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Headers: Content-Type\r\n")
                .append("Cache-Control: no-store\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }

    private static String mimeType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (name.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".woff2")) {
            return "font/woff2";
        }
        return "application/octet-stream";
    }

    /** One subscribed browser. Writes are serialised on the client itself. */
    private static final class SseClient {

        private final Socket connection;
        private final OutputStream out;
        private volatile boolean open = true;

        SseClient(Socket connection, OutputStream out) {
            this.connection = connection;
            this.out = out;
        }

        boolean isOpen() {
            return open && !connection.isClosed();
        }

        /**
         * @param event the SSE event name, or null for the default event. The
         *              default event is what {@code EventSource.onmessage} receives,
         *              which keeps a page that only knows about state working.
         * @return false when the client has gone and should be dropped
         */
        synchronized boolean send(String event, String json) {
            if (!isOpen()) {
                return false;
            }
            try {
                StringBuilder frame = new StringBuilder();
                if (event != null) {
                    frame.append("event: ").append(event).append('\n');
                }
                // A document with an embedded newline would end the SSE frame early
                // and truncate the JSON, so the payload is written as one data line
                // per line of input, which is what the format is for.
                for (String line : json.split("\n", -1)) {
                    frame.append("data: ").append(line).append('\n');
                }
                frame.append('\n');
                out.write(frame.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                open = false;
                return false;
            }
        }

        synchronized void close() {
            open = false;
            try {
                connection.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }
}
