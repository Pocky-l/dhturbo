package com.pockyl.dhturbo.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.world.LevelGenInfo;

import java.util.List;

/**
 * Server -> client: the world seed and how each level generates, so a client with the mod can generate distant
 * terrain itself on a server without Distant Horizons. Optional channel: players without the mod can join.
 */
public record WorldgenInfoPayload(long seed, List<LevelGenInfo> levels) implements CustomPacketPayload {
    public static final Type<WorldgenInfoPayload> TYPE = new Type<>(DhTurbo.id("worldgen_info"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WorldgenInfoPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, WorldgenInfoPayload::seed,
            LevelGenInfo.STREAM_CODEC.apply(ByteBufCodecs.list()), WorldgenInfoPayload::levels,
            WorldgenInfoPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
