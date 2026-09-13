package com.rate.stevehud.mod.mc.hud;

import com.rate.stevehud.mod.client.anim.Anim;
import com.rate.stevehud.mod.client.layout.ElementMetrics;
import com.rate.stevehud.mod.client.layout.Anchor;
import com.rate.stevehud.mod.mc.config.Configs;
import com.rate.stevehud.mod.mc.impl.HudScale;
import com.rate.stevehud.mod.mc.web.WebBridge;
import com.rate.stevehud.protocol.model.BroadcastState;
import com.rate.stevehud.protocol.model.Layout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;

import java.util.List;

/**
 * Draws the broadcast package the layout document describes.
 *
 * <p>An orchestrator, not a renderer. It walks the document, decides for each
 * element whether the current scene shows it, works out where it goes and what
 * colours it uses, and hands the drawing to {@link Panels}. The state comes from
 * {@link HudState}, which mirrors what the server sent; the layout comes from
 * {@link WebBridge}, which is the same document the browser overlay draws.
 *
 * <p>Two coordinate steps, kept apart on purpose:
 *
 * <ol>
 *   <li><b>Design to screen.</b> One uniform factor from the document's canvas to
 *       this window. Uniform rather than per-axis, because a package stretched to
 *       fill an ultrawide window would have an oval score disc and a timer whose
 *       digits changed width as it counted.</li>
 *   <li><b>Scaling about each element's own corner.</b> A top-right panel therefore
 *       stays in the top-right as it grows, where scaling everything from the screen
 *       origin would drag it toward the middle. The placement and the division by
 *       the scale are kept as separate steps because the matrix transform scales
 *       about the origin, and an element's own layout code should not have to know
 *       that.</li>
 * </ol>
 */
public final class MatchHud {

    private MatchHud() {
    }

    public static void onWorldJoin() {
        HudState.onWorldJoin();
    }

    public static void render(DrawContext ctx) {
        var config = Configs.get();
        if (!config.hudEnabled) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) {
            return;
        }
        int opacity = Math.clamp(config.hudOpacity, 0, 100);
        if (opacity == 0) {
            return;
        }

        float enter = HudState.enter();
        if (enter <= 0.01f) {
            return;
        }

        BroadcastState state = HudState.state();
        Layout layout = WebBridge.layout();
        TextRenderer font = client.textRenderer;
        int screenW = ctx.getScaledWindowWidth();
        int screenH = ctx.getScaledWindowHeight();
        String scene = state.getScene();

        float unit = unit(layout, screenW, screenH);
        float userScale = Math.clamp(config.hudScale, 25, 400) / 100f;
        int tickerScreenHeight = tickerScreenHeight(state, layout, scene, unit, userScale);

        Panels.Ctx base = new Panels.Ctx(ctx, font, state, playerEntries(client),
                screenW, screenH, true, Anim.nowMs(), opacity / 100f * enter,
                HudState.scorePops(), HudState.lowerThirdSlide(),
                HudState.announcementEnter(), HudState.tickerScroll(),
                unit, tickerScreenHeight);

        // Document order is draw order, back to front. The shipped packages put the
        // ticker and the frame first for exactly that reason: they span the viewport,
        // and painting them behind everything else stops a panel from ever being
        // covered by the strip it has to clear.
        for (Layout.Element element : layout.elements) {
            if (!Layout.showsIn(element, scene)) {
                continue;
            }
            draw(base, layout, element, userScale);
        }
    }

    // ---- placement ----------------------------------------------------------

    private static void draw(Panels.Ctx base, Layout layout, Layout.Element element, float userScale) {
        Palette palette = Palette.of(layout.theme, element);
        float alpha = base.alpha * clamp01(element.opacity);
        if (alpha <= 0.01f) {
            return;
        }
        float scale = effectiveScale(element, userScale);

        switch (element.type) {
            case Layout.TYPE_FRAME -> Panels.frame(base.forElement(element, palette, 0, 0, alpha),
                    Math.round((layout.safe.x + element.x) * base.unit),
                    base.tickerScreenHeight + Math.round(element.y * base.unit));
            case Layout.TYPE_ANNOUNCEMENT -> Panels.announcement(
                    base.forElement(element, palette, 0, 0, alpha),
                    base.screenH / 2 + Math.round(element.y * base.unit));
            case Layout.TYPE_TICKER -> drawTicker(base, element, palette, alpha, scale);
            default -> anchored(base, element, palette, alpha, scale);
        }
    }

    /**
     * The ticker is drawn like any other anchored element, with one difference: its
     * scissor rectangle is computed in window pixels rather than in the drawing
     * space, because that is the coordinate system the clip takes and it is not
     * transformed. Getting this wrong is invisible at scale 1 and wrong at every
     * other scale, which is exactly the kind of bug that only shows up on the one
     * machine with a non-default setting.
     */
    private static void drawTicker(Panels.Ctx base, Layout.Element element,
                                   Palette palette, float alpha, float scale) {
        if (base.state.getTicker().isEmpty()) {
            return;
        }
        scale *= boxRatio(element);
        int reference = Panels.naturalWidth(Layout.TYPE_TICKER);
        int width = Math.round(reference * base.unit);
        int height = Math.round((element.height > 0 ? element.height : Panels.TICKER_H) * base.unit);
        if (width <= 0 || height <= 0) {
            return;
        }

        Anchor anchor = Anchor.of(element.anchor);
        int[] at = anchor.place(width, height, scale, base.screenW, base.screenH,
                Math.round(element.x * base.unit), Math.round(element.y * base.unit));
        int localX = Math.round(at[0] / scale);
        int localY = Math.round(at[1] / scale);

        Panels.Ctx configured = base.forElement(element, palette, width, height, alpha);
        HudScale.push(base.ctx, scale);
        try {
            Panels.ticker(configured,
                    new Panels.Rect(localX, localY, width, height),
                    new Panels.Rect(at[0], at[1],
                            Math.round(width * scale), Math.round(height * scale)));
        } finally {
            HudScale.pop(base.ctx);
        }
    }

    /**
     * Measures an element, works out where its anchored corner puts it, and draws it
     * inside a transform that scales about that corner.
     *
     * <p>The box the document asks for is reached by scaling the type's <em>reference</em>
     * box rather than by handing the painter the resized width. That distinction is
     * the whole of "the text follows the box": the painter keeps drawing at its
     * designed sizes, the transform magnifies the result, and a wider box therefore
     * takes its type, padding, rules and dots with it instead of leaving fixed-size
     * text stranded in a bigger panel.
     */
    private static void anchored(Panels.Ctx base, Layout.Element element,
                                 Palette palette, float alpha, float scale) {
        float ratio = boxRatio(element);
        scale *= ratio;
        int boxW = Math.round(Panels.naturalWidth(element.type) * base.unit);
        int boxH = element.height > 0 ? Math.round(element.height * base.unit) : 0;

        Panels.Ctx configured = base.forElement(element, palette, boxW, boxH, alpha);
        int measured = paint(configured.measuring(), element.type, 0, 0);
        // A painter that has nothing to say at this moment returns 0, and the element
        // is skipped rather than drawn as an empty box.
        if (measured <= 0) {
            return;
        }
        int height = boxH > 0 ? boxH : measured;

        Anchor anchor = Anchor.of(element.anchor);
        int marginX = Math.round(element.x * base.unit);
        int marginY = Math.round(element.y * base.unit);
        if (anchor.isBottom() && !element.type.equals(Layout.TYPE_TICKER)) {
            // The bottom edge already carries the ticker, so bottom-anchored elements
            // sit above it rather than on top of it. The ticker itself does not clear
            // itself.
            marginY += Math.round(base.tickerScreenHeight / Math.max(scale, 0.05f));
        }

        int[] at = anchor.place(boxW, height, scale, base.screenW, base.screenH, marginX, marginY);
        // The transform scales about the origin, so drawing at (screen / scale) is what
        // puts the element's corner at screen.
        int localX = Math.round(at[0] / scale);
        int localY = Math.round(at[1] / scale);

        HudScale.push(base.ctx, scale);
        try {
            paint(configured, element.type, localX, localY);
        } finally {
            HudScale.pop(base.ctx);
        }
    }

    /**
     * How much the document's box differs from the size the type is designed at.
     *
     * <p>1 when the element is content-sized, since there is then no box to fit into.
     * Clamped, because a document is edited by hand and a ratio of 40 would put a
     * 900-pixel scoreboard on screen.
     *
     * <p>The reference widths live in {@link Panels#naturalWidth} and are mirrored in
     * the browser overlay's {@code REFERENCE_WIDTH}. Two renderers, one set of
     * numbers; a test pins the Java side.
     */
    private static float boxRatio(Layout.Element element) {
        return ElementMetrics.boxRatio(element.type, element.width);
    }

    /** One element type's drawing routine. */
    private static int paint(Panels.Ctx c, String type, int x, int y) {
        return switch (type) {
            case Layout.TYPE_MATCH_BUG -> Panels.matchBug(c, x, y);
            case Layout.TYPE_EVENT_INFO -> Panels.eventInfo(c, x, y);
            case Layout.TYPE_TIMER -> Panels.timer(c, x, y);
            case Layout.TYPE_LOWER_THIRD -> Panels.lowerThird(c, x, y);
            case Layout.TYPE_TEXT -> Panels.text(c, x, y);
            default -> 0;
        };
    }

    /**
     * One uniform factor from the design canvas to this window.
     *
     * <p>The smaller of the two ratios, so a package designed for 16:9 always fits
     * inside a window of another shape instead of overflowing the shorter axis.
     */
    private static float unit(Layout layout, int screenW, int screenH) {
        int canvasW = Math.max(1, layout.canvas.width);
        int canvasH = Math.max(1, layout.canvas.height);
        return Math.min(screenW / (float) canvasW, screenH / (float) canvasH);
    }

    /** An element's own scale, times the package's type scale, times the viewer's. */
    private static float effectiveScale(Layout.Element element, float userScale) {
        float fontScale = element.style == null ? 1f : element.style.fontScale;
        return Math.clamp(element.scale * fontScale * userScale, 0.05f, 16f);
    }

    /**
     * How tall the ticker is on screen, so bottom-anchored elements can clear it.
     *
     * <p>Zero when the ticker is absent from the document, hidden in this scene or has
     * nothing to say — which is what lets the timer drop back to the corner when the
     * notices run out, instead of leaving a gap where a strip used to be.
     */
    private static int tickerScreenHeight(BroadcastState state, Layout layout,
                                          String scene, float unit, float userScale) {
        Layout.Element element = layout.first(Layout.TYPE_TICKER);
        if (element == null || !Layout.showsIn(element, scene) || state.getTicker().isEmpty()) {
            return 0;
        }
        int design = element.height > 0 ? element.height : Panels.TICKER_H;
        return Math.round(design * unit * effectiveScale(element, userScale));
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    private static List<PlayerListEntry> playerEntries(MinecraftClient client) {
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        return handler == null ? List.of() : List.copyOf(handler.getPlayerList());
    }
}
