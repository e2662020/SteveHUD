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
