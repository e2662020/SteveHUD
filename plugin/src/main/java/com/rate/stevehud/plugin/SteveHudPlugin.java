package com.rate.stevehud.plugin;

import com.rate.stevehud.plugin.net.ChannelSender;
import com.rate.stevehud.protocol.MessageType;
import com.rate.stevehud.protocol.Protocol;
import com.rate.stevehud.protocol.model.BroadcastState;
import com.rate.stevehud.protocol.model.HelloBody;
import com.rate.stevehud.protocol.model.Layout;
import com.rate.stevehud.protocol.model.LayoutBody;
import com.rate.stevehud.protocol.model.Layouts;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * The server half of SteveHUD: it owns the match and, optionally, the package.
 *
 * <p>The state lives here, not on each client. That is the point: a competition has
 * one scoreboard, and every screen watching it — the players' clients, the observer
 * clients, the web overlays — must show the same thing. An operator changes a score
 * once, on the server, and every client follows.
 *
 * <p>Layout has <b>two scopes</b>, and they are different in kind:
 *
 * <ul>
 *   <li>The <b>local</b> package is the file on one machine's disk. It drives that
 *       machine's OBS overlay and that machine's HUD, and nothing else. That is the
 *       operator's own packaging work, so it needs no permission and the server is
 *       not involved at all.</li>
 *   <li>The <b>server</b> package is the one below. While it is set, every client
 *       draws it instead of its own, which is what makes the graphics identical on
 *       every screen — so only an operator can set it.</li>
 * </ul>
 *
 * <p>It starts unset on purpose. A plugin that forced a package on everyone by
 * default would make the local scope unreachable, and the local scope is where OBS
 * packaging actually happens.
 *
 * <p>Clients still own the rest of their presentation: scale, opacity, and whether to
 * draw the package in game at all. Those are personal and deliberately not
 * synchronised.
 *
 * <p>One jar covers every 1.21.x release, because the Bukkit API surface used here
 * is stable within a major version.
 */
public final class SteveHudPlugin extends JavaPlugin implements Listener {

    /**
     * Feature keys the server understands. Clients read these to decide what they may
     * ask for, instead of comparing version numbers.
     */
    static final List<String> CAPABILITIES = List.of("snapshot", "state", "layout", "web");

    /** Grace period before the first message, so the client's channel is certainly up. */
    private static final long JOIN_GRACE_TICKS = 10L;

    private ChannelSender sender;
    private Messages messages;
    private DemoDirector demo;

    /**
     * The match package.
     *
     * <p>Mutated and serialised only on the server thread — commands, the demo
     * runnable and the broadcast all run there — so no locking is needed. If any of
     * that ever moves off-thread, this needs revisiting.
     */
    private final BroadcastState state = new BroadcastState();

    /**
     * The server-wide package, or null when each client should draw its own.
     *
     * <p>Null is the default and a meaningful state, not a missing value: see the
     * class comment. Written and read on the server thread only.
     */
    private LayoutBody layout;

    @Override
    public void onEnable() {
        sender = new ChannelSender(this);
        messages = new Messages(this);
        demo = new DemoDirector(this);

        applyDefaults();

        getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);

        var command = getCommand("stevehud");
        if (command != null) {
            var handler = new SteveHudCommand(this, messages);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        getLogger().info("SteveHUD enabled - protocol v" + Protocol.VERSION
                + ", channel " + Protocol.CHANNEL);
        getLogger().info("Match state is server-owned: /stevehud scene, score, clock, "
                + "announce, ticker, lower3, event, demo");
    }

    @Override
    public void onDisable() {
        if (demo != null) {
            demo.stop();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, Protocol.CHANNEL);
    }

    /**
     * A fresh server shows a complete, sensible package rather than an empty one, so
     * an operator can see what they are about to change.
     */
    private void applyDefaults() {
        state.getEvent().name = "示例杯 决赛";
        state.getEvent().stage = "BO3 · 第 1 局";

        BroadcastState.Side home = new BroadcastState.Side("home", "主队", "HOM", "#4C9AFF");
        home.competitors.add(new BroadcastState.Competitor("选手一", "07"));
        BroadcastState.Side away = new BroadcastState.Side("away", "客队", "AWY", "#FF6B6B");
        away.competitors.add(new BroadcastState.Competitor("选手二", "11"));
        state.setSides(new java.util.ArrayList<>(List.of(home, away)));

        state.getClock().label = "比赛计时";
        state.getClock().running = false;
        state.setClockMillis(0L);

        state.getTicker().addAll(List.of(
                "欢迎收看 SteveHUD 示例赛事",
                "图形由服务器统一控制，所有客户端显示一致"));
        state.setScene(BroadcastState.SCENE_FULL);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Sent a beat late on purpose: a payload pushed during the join itself can
        // reach a client whose channel is not registered yet, and would be dropped.
        getServer().getScheduler().runTaskLater(this,
                () -> greetAndSync(event.getPlayer()), JOIN_GRACE_TICKS);
    }

    /** Greets a client and hands it the current package, so it never starts blank. */
    void greetAndSync(Player player) {
        HelloBody hello = new HelloBody(
                Protocol.VERSION,
                "SteveHUD-Plugin/" + getPluginMeta().getVersion(),
                CAPABILITIES,
                // The server decides who may author the package; the client just reads
                // the answer. Asking permission locally is neither authoritative nor
                // portable across Minecraft versions.
                player.isOp());

        var registered = player.getListeningPluginChannels();
        getLogger().info("Greeting " + player.getName() + " - client accepts: "
                + (registered.isEmpty() ? "<no plugin channels>" : String.join(", ", registered)));

        sender.send(player, MessageType.HELLO, sender.nextRevision(), hello);
        if (sender.canReceive(player)) {
            // Layout before state: a client that receives a score before it knows which
            // package to draw would render one frame of the wrong graphics.
            if (layout != null) {
                sender.send(player, MessageType.LAYOUT, sender.nextRevision(), layout);
            }
            sendState(player);
        }
    }

    // ---- the server-wide package -------------------------------------------

    /** The package every client is drawing, or null when each draws its own. */
    LayoutBody layout() {
        return layout;
    }

    /**
     * Sets the package every client must draw, and tells them.
     *
     * @return false when {@code preset} is not a package the mod ships
     */
    boolean applyLayoutPreset(String preset, String author) {
        if (!Layouts.isPreset(preset)) {
            return false;
        }
        Layout document = Layouts.load(preset);
        this.layout = LayoutBody.of(document, Layouts.presetLabel(preset), author);
        broadcastLayout();
        return true;
    }

    /**
     * Goes back to each client drawing its own package.
     *
     * <p>Broadcast rather than merely forgotten: a client that kept drawing the old
     * package because it was never told would be a screen that disagrees with every
     * other one, which is the failure this whole scope exists to prevent.
     */
    void clearLayout(String author) {
        this.layout = LayoutBody.cleared(author);
        broadcastLayout();
    }

    /** Sends the current scope to everyone. */
    void broadcastLayout() {
        if (layout == null) {
            return;
        }
        long rev = sender.nextRevision();
        for (Player player : getServer().getOnlinePlayers()) {
            sender.send(player, MessageType.LAYOUT, rev, layout);
        }
    }

    /** Sends the whole package to one client. */
    void sendState(Player player) {
        sender.send(player, MessageType.DELTA, sender.nextRevision(), state);
    }

    /**
     * Sends the whole package to everyone.
     *
     * <p>The full state rather than a delta: it is a small document, and a scheme
     * where every client independently applies increments is a scheme where one
     * dropped message leaves one screen permanently wrong. Sending everything makes
     * a lost update self-correcting on the next change.
     */
    void broadcastState() {
        long rev = sender.nextRevision();
        for (Player player : getServer().getOnlinePlayers()) {
            sender.send(player, MessageType.DELTA, rev, state);
        }
    }

    BroadcastState state() {
        return state;
    }

    DemoDirector demo() {
        return demo;
    }
}
