package com.rate.stevehud.protocol;

/** Raised when text on the wire is not a well-formed SteveHUD message. */
public class MalformedMessageException extends RuntimeException {

    public MalformedMessageException(String message) {
        super(message);
    }

    public MalformedMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
