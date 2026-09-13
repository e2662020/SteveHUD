package com.rate.stevehud.mod.mc.impl;

import net.minecraft.client.gui.DrawContext;

/**
 * Applies the HUD scale transform.
 *
 * <p>This is the one drawing operation that genuinely cannot be shared across the
 * supported Minecraft versions, which is why it is a class each version module
 * provides rather than code in the shared source set. See the sibling copies in
 * the other version modules.
 *
 * <p>1.21.1 and 1.21.4: {@code DrawContext.getMatrices()} returns the game's own
 * {@code MatrixStack}, whose transform methods take three floats.
 */
public final class HudScale {

    private HudScale() {
    }

    public static void push(DrawContext context, float scale) {
        var matrices = context.getMatrices();
        matrices.push();
        matrices.scale(scale, scale, 1.0f);
    }

    public static void pop(DrawContext context) {
        context.getMatrices().pop();
    }
}
