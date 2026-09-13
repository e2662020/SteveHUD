package com.rate.stevehud.mod.mc.config;

import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single way the rest of the mod reads and opens its settings.
 *
 * <p>Every accessor degrades to defaults or to a reported failure rather than
 * throwing: reading a config before registration has run, or failing to build the
 * screen, must never take the client down during startup.
 *
 * <p>Opening the screen reports what happened instead of returning silently.
 * ClothConfig can hand back a supplier that yields nothing, and setting a null
 * screen merely closes whatever was open — no message, no log line. That is the
 * worst possible failure for a broadcast tool: the operator presses the key, the
 * chat closes, and nothing says why.
 */
public final class Configs {

    private static final Logger LOGGER = LoggerFactory.getLogger("SteveHUD");

    /** What happened when we tried to open the settings screen. */
    public enum OpenResult {
        OPENED,
        /** ClothConfig was registered but produced no screen. */
        UNAVAILABLE,
        /** Building the screen threw. */
        FAILED
    }

    private static final SteveHudConfig FALLBACK = new SteveHudConfig();
    private static boolean registered;

    private Configs() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        AutoConfig.register(SteveHudConfig.class,
                me.shedaniel.autoconfig.serializer.GsonConfigSerializer::new);
        registered = true;
    }

    public static SteveHudConfig get() {
        if (!registered) {
            return FALLBACK;
        }
        try {
            return AutoConfig.getConfigHolder(SteveHudConfig.class).getConfig();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the SteveHUD settings; using defaults", e);
            return FALLBACK;
        }
    }

    public static void save() {
        if (!registered) {
            return;
        }
        try {
            AutoConfig.getConfigHolder(SteveHudConfig.class).save();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not save the SteveHUD settings", e);
        }
    }

    /**
     * The settings screen, to be opened on top of {@code parent}, or null when
     * ClothConfig produced none.
     */
    public static Screen screen(Screen parent) {
        if (!registered) {
            LOGGER.error("Settings screen requested before registration; nothing to show");
            return null;
        }
        try {
            return AutoConfig.getConfigScreen(SteveHudConfig.class, parent).get();
        } catch (RuntimeException e) {
            LOGGER.error("ClothConfig threw while building the SteveHUD settings screen", e);
            return null;
        }
    }

    /** Opens the settings screen, reporting the outcome so the caller can say something. */
    public static OpenResult openScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        Screen built = screen(client.currentScreen);
        if (built == null) {
            LOGGER.error("ClothConfig produced no settings screen for SteveHUD");
            return OpenResult.UNAVAILABLE;
        }
        try {
            client.setScreen(built);
        } catch (RuntimeException e) {
            LOGGER.error("Could not display the SteveHUD settings screen", e);
            return OpenResult.FAILED;
        }
        // Logged on success too: without this there is no way to tell a working
        // button from one that silently did nothing.
        LOGGER.info("Opened the SteveHUD settings screen ({})", built.getClass().getName());
        return OpenResult.OPENED;
    }
}
