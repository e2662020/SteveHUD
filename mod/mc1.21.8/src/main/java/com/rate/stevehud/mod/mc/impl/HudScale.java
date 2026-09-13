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
 * <p>1.21.8 and 1.21.11: {@code DrawContext.getMatrices()} returns a JOML
 * {@code Matrix3x2fStack} instead, whose methods are named differently and take
 * two floats because the stack is two-dimensional. There is no overlap with the
 * three-float {@code MatrixStack} API — not even the push and pop names.
 */
public final class HudScale {

    private HudScale() {
    }

    public static void push(DrawContext context, float scale) {
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.scale(scale, scale);
    }

    public static void pop(DrawContext context) {
        context.getMatrices().popMatrix();
    }
}
