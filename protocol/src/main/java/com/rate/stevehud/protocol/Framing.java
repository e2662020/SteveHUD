package com.rate.stevehud.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Splits oversized messages into fragments and puts them back together.
 *
 * <p>A roster for a large battle-royale event, or a layout document, can easily
 * exceed what one envelope may carry, so every message goes through
 * {@link #split} on the way out and {@link Reassembler} on the way in. The
 * reassembler is tolerant of fragments arriving out of order and of duplicates,
 * but it never buffers without bound: when more than
 * {@link Protocol#MAX_PENDING_MESSAGES} messages are partially received, the
 * oldest is dropped, so a peer that stops mid-message cannot exhaust memory.
 */
public final class Framing {

    private Framing() {
    }

    public static List<Envelope> split(MessageType type, long rev, String id, String body) {
        return split(type, rev, id, body, Protocol.MAX_BODY_CHARS);
    }

    /**
     * @param id           identifies this message so its fragments can be grouped;
     *                     must be unique among in-flight messages
     * @param maxBodyChars characters per fragment
     */
    public static List<Envelope> split(
            MessageType type, long rev, String id, String body, int maxBodyChars) {
        if (maxBodyChars < 1) {
            throw new IllegalArgumentException("maxBodyChars must be positive, was " + maxBodyChars);
        }
        if (body.length() <= maxBodyChars) {
            return List.of(Envelope.of(type, rev, body));
        }

        int count = (body.length() + maxBodyChars - 1) / maxBodyChars;
        List<Envelope> fragments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from = i * maxBodyChars;
            int to = Math.min(body.length(), from + maxBodyChars);
            fragments.add(Envelope.fragment(type, rev, id, i, count, body.substring(from, to)));
        }
        return fragments;
    }

    /** Rebuilds fragmented messages, one instance per connection. */
    public static final class Reassembler {

        private final int maxPending;
        private final Map<String, Partial> pending;

        public Reassembler() {
            this(Protocol.MAX_PENDING_MESSAGES);
        }

        public Reassembler(int maxPending) {
            if (maxPending < 1) {
                throw new IllegalArgumentException("maxPending must be positive, was " + maxPending);
            }
            this.maxPending = maxPending;
            // Insertion-ordered so the oldest partial can be evicted first.
            this.pending = new LinkedHashMap<>(16, 0.75f, false);
        }

        /**
         * @return the whole envelope once the message is complete, the envelope
         *         itself when it was never fragmented, or empty while fragments
         *         are still missing
         */
        public Optional<Envelope> accept(Envelope envelope) {
            if (!envelope.fragmented()) {
                return Optional.of(envelope);
            }
            if (envelope.index() < 0 || envelope.index() >= envelope.total()) {
                return Optional.empty();
            }

            Partial partial = pending.get(envelope.id());
            if (partial == null || partial.total != envelope.total()) {
                // A new message, or the same id reused for a differently sized one.
                if (partial == null && pending.size() >= maxPending) {
                    evictOldest();
                }
                partial = new Partial(envelope.total());
                pending.put(envelope.id(), partial);
            }

            partial.put(envelope);
            if (!partial.complete()) {
                return Optional.empty();
            }

            pending.remove(envelope.id());
            return Optional.of(Envelope.of(envelope.type(), envelope.rev(), partial.join()));
        }

        /** Drops all partial state; call when a connection is replaced. */
        public void reset() {
            pending.clear();
        }

        public int pendingCount() {
            return pending.size();
        }

        private void evictOldest() {
            var iterator = pending.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }

        private static final class Partial {

            private final int total;
            private final Map<Integer, String> parts = new HashMap<>();

            Partial(int total) {
                this.total = total;
            }

            void put(Envelope envelope) {
                parts.put(envelope.index(), envelope.body());
            }

            boolean complete() {
                return parts.size() == total;
            }

            String join() {
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < total; i++) {
                    joined.append(parts.get(i));
                }
                return joined.toString();
            }
        }
    }
}
