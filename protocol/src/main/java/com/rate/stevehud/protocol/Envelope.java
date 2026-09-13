package com.rate.stevehud.protocol;

/**
 * One unit on the wire.
 *
 * <p>A message whose JSON body exceeds {@link Protocol#MAX_BODY_CHARS} travels as
 * several envelopes that share an {@link #id} and are ordered by {@link #index}
 * within {@link #total}. A message that fits travels as a single envelope with
 * {@code total == 1}, which is the overwhelmingly common case.
 *
 * @param v     protocol version
 * @param rev   match state revision this message belongs to; strictly increasing per connection
 * @param type  what is being carried
 * @param id    groups the fragments of one message; empty when not fragmented
 * @param index zero-based position of this fragment
 * @param total number of fragments; 1 when the message is not fragmented
 * @param body  the JSON body, or this fragment's slice of it
 */
public record Envelope(
        int v,
        long rev,
        MessageType type,
        String id,
        int index,
        int total,
        String body
) {

    /** An unfragmented message. */
    public static Envelope of(MessageType type, long rev, String body) {
        return new Envelope(Protocol.VERSION, rev, type, "", 0, 1, body);
    }

    /** One fragment of a larger message. */
    public static Envelope fragment(
            MessageType type, long rev, String id, int index, int total, String body) {
        return new Envelope(Protocol.VERSION, rev, type, id, index, total, body);
    }

    public boolean fragmented() {
        return total > 1;
    }

    public boolean hasCompatibleVersion() {
        return v == Protocol.VERSION;
    }
}
