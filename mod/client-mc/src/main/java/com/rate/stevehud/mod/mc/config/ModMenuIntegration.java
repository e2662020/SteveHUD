package com.rate.stevehud.mod.mc.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screen.Screen;

/**
 * Puts a settings button on SteveHUD's entry in the mod list.
 *
 * <p>ModMenu is optional, so this class is only ever loaded when ModMenu is
 * present — the {@code modmenu} entrypoint it is registered under is resolved by
 * ModMenu itself, never by Fabric Loader on our behalf.
 */
public final class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<Screen> getModConfigScreenFactory() {
        return Configs::screen;
    }
}
