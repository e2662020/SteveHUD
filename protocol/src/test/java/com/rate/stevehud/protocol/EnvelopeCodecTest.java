package com.rate.stevehud.protocol;

import com.rate.stevehud.protocol.model.HelloBody;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeCodecTest {

    private final EnvelopeCodec codec = new EnvelopeCodec();

    @Test
    void roundTripsEveryField() {
        Envelope original = Envelope.fragment(MessageType.LAYOUT, 42L, "layout-7", 3, 9, "{\"a\":1}");

        Envelope decoded = codec.decode(codec.encode(original));

        assertEquals(original, decoded);
    }

    @Test
    void preservesNonAsciiWithoutBloat() {
        // Chinese competitor names are the common case, and escaping them would
        // burn most of the per-envelope character budget.
        String body = "{\"name\":\"张三\",\"team\":\"北京队\"}";
        Envelope envelope = Envelope.of(MessageType.SNAPSHOT, 1L, body);

        String wire = codec.encode(envelope);

        assertTrue(wire.contains("张三"), "non-ASCII should survive verbatim, was: " + wire);
        assertEquals(body, codec.decode(wire).body());
    }

    @Test
    void doesNotEscapeMarkupCharacters() {
        String body = "{\"markup\":\"<b>bold</b> & 'quoted'\"}";

        String wire = codec.encode(Envelope.of(MessageType.LAYOUT, 1L, body));

        assertTrue(wire.contains("<b>bold</b>"), "markup should not be \\u-escaped, was: " + wire);
        assertEquals(body, codec.decode(wire).body());
    }

    @Test
    void decodesTypedBodies() {
        HelloBody hello = new HelloBody(
                Protocol.VERSION, "SteveHUD-Plugin/0.1.0", List.of("snapshot", "layout"), true);
        Envelope envelope = Envelope.of(MessageType.HELLO, 1L, codec.encodeBody(hello));

        assertEquals(hello, codec.decodeBody(envelope, HelloBody.class));
    }

    @Test
    void rejectsTextThatIsNotAnEnvelope() {
        assertThrows(MalformedMessageException.class, () -> codec.decode("not json at all"));
        assertThrows(MalformedMessageException.class, () -> codec.decode("null"));
        assertThrows(MalformedMessageException.class, () -> codec.decode("{\"v\":1}"));
    }

    @Test
    void missingCapabilitiesDecodeAsEmptyRatherThanNull() {
        HelloBody decoded = codec.decodeBody(
                Envelope.of(MessageType.HELLO, 1L, "{\"protocolVersion\":1,\"implementation\":\"x\"}"),
                HelloBody.class);

        assertEquals(List.of(), decoded.capabilities());
    }

    @Test
    void missingOperatorFlagDecodesAsNotOperator() {
        // A message from an older sender has no operator field. Defaulting to false
        // is the safe direction: it withholds authoring rights rather than granting
        // them on the strength of an absent field.
        HelloBody decoded = codec.decodeBody(
                Envelope.of(MessageType.HELLO, 1L,
                        "{\"protocolVersion\":1,\"implementation\":\"x\",\"capabilities\":[]}"),
                HelloBody.class);

        assertFalse(decoded.operator());
    }
}
