package com.rate.stevehud.mod.client;

import com.rate.stevehud.protocol.Envelope;
import com.rate.stevehud.protocol.EnvelopeCodec;
import com.rate.stevehud.protocol.Framing;
import com.rate.stevehud.protocol.MalformedMessageException;
import com.rate.stevehud.protocol.MessageType;
import com.rate.stevehud.protocol.Protocol;
import com.rate.stevehud.protocol.model.HelloBody;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The client's half of the channel: takes raw payload text apart, keeps the
 * revision bookkeeping, and hands whole messages to whoever is interested.
 *
 * <p>No Minecraft types appear here, so this logic is exercised by plain unit
 * tests rather than only in-game, and every version module shares one copy of it.
 *
 * <p>Threading: the channel's payload handler runs off the render thread, so
 * {@link #accept} may be called from there. Revision and handshake state are
 * therefore guarded, and listeners are notified while holding no lock.
 */
public final class SteveHudLink {

    /** Receives whole, reassembled messages. */
    public interface Listener {
        void onMessage(Envelope envelope);
    }

    private final EnvelopeCodec codec = new EnvelopeCodec();
    private final Framing.Reassembler reassembler = new Framing.Reassembler();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong revision = new AtomicLong(-1L);
    private final Consumer<String> warn;
    private final String implementation;
    private final List<String> capabilities;

    private volatile HelloBody serverHello;
    private volatile boolean incompatibleProtocolLogged;
    /**
     * When the last complete message arrived, in milliseconds from the same
     * monotonic source as {@link com.rate.stevehud.mod.client.anim.Anim}.
     *
     * <p>Exposed because "connected" and "receiving" are different facts, and a
     * broadcast operator needs the second one: a link that established and then
     * went quiet looks identical to a healthy one from the outside.
     */
    private volatile long lastMessageAtMs = -1L;

    /**
     * @param implementation this client's name and version, reported to the server
     * @param warn           where to report recoverable problems; nothing here throws
     */
    public SteveHudLink(String implementation, List<String> capabilities, Consumer<String> warn) {
        this.implementation = implementation;
        this.capabilities = List.copyOf(capabilities);
        this.warn = warn;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** The revision of the newest message accepted, or -1 before anything arrives. */
    public long revision() {
        return revision.get();
    }

    /**
     * Milliseconds since the last complete message arrived, or -1 if none ever has.
     */
    public long millisSinceLastMessage() {
        long last = lastMessageAtMs;
        return last < 0L ? -1L : System.nanoTime() / 1_000_000L - last;
    }

    public Optional<HelloBody> serverHello() {
        return Optional.ofNullable(serverHello);
    }

    /** True once the server has greeted us with a protocol we understand. */
    public boolean ready() {
        return serverHello != null;
    }

    /**
     * The greeting to send back to the server.
     *
     * <p>{@code operator} is false here by definition: the flag means "this peer
     * may author the graphics package", and a client is in no position to grant
     * that to a server. Only the server sets it, in the greeting it sends us.
     */
    public String encodeHello() {
        return codec.encodeBody(new HelloBody(Protocol.VERSION, implementation, capabilities, false));
    }

    /**
     * Feeds one payload from the channel.
     *
     * @return whether the text was a well-formed message. A malformed payload is
     *         reported and dropped; it never propagates to the caller, because a
     *         bad message must not be able to break the connection.
     */
    public boolean accept(String json) {
        Envelope envelope;
        try {
            envelope = codec.decode(json);
        } catch (MalformedMessageException e) {
            warn.accept("Dropped a malformed message: " + e.getMessage());
            return false;
        }

        if (!envelope.hasCompatibleVersion()) {
            if (!incompatibleProtocolLogged) {
                incompatibleProtocolLogged = true;
                warn.accept("Server speaks SteveHUD protocol v" + envelope.v()
                        + " but this client speaks v" + Protocol.VERSION
                        + "; update whichever is older.");
            }
            return false;
        }

        Optional<Envelope> whole = reassembler.accept(envelope);
        if (whole.isEmpty()) {
            // A fragment; the rest is still in flight.
            return true;
        }

        deliver(whole.get());
        return true;
    }

    /** Forget all partial state; call when the connection is replaced. */
    public void reset() {
        reassembler.reset();
        serverHello = null;
        revision.set(-1L);
        lastMessageAtMs = -1L;
        incompatibleProtocolLogged = false;
    }

    private void deliver(Envelope message) {
        if (message.type() == MessageType.HELLO) {
            try {
                serverHello = codec.decodeBody(message, HelloBody.class);
            } catch (MalformedMessageException e) {
                warn.accept("Server greeting was unreadable: " + e.getMessage());
                return;
            }
        }

        revision.accumulateAndGet(message.rev(), Math::max);
        lastMessageAtMs = System.nanoTime() / 1_000_000L;

        for (Listener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (RuntimeException e) {
                // One bad listener must not stop the others from seeing the message.
                warn.accept("A message listener failed on " + message.type() + ": " + e);
            }
        }
    }

    /**
     * Decodes a message body into a typed object.
     *
     * <p>Exposed so the client can handle message types this class does not care
     * about — the layout document, for one — without reaching past it for the codec
     * and thereby keeping a second copy of the decoding rules.
     *
     * @throws com.rate.stevehud.protocol.MalformedMessageException when the body does
     *         not fit, which every caller should treat as "ignore this message"
     */
    public <T> T decodeBody(com.rate.stevehud.protocol.Envelope envelope, Class<T> type) {
        return codec.decodeBody(envelope, type);
    }
}
