package com.rate.stevehud.mod.client.anim;

/**
 * Animation primitives: wall-clock timing, easing curves and interpolated values.
 *
 * <p>Lives in the Minecraft-free half of the mod on purpose. Easing math is the
 * part of a HUD most likely to be subtly wrong (off-by-one at the ends, curves
 * that overshoot when they should not, tweens that never quite reach their
 * target), and here it can be tested without launching a game.
 *
 * <p>Time comes from {@link System#nanoTime()} rather than the game's tick
 * counter. That is deliberate: the tick counter's accessor was renamed between
 * 1.21.4 and 1.21.8, and animation smoothness should not depend on tick rate
 * anyway — a HUD that animates at 20 Hz looks broken next to one that animates at
 * frame rate.
 */
public final class Anim {

    private Anim() {
    }

    /** Monotonic milliseconds, for measuring elapsed time. */
    public static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    public static float clamp01(float t) {
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }

    public static float clamp(float t, float min, float max) {
        return t < min ? min : (t > max ? max : t);
    }

    /** Linear interpolation. */
    public static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /** Interpolates between two ARGB colours, channel by channel. */
    public static int lerpColor(int from, int to, float t) {
        float clamped = clamp01(t);
        int a = channel(from, 24, clamped, to);
        int r = channel(from, 16, clamped, to);
        int g = channel(from, 8, clamped, to);
        int b = channel(from, 0, clamped, to);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int from, int shift, float t, int to) {
        int f = (from >>> shift) & 0xFF;
        int o = (to >>> shift) & 0xFF;
        return Math.round(f + (o - f) * t);
    }

    // ---- easing -------------------------------------------------------------

    /** Fast start, gentle stop. The default for anything moving into place. */
    public static float easeOutCubic(float t) {
        float c = clamp01(t);
        float inv = 1f - c;
        return 1f - inv * inv * inv;
    }

    /** Very fast start, long settle. For panels and score pops. */
    public static float easeOutQuint(float t) {
        float c = clamp01(t);
        float inv = 1f - c;
        return 1f - inv * inv * inv * inv * inv;
    }

    /** Overshoots past the target and settles back, for celebratory pops. */
    public static float easeOutBack(float t) {
        float c = clamp01(t);
        final float c1 = 1.70158f;
        final float c3 = c1 + 1f;
        float inv = c - 1f;
        return 1f + c3 * inv * inv * inv + c1 * inv * inv;
    }

    public static float easeInOutSine(float t) {
        return -(float) Math.cos(Math.PI * clamp01(t)) / 2f + 0.5f;
    }

    // ---- oscillators --------------------------------------------------------

    /**
     * A 0..1..0 wave over {@code periodMs}, for pulsing something continuously.
     */
    public static float pulse(long nowMs, long periodMs) {
        if (periodMs <= 0) {
            return 0f;
        }
        float phase = (nowMs % periodMs) / (float) periodMs;
        return (1f - (float) Math.cos(phase * 2.0 * Math.PI)) / 2f;
    }

    /** A 0..1 sawtooth over {@code periodMs}, for sweeps that repeat. */
    public static float phase(long nowMs, long periodMs) {
        if (periodMs <= 0) {
            return 0f;
        }
        return (nowMs % periodMs) / (float) periodMs;
    }

    /**
     * A value that eases from one number to another over a fixed duration,
     * with no state of its own.
     *
     * <p>Deliberately a value object rather than a mutating animation object: the
     * HUD is redrawn every frame and holds no per-frame state, so an animation is
     * fully described by "from, to, when it started, how long, which curve". That
     * makes it impossible to leave an animation stuck half-way, and it survives a
     * frame being skipped.
     */
    public record Tween(float from, float to, long startMs, long durationMs, Curve curve) {

        public enum Curve {
            LINEAR,
            OUT_CUBIC,
            OUT_QUINT,
            OUT_BACK,
            IN_OUT_SINE
        }

        public static Tween of(float from, float to, long durationMs, Curve curve) {
            return new Tween(from, to, nowMs(), durationMs, curve);
        }

        /** Starts a tween that is already finished, for "no animation" cases. */
        public static Tween settled(float value) {
            return new Tween(value, value, 0L, 0L, Curve.LINEAR);
        }

        public float at(long nowMs) {
            if (durationMs <= 0L) {
                return to;
            }
            float raw = (nowMs - startMs) / (float) durationMs;
            if (raw >= 1f) {
                return to;
            }
            if (raw <= 0f) {
                return from;
            }
            return Anim.lerp(from, to, ease(curve, raw));
        }

        public float now() {
            return at(nowMs());
        }

        public boolean finished(long nowMs) {
            return durationMs <= 0L || nowMs - startMs >= durationMs;
        }

        private static float ease(Curve curve, float t) {
            return switch (curve) {
                case LINEAR -> t;
                case OUT_CUBIC -> easeOutCubic(t);
                case OUT_QUINT -> easeOutQuint(t);
                case OUT_BACK -> easeOutBack(t);
                case IN_OUT_SINE -> easeInOutSine(t);
            };
        }
    }
}
