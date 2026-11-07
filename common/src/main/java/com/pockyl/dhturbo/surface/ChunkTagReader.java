package com.pockyl.dhturbo.surface;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

import org.jetbrains.annotations.Nullable;

/**
 * The part of {@link SavedChunks} that depends on the Minecraft version: the sections of a chunk tag in the current format.
 * {@link SerializableChunkData#read} is not an option, it reports every section to the real level's points of interest and
 * light engine; {@code parse} is free of that.
 */
public final class ChunkTagReader {
    private final ServerLevel level;

    public ChunkTagReader(ServerLevel level) {
        this.level = level;
    }

    /** Null when the chunk is not decorated yet. */
    @Nullable
    public LevelChunkSection[] sections(CompoundTag tag) {
        SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
        if (data == null || !data.chunkStatus().isOrAfter(ChunkStatus.FEATURES)) {
            return null;
        }
        LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        for (SerializableChunkData.SectionData section : data.sectionData()) {
            int index = level.getSectionIndexFromSectionY(section.y());
            if (index >= 0 && index < sections.length && section.chunkSection() != null) {
                sections[index] = section.chunkSection();
            }
        }
        return sections;
    }

    /** A chunk as the game saves it. */
    public static CompoundTag write(ServerLevel level, ChunkAccess chunk) {
        return SerializableChunkData.copyOf(level, chunk).write();
    }
}
