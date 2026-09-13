package com.rate.stevehud.mod.mc.hud;

import com.rate.stevehud.mod.client.SteveHudLink;
import com.rate.stevehud.mod.client.anim.Anim;
import com.rate.stevehud.mod.client.layout.ElementMetrics;
import com.rate.stevehud.mod.client.layout.Bindings;
import com.rate.stevehud.mod.mc.SteveHudClient;
import com.rate.stevehud.mod.mc.impl.HudScale;
import com.rate.stevehud.protocol.model.BroadcastState;
import com.rate.stevehud.protocol.model.Layout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The drawing routines for every element type in a package.
 *
 * <p>Each one measures and draws through the same method, gated on {@link Ctx#draw},
 * so the two passes cannot disagree about how tall something is — the failure that
 * once let a progress bar be painted outside its own background.
 *
 * <p>Nothing here decides <em>where</em> an element goes or <em>what colour</em> it
 * is: those come from the layout document, resolved by {@link MatchHud} into
 * {@link Ctx#boxW}, {@link Ctx#boxH} and {@link Ctx#palette}. Keeping that split is
 * what lets the same document drive the browser overlay as well, and what makes a
 * new preset a data change rather than a code change.
 *
 * <p>Only version-stable drawing calls appear here: {@code fill}, {@code fillGradient},
 * the {@code drawText} family, scissor, window metrics, {@link PlayerSkinDrawer}.
 * Scaling goes through {@link HudScale}, the one operation that genuinely differs
 * between versions.
 */
final class Panels {

    /*
     * Natural widths, in design pixels, for when a document leaves width at 0.
     *
     * <p>Read from {@link ElementMetrics} rather than written out again here, so the
     * number a panel falls back to and the number its text is fitted against cannot
     * disagree.
     */
    static final int MATCH_BUG_W = ElementMetrics.referenceWidth(Layout.TYPE_MATCH_BUG);
    static final int EVENT_W = ElementMetrics.referenceWidth(Layout.TYPE_EVENT_INFO);
    static final int TIMER_W = ElementMetrics.referenceWidth(Layout.TYPE_TIMER);
    static final int LOWER_THIRD_W = ElementMetrics.referenceWidth(Layout.TYPE_LOWER_THIRD);
    /** The ticker's height. A height, not a width: it does not drive the type scale. */
    static final int TICKER_H = 53;

    private static final int PAD = 6;
    private static final int ROW = 20;
    private static final int HEAD = 10;
    private static final int SCORE_SCALE = 2;
    private static final int SCORE_COLUMN = 34;
    private static final int ROW_GAP = 2;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Panels() {
    }

    /** The natural width of a type, in design pixels. 0 means "spans the viewport". */
    /**
     * The natural width of a type, in design pixels. 0 means "content-sized".
     *
     * <p>Delegated to {@link ElementMetrics}, which is the single source for these
     * numbers and lives in the Minecraft-free half precisely so they can be tested
     * against the packages that ship.
     */
    static int naturalWidth(String type) {
        return ElementMetrics.referenceWidth(type);
    }

    /**
     * Everything a painter needs, plus the per-element context the document
     * supplies.
     *
     * <p>A class rather than a record because the per-element fields change between
     * elements while the rest stays fixed, and threading fourteen arguments through
     * each call would make the painters unreadable.
     */
    static final class Ctx {

        final DrawContext ctx;
        final TextRenderer font;
        final BroadcastState state;
        final List<PlayerListEntry> entries;
        final int screenW;
        final int screenH;
        /** False while measuring, so one method can serve both passes. */
        boolean draw;
        final long now;
        /** Opacity for the element being drawn: the HUD's, times that element's. */
        float alpha;
        final float[] scorePop;
        final float lowerThirdSlide;
        final float announcementEnter;
        final float tickerScroll;
        /** Design pixels to screen pixels. One factor, so the package is uniform. */
        final float unit;
        /**
         * How tall the ticker is on screen, in screen pixels.
         *
         * <p>Kept in screen pixels rather than in the drawing space because it is a
         * property of the viewport, not of the element that has to clear it. A panel
         * drawn at 2x needs half as much of its own space to clear the same strip.
         */
        final int tickerScreenHeight;

        Layout.Element element;
        Palette palette;
        /** The element's box in screen pixels; 0 means "as the painter wants". */
        int boxW;
        int boxH;

        Ctx(DrawContext ctx, TextRenderer font, BroadcastState state,
            List<PlayerListEntry> entries, int screenW, int screenH,
            boolean draw, long now, float alpha, float[] scorePop,
            float lowerThirdSlide, float announcementEnter, float tickerScroll,
            float unit, int tickerScreenHeight) {
            this.ctx = ctx;
            this.font = font;
            this.state = state;
            this.entries = entries;
            this.screenW = screenW;
            this.screenH = screenH;
            this.draw = draw;
            this.now = now;
            this.alpha = alpha;
            this.scorePop = scorePop;
            this.lowerThirdSlide = lowerThirdSlide;
            this.announcementEnter = announcementEnter;
            this.tickerScroll = tickerScroll;
            this.unit = unit;
            this.tickerScreenHeight = tickerScreenHeight;
        }

        private Ctx(Ctx other) {
            this(other.ctx, other.font, other.state, other.entries, other.screenW,
                    other.screenH, other.draw, other.now, other.alpha, other.scorePop,
                    other.lowerThirdSlide, other.announcementEnter, other.tickerScroll,
                    other.unit, other.tickerScreenHeight);
            this.element = other.element;
            this.palette = other.palette;
            this.boxW = other.boxW;
            this.boxH = other.boxH;
        }

        /** The same context with drawing switched off, to measure without painting. */
        Ctx measuring() {
            Ctx copy = new Ctx(this);
            copy.draw = false;
            return copy;
        }

        /** The same context bound to one element of the package. */
        Ctx forElement(Layout.Element element, Palette palette, int boxW, int boxH, float alpha) {
            Ctx copy = new Ctx(this);
            copy.element = element;
            copy.palette = palette;
            copy.boxW = boxW;
            copy.boxH = boxH;
            copy.alpha = alpha;
            return copy;
        }
    }

    // =========================================================================
    // match bug — live badge, wall clock, one band per side
    // =========================================================================

    static int matchBug(Ctx c, int x, int y) {
        int width = c.boxW > 0 ? c.boxW : MATCH_BUG_W;
        int innerX = x + PAD;
        int innerWidth = width - PAD * 2;
        int innerRight = innerX + innerWidth;
        int cursor = y + PAD;

        if (c.draw) {
            float beat = Anim.pulse(c.now, 1_100L);
            c.ctx.fill(innerX, cursor + 3, innerX + 5, cursor + 8,
                    Prim.scaled(c.palette.live, 0.45f + 0.55f * beat));
            TextFx.tracked(c.ctx, c.font, "LIVE", innerX + 9, cursor, 40,
                    Prim.scaled(c.palette.live, 0.92f), Prim.OUTLINE, 1);
            TextFx.outlinedRight(c.ctx, c.font, LocalTime.now().format(CLOCK), innerRight,
                    cursor, c.palette.ink, Prim.OUTLINE);
        }
        cursor += 11;

        cursor = rule(c, innerX, cursor, innerWidth);

        List<BroadcastState.Side> sides = c.state.getSides();
        for (int i = 0; i < sides.size(); i++) {
            cursor = sideRow(c, sides.get(i), i, innerX, cursor, innerWidth);
            if (i < sides.size() - 1) {
                cursor += ROW_GAP;
            }
        }
        if (sides.isEmpty()) {
            if (c.draw) {
                TextFx.tracked(c.ctx, c.font,
                        c.palette.text(Text.translatable("stevehud.hud.waiting").getString()),
                        innerX, cursor, innerWidth, c.palette.inkDim, Prim.OUTLINE, 1);
            }
            cursor += 10;
        }

        cursor = rule(c, innerX, cursor + 2, innerWidth);

        if (c.draw) {
            TextFx.clipped(c.ctx, c.font, statusLine(), innerX, cursor,
                    innerWidth, Prim.scaled(c.palette.inkDim, 0.85f), false);
        }
        cursor += 9;

        return cursor + PAD - y;
    }

    private static int sideRow(Ctx c, BroadcastState.Side side, int index, int x, int y, int width) {
        int colour = Colors.parse(side.color, index == 0 ? c.palette.home : c.palette.away);
        float bump = index < c.scorePop.length ? c.scorePop[index] : 1f;

        if (c.draw) {
            c.ctx.fill(x - 2, y - 1, x + width + 2, y + ROW - 3, Prim.scaled(colour, 0.10f));
            c.ctx.fill(x - 2, y - 1, x - 1, y + ROW - 3, Prim.scaled(colour, 0.80f));

            int textX = x + 3;
            if (c.entries.size() > index) {
                int headY = y + (ROW - 4 - HEAD) / 2;
                // The skin type moved package in 1.21.11, so it is never named:
                // the compiler infers it from the entry.
                PlayerSkinDrawer.draw(c.ctx, c.entries.get(index).getSkinTextures(),
                        textX, headY, HEAD);
                Prim.border(c.ctx, textX, headY, HEAD, HEAD, Prim.HEAD_FRAME);
                textX += HEAD + 4;
            } else {
                // An empty slot that reads as "awaiting a competitor" rather than
                // as a rendering failure.
                int headY = y + (ROW - 4 - HEAD) / 2;
                Prim.dashedRect(c.ctx, textX, headY, HEAD, HEAD,
                        Prim.scaled(c.palette.inkDim, 0.5f), 2);
                textX += HEAD + 4;
            }

            String label = side.shortName.isEmpty() ? side.name : side.shortName;
            String name = side.competitors.isEmpty()
                    ? label
                    : label + " · " + side.competitors.get(0).name;
            TextFx.tracked(c.ctx, c.font, c.palette.text(name), textX,
                    y + (ROW - 4 - c.font.fontHeight) / 2,
                    width - (textX - x) - SCORE_COLUMN, c.palette.ink, Prim.OUTLINE, 1);
        }

        if (c.draw) {
            String score = Integer.toString(side.score);
            float scale = SCORE_SCALE * bump;
            int centreX = x + width - SCORE_COLUMN / 2;
            int baseline = y + (ROW - 4) / 2 - c.font.fontHeight;
            HudScale.push(c.ctx, scale);
            try {
                TextFx.outlinedCentre(c.ctx, c.font, score,
                        Math.round(centreX / scale), Math.round(baseline / scale),
                        colour, Prim.OUTLINE);
            } finally {
                HudScale.pop(c.ctx);
            }
        }

        return y + ROW;
    }

    private static String statusLine() {
        MinecraftClient client = MinecraftClient.getInstance();
        SteveHudLink link = SteveHudClient.LINK;
        StringBuilder line = new StringBuilder("rev ").append(Math.max(link.revision(), 0));
        long since = link.millisSinceLastMessage();
        line.append(" · ").append(since < 0
                ? Text.translatable("stevehud.hud.no_data").getString()
                : since + "ms");
        if (client.getNetworkHandler() != null) {
            line.append(" · ").append(client.getNetworkHandler().getPlayerList().size()).append('p');
        }
        return line.append(" · ").append(client.getCurrentFps()).append("fps").toString();
    }

    private static int rule(Ctx c, int x, int y, int width) {
        if (c.draw) {
            c.ctx.fill(x, y + 3, x + width, y + 4, Prim.scaled(c.palette.border, 0.7f));
        }
        return y + 4;
    }

    // =========================================================================
    // event info — event name and stage, right-aligned
    // =========================================================================

    static int eventInfo(Ctx c, int x, int y) {
        String name = c.palette.text(c.state.getEvent().name);
        String stage = c.palette.text(c.state.getEvent().stage);
        int width = c.boxW > 0 ? c.boxW : EVENT_W;
        int innerWidth = width - PAD * 2;
        int right = x + width - PAD;
        int cursor = y + PAD;

        if (c.draw) {
            if (!name.isEmpty()) {
                TextFx.trackedRight(c.ctx, c.font, name, right, cursor, innerWidth,
                        c.palette.ink, Prim.OUTLINE, 1);
            }
            if (!stage.isEmpty()) {
                TextFx.trackedRight(c.ctx, c.font, stage, right, cursor + 11, innerWidth,
                        c.palette.accent, Prim.OUTLINE, 2);
            }
        }
        cursor += stage.isEmpty() ? 10 : 21;

        return cursor + PAD - y;
    }

    // =========================================================================
    // timer — the match clock, at the largest type in the package
    // =========================================================================

    static int timer(Ctx c, int x, int y) {
        BroadcastState.Clock clock = c.state.getClock();
        int width = c.boxW > 0 ? c.boxW : TIMER_W;
        int right = x + width - PAD;
        int centre = x + width / 2;
        int cursor = y + PAD;

        String label = clock.label.isEmpty() ? "MATCH CLOCK" : clock.label;
        if (c.draw) {
            TextFx.trackedRight(c.ctx, c.font, c.palette.text(label), right, cursor,
                    width - PAD * 2, c.palette.inkDim, Prim.OUTLINE, 1);
        }
        cursor += 11;

        if (c.draw) {
            // The one element a viewer reads at a glance, so it gets the largest type
            // in the package: 2x, outlined, in the accent colour.
            float scale = 2.0f;
            HudScale.push(c.ctx, scale);
            try {
                TextFx.outlinedCentre(c.ctx, c.font, clock.value,
                        Math.round(centre / scale), Math.round((cursor - 1) / scale),
                        c.palette.accent, Prim.OUTLINE);
            } finally {
                HudScale.pop(c.ctx);
            }
        }
        cursor += c.font.fontHeight * 2 + 2;

        if (clock.running) {
            if (c.draw) {
                // A breathing bar under a running clock, so "the match is live" reads
                // without reading the digits.
                float beat = Anim.pulse(c.now, 1_400L);
                int barWidth = Math.round((width - PAD * 2) * (0.35f + 0.65f * beat));
                c.ctx.fill(x + PAD, cursor, x + PAD + barWidth, cursor + 2,
                        Prim.scaled(c.palette.live, 0.9f));
            }
            cursor += 5;
        }

        return cursor + PAD - y;
    }

    // =========================================================================
    // lower third — the competitor card
    // =========================================================================

    static int lowerThird(Ctx c, int x, int y) {
        BroadcastState.Side side = c.state.side(c.state.getLowerThird().side);
        if (side == null) {
            return 0;
        }
        int width = c.boxW > 0 ? c.boxW : LOWER_THIRD_W;
        int height = c.boxH > 0 ? c.boxH : 38;
        int colour = Colors.parse(side.color, c.palette.home);
        // The slide starts off-screen to the left and settles at x, so the card
        // arrives instead of appearing.
        int drawX = x - Math.round((1f - c.lowerThirdSlide) * 60f);

        if (c.draw) {
            c.ctx.fill(drawX + 3, y + 3, drawX + width + 3, y + height + 3,
                    Prim.scaled(Prim.SHADOW, c.alpha));
            c.ctx.fillGradient(drawX, y, drawX + width, y + height,
                    Prim.scaled(c.palette.panelTop, c.alpha),
                    Prim.scaled(c.palette.panelBottom, c.alpha));

            // A solid team-colour block carrying the short code: the most
            // recognisable part of a competition lower third.
            int blockW = Math.min(52, width / 3);
            c.ctx.fill(drawX, y, drawX + blockW, y + height, Prim.scaled(colour, c.alpha));
            String code = c.palette.text(side.shortName.isEmpty() ? side.name : side.shortName);
            TextFx.outlinedCentre(c.ctx, c.font, code, drawX + blockW / 2,
                    y + (height - c.font.fontHeight) / 2, Prim.ink(colour), Prim.OUTLINE);

            int textX = drawX + blockW + 7;
            int textWidth = width - blockW - 14;
            TextFx.tracked(c.ctx, c.font, c.palette.text(side.name), textX, y + 7, textWidth,
                    c.palette.ink, Prim.OUTLINE, 1);
            if (!side.competitors.isEmpty()) {
                BroadcastState.Competitor competitor = side.competitors.get(0);
                String line = competitor.number.isEmpty()
                        ? competitor.name
                        : competitor.number + "  " + competitor.name;
                TextFx.tracked(c.ctx, c.font, c.palette.text(line), textX, y + 21, textWidth,
                        c.palette.accent, Prim.OUTLINE, 1);
            }
            c.ctx.fill(drawX, y, drawX + 3, y + height, Prim.scaled(c.palette.accent, c.alpha));
        }
        return height;
    }

    // =========================================================================
    // ticker — scrolling notices
    // =========================================================================

    /** A rectangle in some drawing space. */
    record Rect(int x, int y, int width, int height) {
    }

    /**
     * @param box     where the strip is drawn, in the current drawing space
     * @param scissor the same rectangle in window pixels. Not the same thing when a
     *                transform is in effect: {@code enableScissor} takes window
     *                coordinates and is not transformed, so deriving it from
     *                {@code box} inside a scaled element would clip in the wrong
     *                place — the strip would lose its text or paint past its ends.
     */
    static void ticker(Ctx c, Rect box, Rect scissor) {
        List<String> items = c.state.getTicker();
        if (items.isEmpty() || !c.draw || box.width <= 0 || box.height <= 0) {
            return;
        }
        String joined = String.join("     ◆     ", items) + "     ◆     ";
        int strip = c.font.getWidth(joined) + box.width;
        int startX = box.x + box.width - Math.round(c.tickerScroll * strip);

        c.ctx.fill(box.x, box.y, box.x + box.width, box.y + box.height,
                Prim.scaled(0xE00A0E14, c.alpha));
        c.ctx.fill(box.x, box.y, box.x + box.width, box.y + 1,
                Prim.scaled(c.palette.accent, 0.8f * c.alpha));

        // Scissored so the loop cannot paint outside the strip, and drawn twice so it
        // wraps without a gap.
        c.ctx.enableScissor(scissor.x, scissor.y,
                scissor.x + scissor.width, scissor.y + scissor.height);
        try {
            int textY = box.y + Math.max(1, (box.height - c.font.fontHeight) / 2);
            TextFx.outlined(c.ctx, c.font, joined, startX, textY,
                    c.palette.ink, Prim.OUTLINE);
            TextFx.outlined(c.ctx, c.font, joined, startX - strip, textY,
                    c.palette.ink, Prim.OUTLINE);
        } finally {
            c.ctx.disableScissor();
        }
    }

    // =========================================================================
    // announcement — centre card
    // =========================================================================

    static void announcement(Ctx c, int centreY) {
        BroadcastState.Announcement show = c.state.getAnnouncement();
        if (!show.visible || show.title.isEmpty() || !c.draw) {
            return;
        }
        float enter = c.announcementEnter;
        int centreX = c.screenW / 2;
        String title = c.palette.text(show.title);
        String subtitle = c.palette.text(show.subtitle);

        // A scrim behind the text so a headline is readable over any world.
        c.ctx.fillGradient(0, centreY - 14, c.screenW, centreY + 14,
                Prim.scaled(0x00000000, c.alpha), Prim.scaled(0xC8000000, c.alpha));
        c.ctx.fill(0, centreY + 14, c.screenW, centreY + 52, Prim.scaled(0xC8000000, c.alpha));
        c.ctx.fillGradient(0, centreY + 52, c.screenW, centreY + 80,
                Prim.scaled(0xC8000000, c.alpha), Prim.scaled(0x00000000, c.alpha));

        HudScale.push(c.ctx, 3.0f);
        try {
            TextFx.outlinedCentre(c.ctx, c.font, title,
                    Math.round(centreX / 3f), Math.round(centreY / 3f),
                    Prim.mix(c.palette.accent, 0xFFFFFFFF, enter), Prim.OUTLINE);
        } finally {
            HudScale.pop(c.ctx);
        }

        // Rules that grow outward from the title, which is what makes an announcement
        // read as designed rather than as typed.
        int ruleWidth = Math.round(Anim.lerp(0f, 190f, enter));
        c.ctx.fill(centreX - ruleWidth, centreY + 36, centreX - 22, centreY + 37,
                Prim.scaled(c.palette.accent, enter));
        c.ctx.fill(centreX + 22, centreY + 37 - 1, centreX + ruleWidth, centreY + 37,
                Prim.scaled(c.palette.accent, enter));

        if (!subtitle.isEmpty()) {
            TextFx.outlinedCentre(c.ctx, c.font, subtitle, centreX, centreY + 46,
                    Prim.scaled(c.palette.accent, enter), Prim.OUTLINE);
        }
    }

    // =========================================================================
    // text — one line, literal or bound to the match state
    // =========================================================================

    static int text(Ctx c, int x, int y) {
        Layout.Element element = c.element;
        String value = element == null ? "" : element.binding == null || element.binding.isEmpty()
                ? element.text
                : Bindings.resolve(HudState.raw(), element.binding);
        value = c.palette.text(value == null ? "" : value);
        if (value.isEmpty()) {
            // Nothing to say: an absent value hides the element rather than parking a
            // stray label on air.
            return 0;
        }

        int height = c.font.fontHeight + 2;
        if (c.draw) {
            if (c.boxW > 0) {
                TextFx.outlinedCentre(c.ctx, c.font, value, x + c.boxW / 2, y,
                        c.palette.ink, Prim.OUTLINE);
            } else {
                TextFx.outlined(c.ctx, c.font, value, x, y, c.palette.ink, Prim.OUTLINE);
            }
        }
        return height;
    }

    // =========================================================================
    // frame — safe-area corner brackets, drawn in screen space
    // =========================================================================

    /**
     * @param inset how far in from the viewport edge the brackets sit, in screen pixels
     * @param bottomExtra how far the bottom pair rides above the viewport edge, so the
     *                    frame reads as a complete rectangle whose bottom edge is the
     *                    ticker bar
     */
    static void frame(Ctx c, int inset, int bottomExtra) {
        if (!c.draw) {
            return;
        }
        int length = 22;
        int colour = Prim.scaled(c.palette.accent, 0.35f * c.alpha);
        int w = c.screenW;
        int h = c.screenH;
        int bottom = h - inset - bottomExtra;

        // Drawn unscaled, in screen space: it frames the viewport, not a panel.
        c.ctx.fill(inset, inset, inset + length, inset + 1, colour);
        c.ctx.fill(inset, inset, inset + 1, inset + length, colour);
        c.ctx.fill(w - inset - length, inset, w - inset, inset + 1, colour);
        c.ctx.fill(w - inset - 1, inset, w - inset, inset + length, colour);
        c.ctx.fill(inset, bottom - 1, inset + length, bottom, colour);
        c.ctx.fill(inset, bottom - length, inset + 1, bottom, colour);
        c.ctx.fill(w - inset - length, bottom - 1, w - inset, bottom, colour);
        c.ctx.fill(w - inset - 1, bottom - length, w - inset, bottom, colour);
    }
}
