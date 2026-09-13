package com.rate.stevehud.mod.client.anim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimTest {

    private static final float EPSILON = 1.0e-4f;

    @Test
    void easingsStartAtZeroAndEndAtOne() {
        for (var curve : Anim.Tween.Curve.values()) {
            Anim.Tween tween = new Anim.Tween(0f, 1f, 0L, 100L, curve);
            assertEquals(0f, tween.at(0L), EPSILON, curve + " should start at 0");
            // IN_OUT_SINE and OUT_BACK both reach exactly 1 at the end.
            assertEquals(1f, tween.at(100L), EPSILON, curve + " should end at 1");
            assertEquals(1f, tween.at(150L), EPSILON, curve + " should hold at 1 past the end");
        }
    }

    @Test
    void easingsAreMonotonicApartFromTheOvershootingOne() {
        for (var curve : Anim.Tween.Curve.values()) {
            if (curve == Anim.Tween.Curve.OUT_BACK) {
                continue; // overshoot is the point of this one
            }
            float previous = -1f;
            for (int step = 0; step <= 20; step++) {
                float value = new Anim.Tween(0f, 1f, 0L, 100L, curve).at(step * 5L);
                assertTrue(value >= previous - EPSILON,
                        curve + " went backwards at step " + step + ": " + previous + " -> " + value);
                previous = value;
            }
        }
    }

    @Test
    void outBackOvershootsThenSettlesExactlyOnTarget() {
        Anim.Tween tween = new Anim.Tween(0f, 1f, 0L, 100L, Anim.Tween.Curve.OUT_BACK);

        float peak = 0f;
        for (int step = 0; step <= 100; step++) {
            peak = Math.max(peak, tween.at(step));
        }

        assertTrue(peak > 1f, "an overshoot curve should exceed the target, peaked at " + peak);
        assertEquals(1f, tween.at(100L), EPSILON, "but it must still land exactly on it");
    }

    @Test
    void tweenNeverPassesThroughTheTargetOnItsWay() {
        // The bug class this guards: a curve that reports the end value early, so a
        // pop animation becomes a flicker with no visible motion.
        Anim.Tween tween = new Anim.Tween(0f, 1f, 1_000L, 400L, Anim.Tween.Curve.OUT_CUBIC);

        assertEquals(0f, tween.at(1_000L), EPSILON, "at the start");
        assertTrue(tween.at(1_200L) > 0f && tween.at(1_200L) < 1f, "half way is strictly between");
        assertFalse(tween.finished(1_200L), "not finished half way");
        assertTrue(tween.finished(1_400L), "finished at the end");
    }

    @Test
    void tweenBeforeItsStartTimeReadsAsTheStartValue() {
        Anim.Tween tween = new Anim.Tween(0f, 1f, 5_000L, 100L, Anim.Tween.Curve.LINEAR);

        assertEquals(0f, tween.at(0L), EPSILON);
    }

    @Test
    void zeroDurationTweenIsImmediatelyAtItsTarget() {
        Anim.Tween tween = new Anim.Tween(0f, 7f, 1_000L, 0L, Anim.Tween.Curve.OUT_BACK);

        assertEquals(7f, tween.at(1_000L), EPSILON);
        assertTrue(tween.finished(1_000L));
    }

    @Test
    void settledTweenNeedsNoTiming() {
        assertEquals(3.5f, Anim.Tween.settled(3.5f).now(), EPSILON);
    }

    @Test
    void pulseRisesAndFallsTwicePerPeriod() {
        long period = 1_000L;

        assertEquals(0f, Anim.pulse(0L, period), EPSILON);
        assertEquals(1f, Anim.pulse(period / 2, period), EPSILON);
        assertEquals(0f, Anim.pulse(period, period), EPSILON);
    }

    @Test
    void phaseSweepsZeroToOneOnce() {
        assertEquals(0f, Anim.phase(0L, 1_000L), EPSILON);
        assertEquals(0.5f, Anim.phase(500L, 1_000L), EPSILON);
        assertEquals(0.9f, Anim.phase(900L, 1_000L), EPSILON);
    }

    @Test
    void zeroPeriodOscillatorsDoNotDivideByZero() {
        assertEquals(0f, Anim.pulse(123L, 0L), EPSILON);
        assertEquals(0f, Anim.phase(123L, 0L), EPSILON);
    }

    @Test
    void lerpColorWalksEveryChannel() {
        int black = 0xFF000000;
        int white = 0xFFFFFFFF;

        assertEquals(black, Anim.lerpColor(black, white, 0f));
        assertEquals(white, Anim.lerpColor(black, white, 1f));
        assertEquals(0xFF808080, Anim.lerpColor(black, white, 0.5f));
    }

    @Test
    void lerpColorRespectsAlphaSoFadesWork() {
        int transparentRed = 0x00FF0000;
        int opaqueRed = 0xFFFF0000;

        assertEquals(transparentRed, Anim.lerpColor(transparentRed, opaqueRed, 0f), "at 0 it is the source");
        assertEquals(opaqueRed, Anim.lerpColor(transparentRed, opaqueRed, 1f), "at 1 it is the target");
        // Half way: alpha interpolates, but the RGB was already equal at both ends,
        // so it must come out exactly unchanged. A fade that tints is a real bug.
        assertEquals(0x80FF0000, Anim.lerpColor(transparentRed, opaqueRed, 0.502f));
    }

    @Test
    void clampHelpersDoNotOverflow() {
        assertEquals(0f, Anim.clamp01(-5f));
        assertEquals(1f, Anim.clamp01(5f));
        assertEquals(2f, Anim.clamp(1f, 2f, 8f));
        assertEquals(8f, Anim.clamp(99f, 2f, 8f));
    }
}
