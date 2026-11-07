package com.pockyl.dhturbo.surface;

import com.mojang.serialization.Codec;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

import org.jetbrains.annotations.Nullable;

/**
 * The part of {@link SavedChunks} that depends on the Minecraft version: the sections of a chunk tag in the current format.
 * {@link ChunkSerializer#read} is not an option, it reports every section to the real level's points of interest and
 * light engine.
 */
public final class ChunkTagReader {
    private final ServerLevel level;
    private final Registry<Biome> biomes;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;

    public ChunkTagReader(ServerLevel level) {
        this.level = level;
        this.biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.biomeCodec = ChunkSerializer.makeBiomeCodec(biomes);
    }

    /** Null when the chunk is not decorated yet. */
    @Nullable
    public LevelChunkSection[] sections(CompoundTag tag) {
        if (!ChunkStatus.byName(tag.getString("Status")).isOrAfter(ChunkStatus.FEATURES)) {
            return null;
        }
        LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        ListTag saved = tag.getList(ChunkSerializer.SECTIONS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag section = saved.getCompound(i);
            int index = level.getSectionIndexFromSectionY(section.getByte("Y"));
            if (index >= 0 && index < sections.length && section.contains("block_states", Tag.TAG_COMPOUND)) {
                sections[index] = new LevelChunkSection(blocks(section), biomes(section));
            }
        }
        return sections;
    }

    /** A chunk as the game saves it. */
    public static CompoundTag write(ServerLevel level, ChunkAccess chunk) {
        return ChunkSerializer.write(level, chunk);
    }

    private static PalettedContainer<BlockState> blocks(CompoundTag section) {
        return Util.getOrThrow(ChunkSerializer.BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, section.getCompound("block_states"))
                .promotePartial(error -> { }), IllegalStateException::new);
    }

    private PalettedContainerRO<Holder<Biome>> biomes(CompoundTag section) {
        if (section.contains("biomes", Tag.TAG_COMPOUND)) {
            return Util.getOrThrow(biomeCodec.parse(NbtOps.INSTANCE, section.getCompound("biomes"))
                    .promotePartial(error -> { }), IllegalStateException::new);
        }
        return new PalettedContainer<>(biomes.asHolderIdMap(), biomes.getHolderOrThrow(Biomes.PLAINS),
                PalettedContainer.Strategy.SECTION_BIOMES);
    }
}
