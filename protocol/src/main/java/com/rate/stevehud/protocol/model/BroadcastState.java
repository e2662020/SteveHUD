package com.rate.stevehud.protocol.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the graphics package displays, as one plain object.
 *
 * <p>This is the single source of truth for a broadcast, and it is shared: the
 * plugin owns one, sends it to every client, and each client renders it in game
 * and republishes it to its local web server. That is what makes the window shape
 * and the styling identical for everyone watching — an operator changing a score
 * changes it once, on the server, and every screen follows.
 *
 * <p>A plain mutable object rather than a record, because the plugin edits it in
 * place as commands arrive and the JSON shape is fixed by the web pages. It is
 * not thread-safe; {@link com.rate.stevehud.protocol.model.BroadcastState.Holder}
 * handles the cross-thread part.
 *
 * <p>No Minecraft types, so the plugin, the mod, the web pages and the tests all
 * agree on one definition.
 */
public final class BroadcastState {

    /** A competitor on a side. Both fields may be empty; neither is ever null. */
    public static final class Competitor {
        public String name = "";
        public String number = "";

        public Competitor() {
        }

        public Competitor(String name, String number) {
            this.name = name == null ? "" : name;
            this.number = number == null ? "" : number;
        }
    }

    /** One competing side: a team, a player, a pair. */
    public static final class Side {
        public String id = "";
        public String name = "";
        public String shortName = "";
        /** CSS-style hex, e.g. {@code #4C9AFF}. The client parses it; nothing assumes a palette. */
        public String color = "#4C9AFF";
        public int score;
        /** Free-form standing, e.g. {@code "14 - 3"}. Shown on the head-to-head card. */
        public String record = "";
        /**
         * This side's value over time, oldest first.
         *
         * <p>What a trend chart draws. The units are the caller's business — gold,
         * objectives, net kills — and a renderer only needs them to be comparable
         * between the two sides, so it can put them on one axis.
         */
        public List<Double> series = new ArrayList<>();
        public List<Competitor> competitors = new ArrayList<>();

        public Side() {
        }

        public Side(String id, String name, String shortName, String color) {
            this.id = id;
            this.name = name;
            this.shortName = shortName;
            this.color = color;
        }
    }

    /**
     * One number on a board.
     *
     * <p>{@code key} groups the rows that belong together — on a two-sided bar,
     * the home row and the away row of one metric share a key and are drawn as a
     * pair. {@code side} says which side it belongs to; empty means neutral,
     * which is what a leaderboard or a tile wall wants.
     */
    public static final class Metric {
        public String key = "";
        public String label = "";
        /** A second line under the label, e.g. the team and role on a leaderboard. */
        public String sub = "";
        /** A {@link Side#id}, or empty for a value that belongs to nobody in particular. */
        public String side = "";
        public double value;
        /** Preformatted text; when set it wins over {@link #value}. */
        public String display = "";
        public String unit = "";
        /** How much this moved, for a tile's up/down arrow. 0 means "no delta". */
        public double delta;
        /** Free-form state word a board may style on: {@code live}, {@code done}, {@code todo}. */
        public String state = "";
        /** A short left-hand tag, e.g. {@code G1} for a game in a series. */
        public String index = "";
        /** A timestamp, already formatted: {@code 23:59}. */
        public String time = "";
        /** This metric's own history, for a chart that is fed row by row. */
        public List<Double> series = new ArrayList<>();
    }

    /**
     * One named table of data — the single shape every data board reads.
     *
     * <p>A board element names one of these through its {@code board} option and
     * does whatever its own drawing code does with the rows. That is the whole
     * contract, and it is why adding a tenth panel does not touch this class.
     *
     * <p>A board with no rows draws nothing at all rather than an empty frame, so
     * a package can ship with every panel configured and only the fed ones appear.
     */
    public static final class Board {
        public String key = "";
        public String title = "";
        public String subtitle = "";
        public String unit = "";
        public List<Metric> rows = new ArrayList<>();

        public Board() {
        }

        public Board(String key, String title) {
            this.key = key;
            this.title = title;
        }

        /** The rows belonging to one side, in order. */
        public List<Metric> rowsOf(String sideId) {
            List<Metric> out = new ArrayList<>();
            for (Metric row : rows) {
                if (row != null && java.util.Objects.equals(row.side, sideId)) {
                    out.add(row);
                }
            }
            return out;
        }

        /** Adds a row and returns this board, so a demo reads as one chain. */
        public Board with(String key, String label, String side, double value) {
            Metric metric = new Metric();
            metric.key = key;
            metric.label = label;
            metric.side = side == null ? "" : side;
            metric.value = value;
            rows.add(metric);
            return this;
        }
    }

    public static final class Event {
        public String name = "";
        public String stage = "";
    }

    public static final class Clock {
        public String label = "";
        public String value = "00:00";
        public boolean running;
    }

    public static final class Announcement {
        public String title = "";
        public String subtitle = "";
        public boolean visible;
    }

    public static final class LowerThird {
        public boolean visible;
        /** The {@code id} of the side to introduce. */
        public String side = "";
    }

    private Event event = new Event();
    private List<Side> sides = new ArrayList<>();
    private List<Board> boards = new ArrayList<>();
    private Clock clock = new Clock();
    private List<String> ticker = new ArrayList<>();
    private Announcement announcement = new Announcement();
    private LowerThird lowerThird = new LowerThird();
    /** Which elements the package shows. See the constants below. */
    private String scene = SCENE_FULL;

    public static final String SCENE_FULL = "full";
    public static final String SCENE_COMPACT = "compact";
    public static final String SCENE_MINIMAL = "minimal";

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event == null ? new Event() : event;
    }

    public List<Side> getSides() {
        return sides;
    }

    public void setSides(List<Side> sides) {
        this.sides = sides == null ? new ArrayList<>() : sides;
    }

    /** The data tables the package's boards read. Never null; may be empty. */
    public List<Board> getBoards() {
        return boards;
    }

    public void setBoards(List<Board> boards) {
        this.boards = boards == null ? new ArrayList<>() : boards;
    }

    /** The board with this key, or null. */
    public Board board(String key) {
        if (key == null) {
            return null;
        }
        for (Board board : boards) {
            if (board != null && key.equals(board.key)) {
                return board;
            }
        }
        return null;
    }

    /**
     * Adds a metric to the board with that key, creating the board if needed.
     *
     * <p>The one write path the commands and the API need, so nothing else has to
     * know how the list is kept.
     */
    public Board boardFor(String key, String title) {
        Board existing = board(key);
        if (existing != null) {
            if (title != null && !title.isEmpty()) {
                existing.title = title;
            }
            return existing;
        }
        Board created = new Board(key == null ? "" : key, title == null ? "" : title);
        boards.add(created);
        return created;
    }

    public Clock getClock() {
        return clock;
    }

    public void setClock(Clock clock) {
        this.clock = clock == null ? new Clock() : clock;
    }

    public List<String> getTicker() {
        return ticker;
    }

    public void setTicker(List<String> ticker) {
        this.ticker = ticker == null ? new ArrayList<>() : ticker;
    }

    public Announcement getAnnouncement() {
        return announcement;
    }

    public void setAnnouncement(Announcement announcement) {
        this.announcement = announcement == null ? new Announcement() : announcement;
    }

    public LowerThird getLowerThird() {
        return lowerThird;
    }

    public void setLowerThird(LowerThird lowerThird) {
        this.lowerThird = lowerThird == null ? new LowerThird() : lowerThird;
    }

    public String getScene() {
        return scene;
    }

    /** Unknown scene names fall back to {@code full} rather than blanking the package. */
    public void setScene(String scene) {
        if (SCENE_COMPACT.equals(scene) || SCENE_MINIMAL.equals(scene)) {
            this.scene = scene;
        } else {
            this.scene = SCENE_FULL;
        }
    }

    public boolean showsLowerThird() {
        return SCENE_FULL.equals(scene);
    }

    public boolean showsAnnouncement() {
        return SCENE_FULL.equals(scene);
    }

    public boolean showsEventInfo() {
        return !SCENE_MINIMAL.equals(scene);
    }

    public boolean showsTicker() {
        return !SCENE_MINIMAL.equals(scene);
    }

    // ---- content helpers ----------------------------------------------------

    /** @return the side with this id, or null */
    public Side side(String id) {
        if (id == null) {
            return null;
        }
        for (Side side : sides) {
            if (id.equals(side.id)) {
                return side;
            }
        }
        return null;
    }

    /** Adds a score to a side, clamped at zero. @return the new score, or -1 if no such side */
    public int bumpScore(String sideId, int delta) {
        Side side = side(sideId);
        if (side == null) {
            return -1;
        }
        side.score = Math.max(0, side.score + delta);
        return side.score;
    }

    /** Formats the clock value from a millisecond count. */
    public void setClockMillis(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        clock.value = String.format("%02d:%02d", total / 60L, total % 60L);
    }

    public String describeScores() {
        StringBuilder text = new StringBuilder();
        for (Side side : sides) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(side.shortName.isEmpty() ? side.name : side.shortName)
                    .append(' ').append(side.score);
        }
        return text.toString();
    }
}
