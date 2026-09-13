package com.rate.stevehud.mod.mc.net;

import com.rate.stevehud.protocol.Protocol;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server to client: one SteveHUD envelope, already serialised to JSON.
 *
 * <p>The payload knows nothing about the message format. The whole wire contract
 * lives in the Minecraft-free {@code protocol} module, so this class stays
 * identical across every supported Minecraft version and the format can evolve
 * without touching several copies.
 *
 * <p>Held as bytes rather than as a decoded string because that is what actually
 * travels on this channel; see {@link ChannelPayloads}.
 */
public record MainPayload(byte[] bytes) implements CustomPayload {

    public static final CustomPayload.Id<MainPayload> ID = new CustomPayload.Id<>(
            Identifier.of(Protocol.CHANNEL_NAMESPACE, Protocol.CHANNEL_PATH));

    public static final PacketCodec<RegistryByteBuf, MainPayload> CODEC =
            ChannelPayloads.rawBytes().xmap(MainPayload::new, MainPayload::bytes);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
