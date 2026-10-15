package com.pockyl.dhturbo.network;

import net.minecraft.network.FriendlyByteBuf;

import com.pockyl.dhturbo.world.LevelGenInfo;

import java.util.List;

/**
 * Server -> client: the world seed and how each level generates, so a client with the mod can generate distant
 * terrain itself on a server without Distant Horizons. Optional channel: players without the mod can join.
 */
public record WorldgenInfoPayload(long seed, List<LevelGenInfo> levels) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(seed);
        buffer.writeCollection(levels, (out, level) -> level.write(out));
    }

    public static WorldgenInfoPayload decode(FriendlyByteBuf buffer) {
        long seed = buffer.readVarLong();
        List<LevelGenInfo> levels = buffer.readList(LevelGenInfo::read);
        return new WorldgenInfoPayload(seed, List.copyOf(levels));
    }
}
