package com.pockyl.dhturbo.world.version;

import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.ticks.ProtoChunkTicks;

/**
 * What differs between Minecraft versions in world generation. Every line of the mod has this class with the same
 * methods, so a change to the generator merges into all of them.
 */
public final class WorldgenApi {
    private WorldgenApi() {
    }

    public static ProtoChunk protoChunk(ChunkPos pos, LevelHeightAccessor height, RegistryAccess registries) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, height, registries.registryOrThrow(Registries.BIOME), null);
    }

    public static ProtoChunk protoChunk(ChunkPos pos, LevelChunkSection[] sections, LevelHeightAccessor height,
                                        RegistryAccess registries) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), height,
                registries.registryOrThrow(Registries.BIOME), null);
    }

    public static void carve(NoiseBasedChunkGenerator generator, WorldGenRegion region, long seed, RandomState random,
                             BiomeManager biomes, StructureManager manager, ChunkAccess chunk) {
        generator.applyCarvers(region, seed, random, biomes, manager, chunk, GenerationStep.Carving.AIR);
    }

    /** The structures starting in a chunk, from the level's own generator state. */
    public static void createStarts(ChunkGenerator generator, ServerLevel level, ChunkAccess chunk) {
        generator.createStructures(level.registryAccess(), level.getChunkSource().getGeneratorState(),
                level.structureManager(), chunk, level.getStructureManager());
    }

    public static StructureStart start(Holder<Structure> holder, RegistryAccess registries, ChunkGenerator generator,
                                       RandomState random, StructureTemplateManager templates, long seed, ChunkPos pos,
                                       LevelHeightAccessor height) {
        Structure structure = holder.value();
        return structure.generate(registries, generator, generator.getBiomeSource(), random, templates, seed, pos, 0, height,
                structure.biomes()::contains);
    }

    public static Beardifier beardifier(ObjectList<Beardifier.Rigid> rigids, ObjectList<JigsawJunction> junctions) {
        return new Beardifier(rigids.iterator(), junctions.iterator());
    }

    /** The same router with another final density. */
    public static NoiseRouter withFinalDensity(NoiseRouter router, DensityFunction finalDensity) {
        return new NoiseRouter(router.barrierNoise(), router.fluidLevelFloodednessNoise(),
                router.fluidLevelSpreadNoise(), router.lavaNoise(), router.temperature(), router.vegetation(),
                router.continents(), router.erosion(), router.depth(), router.ridges(),
                router.initialDensityWithoutJaggedness(), finalDensity, router.veinToggle(), router.veinRidged(),
                router.veinGap());
    }

    /** {@code seaLevel} is for the versions whose temperature drop starts there. */
    public static boolean coldEnoughToSnow(Biome biome, BlockPos pos, int seaLevel) {
        return biome.coldEnoughToSnow(pos);
    }

    public static int lightBlock(BlockState state) {
        return state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** Sets a block as a world generation region does; {@code flags} are the ones its {@code setBlock} got. */
    public static BlockState setBlock(ChunkAccess chunk, BlockPos pos, BlockState state, int flags) {
        return chunk.setBlockState(pos, state, false);
    }

    /** Whether a block set with these flags goes on the chunk's post processing list when it asks for that. */
    public static boolean marksPostProcessing(int flags) {
        return true;
    }
}
