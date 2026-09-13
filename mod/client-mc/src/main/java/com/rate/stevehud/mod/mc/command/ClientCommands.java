package com.rate.stevehud.mod.mc.command;

import com.rate.stevehud.mod.client.SteveHudLink;
import com.rate.stevehud.mod.mc.SteveHudClient;
import com.rate.stevehud.mod.mc.config.Configs;
import com.rate.stevehud.mod.mc.hud.HudState;
import com.rate.stevehud.mod.mc.web.WebBridge;
import com.rate.stevehud.protocol.Protocol;
import com.rate.stevehud.protocol.model.Layout;
import com.rate.stevehud.protocol.model.Layouts;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Client-side commands.
 *
 * <p>Namespaced rather than under a single {@code /stevehud}: the server plugin owns
 * that name, and a client command sharing it would collide in the client's merged
 * command dispatcher.
 *
 * <p>Every command also answers to a short {@code /shud*} form, because these get
 * typed under time pressure during a match.
 *
 * <p>Everything a viewer can change locally is here, and everything about the match
 * itself is on the server. That split is deliberate: the package has to look the
 * same for every screen in the match, so its content and scene come from the
 * server, while scale, opacity and visibility are personal.
 */
public final class ClientCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger("SteveHUD");

    private ClientCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            aliases(dispatcher, ClientCommands::openSettings,
                    "stevehudconfig", "shudconfig", "shudc");

            aliases(dispatcher, context -> context.getSource().sendFeedback(status()),
                    "stevehudlink", "shudlink", "shudl");

            aliases(dispatcher, ClientCommands::openEditor,
                    "stevehudeditor", "shudeditor", "shude");

            aliases(dispatcher, ClientCommands::openWebOverlay,
                    "stevehudweb", "shudweb", "shudw");

            aliases(dispatcher, ClientCommands::toggleHud,
                    "stevehudhud", "shudh");

            // The package itself. Registered as a tree per alias rather than as a
            // single action, because switching a preset and asking what is loaded are
            // both things an operator does mid-match.
            for (String name : new String[]{"stevehudlayout", "shudlayout", "shudlay"}) {
                layoutCommand(dispatcher, name);
            }
        });
    }

    /**
     * {@code /shudlayout} — the broadcast package this client is drawing.
     *
     * <p>No arguments reports what is loaded and where it lives, which is the question
     * an operator actually has. {@code list} and {@code reload} cover the other two:
     * "what else is there" and "I edited the file by hand".
     */
    private static void layoutCommand(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                      String name) {
        dispatcher.register(ClientCommandManager.literal(name)
                .executes(context -> {
                    report(context);
                    return 1;
                })
                .then(ClientCommandManager.literal("list")
                        .executes(context -> {
                            context.getSource().sendFeedback(Text.translatable(
                                    "stevehud.command.layout.presets",
                                    String.join(", ", Layouts.presetNames())));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("reload")
                        .executes(context -> {
                            WebBridge.reloadLayout();
                            context.getSource().sendFeedback(
                                    Text.translatable("stevehud.command.layout.reloaded"));
                            report(context);
                            return 1;
                        }))
                .then(ClientCommandManager.argument("preset", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (String preset : Layouts.presetNames()) {
                                if (preset.startsWith(builder.getRemainingLowerCase())) {
                                    builder.suggest(preset);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String preset = StringArgumentType.getString(context, "preset");
                            if (!WebBridge.applyPreset(preset)) {
                                context.getSource().sendFeedback(Text.translatable(
                                        "stevehud.command.layout.unknown", preset,
                                        String.join(", ", Layouts.presetNames())));
                                return 0;
                            }
                            context.getSource().sendFeedback(Text.translatable(
                                    "stevehud.command.layout.switched",
                                    Layouts.presetLabel(preset)));
                            if (WebBridge.serverLayoutActive()) {
                                // Said plainly. Without it an operator switches preset,
                                // sees no change on screen, and concludes the command is
                                // broken.
                                context.getSource().sendFeedback(Text.translatable(
                                        "stevehud.command.layout.shadowed"));
                            }
                            report(context);
                            return 1;
                        })));
    }

    private static void report(CommandContext<FabricClientCommandSource> context) {
        Layout layout = WebBridge.layout();
        context.getSource().sendFeedback(Text.translatable("stevehud.command.layout.status",
                layout.name, WebBridge.preset(), layout.elements.size()));
        // Which scope is on air, said before anything else: it decides whether an edit
        // here will be visible at all.
        context.getSource().sendFeedback(WebBridge.serverLayoutActive()
                ? Text.translatable("stevehud.command.layout.scope_server",
                        WebBridge.onAirAuthor().isBlank() ? "?" : WebBridge.onAirAuthor())
                : Text.translatable("stevehud.command.layout.scope_local"));
        Path file = WebBridge.layoutFile();
        if (file != null) {
            context.getSource().sendFeedback(
                    Text.translatable("stevehud.command.layout.file", file.toString()));
        }
        if (WebBridge.previewing()) {
            context.getSource().sendFeedback(
                    Text.translatable("stevehud.command.layout.preview_on"));
        }
        context.getSource().sendFeedback(Text.translatable("stevehud.command.layout.hint"));
    }

    /** Registers one action under several names, so a command can have a short form. */
    private static void aliases(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                Consumer<CommandContext<FabricClientCommandSource>> action,
                                String... names) {
        for (String name : names) {
            dispatcher.register(ClientCommandManager.literal(name).executes(context -> {
                action.accept(context);
                return 1;
            }));
        }
    }

    // ---- local visibility ---------------------------------------------------

    private static void toggleHud(CommandContext<FabricClientCommandSource> context) {
        var config = Configs.get();
        config.hudEnabled = !config.hudEnabled;
        Configs.save();
        context.getSource().sendFeedback(Text.translatable(
                config.hudEnabled ? "stevehud.command.hud.on" : "stevehud.command.hud.off"));
    }

    // ---- screens and pages --------------------------------------------------

    private static void openSettings(CommandContext<FabricClientCommandSource> context) {
        switch (Configs.openScreen()) {
            case OPENED -> {
                // The screen itself is the feedback.
            }
            case UNAVAILABLE -> context.getSource().sendFeedback(
                    Text.translatable("stevehud.command.settings.unavailable"));
            case FAILED -> context.getSource().sendFeedback(
                    Text.translatable("stevehud.command.settings.failed"));
        }
    }

    /**
     * Opens the local overlay page in a browser.
     *
     * <p>This is the browsing counterpart of the in-game package: the same state,
     * rendered by the web package, which is where the unlimited typography and
     * animation live.
     */
    private static void openWebOverlay(CommandContext<FabricClientCommandSource> context) {
        if (!WebBridge.isServing()) {
            context.getSource().sendFeedback(Text.translatable(
                    Configs.get().localServerEnabled
                            ? "stevehud.command.web.failed"
                            : "stevehud.command.web.disabled"));
            return;
        }
        String url = "http://127.0.0.1:" + WebBridge.port() + "/overlay/";
        context.getSource().sendFeedback(Text.translatable(
                "stevehud.command.web.opening", url, WebBridge.listeners()));
        open(context, url, "stevehud.command.web.browser_failed");
    }

    /**
     * Opens the layout editor in the default browser.
     *
     * <p><b>No permission is required, deliberately.</b> The editor edits the document
     * on this machine's disk, which drives this machine's OBS overlay and this
     * machine's HUD and changes nothing for anybody else. That is the operator's own
     * packaging work, and gating it behind an op would be gating a local preference.
     *
     * <p>The server-wide package is the opposite case and is gated — on the server, by
     * {@code /stevehud layout}, which {@code plugin.yml} restricts to
     * {@code stevehud.admin}. Every decision about what other people's screens show is
     * made where it can actually be enforced.
     */
    private static void openEditor(CommandContext<FabricClientCommandSource> context) {
        if (!WebBridge.isServing()) {
            context.getSource().sendFeedback(Text.translatable(
                    Configs.get().localServerEnabled
                            ? "stevehud.command.web.failed"
                            : "stevehud.command.web.disabled"));
            return;
        }
        String url = "http://127.0.0.1:" + WebBridge.port() + "/editor/";
        context.getSource().sendFeedback(Text.translatable("stevehud.command.editor.opening", url));
        open(context, url, "stevehud.command.editor.failed");
    }

    /** Opens a URL, reporting the address either way so it can be pasted by hand. */
    private static void open(CommandContext<FabricClientCommandSource> context,
                             String url, String failureKey) {
        try {
            Util.getOperatingSystem().open(URI.create(url));
            LOGGER.info("Opened {}", url);
        } catch (RuntimeException e) {
            // Nothing is worse than a button that appears to work and does not, so the
            // address is printed regardless.
            LOGGER.warn("Could not open a browser for {}", url, e);
            context.getSource().sendFeedback(Text.translatable(failureKey, url));
        }
    }

    // ---- link report --------------------------------------------------------

    private static Text status() {
        SteveHudLink link = SteveHudClient.LINK;
        if (!link.ready()) {
            return Text.translatable("stevehud.command.link.none");
        }
        return Text.translatable("stevehud.command.link.status",
                link.serverHello().map(hello -> hello.implementation()).orElse("?"),
                Protocol.VERSION,
                link.revision(),
                HudState.state().describeScores());
    }
}
