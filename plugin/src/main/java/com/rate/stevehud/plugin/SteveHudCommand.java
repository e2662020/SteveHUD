package com.rate.stevehud.plugin;

import com.rate.stevehud.protocol.Protocol;
import com.rate.stevehud.protocol.model.BroadcastState;
import com.rate.stevehud.protocol.model.Layouts;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /stevehud} — the operator's controls for the broadcast package.
 *
 * <p>One command with subcommands rather than several commands, because everything
 * here edits the same document and an operator should not have to remember which
 * name goes with which part of it.
 *
 * <p>Arguments are parsed by hand instead of through Brigadier. The tree would be
 * longer than the handler, several subcommands take free text with spaces, and the
 * plugin runs on Spigot as well as Paper — hand parsing is the smaller, more
 * portable surface. Tab completion is provided for everything that has a fixed set
 * of values.
 *
 * <p>Replies are looked up in the sender's own locale, because one server hosts
 * players on differently localised clients.
 */
final class SteveHudCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "status", "resend", "scene", "score", "clock", "announce",
            "ticker", "lower3", "event", "stage", "demo", "reset", "config", "layout");

    private final SteveHudPlugin plugin;
    private final Messages messages;

    SteveHudCommand(SteveHudPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = sender instanceof Player p ? p : null;
        if (args.length == 0) {
            reply(sender, player, "command.usage");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "status" -> status(sender, player);
            case "resend" -> resend(sender, player);
            // The settings screen lives on the client. Saying so is more useful than
            // an error: it is the first thing an operator tries here.
            case "config" -> reply(sender, player, "command.config");
            case "scene" -> scene(sender, player, rest);
            case "score" -> score(sender, player, rest);
            case "clock" -> clock(sender, player, rest);
            case "announce" -> announce(sender, player, rest);
            case "ticker" -> ticker(sender, player, rest);
            case "lower3" -> lowerThird(sender, player, rest);
            case "event" -> event(sender, player, rest);
            case "stage" -> stage(sender, player, rest);
            case "demo" -> demo(sender, player, rest);
            case "layout" -> layout(sender, player, rest);
            case "reset" -> reset(sender, player);
            default -> reply(sender, player, "command.unknown", args[0]);
        }
        return true;
    }

    // ---- diagnostics --------------------------------------------------------

    private void status(CommandSender sender, Player player) {
        BroadcastState state = plugin.state();
        reply(sender, player, "command.status",
                Protocol.VERSION, Protocol.CHANNEL, state.getScene(),
                state.describeScores(),
                plugin.demo().isRunning() ? "on" : "off");
        reply(sender, player, "command.status.clock",
                state.getClock().value, state.getClock().running ? "running" : "stopped");
    }

    private void resend(CommandSender sender, Player player) {
        if (player == null) {
            reply(sender, null, "command.resend.players_only");
            return;
        }
        plugin.greetAndSync(player);
        reply(sender, player, "command.resend.ok");
    }

    // ---- package contents ---------------------------------------------------

    private void scene(CommandSender sender, Player player, String[] args) {
        if (args.length == 0) {
            reply(sender, player, "command.scene.current", plugin.state().getScene());
            return;
        }
        String wanted = args[0].toLowerCase(Locale.ROOT);
        if (!wanted.equals(BroadcastState.SCENE_FULL)
                && !wanted.equals(BroadcastState.SCENE_COMPACT)
                && !wanted.equals(BroadcastState.SCENE_MINIMAL)) {
            reply(sender, player, "command.scene.invalid");
            return;
        }
        plugin.state().setScene(wanted);
        plugin.broadcastState();
        reply(sender, player, "command.scene.set", plugin.state().getScene());
    }

    private void score(CommandSender sender, Player player, String[] args) {
        if (args.length < 2) {
            reply(sender, player, "command.score.usage");
            return;
        }
        BroadcastState.Side side = plugin.state().side(args[0].toLowerCase(Locale.ROOT));
        if (side == null) {
            reply(sender, player, "command.no_such_side", sideIds());
            return;
        }
        int delta;
        try {
            // "+1", "-2" and plain "3" should all do what they look like they do.
            delta = Integer.parseInt(args[1].startsWith("+") ? args[1].substring(1) : args[1]);
        } catch (NumberFormatException e) {
            reply(sender, player, "command.score.invalid");
            return;
        }
        plugin.state().bumpScore(side.id, delta);
        plugin.broadcastState();
        reply(sender, player, "command.score.set", plugin.state().describeScores());
    }

    private void clock(CommandSender sender, Player player, String[] args) {
        BroadcastState.Clock clock = plugin.state().getClock();
        if (args.length == 0) {
            reply(sender, player, "command.clock.usage");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                clock.running = true;
                plugin.broadcastState();
                reply(sender, player, "command.clock.started", clock.value);
            }
            case "stop" -> {
                clock.running = false;
                plugin.broadcastState();
                reply(sender, player, "command.clock.stopped", clock.value);
            }
            case "reset" -> {
                clock.running = false;
                plugin.state().setClockMillis(0L);
                plugin.broadcastState();
                reply(sender, player, "command.clock.set", clock.value);
            }
            case "set" -> {
                if (args.length < 2) {
                    reply(sender, player, "command.clock.usage");
                    return;
                }
                Long millis = parseClock(args[1]);
                if (millis == null) {
                    reply(sender, player, "command.clock.invalid");
                    return;
                }
                clock.running = false;
                plugin.state().setClockMillis(millis);
                plugin.broadcastState();
                reply(sender, player, "command.clock.set", clock.value);
            }
            default -> reply(sender, player, "command.clock.usage");
        }
    }

    /** Accepts {@code mm:ss} or plain seconds. */
    private static Long parseClock(String value) {
        try {
            if (value.contains(":")) {
                String[] parts = value.split(":", 2);
                long minutes = Long.parseLong(parts[0]);
                long seconds = Long.parseLong(parts[1]);
                if (seconds < 0 || seconds > 59 || minutes < 0) {
                    return null;
                }
                return (minutes * 60L + seconds) * 1000L;
            }
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : seconds * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void announce(CommandSender sender, Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("off")) {
            plugin.state().getAnnouncement().visible = false;
            plugin.state().getAnnouncement().title = "";
            plugin.state().getAnnouncement().subtitle = "";
            plugin.broadcastState();
            reply(sender, player, "command.announce.off");
            return;
        }
        // Free text, so everything after the first word is the title unless a "|"
        // separates a subtitle from it.
        String joined = String.join(" ", args);
        String[] halves = joined.split("\\s*\\|\\s*", 2);
        plugin.state().getAnnouncement().title = halves[0];
        plugin.state().getAnnouncement().subtitle = halves.length > 1 ? halves[1] : "";
        plugin.state().getAnnouncement().visible = true;
        plugin.broadcastState();
        reply(sender, player, "command.announce.set", halves[0]);
    }

    private void ticker(CommandSender sender, Player player, String[] args) {
        if (args.length == 0) {
            reply(sender, player, "command.ticker.usage", plugin.state().getTicker().size());
            return;
        }
        List<String> ticker = plugin.state().getTicker();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "clear" -> {
                ticker.clear();
                plugin.broadcastState();
                reply(sender, player, "command.ticker.cleared");
            }
            case "add" -> {
                if (args.length < 2) {
                    reply(sender, player, "command.ticker.usage", ticker.size());
                    return;
                }
                // "|" splits several lines out of one command.
                String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                for (String line : joined.split("\\s*\\|\\s*")) {
                    if (!line.isBlank()) {
                        ticker.add(line);
                    }
                }
                plugin.broadcastState();
                reply(sender, player, "command.ticker.added", ticker.size());
            }
            case "set" -> {
                if (args.length < 2) {
                    reply(sender, player, "command.ticker.usage", ticker.size());
                    return;
                }
                String joined = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                ticker.clear();
                for (String line : joined.split("\\s*\\|\\s*")) {
                    if (!line.isBlank()) {
                        ticker.add(line);
                    }
                }
                plugin.broadcastState();
                reply(sender, player, "command.ticker.added", ticker.size());
            }
            default -> reply(sender, player, "command.ticker.usage", ticker.size());
        }
    }

    private void lowerThird(CommandSender sender, Player player, String[] args) {
        BroadcastState.LowerThird lower = plugin.state().getLowerThird();

        if (args.length == 0) {
            reply(sender, player, "command.lower3.usage", sideIds());
            return;
        }
        if (args[0].equalsIgnoreCase("off")) {
            lower.visible = false;
            lower.side = "";
            plugin.broadcastState();
            reply(sender, player, "command.lower3.off");
            return;
        }

        String id = args[0].toLowerCase(Locale.ROOT);
        if (plugin.state().side(id) == null) {
            reply(sender, player, "command.no_such_side", sideIds());
            return;
        }
        lower.visible = true;
        lower.side = id;
        plugin.broadcastState();
        reply(sender, player, "command.lower3.set", id);
    }

    private void event(CommandSender sender, Player player, String[] args) {
        if (args.length == 0) {
            reply(sender, player, "command.event.current", plugin.state().getEvent().name);
            return;
        }
        plugin.state().getEvent().name = String.join(" ", args);
        plugin.broadcastState();
        reply(sender, player, "command.event.set", plugin.state().getEvent().name);
    }

    private void stage(CommandSender sender, Player player, String[] args) {
        if (args.length == 0) {
            reply(sender, player, "command.event.current", plugin.state().getEvent().stage);
            return;
        }
        plugin.state().getEvent().stage = String.join(" ", args);
        plugin.broadcastState();
        reply(sender, player, "command.event.set", plugin.state().getEvent().stage);
    }

    /**
     * The server-wide package, as opposed to the one on a single machine's disk.
     *
     * <p>The distinction is the permission model. Editing the package on your own
     * machine drives your own OBS overlay and your own HUD and changes nothing for
     * anyone else, so it needs no permission and is not this command. This one makes
     * every client in the match draw the same package, so it is operator-only, which
     * {@code plugin.yml} enforces before this method is reached.
     */
    private void layout(CommandSender sender, Player player, String[] args) {
        if (args.length == 0) {
            reportLayout(sender, player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "show" -> reportLayout(sender, player);
            case "clear" -> {
                plugin.clearLayout(sender.getName());
                reply(sender, player, "command.layout.cleared");
            }
            case "preset" -> {
                if (args.length < 2) {
                    reply(sender, player, "command.layout.presets",
                            String.join(", ", Layouts.presetNames()));
                    return;
                }
                String preset = args[1].toLowerCase(Locale.ROOT);
                if (!plugin.applyLayoutPreset(preset, sender.getName())) {
                    reply(sender, player, "command.layout.unknown", preset,
                            String.join(", ", Layouts.presetNames()));
                    return;
                }
                reply(sender, player, "command.layout.set", plugin.layout().labelOrName());
            }
            default -> reply(sender, player, "command.layout.usage");
        }
    }

    /**
     * What every client is drawing right now.
     *
     * <p>Two whole sentences rather than one sentence with a hole in it: "no server
     * package" is a different statement from "the server's package is X", not a
     * value inside the same one, and a translator should be free to word them
     * differently.
     */
    private void reportLayout(CommandSender sender, Player player) {
        if (plugin.layout() == null || !plugin.layout().present()) {
            reply(sender, player, "command.layout.none");
            return;
        }
        // The attribution is its own message, so a translator can place it — or drop
        // it — without having to reorder the sentence around it.
        String author = plugin.layout().author;
        String clause = author.isBlank()
                ? ""
                : messages.get(player, "command.layout.author", author);
        reply(sender, player, "command.layout.current",
                plugin.layout().labelOrName(), clause);
    }

    private void demo(CommandSender sender, Player player, String[] args) {
        boolean stop = args.length > 0 && args[0].equalsIgnoreCase("stop");
        if (stop) {
            plugin.demo().stop();
            reply(sender, player, "command.demo.stopped");
            return;
        }
        plugin.demo().start();
        reply(sender, player, "command.demo.started");
    }

    private void reset(CommandSender sender, Player player) {
        plugin.demo().stop();
        BroadcastState state = plugin.state();
        for (BroadcastState.Side side : state.getSides()) {
            side.score = 0;
        }
        state.getAnnouncement().visible = false;
        state.getAnnouncement().title = "";
        state.getAnnouncement().subtitle = "";
        state.getLowerThird().visible = false;
        state.getLowerThird().side = "";
        state.getClock().running = false;
        state.setClockMillis(0L);
        state.setScene(BroadcastState.SCENE_FULL);
        plugin.broadcastState();
        reply(sender, player, "command.reset.done");
    }

    // ---- helpers ------------------------------------------------------------

    private String sideIds() {
        StringBuilder ids = new StringBuilder();
        for (BroadcastState.Side side : plugin.state().getSides()) {
            if (ids.length() > 0) {
                ids.append(", ");
            }
            ids.append(side.id);
        }
        return ids.toString();
    }

    private void reply(CommandSender sender, Player player, String key, Object... args) {
        String pattern = messages.get(player, key, args);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', pattern));
    }

    // ---- completion ---------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return matching(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "scene" -> matching(List.of(
                        BroadcastState.SCENE_FULL,
                        BroadcastState.SCENE_COMPACT,
                        BroadcastState.SCENE_MINIMAL), args[1]);
                case "score", "lower3" -> {
                    List<String> options = new ArrayList<>(sideIdList());
                    options.add("off");
                    yield matching(options, args[1]);
                }
                case "clock" -> matching(List.of("start", "stop", "reset", "set"), args[1]);
                case "ticker" -> matching(List.of("add", "set", "clear"), args[1]);
                case "announce" -> matching(List.of("off"), args[1]);
                case "demo" -> matching(List.of("start", "stop"), args[1]);
                case "layout" -> matching(List.of("show", "preset", "clear"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("score")) {
            return matching(List.of("+1", "-1", "1"), args[2]);
        }
        if (args.length == 3 && sub.equals("layout") && args[1].equalsIgnoreCase("preset")) {
            return matching(Layouts.presetNames(), args[2]);
        }
        return List.of();
    }

    private List<String> sideIdList() {
        List<String> ids = new ArrayList<>();
        for (BroadcastState.Side side : plugin.state().getSides()) {
            ids.add(side.id);
        }
        return ids;
    }

    private static List<String> matching(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
