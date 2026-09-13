package com.rate.stevehud.plugin;

import com.rate.stevehud.protocol.model.BroadcastState;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.function.Consumer;

/**
 * A scripted demonstration of every element in the package.
 *
 * <p>Its purpose is to make the whole thing testable and reviewable without a real
 * competition: an operator can start it and watch the scores move, the lower third
 * introduce a competitor, the announcement take over the screen, the ticker scroll
 * and the clock run, all without a match being played.
 *
 * <p>It runs on the server, so every client sees the same demonstration at the same
 * time — which is also the point being demonstrated.
 *
 * <p>Written as a list of timed steps rather than a state machine: at this length a
 * readable script is worth more than a clever one, and the whole thing is one
 * screen long.
 */
final class DemoDirector {

    /** A step's action, given the plugin so it can touch the state and broadcast. */
    private record Step(int atSecond, Consumer<SteveHudPlugin> action) {
    }

    private static final int CYCLE_SECONDS = 40;

    /**
     * The demonstration, in order. Each entry plays once per cycle.
     *
     * <p>Deliberately touches every element: both scores, the lower third on each
     * side, two announcements, the ticker, and the match clock.
     */
    private static final List<Step> SCRIPT = List.of(
            new Step(0, plugin -> lowerThird(plugin, "home")),
            new Step(4, plugin -> lowerThird(plugin, "away")),
            new Step(8, plugin -> score(plugin, "home", 1)),
            new Step(11, plugin -> announce(plugin, "ROUND 3", "决胜局")),
            new Step(15, plugin -> {
                announce(plugin, "", "");
                score(plugin, "away", 1);
            }),
            new Step(19, plugin -> ticker(plugin, List.of(
                    "主队扳回一分", "当前比分 " + plugin.state().describeScores()))),
            new Step(23, plugin -> {
                lowerThird(plugin, "");
                plugin.state().getClock().running = true;
                plugin.state().setClockMillis(0L);
                plugin.broadcastState();
            }),
            new Step(31, plugin -> announce(plugin, "MATCH POINT", "赛点")),
            new Step(35, plugin -> {
                announce(plugin, "", "");
                ticker(plugin, List.of("赛点局开始", "图形随比赛数据实时更新"));
            }),
            new Step(37, plugin -> score(plugin, "home", 1))
    );

    private final SteveHudPlugin plugin;
    private BukkitRunnable task;
    private int elapsedSeconds;

    DemoDirector(SteveHudPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isRunning() {
        return task != null;
    }

    /** Starts or restarts the demonstration from the beginning. */
    void start() {
        stop();
        elapsedSeconds = 0;
        // Reset the clock so a second run looks the same as the first.
        plugin.state().getClock().running = false;
        plugin.state().setClockMillis(0L);
        plugin.broadcastState();

        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (plugin.state().getClock().running) {
                    // Keep the clock counting, so the running-clock animation has
                    // something real to display.
                    plugin.state().setClockMillis(elapsedSeconds * 1000L);
                    plugin.broadcastState();
                }
                for (Step step : SCRIPT) {
                    if (step.atSecond() == elapsedSeconds) {
                        step.action().accept(plugin);
                    }
                }
                elapsedSeconds = (elapsedSeconds + 1) % CYCLE_SECONDS;
            }
        };
        // Once a second is plenty: the demonstration is about what an operator sees,
        // and a faster timer would just mean more packets.
        task.runTaskTimer(plugin, 20L, 20L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ---- step actions -------------------------------------------------------

    private static void score(SteveHudPlugin plugin, String sideId, int delta) {
        plugin.state().bumpScore(sideId, delta);
        plugin.broadcastState();
    }

    private static void announce(SteveHudPlugin plugin, String title, String subtitle) {
        plugin.state().getAnnouncement().title = title;
        plugin.state().getAnnouncement().subtitle = subtitle;
        plugin.state().getAnnouncement().visible = !title.isEmpty();
        plugin.broadcastState();
    }

    private static void lowerThird(SteveHudPlugin plugin, String sideId) {
        BroadcastState.LowerThird lower = plugin.state().getLowerThird();
        lower.visible = !sideId.isEmpty();
        lower.side = sideId;
        plugin.broadcastState();
    }

    private static void ticker(SteveHudPlugin plugin, List<String> lines) {
        plugin.state().setTicker(new java.util.ArrayList<>(lines));
        plugin.broadcastState();
    }
}
