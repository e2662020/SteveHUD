package com.rate.stevehud.plugin.net;

import com.rate.stevehud.protocol.Envelope;
import com.rate.stevehud.protocol.EnvelopeCodec;
import com.rate.stevehud.protocol.Framing;
import com.rate.stevehud.protocol.MessageType;
import com.rate.stevehud.protocol.Protocol;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Pushes messages to a client over the plugin channel.
 *
 * <p>All sending goes through here so that three invariants hold in one place:
 * every message carries the current revision, every message is fragmented if it
 * would not fit an envelope, and a recipient that cannot receive is reported
 * rather than silently skipped.
 *
 * <p>That last one matters more than it looks. The server will only deliver a
 * plugin message to a client that has told it the channel is accepted, and a
 * client which never does so produces no error at all — the message simply goes
 * nowhere. A silent no-op is the worst possible failure mode for a broadcast
 * tool, so it is logged loudly the first time it happens to each player.
 */
public final class ChannelSender {

    private static final AtomicInteger MESSAGE_IDS = new AtomicInteger();

    private final Plugin plugin;
    private final EnvelopeCodec codec = new EnvelopeCodec();
    private final AtomicLong revision = new AtomicLong();
    private final Set<UUID> warned = ConcurrentHashMap.newKeySet();

    public ChannelSender(Plugin plugin) {
        this.plugin = plugin;
    }

    /** The revision the server state is currently at. */
    public long revision() {
        return revision.get();
    }

    /** Allocates the next revision; call once per state change, not once per recipient. */
    public long nextRevision() {
        return revision.incrementAndGet();
    }

    /**
     * Whether {@code player} has declared that it accepts this channel. When this
     * is false the message below will not arrive, whatever we do with it.
     */
    public boolean canReceive(Player player) {
        return player.getListeningPluginChannels().contains(Protocol.CHANNEL);
    }

    /**
     * Sends one message, fragmenting it if needed.
     *
     * @param rev  the revision the body describes
     * @param body any object the codec can serialise
     */
    public void send(Player player, MessageType type, long rev, Object body) {
        if (!canReceive(player)) {
            if (warned.add(player.getUniqueId())) {
                plugin.getLogger().warning(
                        player.getName() + " has not registered the " + Protocol.CHANNEL
                                + " channel, so nothing sent to it will arrive. A SteveHUD client "
                                + "registers the channel on connect; a vanilla client cannot. "
                                + "This warning is printed once per player per session.");
            }
            return;
        }

        String json = codec.encodeBody(body);
        String messageId = player.getUniqueId() + "-" + MESSAGE_IDS.incrementAndGet();

        for (Envelope fragment : Framing.split(type, rev, messageId, json)) {
            byte[] bytes = codec.encode(fragment).getBytes(StandardCharsets.UTF_8);
            try {
                player.sendPluginMessage(plugin, Protocol.CHANNEL, bytes);
            } catch (RuntimeException e) {
                // A single failing recipient must never disturb the others, nor the
                // match running on the server.
                plugin.getLogger().log(Level.WARNING,
                        "Failed to send " + type + " to " + player.getName(), e);
                return;
            }
        }
    }
}
