package com.pockyl.dhturbo.world.version;

import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.ticks.ProtoChunkTicks;

import java.util.List;

/**
 * What differs between Minecraft versions in world generation. Every line of the mod has this class with the same
 * methods, so a change to the generator merges into all of them.
 */
public final class WorldgenApi {
    /** Vanilla's margin around a beardifier's pieces. */
    private static final int BEARD_BOX_MARGIN = 24;

    private WorldgenApi() {
    }

    public static ProtoChunk protoChunk(ChunkPos pos, LevelHeightAccessor height, RegistryAccess registries) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, height, containers(height, registries), null);
    }

    public static ProtoChunk protoChunk(ChunkPos pos, LevelChunkSection[] sections, LevelHeightAccessor height,
                                        RegistryAccess registries) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), height,
                containers(height, registries), null);
    }

    /** A level keeps one for its chunks. */
    private static PalettedContainerFactory containers(LevelHeightAccessor height, RegistryAccess registries) {
        return height instanceof Level level ? level.palettedContainerFactory() : PalettedContainerFactory.create(registries);
    }

    public static void carve(NoiseBasedChunkGenerator generator, WorldGenRegion region, long seed, RandomState random,
                             BiomeManager biomes, StructureManager manager, ChunkAccess chunk) {
        generator.applyCarvers(region, seed, random, biomes, manager, chunk);
    }

    /** The structures starting in a chunk, from the level's own generator state. */
    public static void createStarts(ChunkGenerator generator, ServerLevel level, ChunkAccess chunk) {
        generator.createStructures(level.registryAccess(), level.getChunkSource().getGeneratorState(),
                level.structureManager(), chunk, level.getStructureManager(), level.dimension());
    }

    public static StructureStart start(Holder<Structure> holder, RegistryAccess registries, ChunkGenerator generator,
                                       RandomState random, StructureTemplateManager templates, long seed, ChunkPos pos,
                                       LevelHeightAccessor height) {
        Structure structure = holder.value();
        // The level key only labels the game's profiler.
        return structure.generate(holder, Level.OVERWORLD, registries, generator, generator.getBiomeSource(), random, templates,
                seed, pos, 0, height, structure.biomes()::contains);
    }

    /** Vanilla's beardifier answers only inside the box of what it holds, 24 blocks around it. */
    public static Beardifier beardifier(ObjectList<Beardifier.Rigid> rigids, ObjectList<JigsawJunction> junctions) {
        BoundingBox box = null;
        for (Beardifier.Rigid rigid : rigids) {
            box = box == null ? rigid.box() : BoundingBox.encapsulating(box, rigid.box());
        }
        for (JigsawJunction junction : junctions) {
            BoundingBox point = new BoundingBox(new BlockPos(junction.getSourceX(), junction.getSourceGroundY(), junction.getSourceZ()));
            box = box == null ? point : BoundingBox.encapsulating(box, point);
        }
        return new Beardifier(List.copyOf(rigids), List.copyOf(junctions), box == null ? null : box.inflatedBy(BEARD_BOX_MARGIN));
    }

    /** The same router with another final density. */
    public static NoiseRouter withFinalDensity(NoiseRouter router, DensityFunction finalDensity) {
        return new NoiseRouter(router.barrierNoise(), router.fluidLevelFloodednessNoise(),
                router.fluidLevelSpreadNoise(), router.lavaNoise(), router.temperature(), router.vegetation(),
                router.continents(), router.erosion(), router.depth(), router.ridges(),
                router.preliminarySurfaceLevel(), finalDensity, router.veinToggle(), router.veinRidged(),
                router.veinGap());
    }

    /** {@code seaLevel} is where the temperature drop starts. */
    public static boolean coldEnoughToSnow(Biome biome, BlockPos pos, int seaLevel) {
        return biome.coldEnoughToSnow(pos, seaLevel);
    }

    public static int lightBlock(BlockState state) {
        return state.getLightBlock();
    }

    /** Sets a block as a world generation region does; {@code flags} are the ones its {@code setBlock} got. */
    public static BlockState setBlock(ChunkAccess chunk, BlockPos pos, BlockState state, int flags) {
        return chunk.setBlockState(pos, state, flags);
    }

    /** Whether a block set with these flags goes on the chunk's post processing list when it asks for that. */
    public static boolean marksPostProcessing(int flags) {
        return (flags & Block.UPDATE_KNOWN_SHAPE) == 0;
    }
}
