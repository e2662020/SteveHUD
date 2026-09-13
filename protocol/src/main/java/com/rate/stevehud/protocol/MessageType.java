package com.rate.stevehud.protocol;

/** What an {@link Envelope} is carrying. */
public enum MessageType {

    /** Server to client: greeting, sent when a connection becomes usable. */
    HELLO,

    /** Server to client: the complete match state at {@link Envelope#rev()}. */
    SNAPSHOT,

    /** Server to client: the change that produced {@link Envelope#rev()} from the previous revision. */
    DELTA,

    /** Server to client: a broadcast graphics layout document. */
    LAYOUT,

    /** Either direction: a command or a request. */
    COMMAND,

    /** Server to client: a revision was accepted, so the sender can drop its backlog. */
    ACK
}
