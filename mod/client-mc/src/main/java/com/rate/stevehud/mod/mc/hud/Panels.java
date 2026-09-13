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
    static final int STAT_COMPARE_W = ElementMetrics.referenceWidth(Layout.TYPE_STAT_COMPARE);
    static final int LEADER_BOARD_W = ElementMetrics.referenceWidth(Layout.TYPE_LEADER_BOARD);
    static final int SERIES_CHART_W = ElementMetrics.referenceWidth(Layout.TYPE_SERIES_CHART);
    static final int KPI_TILES_W = ElementMetrics.referenceWidth(Layout.TYPE_KPI_TILES);
    static final int ROSTER_CARD_W = ElementMetrics.referenceWidth(Layout.TYPE_ROSTER_CARD);
    static final int SERIES_SCORE_W = ElementMetrics.referenceWidth(Layout.TYPE_SERIES_SCORE);
    static final int TIMELINE_W = ElementMetrics.referenceWidth(Layout.TYPE_TIMELINE);
    static final int HEAD_TO_HEAD_W = ElementMetrics.referenceWidth(Layout.TYPE_HEAD_TO_HEAD);
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
    // data boards — one chrome, eight shapes
    // =========================================================================
    //
    // Every board reads the same thing out of the match state: a named table of
    // labelled metrics (BroadcastState.Board). The element's "board" option names
    // the table, and an element that names nothing, or names a table that has no
    // rows, is left off air rather than drawn as an empty frame — that is what
    // lets a package ship with every panel configured and only the fed ones show.
    //
    // These are the compact in-game forms. The browser overlay draws the same
    // boards richer (gradients, animated bars, real curves); here they are built
    // only from fill/fillGradient/drawText, which are the calls that have not
    // moved between 1.21.1 and 1.21.11.

    /**
     * The panel body every data board sits in.
     *
     * <p>Kept here rather than in {@link MatchHud} so the primitives a board uses
     * for its background and the ones it uses for its rows come from one place.
     */
    static void panelChrome(Ctx c, int x, int y, int width, int height) {
        if (!c.draw) {
            return;
        }
        Prim.panel(c.ctx, x, y, width, height, c.palette.accent, c.alpha);
    }

    /** A string option, or the fallback when it is absent or blank. */
    private static String option(Ctx c, String key, String fallback) {
        Layout.Element element = c.element;
        if (element == null || element.options == null) {
            return fallback;
        }
        String value = element.options.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** A numeric option, or the fallback when it is absent or unparsable. */
    private static int optionInt(Ctx c, String key, int fallback) {
        try {
            return Integer.parseInt(option(c, key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The table this element draws, or null when it names none. */
    private static BroadcastState.Board boardOf(Ctx c) {
        String key = option(c, "board", "");
        return key.isEmpty() ? null : c.state.board(key);
    }

    /**
     * The panel body and title bar every board shares.
     *
     * <p>Returns the y the content starts at, so a painter only has to draw rows.
     * Drawn in one pass in both modes: the header is a fixed height, so measuring
     * and painting cannot disagree about it.
     */
    private static int boardHeading(Ctx c, String title, String right, int x, int y, int width) {
        int innerX = x + PAD + 3;              // clear of the accent spine
        int innerWidth = width - (PAD + 3) - PAD;
        int cursor = y + PAD;

        if (c.draw) {
            if (!title.isEmpty()) {
                TextFx.tracked(c.ctx, c.font, title, innerX, cursor, innerWidth, c.palette.ink,
                        Prim.OUTLINE, 1);
            }
            if (!right.isEmpty()) {
                TextFx.trackedRight(c.ctx, c.font, right, innerX + innerWidth, cursor,
                        innerWidth / 2, c.palette.inkDim, Prim.OUTLINE, 1);
            }
            c.ctx.fill(innerX, cursor + 11, innerX + innerWidth, cursor + 12,
                    Prim.scaled(c.palette.border, 0.7f));
        }
        return cursor + 15;
    }

    /** The one-line value a metric shows: its formatted text when it has one. */
    private static String metricText(BroadcastState.Metric metric) {
        if (metric == null) {
            return "";
        }
        return metric.display == null || metric.display.isEmpty()
                ? formatValue(metric.value) : metric.display;
    }

    private static String formatValue(double value) {
        if (Math.abs(value) >= 1000) {
            return String.format("%,.0f", value);
        }
        return Math.abs(value - Math.rint(value)) < 0.05
                ? String.valueOf(Math.round(value)) : String.format("%.1f", value);
    }

    static int statCompare(Ctx c, int x, int y) {
        BroadcastState.Board board = boardOf(c);
        if (board == null || board.rows.isEmpty()) {
            return 0;
        }
        int width = c.boxW > 0 ? c.boxW : STAT_COMPARE_W;
        int innerX = x + PAD + 3;
        int innerWidth = width - (PAD + 3) - PAD;
        int cursor = boardHeading(c, option(c, "title", board.title), board.unit, x, y, width);

        List<BroadcastState.Side> sides = c.state.getSides();
        BroadcastState.Side home = sides.isEmpty() ? null : sides.get(0);
        BroadcastState.Side away = sides.size() > 1 ? sides.get(1) : null;
        int maxRows = optionInt(c, "maxRows", 6);
        boolean showLabel = !"false".equals(option(c, "showLabel", "true"));

        double max = optionInt(c, "max", 0);
        if (max <= 0) {
            for (BroadcastState.Metric metric : board.rows) {
                max = Math.max(max, metric.value);
            }
        }
        if (max <= 0) {
            max = 1;
        }

        // Rows are paired by key: the home row and the away row of one metric
        // share a key, which is what makes them two ends of one bar.
        java.util.LinkedHashMap<String, BroadcastState.Metric[]> paired = new java.util.LinkedHashMap<>();
        for (BroadcastState.Metric metric : board.rows) {
            String key = metric.key.isEmpty() ? metric.label : metric.key;
            BroadcastState.Metric[] pair = paired.computeIfAbsent(key, k -> new BroadcastState.Metric[2]);
            if (home != null && home.id.equals(metric.side)) {
                pair[0] = metric;
            } else if (away != null && away.id.equals(metric.side)) {
                pair[1] = metric;
            } else if (pair[0] == null) {
                pair[0] = metric;
            } else {
                pair[1] = metric;
            }
        }

        int colourHome = home == null ? c.palette.home : Colors.parse(home.color, c.palette.home);
        int colourAway = away == null ? c.palette.away : Colors.parse(away.color, c.palette.away);
        int drawn = 0;
        for (BroadcastState.Metric[] pair : paired.values()) {
            if (drawn >= maxRows) {
                break;
            }
            drawn++;
            int valueWidth = 26;
            int trackX = innerX + valueWidth + 3;
            int trackWidth = innerWidth - (valueWidth + 3) * 2;
            if (trackWidth < 8) {
                trackWidth = 8;
            }
            int middle = trackX + trackWidth / 2;
            int barY = cursor + 3;

            if (c.draw) {
                float hShare = pair[0] == null ? 0f : (float) Math.min(1.0, pair[0].value / max);
                float aShare = pair[1] == null ? 0f : (float) Math.min(1.0, pair[1].value / max);
                c.ctx.fill(trackX, barY, trackX + trackWidth, barY + 6,
                        Prim.scaled(Prim.TRACK, c.alpha));
                int hFill = Math.round((trackWidth / 2f) * hShare);
                if (hFill > 0) {
                    c.ctx.fill(middle - hFill, barY, middle, barY + 6,
                            Prim.scaled(colourHome, c.alpha));
                }
                int aFill = Math.round((trackWidth / 2f) * aShare);
                if (aFill > 0) {
                    c.ctx.fill(middle, barY, middle + aFill, barY + 6,
                            Prim.scaled(colourAway, c.alpha));
                }
                c.ctx.fill(middle, barY - 1, middle + 1, barY + 7,
                        Prim.scaled(c.palette.inkDim, 0.7f));

                TextFx.trackedRight(c.ctx, c.font, metricText(pair[0]), innerX + valueWidth,
                        cursor, valueWidth, c.palette.ink, Prim.OUTLINE, 0);
                TextFx.tracked(c.ctx, c.font, metricText(pair[1]),
                        innerX + innerWidth - valueWidth, cursor, valueWidth,
                        c.palette.ink, Prim.OUTLINE, 0);
            }
            cursor += 11;

            if (showLabel && pair[0] != null) {
                String label = pair[0].label.isEmpty() ? pair[0].key : pair[0].label;
                if (c.draw) {
                    TextFx.tracked(c.ctx, c.font, c.palette.text(label), trackX, cursor,
                            trackWidth, c.palette.inkDim, Prim.OUTLINE, 1);
                }
                cursor += 9;
            }
            cursor += 2;
        }

        if (drawn == 0) {
            return 0;
        }
        return cursor + PAD - y;
    }

    static int leaderBoard(Ctx c, int x, int y) {
        BroadcastState.Board board = boardOf(c);
        if (board == null || board.rows.isEmpty()) {
            return 0;
        }
        int width = c.boxW > 0 ? c.boxW : LEADER_BOARD_W;
        int innerX = x + PAD + 3;
        int innerWidth = width - (PAD + 3) - PAD;
        int cursor = boardHeading(c, option(c, "title", board.title), board.unit, x, y, width);

        List<BroadcastState.Metric> rows = new java.util.ArrayList<>(board.rows);
        String sort = option(c, "sort", "desc");
        if (!"none".equals(sort)) {
            rows.sort((a, b) -> "asc".equals(sort)
                    ? Double.compare(a.value, b.value) : Double.compare(b.value, a.value));
        }
        int limit = optionInt(c, "maxRows", 5);
        boolean showRank = !"false".equals(option(c, "showRank", "true"));
        int highlight = optionInt(c, "highlight", 3);

        int drawn = 0;
        for (BroadcastState.Metric metric : rows) {
            if (drawn >= limit) {
                break;
            }
            int rankWidth = showRank ? 10 : 0;
            int valueWidth = 26;
            int nameX = innerX + rankWidth;
            int nameWidth = innerWidth - rankWidth - valueWidth - 4;
            int colour = highlight > 0 && drawn < highlight ? c.palette.accent : c.palette.ink;

            if (c.draw) {
                if (showRank) {
                    TextFx.tracked(c.ctx, c.font, Integer.toString(drawn + 1), innerX, cursor,
                            rankWidth, colour, Prim.OUTLINE, 0);
                }
                String label = metric.label.isEmpty() ? metric.key : metric.label;
                TextFx.tracked(c.ctx, c.font, c.palette.text(label), nameX, cursor,
                        Math.max(4, nameWidth), colour, Prim.OUTLINE, 0);
                TextFx.trackedRight(c.ctx, c.font, metricText(metric),
                        innerX + innerWidth, cursor, valueWidth, c.palette.ink, Prim.OUTLINE, 0);
            }
            cursor += c.font.fontHeight + 1;
            if (metric.sub != null && !metric.sub.isEmpty()) {
                if (c.draw) {
                    TextFx.tracked(c.ctx, c.font, c.palette.text(metric.sub), nameX, cursor,
                            Math.max(4, nameWidth), c.palette.inkDim, Prim.OUTLINE, 0);
                }
                cursor += c.font.fontHeight;
            }
            drawn++;
        }
        return cursor + PAD - y;
    }

    static int kpiTiles(Ctx c, int x, int y) {
        BroadcastState.Board board = boardOf(c);
        if (board == null || board.rows.isEmpty()) {
            return 0;
        }
        int width = c.boxW > 0 ? c.boxW : KPI_TILES_W;
        int innerX = x + PAD + 3;
        int innerWidth = width - (PAD + 3) - PAD;
        int cursor = boardHeading(c, option(c, "title", board.title), board.unit, x, y, width);

        int cols = Math.max(1, Math.min(6, optionInt(c, "cols", 3)));
        int limit = optionInt(c, "maxRows", 6);
        int gap = 3;
        int cellWidth = Math.max(12, (innerWidth - gap * (cols - 1)) / cols);
        int cellHeight = c.font.fontHeight * 2 + 6;

        for (int i = 0; i < board.rows.size() && i < limit; i++) {
            BroadcastState.Metric metric = board.rows.get(i);
            int col = i % cols;
            int row = i / cols;
            int cellX = innerX + col * (cellWidth + gap);
            int cellY = cursor + row * (cellHeight + gap);

            if (c.draw) {
                c.ctx.fill(cellX, cellY, cellX + cellWidth, cellY + cellHeight,
                        Prim.scaled(Prim.TRACK, c.alpha * 0.6f));
                String label = metric.label.isEmpty() ? metric.key : metric.label;
                TextFx.tracked(c.ctx, c.font,
                        TextFx.fit(c.font, c.palette.text(label), cellWidth - 4, 1),
                        cellX + 2, cellY + 2, cellWidth - 4, c.palette.inkDim, Prim.OUTLINE, 1);
                TextFx.tracked(c.ctx, c.font,
                        TextFx.fit(c.font, metricText(metric), cellWidth - 4, 0),
                        cellX + 2, cellY + 2 + c.font.fontHeight + 1,
                        cellWidth - 4, c.palette.ink, Prim.OUTLINE, 0);
            }
        }
        int rows = (Math.min(board.rows.size(), limit) + cols - 1) / cols;
        return cursor + rows * (cellHeight + gap) - gap + PAD - y;
    }

    static int rosterCard(Ctx c, int x, int y) {
        List<BroadcastState.Side> sides = c.state.getSides();
        String wanted = option(c, "side", "");
        BroadcastState.Side side = c.state.side(wanted);
        if (side == null && !sides.isEmpty()) {
            side = sides.get(0);
        }
        if (side == null || side.competitors.isEmpty()) {
            return 0;
        }
        int width = c.boxW > 0 ? c.boxW : ROSTER_CARD_W;
        int innerX = x + PAD + 3;
        int innerWidth = width - (PAD + 3) - PAD;
        int colour = Colors.parse(side.color, c.palette.home);
        String code = option(c, "title", side.shortName.isEmpty() ? side.name : side.shortName);
        String name = option(c, "subtitle", side.name);
        int cursor = boardHeading(c, code, "", x, y, width);

        if (c.draw && !name.isEmpty()) {
            TextFx.tracked(c.ctx, c.font, c.palette.text(name), innerX, cursor, innerWidth,
                    c.palette.ink, Prim.OUTLINE, 0);
            cursor += c.font.fontHeight + 2;
        }

        int limit = optionInt(c, "maxRows", 5);
        for (int i = 0; i < side.competitors.size() && i < limit; i++) {
            BroadcastState.Competitor competitor = side.competitors.get(i);
            if (c.draw) {
                c.ctx.fill(innerX, cursor + 1, innerX + 2, cursor + c.font.fontHeight - 1,
                        Prim.scaled(colour, c.alpha));
                String line = competitor.number == null || competitor.number.isEmpty()
                        ? competitor.name : competitor.number + "  " + competitor.name;
                TextFx.tracked(c.ctx, c.font, c.palette.text(line), innerX + 5, cursor,
                        innerWidth - 5, c.palette.ink, Prim.OUTLINE, 0);
            }
            cursor += c.font.fontHeight + 2;
        }
        return cursor + PAD - y;
    }

    static int seriesScore(Ctx c, int x, int y) {
        BroadcastState.Board board = boardOf(c);
        if (board == null || board.rows.isEmpty()) {
            return 0;
        }
        int width = c.boxW > 0 ? c.boxW : SERIES_SCORE_W;
        int innerX = x + PAD + 3;
        int innerWidth = width - (PAD + 3) - PAD;
        int cursor = boardHeading(c, option(c, "title", board.title), board.unit, x, y, width);

        int limit = optionInt(c, "maxRows", 5);
        int indexWidth = 16;
        int scoreWidth = 30;
        for (int i = 0; i < board.rows.size() && i < limit; i++) {
            BroadcastState.Metric metric = board.rows.get(i);
            boolean live = "live".equalsIgnoreCase(metric.state);
            if (c.draw) {
                if (live) {
                    c.ctx.fill(innerX - 3, cursor - 1, innerX + innerWidth + 3,
                            cursor + c.font.fontHeight + 2,
                            Prim.scaled(c.palette.accent, c.alpha * 0.16f));
                }
                String index = metric.index.isEmpty() ? "G" + (i + 1) : metric.index;
                TextFx.tracked(c.ctx, c.font, index, innerX, cursor, indexWidth,
                        live ? c.palette.accent : c.palette.inkDim, Prim.OUTLINE, 0);
                TextFx.tracked(c.ctx, c.font, c.palette.text(metric.label),
                        innerX + indexWidth, cursor,
                        innerWidth - indexWidth - scoreWidth - 3, c.palette.ink, Prim.OUTLINE, 0);
                TextFx.trackedRight(c.ctx, c.font, metricText(metric), innerX + innerWidth,
                        cursor, scoreWidth, c.palette.ink, Prim.OUTLINE, 0);
            }
            cursor += c.font.fontHeight + 3;
        }

        // The running tally: this is the number a viewer is actually counting.
        List<BroadcastState.Side> sides = c.state.getSides();
        if (sides.size() >= 2) {
            if (c.draw) {
                c.ctx.fill(innerX, cursor + 1, innerX + innerWidth, cursor + 2,
                        Prim.scaled(c.palette.border, 0.7f));
                TextFx.tracked(c.ctx, c.font,
                        c.palette.text(sides.get(0).shortName.isEmpty()
                                ? sides.get(0).name : sides.get(0).shortName),
                        innerX, cursor + 3, innerWidth / 2 - 8, c.palette.inkDim, Prim.OUTLINE, 1);
                TextFx.tracked(c.ctx, c.font, sides.get(0).score + " : " + sides.get(1).score,
                        innerX + innerWidth / 2 - 12, cursor + 3, 26, c.palette.ink,
                        Prim.OUTLINE, 0);
                TextFx.trackedRight(c.ctx, c.font,
                        c.palette.text(sides.get(1).shortName.isEmpty()
                                ? sides.get(1).name : sides.get(1).shortName),
                        innerX + innerWidth, cursor + 3, innerWidth / 2 - 8,
                        c.palette.inkDim, Prim.OUTLINE, 1);
            }
            cursor += c.font.fontHeight + 3;
        }
        return cursor + PAD - y;
    }

    static int timeline(Ctx c, int x, int y) {
        BroadcastState.Board board = boardOf(c);
        if (board == null || board.rows.isEmpty()) {
            return 0;
        }
        int width = c.boxW > 0 ? c.boxW : TIMELINE_W;
        int innerX = x + PAD + 3;
        int innerWidth = width - (PAD + 3) - PAD;
        int cursor = boardHeading(c, option(c, "title", board.title), board.unit, x, y, width);

        List<BroadcastState.Metric> rows = new java.util.ArrayList<>(board.rows);
        if (!"asc".equals(option(c, "order", "desc"))) {
            java.util.Collections.reverse(rows);
        }
        int limit = optionInt(c, "maxRows", 5);
        int timeWidth = 30;
        int lineX = innerX + timeWidth + 2;

        for (int i = 0; i < rows.size() && i < limit; i++) {
            BroadcastState.Metric metric = rows.get(i);
            BroadcastState.Side side = metric.side.isEmpty() ? null : c.state.side(metric.side);
            int colour = side == null ? c.palette.accent : Colors.parse(side.color, c.palette.accent);
            if (c.draw) {
                // The rail is drawn per row so it spans exactly the rows that exist;
                // a full-height line would stick out of a two-item timeline.
                c.ctx.fill(lineX, cursor - 1, lineX + 1, cursor + c.font.fontHeight + 2,
                        Prim.scaled(c.palette.border, 0.6f));
                c.ctx.fill(lineX - 1, cursor + 2, lineX + 2, cursor + 5,
                        Prim.scaled(colour, c.alpha));
                TextFx.trackedRight(c.ctx, c.font, metric.time, innerX + timeWidth,
                        cursor, timeWidth, c.palette.inkDim, Prim.OUTLINE, 0);
                String label = metric.label.isEmpty() ? metricText(metric) : metric.label;
                TextFx.tracked(c.ctx, c.font, c.palette.text(label), lineX + 5, cursor,
                        Math.max(4, innerWidth - timeWidth - 7), c.palette.ink, Prim.OUTLINE, 0);
            }
            cursor += c.font.fontHeight + 3;
        }
        return cursor + PAD - y;
    }

    static int headToHead(Ctx c, int x, int y) {
        List<BroadcastState.Side> sides = c.state.getSides();
        if (sides.size() < 2) {
            return 0;
        }
        int width = c.boxW > 0 ? c.boxW : HEAD_TO_HEAD_W;
        int innerX = x + PAD + 3;
        int innerWidth = width - (PAD + 3) - PAD;
        int cursor = y + PAD;
        int column = (innerWidth - 30) / 2;
        int rightColumn = innerX + innerWidth - column;

        for (int i = 0; i < 2; i++) {
            BroadcastState.Side side = sides.get(i);
            int colour = Colors.parse(side.color, i == 0 ? c.palette.home : c.palette.away);
            String code = side.shortName.isEmpty() ? side.name : side.shortName;
            int at = i == 0 ? innerX : rightColumn;
            if (c.draw) {
                c.ctx.fill(at, cursor, at + column, cursor + c.font.fontHeight + 10,
                        Prim.scaled(colour, c.alpha * 0.14f));
                TextFx.tracked(c.ctx, c.font, c.palette.text(code), at + 3, cursor + 1,
                        column - 6, colour, Prim.OUTLINE, 1);
                TextFx.tracked(c.ctx, c.font,
                        TextFx.fit(c.font, c.palette.text(side.name), column - 6, 0),
                        at + 3, cursor + c.font.fontHeight + 4, column - 6,
                        c.palette.ink, Prim.OUTLINE, 0);
            }
        }
        if (c.draw) {
            int middle = innerX + innerWidth / 2;
            TextFx.outlinedCentre(c.ctx, c.font, option(c, "vs", "VS"),
                    middle, cursor + c.font.fontHeight + 1, c.palette.accent, Prim.OUTLINE);
        }
        cursor += c.font.fontHeight * 2 + 12;

        String subtitle = option(c, "subtitle", c.state.getEvent().stage);
        if (!subtitle.isEmpty()) {
            if (c.draw) {
                TextFx.tracked(c.ctx, c.font, c.palette.text(subtitle),
                        innerX + innerWidth / 2 - innerWidth / 4, cursor, innerWidth / 2,
                        c.palette.inkDim, Prim.OUTLINE, 1);
            }
            cursor += c.font.fontHeight + 2;
        }
        return cursor + PAD - y;
    }

    static int seriesChart(Ctx c, int x, int y) {
        List<BroadcastState.Side> sides = c.state.getSides();
        if (sides.isEmpty()) {
            return 0;
        }
        int width = c.boxW > 0 ? c.boxW : SERIES_CHART_W;
        int innerX = x + PAD + 3;
        int innerWidth = width - (PAD + 3) - PAD;
        int cursor = boardHeading(c, option(c, "title", ""), "", x, y, width);

        int plotHeight = optionInt(c, "height", 30);
        if (plotHeight < 12) {
            plotHeight = 12;
        }

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < 2 && i < sides.size(); i++) {
            for (Double sample : sides.get(i).series) {
                if (sample == null || !Double.isFinite(sample)) {
                    continue;
                }
                min = Math.min(min, sample);
                max = Math.max(max, sample);
            }
        }
        if (min > max) {
            return 0;   // no series on either side: nothing to plot
        }
        if (max - min < 1e-6) {
            max = min + 1;
        }

        if (c.draw) {
            c.ctx.fill(innerX, cursor, innerX + innerWidth, cursor + plotHeight,
                    Prim.scaled(Prim.TRACK, c.alpha * 0.5f));
        }
        for (int i = 0; i < 2 && i < sides.size(); i++) {
            List<Double> series = sides.get(i).series;
            if (series.size() < 2) {
                continue;
            }
            int colour = Colors.parse(sides.get(i).color,
                    i == 0 ? c.palette.home : c.palette.away);
            int steps = Math.min(series.size(), innerWidth);
            int previousX = -1;
            int previousY = -1;
            for (int step = 0; step < steps; step++) {
                int at = (int) Math.round(step * (series.size() - 1) / (double) (steps - 1));
                Double sample = series.get(at);
                if (sample == null || !Double.isFinite(sample)) {
                    continue;
                }
                int px = innerX + step;
                int py = cursor + plotHeight - 1
                        - (int) Math.round((sample - min) / (max - min) * (plotHeight - 2));
                if (c.draw) {
                    if (previousX >= 0) {
                        // A one-pixel segment per column: a real line rasteriser would
                        // be nicer and would also be the only place in this file that
                        // needs one.
                        int from = Math.min(previousY, py);
                        int to = Math.max(previousY, py);
                        c.ctx.fill(px, from, px + 1, to + 1, Prim.scaled(colour, c.alpha));
                    } else {
                        c.ctx.fill(px, py, px + 1, py + 1, Prim.scaled(colour, c.alpha));
                    }
                }
                previousX = px;
                previousY = py;
            }
        }
        cursor += plotHeight + 3;

        if (c.draw) {
            for (int i = 0; i < 2 && i < sides.size(); i++) {
                BroadcastState.Side side = sides.get(i);
                int colour = Colors.parse(side.color, i == 0 ? c.palette.home : c.palette.away);
                int at = i == 0 ? innerX : innerX + innerWidth / 2;
                c.ctx.fill(at, cursor + 2, at + 6, cursor + 4, Prim.scaled(colour, c.alpha));
                String label = side.shortName.isEmpty() ? side.name : side.shortName;
                List<Double> series = side.series;
                if (!series.isEmpty()) {
                    label += " " + formatValue(series.get(series.size() - 1));
                }
                TextFx.tracked(c.ctx, c.font, c.palette.text(label), at + 9, cursor,
                        innerWidth / 2 - 12, c.palette.inkDim, Prim.OUTLINE, 1);
            }
        }
        cursor += c.font.fontHeight + 3;
        return cursor + PAD - y;
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
