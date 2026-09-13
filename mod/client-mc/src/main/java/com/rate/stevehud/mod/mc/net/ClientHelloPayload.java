package com.rate.stevehud.mod.mc.net;

import com.rate.stevehud.protocol.Protocol;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client to server: the client's greeting, which tells the server which protocol
 * and capabilities it speaks before any state is pushed at it.
 *
 * <p>Shares the channel with {@link MainPayload}; the two directions are separate
 * registries, so one channel id serves both. Bytes rather than a string, for the
 * reason documented in {@link ChannelPayloads}.
 */
public record ClientHelloPayload(byte[] bytes) implements CustomPayload {

    public static final CustomPayload.Id<ClientHelloPayload> ID = new CustomPayload.Id<>(
            Identifier.of(Protocol.CHANNEL_NAMESPACE, Protocol.CHANNEL_PATH));

    public static final PacketCodec<RegistryByteBuf, ClientHelloPayload> CODEC =
            ChannelPayloads.rawBytes().xmap(ClientHelloPayload::new, ClientHelloPayload::bytes);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
