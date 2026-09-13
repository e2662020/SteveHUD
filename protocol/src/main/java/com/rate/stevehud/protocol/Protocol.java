package com.rate.stevehud.protocol;

/**
 * Constants of the SteveHUD wire format.
 *
 * <p>This class and everything else in this module are free of any Minecraft
 * dependency on purpose: the Spigot plugin, the four Fabric mod builds and the
 * test suite all compile against this one definition.
 */
public final class Protocol {

    private Protocol() {
    }

    /**
     * Bumped whenever the wire format changes in an incompatible way.
     *
     * <p>v2 added the {@code operator} flag to the greeting body, so a v1 client
     * and a v2 server must refuse each other rather than half-working.
     *
     * <p>v3 carries the broadcast state: the match package is now owned by the
     * server and rendered identically by every client, rather than each client
     * inventing its own content.
     */
    public static final int VERSION = 3;

    /**
     * Plugin channel namespace and path. Deliberately outside the {@code minecraft}
     * namespace; a vanilla client that does not have the mod installed simply logs
     * and ignores these payloads.
     */
    public static final String CHANNEL_NAMESPACE = "stevehud";
    public static final String CHANNEL_PATH = "main";
    public static final String CHANNEL = CHANNEL_NAMESPACE + ":" + CHANNEL_PATH;

    /**
     * Upper bound, in characters, on the JSON body carried by one envelope.
     *
     * <p>Minecraft encodes a string packet field with a 32767-character ceiling,
     * and the body is additionally JSON-escaped when nested inside the envelope,
     * so a body larger than this is split across several envelopes by
     * {@link Framing#split}. The margin is generous enough that even a body made
     * entirely of escape-heavy characters stays inside the limit.
     */
    public static final int MAX_BODY_CHARS = 8_000;

    /** How many partially received messages a reassembler tracks before evicting the oldest. */
    public static final int MAX_PENDING_MESSAGES = 32;
}
