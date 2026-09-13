package com.rate.stevehud.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/**
 * Turns envelopes into text and back.
 *
 * <p>HTML escaping is disabled so that XML-ish characters in player names and
 * layout markup survive as themselves rather than as {@code \\u003c}-style
 * escapes, which would waste a large part of the per-envelope character budget.
 */
public final class EnvelopeCodec {

    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public String encode(Envelope envelope) {
        return gson.toJson(envelope, Envelope.class);
    }

    /**
     * @throws MalformedMessageException if the text is not a well-formed envelope
     */
    public Envelope decode(String json) {
        Envelope envelope;
        try {
            envelope = gson.fromJson(json, Envelope.class);
        } catch (JsonSyntaxException e) {
            throw new MalformedMessageException("not a valid envelope: " + e.getMessage(), e);
        }
        if (envelope == null) {
            throw new MalformedMessageException("envelope decoded to null");
        }
        if (envelope.type() == null) {
            throw new MalformedMessageException("envelope has no message type");
        }
        return envelope;
    }

    /** Serialises a message body for use as {@link Envelope#body()}. */
    public String encodeBody(Object body) {
        return gson.toJson(body);
    }

    public <T> T decodeBody(Envelope envelope, Class<T> bodyType) {
        try {
            return gson.fromJson(envelope.body(), bodyType);
        } catch (JsonSyntaxException e) {
            throw new MalformedMessageException(
                    "body of " + envelope.type() + " is not a valid " + bodyType.getSimpleName()
                            + ": " + e.getMessage(), e);
        }
    }

    /** Decodes a plain JSON document, for payloads that are not wrapped in an envelope. */
    public <T> T decode(String json, Class<T> bodyType) {
        try {
            return gson.fromJson(json, bodyType);
        } catch (JsonSyntaxException e) {
            throw new MalformedMessageException(
                    "not a valid " + bodyType.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
