package com.pockyl.dhturbo.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * How a level generates its terrain, compact enough to send to clients: the noise settings by registry key, the biome
 * source as JSON (normally just a preset key) and a fingerprint of both with everything they reference. A client
 * rebuilds the generator from its own data packs and uses it only if its fingerprint is the same, so a server with
 * terrain mods or data packs the client lacks is never drawn wrong.
 */
public record LevelGenInfo(ResourceKey<Level> dimension, ResourceKey<NoiseGeneratorSettings> settings, String biomeSource,
                           int fingerprint) {
    public static final StreamCodec<ByteBuf, LevelGenInfo> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION), LevelGenInfo::dimension,
            ResourceKey.streamCodec(Registries.NOISE_SETTINGS), LevelGenInfo::settings,
            ByteBufCodecs.STRING_UTF8, LevelGenInfo::biomeSource,
            ByteBufCodecs.INT, LevelGenInfo::fingerprint,
            LevelGenInfo::new);

    /** A rebuilt generator. */
    public record Resolved(Holder<NoiseGeneratorSettings> settings, BiomeSource biomeSource, NoiseBasedChunkGenerator generator) {
    }

    /** Describes a server level, or empty when it does not generate with named noise settings. */
    public static Optional<LevelGenInfo> of(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            return Optional.empty();
        }
        return describe(level.dimension(), generator.generatorSettings(), generator.getBiomeSource(), level.registryAccess());
    }

    public static Optional<LevelGenInfo> describe(ResourceKey<Level> dimension, Holder<NoiseGeneratorSettings> settings,
                                                  BiomeSource biomeSource, RegistryAccess registries) {
        Optional<ResourceKey<NoiseGeneratorSettings>> key = settings.unwrapKey();
        if (key.isEmpty()) {
            return Optional.empty();
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        String source = BiomeSource.CODEC.encodeStart(ops, biomeSource).getOrThrow().toString();
        return Optional.of(new LevelGenInfo(dimension, key.get(), source, fingerprint(settings.value(), source, ops)));
    }

    /** Rebuilds the generator from the given registries, or null when they do not have the same one. */
    @Nullable
    public Resolved resolve(RegistryAccess registries) {
        Optional<Holder.Reference<NoiseGeneratorSettings>> settingsHolder =
                registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolder(settings);
        if (settingsHolder.isEmpty()) {
            return null;
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        Optional<BiomeSource> source = BiomeSource.CODEC.parse(ops, JsonParser.parseString(biomeSource)).result();
        if (source.isEmpty()) {
            return null;
        }
        String reencoded = BiomeSource.CODEC.encodeStart(ops, source.get()).getOrThrow().toString();
        if (fingerprint(settingsHolder.get().value(), reencoded, ops) != fingerprint) {
            return null;
        }
        return new Resolved(settingsHolder.get(), source.get(), new NoiseBasedChunkGenerator(source.get(), settingsHolder.get()));
    }

    /**
     * Hash of the noise settings with every density function they use inlined (references resolved), plus the biome
     * source: equal only when terrain shape and biome layout are the same.
     */
    private static int fingerprint(NoiseGeneratorSettings settings, String biomeSource, RegistryOps<JsonElement> ops) {
        // Direct codec through plain JSON ops: holders are written out in full, not as registry keys.
        String shape = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, settings).result()
                .map(JsonElement::toString)
                .orElseGet(() -> NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, settings).getOrThrow().toString());
        return 31 * shape.hashCode() + biomeSource.hashCode();
    }

    @Override
    public String toString() {
        return dimension.location() + " (" + settings.location() + ")";
    }
}
