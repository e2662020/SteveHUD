package com.rate.stevehud.protocol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FramingTest {

    private static final String BODY_ID = "msg-1";

    @Test
    void bodyWithinBudgetTravelsAsOneEnvelope() {
        String body = "{\"name\":\"short\"}";

        List<Envelope> fragments = Framing.split(MessageType.SNAPSHOT, 5L, BODY_ID, body, 100);

        assertEquals(1, fragments.size());
        assertFalse(fragments.get(0).fragmented());
        assertEquals(body, fragments.get(0).body());
    }

    @Test
    void bodyExactlyAtBudgetDoesNotFragment() {
        String body = "x".repeat(100);

        List<Envelope> fragments = Framing.split(MessageType.SNAPSHOT, 5L, BODY_ID, body, 100);

        assertEquals(1, fragments.size());
    }

    @Test
    void largeBodyFragmentsAndReassembles() {
        String body = bigBody(20_000);
        List<Envelope> fragments = Framing.split(MessageType.SNAPSHOT, 7L, BODY_ID, body, 1_000);

        assertEquals(20, fragments.size());
        for (Envelope fragment : fragments) {
            assertTrue(fragment.fragmented());
            assertEquals(20, fragment.total());
            assertTrue(fragment.body().length() <= 1_000);
        }

        Optional<Envelope> reassembled = feedAll(new Framing.Reassembler(), fragments);

        assertTrue(reassembled.isPresent());
        assertEquals(body, reassembled.get().body());
        assertEquals(MessageType.SNAPSHOT, reassembled.get().type());
        assertEquals(7L, reassembled.get().rev());
        assertFalse(reassembled.get().fragmented());
    }

    @Test
    void fragmentsMayArriveOutOfOrder() {
        String body = bigBody(5_000);
        List<Envelope> fragments = new ArrayList<>(Framing.split(MessageType.LAYOUT, 2L, BODY_ID, body, 512));
        Collections.shuffle(fragments, new Random(1234));

        Optional<Envelope> reassembled = feedAll(new Framing.Reassembler(), fragments);

        assertTrue(reassembled.isPresent(), "shuffled fragments must still reassemble");
        assertEquals(body, reassembled.get().body());
    }

    @Test
    void duplicateFragmentsAreHarmless() {
        String body = bigBody(3_000);
        List<Envelope> fragments = Framing.split(MessageType.LAYOUT, 2L, BODY_ID, body, 512);
        fragments.add(fragments.get(0));
        fragments.add(fragments.get(0));

        Optional<Envelope> reassembled = feedAll(new Framing.Reassembler(), fragments);

        assertTrue(reassembled.isPresent());
        assertEquals(body, reassembled.get().body());
    }

    @Test
    void incompleteMessageYieldsNothing() {
        List<Envelope> fragments = Framing.split(MessageType.SNAPSHOT, 1L, BODY_ID, bigBody(3_000), 512);
        Framing.Reassembler reassembler = new Framing.Reassembler();

        List<Envelope> received = new ArrayList<>();
        for (int i = 0; i < fragments.size() - 1; i++) {
            reassembler.accept(fragments.get(i)).ifPresent(received::add);
        }

        assertTrue(received.isEmpty(), "a message with a missing fragment must not be delivered");
        assertEquals(1, reassembler.pendingCount());
    }

    @Test
    void unfragmentedMessagesBypassBuffering() {
        Framing.Reassembler reassembler = new Framing.Reassembler();

        Optional<Envelope> delivered = reassembler.accept(
                Envelope.of(MessageType.DELTA, 9L, "{\"kills\":3}"));

        assertTrue(delivered.isPresent());
        assertEquals("{\"kills\":3}", delivered.get().body());
        assertEquals(0, reassembler.pendingCount());
    }

    @Test
    void abandonedMessagesAreEvictedInsteadOfAccumulating() {
        Framing.Reassembler reassembler = new Framing.Reassembler(3);
        // Four distinct messages that each stop after their first fragment.
        for (int i = 0; i < 4; i++) {
            Envelope first = Framing.split(MessageType.SNAPSHOT, i, "abandoned-" + i, bigBody(2_000), 512).get(0);
            reassembler.accept(first);
        }

        assertEquals(3, reassembler.pendingCount(), "the oldest partial must be dropped at the cap");
    }

    @Test
    void reusingAnIdForADifferentlySizedMessageRestartsIt() {
        Framing.Reassembler reassembler = new Framing.Reassembler();
        reassembler.accept(Framing.split(MessageType.SNAPSHOT, 1L, BODY_ID, bigBody(2_000), 512).get(0));

        // Same id, different fragment count: the previous partial must not be merged in.
        List<Envelope> replacement = Framing.split(MessageType.SNAPSHOT, 2L, BODY_ID, bigBody(4_000), 512);
        Optional<Envelope> delivered = feedAll(reassembler, replacement);

        assertTrue(delivered.isPresent());
        assertEquals(4_000, delivered.get().body().length());
    }

    @Test
    void outOfRangeFragmentIndicesAreIgnored() {
        Framing.Reassembler reassembler = new Framing.Reassembler();

        Optional<Envelope> delivered = reassembler.accept(
                Envelope.fragment(MessageType.SNAPSHOT, 1L, BODY_ID, 5, 3, "junk"));

        assertTrue(delivered.isEmpty());
    }

    @Test
    void resetDiscardsPartialState() {
        Framing.Reassembler reassembler = new Framing.Reassembler();
        reassembler.accept(Framing.split(MessageType.SNAPSHOT, 1L, BODY_ID, bigBody(2_000), 512).get(0));
        assertEquals(1, reassembler.pendingCount());

        reassembler.reset();

        assertEquals(0, reassembler.pendingCount());
    }

    @Test
    void wireFormatSurvivesTheRoundTripThroughText() {
        // The real risk this guards against: a body full of quotes and backslashes
        // doubles in length once nested inside the envelope, and must still fit.
        EnvelopeCodec codec = new EnvelopeCodec();
        String body = "{\"markup\":\"" + "\\\"a\\\":<b>".repeat(900) + "\"}";
        List<Envelope> fragments = Framing.split(MessageType.LAYOUT, 1L, BODY_ID, body);

        for (Envelope fragment : fragments) {
            String wire = codec.encode(fragment);
            assertTrue(wire.length() < 32_767,
                    "an encoded fragment must fit Minecraft's string limit, was " + wire.length());
        }

        Framing.Reassembler reassembler = new Framing.Reassembler();
        Optional<Envelope> delivered = feedAll(reassembler,
                fragments.stream().map(f -> codec.decode(codec.encode(f))).toList());

        assertTrue(delivered.isPresent());
        assertEquals(body, delivered.get().body());
    }

    private static Optional<Envelope> feedAll(Framing.Reassembler reassembler, List<Envelope> fragments) {
        Optional<Envelope> delivered = Optional.empty();
        for (Envelope fragment : fragments) {
            Optional<Envelope> result = reassembler.accept(fragment);
            if (result.isPresent()) {
                delivered = result;
            }
        }
        return delivered;
    }

    private static String bigBody(int length) {
        StringBuilder body = new StringBuilder(length);
        while (body.length() < length) {
            body.append("abcdefghij");
        }
        return body.substring(0, length);
    }
}
