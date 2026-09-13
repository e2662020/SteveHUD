package com.rate.stevehud.mod.mc.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

/**
 * The mod's own settings — personal client preferences, nothing more.
 *
 * <p>Content that a broadcast operator edits (rosters, layouts, animations) is
 * deliberately not here: that is authored in the web editor and arrives over the
 * channel, so it never becomes a per-machine config file that has to be kept in
 * sync by hand.
 *
 * <p>Only booleans and bounded integers on purpose. ClothConfig's API has drifted
 * across the versions we target, and these two entry types plus categories have
 * been stable throughout, so this one source file compiles against every
 * cloth-config we depend on instead of needing a copy per Minecraft version.
 */
@Config(name = "stevehud")
public class SteveHudConfig implements ConfigData {

    // ---- In-game HUD ----

    @ConfigEntry.Category("hud")
    @ConfigEntry.Gui.Tooltip
    public boolean hudEnabled = true;

    @ConfigEntry.Category("hud")
    @ConfigEntry.BoundedDiscrete(min = 25, max = 400)
    @ConfigEntry.Gui.Tooltip
    public int hudScale = 100;

    @ConfigEntry.Category("hud")
    @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
    @ConfigEntry.Gui.Tooltip
    public int hudOpacity = 100;

    // ---- Link to the server plugin ----

    @ConfigEntry.Category("link")
    @ConfigEntry.Gui.Tooltip
    public boolean announceHandshakeInChat = true;

    @ConfigEntry.Category("link")
    @ConfigEntry.Gui.Tooltip
    public boolean verboseLinkLogging = false;

    // ---- Local graphics server (what OBS points at) ----

    @ConfigEntry.Category("server")
    @ConfigEntry.Gui.Tooltip
    public boolean localServerEnabled = true;

    @ConfigEntry.Category("server")
    @ConfigEntry.BoundedDiscrete(min = 1024, max = 65535)
    @ConfigEntry.Gui.Tooltip
    public int localServerPort = 8787;

    @ConfigEntry.Category("server")
    @ConfigEntry.Gui.Tooltip
    public boolean bindLoopbackOnly = true;
}
