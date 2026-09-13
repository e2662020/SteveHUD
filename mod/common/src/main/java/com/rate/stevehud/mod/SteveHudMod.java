package com.rate.stevehud.mod;

/** Identifiers shared by every version module. */
public final class SteveHudMod {

    private SteveHudMod() {
    }

    public static final String MOD_ID = "stevehud";
    public static final String NAME = "SteveHUD";

    /** Feature keys this client can handle, reported to the server during the handshake. */
    public static final java.util.List<String> CAPABILITIES =
            java.util.List.of("snapshot", "delta", "layout", "local-graphics-server");
}
