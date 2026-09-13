package com.rate.stevehud.mod.mc;

import com.rate.stevehud.mod.SteveHudMod;
import com.rate.stevehud.mod.client.SteveHudLink;
import com.rate.stevehud.mod.mc.command.ClientCommands;
import com.rate.stevehud.mod.mc.config.Configs;
import com.rate.stevehud.mod.mc.hud.HudState;
import com.rate.stevehud.mod.mc.hud.MatchHud;
import com.rate.stevehud.mod.mc.net.ClientHelloPayload;
import com.rate.stevehud.mod.mc.net.MainPayload;
import com.rate.stevehud.mod.mc.web.WebBridge;
import com.rate.stevehud.protocol.Envelope;
import com.rate.stevehud.protocol.MessageType;
import com.rate.stevehud.protocol.Protocol;
import com.rate.stevehud.protocol.model.LayoutBody;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * The client entry point.
 *
 * <p>Everything below this class — the wire format, reassembly, revision
 * bookkeeping, handshake state, the settings model — lives in modules that
 * reference no Minecraft class. This class exists only to bind that logic to
 * Fabric's APIs and the client thread, so the per-version surface stays as thin
 * as it can be.
 *
 * <p>The UTF-8 boundary lives here rather than deeper down, because the payload
 * carries bytes and the protocol module deliberately works in text.
 */
public final class SteveHudClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SteveHudMod.NAME);

    public static final SteveHudLink LINK =
            new SteveHudLink(implementation(), SteveHudMod.CAPABILITIES, LOGGER::warn);

    @Override
    public void onInitializeClient() {
        // Settings first: everything below may want to read them.
        Configs.register();
        ClientCommands.register();
        WebBridge.initialize();

        PayloadTypeRegistry.playS2C().register(MainPayload.ID, MainPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ClientHelloPayload.ID, ClientHelloPayload.CODEC);

        // The receiver runs on the network thread; hop to the client thread before
        // touching anything the game also touches.
        ClientPlayNetworking.registerGlobalReceiver(MainPayload.ID, (payload, context) ->
                context.client().execute(() ->
                        LINK.accept(new String(payload.bytes(), StandardCharsets.UTF_8))));

        LINK.addListener(SteveHudClient::onMessage);

        registerHud();
        registerTicker();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            LINK.reset();
            // Replays the package's entry animation for each session, so a viewer
            // always sees the graphics arrive rather than finding them already up.
            MatchHud.onWorldJoin();
            greetServer();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LINK.reset();
            // The package belonged to the server we just left. Carrying it into the
            // next session would silently restyle a server that never asked for it.
            WebBridge.clearServerLayout();
            // Keep the web overlay up across a reconnect: it shows the last known
            // state and reports the link as down, which is more useful on air than a
            // blank page.
            WebBridge.publish();
        });

        LOGGER.info("SteveHUD ready - protocol v{}, {} capabilities",
                Protocol.VERSION, SteveHudMod.CAPABILITIES.size());
    }

    /**
     * Watches the local graphics server and republishes when the settings change.
     *
     * <p>On a client tick rather than a render frame: it is housekeeping, not
     * drawing, and twenty times a second is ample for it.
     */
    private static void registerTicker() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> WebBridge.tick());
    }

    /**
     * Hooks the example HUD into the game's render pass.
     *
     * <p>{@code HudRenderCallback} is deprecated from 1.21.4 onward in favour of
     * {@code HudElementRegistry}, but it is the only HUD hook that exists and
     * fires on ALL four versions we target — the replacement arrived in 1.21.8 and
     * the intermediate one exists only in 1.21.4. Using the deprecated call keeps
     * one registration instead of three, and it still works. The suppression is
     * deliberate and this comment is the reason for it.
     */
    @SuppressWarnings("deprecation")
    private static void registerHud() {
        HudRenderCallback.EVENT.register((context, tickCounter) -> MatchHud.render(context));
    }

    private static void greetServer() {
        // Only greet a server that will actually accept it. Whether the server
        // advertises the channel is a fact worth logging rather than assuming:
        // a server that has never heard of us will not have advertised it.
        if (!ClientPlayNetworking.canSend(ClientHelloPayload.ID)) {
            LOGGER.info("The server has not advertised the {} channel, so no greeting was sent. "
                    + "This is expected against a server without the SteveHUD plugin, and the "
                    + "client keeps working for outbound inspection either way.", Protocol.CHANNEL);
            return;
        }
        ClientPlayNetworking.send(new ClientHelloPayload(
                LINK.encodeHello().getBytes(StandardCharsets.UTF_8)));
        LOGGER.info("SteveHUD handshake sent on {}", Protocol.CHANNEL);
    }

    private static void onMessage(Envelope envelope) {
        if (envelope.type() == MessageType.HELLO) {
            LINK.serverHello().ifPresent(hello -> {
                LOGGER.info("SteveHUD server: {} (protocol v{}, capabilities {})",
                        hello.implementation(), hello.protocolVersion(), hello.capabilities());
                announceInChat(hello.implementation());
            });
            return;
        }
        if (envelope.type() == MessageType.DELTA || envelope.type() == MessageType.SNAPSHOT) {
            // The server owns the broadcast state; the client mirrors it and both the
            // in-game package and the local web overlay render from that one copy.
            HudState.apply(envelope.body());
            WebBridge.publish();
            return;
        }
        if (envelope.type() == MessageType.LAYOUT) {
            // Which package to draw is the server's call while it makes one. Separate
            // from the local file the editor edits: an operator's own packaging work
            // stays editable whether or not a server package is on air.
            LayoutBody body;
            try {
                body = LINK.decodeBody(envelope, LayoutBody.class);
            } catch (RuntimeException e) {
                LOGGER.warn("Ignoring an unreadable layout message: {}", e.getMessage());
                return;
            }
            if (WebBridge.applyServerLayout(body)) {
                LOGGER.info("SteveHUD layout from the server; scope is now {}", WebBridge.layoutScope());
            }
            return;
        }
        if (Configs.get().verboseLinkLogging) {
            LOGGER.info("SteveHUD received {} at revision {} ({} chars)",
                    envelope.type(), envelope.rev(), envelope.body().length());
        }
    }

    private static void announceInChat(String serverImplementation) {
        if (!Configs.get().announceHandshakeInChat) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        // A translation key, not a literal: the mod has to read correctly on a
        // client that is not in English.
        client.player.sendMessage(
                Text.translatable("stevehud.chat.connected", serverImplementation)
                        .formatted(Formatting.GOLD),
                false);
    }

    private static String implementation() {
        return FabricLoader.getInstance().getModContainer(SteveHudMod.MOD_ID)
                .map(container -> SteveHudMod.NAME + "/"
                        + container.getMetadata().getVersion().getFriendlyString())
                .orElse(SteveHudMod.NAME + "/unknown");
    }
}
