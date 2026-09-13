package com.rate.stevehud.protocol.model;

import java.util.List;

/**
 * Body of a {@code HELLO} message.
 *
 * <p>Sent by the server when a client's channel becomes usable. The client
 * answers with its own {@code HELLO} carrying the same fields, which is how each
 * side learns whether the other speaks a compatible protocol before anything
 * else is exchanged.
 *
 * <p>{@link #operator} is decided by the server and pushed to the client rather
 * than being worked out locally. Two reasons: the server is the authority on who
 * is an operator, and the client-side helpers for asking are not stable across
 * the Minecraft versions we target — one of them does not exist at all on 1.21.11.
 * A boolean on the wire has no such problem.
 *
 * @param protocolVersion the sender's {@code Protocol.VERSION}
 * @param implementation  human-readable name and version, for logs and the status panel
 * @param capabilities    feature keys the sender supports, so features can be
 *                        negotiated without hard-coding version numbers
 * @param operator        whether this client may author the graphics package
 */
public record HelloBody(
        int protocolVersion,
        String implementation,
        List<String> capabilities,
        boolean operator
) {

    public HelloBody {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
