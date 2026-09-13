package com.rate.stevehud.mod.mc.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;

/**
 * The wire shape of a SteveHUD payload: the whole packet body, verbatim.
 *
 * <p>This is not a stylistic choice, it is what makes the channel interoperable
 * with a server plugin. A Bukkit or Paper server sends a plugin message by writing
 * the raw byte array into the payload with no length prefix and no field
 * structure — the packet's own framing is the only delimiter.
 *
 * <p>A codec such as {@code PacketCodecs.STRING}, which expects a length-prefixed
 * string, therefore misreads the first byte of the JSON as a length and leaves the
 * tail of the packet unconsumed. The client reports that as
 * "packet ... was larger than I expected" and disconnects. Reading exactly the
 * remaining bytes is the fix, and it is also what vanilla's own
 * {@code DiscardedPayload} does for unknown channels.
 *
 * <p>Both directions use this shape, so a payload we send is read by a plugin the
 * same way a plugin's payload is read by us.
 */
final class ChannelPayloads {

    private ChannelPayloads() {
    }

    static PacketCodec<RegistryByteBuf, byte[]> rawBytes() {
        return PacketCodec.of(
                (value, buf) -> buf.writeBytes(value),
                buf -> {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return bytes;
                });
    }
}
