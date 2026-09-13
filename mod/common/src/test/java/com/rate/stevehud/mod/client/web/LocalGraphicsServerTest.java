package com.rate.stevehud.mod.client.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the local graphics server without a game.
 *
 * <p>This is the payoff of writing the server on a plain socket instead of using the
 * JDK's: the component that has to keep a live broadcast on air can be tested on its
 * own, in a second, with no client and no launcher involved.
 */
class LocalGraphicsServerTest {

    @TempDir
    Path webRoot;

    private final List<String> log = new ArrayList<>();
    private LocalGraphicsServer server;
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(webRoot.resolve("overlay"));
        Files.writeString(webRoot.resolve("overlay/index.html"),
                "<!doctype html><title>overlay</title>", StandardCharsets.UTF_8);

        server = new LocalGraphicsServer(webRoot, log::add);
        // Port 0 lets the OS pick a free one, so the test cannot collide with a
        // server the developer already has running.
        assertTrue(server.start(0, true), "server should bind an ephemeral port");
        port = server.port();
        assertTrue(port > 0, "a bound server should report a port");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private String get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheOverlayPage() throws Exception {
        String body = get("/overlay/index.html");

        assertTrue(body.contains("overlay"), "should serve the file that was written, was: " + body);
    }

    @Test
    void redirectsTheRootToTheOverlay() throws Exception {
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                        .timeout(Duration.ofSeconds(5)).GET().build());

        assertEquals(302, response.statusCode());
        assertEquals("/overlay/", response.headers().firstValue("Location").orElse(""));
    }

    @Test
    void reportsAMissingFileAsNotFound() throws Exception {
        assertEquals(404, send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/nope.html"))
                        .timeout(Duration.ofSeconds(5)).GET().build()).statusCode());
    }

    @Test
    void refusesToServeFilesOutsideTheWebRoot() throws Exception {
        // The classic directory-traversal request. It must not escape the root.
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + port + "/../../../../Windows/win.ini"))
                        .timeout(Duration.ofSeconds(5)).GET().build());

        assertTrue(response.statusCode() == 403 || response.statusCode() == 404,
                "expected a refusal, got " + response.statusCode());
    }

    @Test
    void servesTheCurrentStateOnDemand() throws Exception {
        server.publish("{\"rev\":7,\"scene\":\"full\"}");

        String body = get("/api/state");

        assertTrue(body.contains("\"rev\":7"), "should hand back the published state, was: " + body);
    }

    @Test
    void stateIsAvailableBeforeAnythingIsPublished() throws Exception {
        // A browser opened before the first match message must not get an empty body.
        assertEquals("{}", get("/api/state"));
    }

    @Test
    void storesAndBroadcastsAPostedLayoutDocument() throws Exception {
        String layout = "{\"elements\":[{\"type\":\"matchBug\"}]}";
        // The sink stands in for the mod's layout store, which is the only thing
        // that knows what a layout document is. The server's job is the ordering:
        // store, then publish, then answer — not store and hope.
        server.setLayoutSink(json -> json);

        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/layout"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(layout))
                .build());

        assertEquals(200, response.statusCode());
        assertEquals(layout, response.body(), "the stored document should come back");
        assertEquals(layout, server.layout(), "and it should be the current one");
    }

    @Test
    void servesTheCurrentLayoutOnDemand() throws Exception {
        server.setLayoutSink(json -> json);
        send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/layout"))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"edited\"}"))
                .build());

        assertTrue(get("/api/layout").contains("\"name\":\"edited\""));
    }

    @Test
    void layoutIsAvailableBeforeAnythingIsPublished() throws Exception {
        // A browser opened before the mod has published anything must get an empty
        // object rather than an empty body, which JSON.parse would reject.
        assertEquals("{}", get("/api/layout"));
    }

    @Test
    void rejectsAnUnsupportedMethodOnTheLayoutEndpoint() throws Exception {
        assertEquals(405, send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/layout"))
                        .timeout(Duration.ofSeconds(5))
                        .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                        .build()).statusCode());
    }

    @Test
    void switchesPackageWhenTheQueryAsksForOne() throws Exception {
        server.setLayoutSink(new LocalGraphicsServer.LayoutSink() {
            @Override
            public String accept(String json) {
                return json;
            }

            @Override
            public String preset(String name) {
                return "olympic".equals(name) ? "{\"name\":\"olympic\"}" : null;
            }

            @Override
            public String presetsJson() {
                return "{\"presets\":[\"esports\",\"olympic\"]}";
            }
        });

        HttpResponse<String> switched = send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/layout?preset=olympic"))
                        .timeout(Duration.ofSeconds(5)).GET().build());
        assertEquals(200, switched.statusCode());
        assertTrue(switched.body().contains("olympic"));
        // The switch has to reach the published document, not just be reported: the
        // browser beside the editor and the next page load both read it.
        assertTrue(server.layout().contains("olympic"));

        assertEquals(404, send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/layout?preset=nope"))
                        .timeout(Duration.ofSeconds(5)).GET().build()).statusCode());
        assertEquals("{\"presets\":[\"esports\",\"olympic\"]}", get("/api/presets"));
    }

    @Test
    void reportsMissingPackagesAsUnavailableBeforeTheStoreIsAttached() throws Exception {
        assertEquals(503, send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/layout?preset=olympic"))
                        .timeout(Duration.ofSeconds(5)).GET().build()).statusCode());
        assertEquals(503, send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/presets"))
                        .timeout(Duration.ofSeconds(5)).GET().build()).statusCode());
    }

    @Test
    void reportsAMissingLayoutStoreAsUnavailable() throws Exception {
        // Before the mod attaches its store there is nowhere to put a document, and
        // saying so is better than accepting one that will be silently dropped.
        assertEquals(503, send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/layout"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build()).statusCode());
    }

    @Test
    void reportsARejectedLayoutAsBadRequestAndKeepsTheOldOne() throws Exception {
        server.setLayoutSink(json -> json);
        server.publishLayout("{\"name\":\"good\"}");

        // A store that cannot read the body hands back null, and the server must
        // report that rather than broadcasting a document it cannot vouch for.
        server.setLayoutSink(json -> null);
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/layout"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString("{ not json"))
                        .build());

        assertEquals(400, response.statusCode());
        assertEquals("{\"name\":\"good\"}", server.layout(),
                "a rejected document must not replace the one on air");
    }

    @Test
    void reportsASinkThatThrowsAsBadRequestRatherThanDroppingTheConnection() throws Exception {
        // A malformed document must not be able to take the graphics server down
        // with it, since that is the process keeping the broadcast on air.
        server.setLayoutSink(json -> {
            throw new IllegalStateException("cannot parse");
        });

        assertEquals(400, send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/layout"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build()).statusCode());
        assertTrue(server.isRunning(), "the server must survive a bad document");
    }

    @Test
    void streamsLayoutUpdatesAsANamedEvent() throws Exception {
        server.setLayoutSink(json -> json);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(
                    ("GET /events HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // skip the response head
            }
            // Consume the two priming frames, so what is read below is the update
            // this test caused and not the snapshot sent on connect.
            readFrame(reader, null);
            readFrame(reader, "layout");

            send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/layout"))
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"restyled\"}"))
                    .build());

            // The layout travels as a named event so a page can tell a restyle from
            // a score change without inspecting the payload.
            Frame frame = readFrame(reader, "layout");
            assertEquals("layout", frame.event());
            assertTrue(frame.data().contains("restyled"), "was: " + frame.data());
        }
    }

    @Test
    void previewOverridesTheStateAndCanBeCleared() throws Exception {
        server.setPreviewSink(json -> json == null ? "{\"preview\":false}" : json);

        HttpResponse<String> applied = send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/preview"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"preview\":true}"))
                        .build());
        assertEquals(200, applied.statusCode());
        assertEquals("{\"preview\":true}", applied.body());
        // The published state is what a browser reads next, so the override has to
        // reach it, not just be acknowledged.
        assertTrue(get("/api/state").contains("\"preview\":true"));

        HttpResponse<String> cleared = send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/preview"))
                        .timeout(Duration.ofSeconds(5))
                        .DELETE().build());
        assertEquals(200, cleared.statusCode());
        assertTrue(get("/api/state").contains("\"preview\":false"));
    }

    @Test
    void previewIsUnavailableWithoutASink() throws Exception {
        assertEquals(503, send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/preview"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build()).statusCode());
    }

    @Test
    void streamsStateUpdatesToASubscriber() throws Exception {
        // Read the SSE stream by hand. This is the path the OBS overlay depends on, so
        // it is worth checking the ordering rather than only the arrival.
        server.publish("{\"rev\":0}");

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(
                    ("GET /events HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            // Skip the response head.
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // headers end at the blank line
            }

            // A subscriber is primed with both documents as they stood when it
            // attached: a page opened mid-match must not sit blank until something
            // happens to change. Reading them before publishing anything also makes
            // the rest of this test deterministic.
            Frame primedState = readFrame(reader, null);
            assertEquals(null, primedState.event(),
                    "state travels as the default event, which onmessage can read");
            assertEquals("{\"rev\":0}", primedState.data());

            Frame primedLayout = readFrame(reader, "layout");
            assertEquals("layout", primedLayout.event());

            server.publish("{\"rev\":1}");
            server.publish("{\"rev\":2}");

            assertEquals("{\"rev\":1}", readFrame(reader, null).data());
            assertEquals("{\"rev\":2}", readFrame(reader, null).data(), "updates arrive in order");
        }
    }

    /** One SSE frame: its event name (null for the default event) and its data. */
    private record Frame(String event, String data) {
    }

    /**
     * Reads frames until one with the expected event name arrives.
     *
     * @param event the event name to wait for, or null for the default event
     */
    private static Frame readFrame(BufferedReader reader, String event) throws IOException {
        String current = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event: ")) {
                current = line.substring("event: ".length());
            } else if (line.startsWith("data: ")) {
                if (java.util.Objects.equals(current, event)) {
                    return new Frame(current, line.substring("data: ".length()));
                }
                current = null;
            }
        }
        return new Frame(null, null);
    }

    @Test
    void countsSubscribersSoTheCommandCanReportThem() throws Exception {
        assertEquals(0, server.clientCount());

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(
                    ("GET /events HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            // Give the accept loop a moment to register the subscriber.
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (server.clientCount() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }

            assertEquals(1, server.clientCount(), "the subscriber should be counted");
        }
    }

    @Test
    void stoppingReleasesThePort() {
        int bound = server.port();
        server.stop();

        assertFalse(server.isRunning());
        assertTrue(bound > 0);
        // Rebinding the same port proves the socket was actually released.
        LocalGraphicsServer second = new LocalGraphicsServer(webRoot, log::add);
        try {
            assertTrue(second.start(bound, true), "the port should be free after stop()");
        } finally {
            second.stop();
        }
    }

    @Test
    void bindingAnOccupiedPortFailsWithoutThrowing() throws Exception {
        // The failure a second game instance hits. It must be reported, not thrown at
        // whoever asked, and it must leave the server in a usable not-started state.
        try (LocalGraphicsServer competing = new LocalGraphicsServer(webRoot, log::add)) {
            int taken = server.port();
            assertFalse(competing.start(taken, true),
                    "a second server must not claim a port that is in use");
            assertFalse(competing.isRunning());
        }
    }
}
