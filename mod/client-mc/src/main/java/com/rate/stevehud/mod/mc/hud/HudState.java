package com.rate.stevehud.mod.mc.hud;

import com.rate.stevehud.mod.client.anim.Anim;
import com.rate.stevehud.protocol.EnvelopeCodec;
import com.rate.stevehud.protocol.model.BroadcastState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The client's copy of the broadcast state, plus the animations that react to it.
 *
 * <p>The state itself belongs to the server: it arrives over the channel and this
 * class only mirrors it, so every screen in the match shows the same package. What
 * this class adds is the part that is genuinely local — noticing <em>changes</em>
 * and turning them into motion. A score going 1 to 2 is a data change; the number
 * bulging and settling back is the animation, and it can only be triggered by
 * comparing the new state against the old one.
 *
 * <p>Deliberately the one place that holds animation state, so the panels stay
 * pure functions of "a state plus a set of animation values".
 */
public final class HudState {

    private static final Logger LOGGER = LoggerFactory.getLogger("SteveHUD");
    private static final EnvelopeCodec CODEC = new EnvelopeCodec();

    private static final long POP_MS = 420L;
    private static final long ENTER_MS = 650L;
    private static final long TICKER_LOOP_MS = 18_000L;
    private static final float POP_AMPLITUDE = 0.30f;
    /** Sentinel in {@link #popStartedAt}: the side is at rest and no pop is running. */
    private static final long AT_REST = -1L;

    private static volatile BroadcastState state = new BroadcastState();

    /**
     * The last state exactly as it arrived.
     *
     * <p>Kept alongside the typed mirror because a layout element can bind to any
     * path in the document, including one this client has no field for. Parsing the
     * message twice is cheaper than making every new binding a code change.
     */
    private static volatile JsonElement raw = new JsonObject();

    private static long[] popStartedAt = {AT_REST, AT_REST};
    private static List<Integer> lastScores = List.of();
    private static boolean lastLowerThirdVisible;
    private static String lastLowerThirdSide = "";
    private static String lastAnnouncementTitle = "";

    private static Anim.Tween intro = Anim.Tween.settled(1f);
    private static Anim.Tween lowerThird = Anim.Tween.settled(0f);
    private static Anim.Tween announcement = Anim.Tween.settled(0f);

    private HudState() {
    }

    public static BroadcastState state() {
        return state;
    }

    /** The last state as raw JSON, for layout elements that bind to a path. */
    public static JsonElement raw() {
        return raw;
    }

    /**
     * Replaces the mirrored state, starting animations for whatever changed.
     *
     * <p>A malformed document leaves the previous state up: during a broadcast the
     * last good scoreboard is far more useful than a blank screen and an error.
     */
    public static void apply(String json) {
        BroadcastState next;
        try {
            next = CODEC.decode(json, BroadcastState.class);
        } catch (RuntimeException e) {
            LOGGER.warn("Ignoring unreadable broadcast state: {}", e.getMessage());
            return;
        }
        if (next == null) {
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            parsed = new JsonObject();
        }
        onChanged(next);
        state = next;
        raw = parsed;
    }

    private static void onChanged(BroadcastState next) {
        List<BroadcastState.Side> sides = next.getSides();

        // Grow the pop bookkeeping if a match has more sides than when we started.
        if (popStartedAt.length < sides.size()) {
            long[] grown = new long[sides.size()];
            Arrays.fill(grown, AT_REST);
            System.arraycopy(popStartedAt, 0, grown, 0, popStartedAt.length);
            popStartedAt = grown;
        }

        List<Integer> scores = new ArrayList<>(sides.size());
        for (BroadcastState.Side side : sides) {
            scores.add(side.score);
        }
        // Only compare once there is a previous reading to compare against; otherwise
        // joining mid-match would pop every score at once.
        if (!lastScores.isEmpty()) {
            for (int i = 0; i < Math.min(lastScores.size(), scores.size()); i++) {
                if (!lastScores.get(i).equals(scores.get(i))) {
                    popStartedAt[i] = Anim.nowMs();
                }
            }
        }
        lastScores = scores;

        BroadcastState.LowerThird lower = next.getLowerThird();
        boolean lowerVisible = lower.visible && next.side(lower.side) != null;
        String lowerSide = lower.side == null ? "" : lower.side;
        if (lowerVisible != lastLowerThirdVisible || !lowerSide.equals(lastLowerThirdSide)) {
            lowerThird = Anim.Tween.of(lowerThird.at(Anim.nowMs()), lowerVisible ? 1f : 0f,
                    ENTER_MS, Anim.Tween.Curve.OUT_CUBIC);
            lastLowerThirdVisible = lowerVisible;
            lastLowerThirdSide = lowerSide;
        }

        String title = next.getAnnouncement().title;
        if (!title.equals(lastAnnouncementTitle)) {
            announcement = Anim.Tween.of(0f, title.isEmpty() ? 0f : 1f,
                    ENTER_MS, Anim.Tween.Curve.OUT_CUBIC);
            lastAnnouncementTitle = title;
        }
    }

    /** Called on world join, so the package animates in each session. */
    public static void onWorldJoin() {
        intro = Anim.Tween.of(0f, 1f, ENTER_MS, Anim.Tween.Curve.OUT_QUINT);
        popStartedAt = new long[]{AT_REST, AT_REST};
        lastScores = List.of();
        lowerThird = Anim.Tween.settled(0f);
        lastLowerThirdVisible = false;
        lastLowerThirdSide = "";
        announcement = Anim.Tween.settled(0f);
        lastAnnouncementTitle = "";
        state = new BroadcastState();
        raw = new JsonObject();
    }

    // ---- values the panels draw with ----------------------------------------

    /**
     * Per-side scale multipliers for the score pop. 1.0 means at rest.
     *
     * <p>A half sine: it rises to {@code 1 + POP_AMPLITUDE} and returns to exactly
     * 1, so a number that changes size always settles back to its resting size and
     * cannot drift over repeated scores.
     */
    public static float[] scorePops() {
        long now = Anim.nowMs();
        float[] values = new float[popStartedAt.length];
        for (int i = 0; i < values.length; i++) {
            if (popStartedAt[i] == AT_REST) {
                values[i] = 1f;
                continue;
            }
            float t = (now - popStartedAt[i]) / (float) POP_MS;
            if (t >= 1f) {
                popStartedAt[i] = AT_REST;
                values[i] = 1f;
                continue;
            }
            values[i] = 1f + POP_AMPLITUDE * (float) Math.sin(Math.PI * t);
        }
        return values;
    }

    public static float enter() {
        return intro.at(Anim.nowMs());
    }

    public static float lowerThirdSlide() {
        return lowerThird.at(Anim.nowMs());
    }

    public static float announcementEnter() {
        return announcement.at(Anim.nowMs());
    }

    public static float tickerScroll() {
        return Anim.phase(Anim.nowMs(), TICKER_LOOP_MS);
    }
}
