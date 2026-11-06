package com.pockyl.dhturbo.surface;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.ticks.ProtoChunkTicks;

import com.pockyl.dhturbo.DhTurbo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chunks the world has saved, read without touching the running game: the seed knows nothing of builds, adventure maps
 * or terrain from other generators, so these go to Distant Horizons as they are. {@link ChunkSerializer#read} is not an
 * option, it reports every section to the real level's points of interest and light engine. Blocks and biomes are all
 * Distant Horizons needs. Not thread safe: one reader per level.
 */
public final class SavedChunks {
    private static final int REGION_SHIFT = 5;
    private static final int REGION_MASK = 31;
    /** A region file starts with one non-zero entry per chunk it holds. */
    private static final int HEADER_BYTES = 4096;
    private static final long READ_TIMEOUT_SECONDS = 30;

    private final ServerLevel level;
    private final ChunkMap storage;
    private final Path regions;
    private final Registry<Biome> biomes;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final Long2ObjectMap<BitSet> present = new Long2ObjectOpenHashMap<>();
    private final AtomicBoolean reported = new AtomicBoolean();

    public SavedChunks(ServerLevel level) {
        this.level = level;
        this.storage = level.getChunkSource().chunkMap;
        this.regions = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT))
                .resolve("region");
        this.biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.biomeCodec = ChunkSerializer.makeBiomeCodec(biomes);
    }

    /** Whether the world holds this chunk on disk. Region headers are read once. */
    public boolean exists(int chunkX, int chunkZ) {
        int regionX = chunkX >> REGION_SHIFT;
        int regionZ = chunkZ >> REGION_SHIFT;
        BitSet chunks = present.computeIfAbsent(ChunkPos.asLong(regionX, regionZ),
                key -> header(regions.resolve("r." + regionX + "." + regionZ + ".mca")));
        return chunks.get((chunkX & REGION_MASK) + (chunkZ & REGION_MASK) * (REGION_MASK + 1));
    }

    /** Forgets the region headers, for chunks saved since they were read. */
    public void forget() {
        present.clear();
    }

    /** The chunks a region file holds; none when it does not exist or cannot be read. */
    public static BitSet header(Path regionFile) {
        BitSet chunks = new BitSet();
        try (InputStream in = Files.newInputStream(regionFile)) {
            IntBuffer entries = ByteBuffer.wrap(in.readNBytes(HEADER_BYTES)).asIntBuffer();
            for (int i = 0; i < entries.limit(); i++) {
                if (entries.get(i) != 0) {
                    chunks.set(i);
                }
            }
        } catch (NoSuchFileException e) {
            // No chunks saved in this region.
        } catch (IOException e) {
            DhTurbo.LOGGER.warn("Could not read the saved chunks of {}", regionFile, e);
        }
        return chunks;
    }

    /** A saved chunk as a proto chunk of its own, empty when it is unfinished or cannot be read. */
    public Optional<ChunkAccess> load(int chunkX, int chunkZ) throws InterruptedException {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        try {
            return storage.read(pos).get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .map(storage::upgradeChunkTag)
                    .flatMap(tag -> decode(pos, tag));
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            if (reported.compareAndSet(false, true)) {
                DhTurbo.LOGGER.warn("Could not read saved chunk {} of {}; distant terrain stays generated there, "
                        + "further chunks are not reported", pos, level.dimension().location(), e);
            }
            return Optional.empty();
        }
    }

    /** Blocks and biomes of a chunk tag in the current format; chunks not decorated yet are left out. */
    public Optional<ChunkAccess> decode(ChunkPos pos, CompoundTag tag) {
        if (!ChunkStatus.byName(tag.getString("Status")).isOrAfter(ChunkStatus.FEATURES)) {
            return Optional.empty();
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
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
                level, biomes, null);
        // The status real generation hands over: Distant Horizons lights such chunks itself.
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
        return Optional.of(chunk);
    }

    private static PalettedContainer<BlockState> blocks(CompoundTag section) {
        return ChunkSerializer.BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, section.getCompound("block_states"))
                .promotePartial(error -> { })
                .getOrThrow(IllegalStateException::new);
    }

    private PalettedContainerRO<Holder<Biome>> biomes(CompoundTag section) {
        if (section.contains("biomes", Tag.TAG_COMPOUND)) {
            return biomeCodec.parse(NbtOps.INSTANCE, section.getCompound("biomes"))
                    .promotePartial(error -> { })
                    .getOrThrow(IllegalStateException::new);
        }
        return new PalettedContainer<>(biomes.asHolderIdMap(), biomes.getHolderOrThrow(Biomes.PLAINS),
                PalettedContainer.Strategy.SECTION_BIOMES);
    }
}
