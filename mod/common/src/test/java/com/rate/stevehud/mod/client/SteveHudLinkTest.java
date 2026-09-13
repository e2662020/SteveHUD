package com.rate.stevehud.mod.client;

import com.rate.stevehud.protocol.Envelope;
import com.rate.stevehud.protocol.EnvelopeCodec;
import com.rate.stevehud.protocol.Framing;
import com.rate.stevehud.protocol.MessageType;
import com.rate.stevehud.protocol.Protocol;
import com.rate.stevehud.protocol.model.HelloBody;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteveHudLinkTest {

    private final EnvelopeCodec codec = new EnvelopeCodec();
    private final List<String> warnings = new ArrayList<>();

    private SteveHudLink newLink() {
        return new SteveHudLink("SteveHUD-Mod/test", List.of("snapshot"), warnings::add);
    }

    private String wire(MessageType type, long revision, String body) {
        return codec.encode(Envelope.of(type, revision, body));
    }

    @Test
    void becomesReadyOnAGreeting() {
        SteveHudLink link = newLink();

        assertFalse(link.ready());
        assertTrue(link.accept(wire(MessageType.HELLO, 0L,
                codec.encodeBody(new HelloBody(
                        Protocol.VERSION, "SteveHUD-Plugin/0.1.0", List.of("snapshot"), false)))));

        assertTrue(link.ready());
        assertEquals("SteveHud-Plugin/0.1.0".toLowerCase(),
                link.serverHello().orElseThrow().implementation().toLowerCase());
    }

    @Test
    void surfacesTheOperatorFlagFromTheGreeting() {
        // The authoring rights decision is the server's, so the only thing the
        // client may do is read and report it faithfully.
        SteveHudLink link = newLink();
        assertFalse(link.serverHello().map(HelloBody::operator).orElse(false),
                "no greeting yet means no rights");

        link.accept(wire(MessageType.HELLO, 0L,
                codec.encodeBody(new HelloBody(Protocol.VERSION, "srv", List.of(), true))));

        assertTrue(link.serverHello().orElseThrow().operator());
    }

    @Test
    void handshakeReportsOurOwnVersionAndCapabilities() {
        SteveHudLink link = newLink();

        HelloBody sent = codec.decodeBody(
                Envelope.of(MessageType.HELLO, 0L, link.encodeHello()), HelloBody.class);

        assertEquals(Protocol.VERSION, sent.protocolVersion());
        assertEquals("SteveHUD-Mod/test", sent.implementation());
        assertEquals(List.of("snapshot"), sent.capabilities());
    }

    @Test
    void revisionNeverGoesBackwards() {
        SteveHudLink link = newLink();

        link.accept(wire(MessageType.SNAPSHOT, 5L, "{}"));
        link.accept(wire(MessageType.DELTA, 3L, "{}"));

        assertEquals(5L, link.revision(), "a late-arriving older revision must not rewind the counter");
    }

    @Test
    void malformedPayloadIsDroppedAndReported() {
        SteveHudLink link = newLink();

        assertFalse(link.accept("}{ not json"));
        assertEquals(1, warnings.size());
        assertEquals(-1L, link.revision());
    }

    @Test
    void incompatibleProtocolIsRejectedOnceNotOncePerMessage() {
        SteveHudLink link = newLink();
        Envelope future = new Envelope(Protocol.VERSION + 1, 1L, MessageType.SNAPSHOT, "", 0, 1, "{}");
        String text = codec.encode(future);

        assertFalse(link.accept(text));
        assertFalse(link.accept(text));
        assertFalse(link.accept(text));

        assertEquals(1, warnings.size(), "the mismatch should be reported once, not per message");
        assertFalse(link.ready());
    }

    @Test
    void fragmentedMessageIsDeliveredWhole() {
        SteveHudLink link = newLink();
        List<Envelope> received = new ArrayList<>();
        link.addListener(received::add);

        String body = "{\"roster\":\"" + "x".repeat(9_000) + "\"}";
        List<Envelope> fragments = Framing.split(MessageType.SNAPSHOT, 4L, "msg-1", body);
        assertTrue(fragments.size() > 1, "this body should have needed fragmenting");

        for (Envelope fragment : fragments) {
            link.accept(codec.encode(fragment));
        }

        assertEquals(1, received.size(), "only the reassembled message should reach the listener");
        assertEquals(body, received.get(0).body());
        assertEquals(MessageType.SNAPSHOT, received.get(0).type());
        assertFalse(received.get(0).fragmented());
    }

    @Test
    void aFailingListenerDoesNotStarveTheOthers() {
        SteveHudLink link = newLink();
        List<Envelope> received = new ArrayList<>();
        link.addListener(envelope -> {
            throw new IllegalStateException("listener is broken");
        });
        link.addListener(received::add);

        assertTrue(link.accept(wire(MessageType.DELTA, 1L, "{}")));

        assertEquals(1, received.size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("listener failed")));
    }

    @Test
    void resetClearsPartialStateAndHandshake() {
        SteveHudLink link = newLink();
        link.accept(wire(MessageType.HELLO, 0L,
                codec.encodeBody(new HelloBody(Protocol.VERSION, "x", List.of(), false))));
        link.accept(codec.encode(
                Framing.split(MessageType.SNAPSHOT, 1L, "msg-2", "y".repeat(9_000)).get(0)));

        link.reset();

        assertFalse(link.ready());
        assertEquals(-1L, link.revision());
        // The abandoned fragments must be gone, so the remaining ones can never
        // complete into a half-message.
        for (Envelope fragment : Framing.split(MessageType.SNAPSHOT, 1L, "msg-2", "y".repeat(9_000)).subList(1, 2)) {
            link.accept(codec.encode(fragment));
        }
        assertEquals(-1L, link.revision());
    }
}
