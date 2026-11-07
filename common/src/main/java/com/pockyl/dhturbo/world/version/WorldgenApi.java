package com.pockyl.dhturbo.world.version;

import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.ticks.ProtoChunkTicks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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

    /** The noise chunk of a chunk's terrain step, which its fill, surface and carvers share; close it afterwards. */
    public static NoiseChunk noiseChunk(NoiseBasedChunkGenerator generator, ChunkAccess chunk, StructureManager manager, RandomState random) {
        return generator.createNoiseChunk(chunk, manager, Blender.empty(), random,
                generator.generatorSettings().value().noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration()));
    }

    public static void carve(NoiseBasedChunkGenerator generator, WorldGenRegion region, RandomState random, BiomeManager biomes,
                             ChunkAccess chunk, NoiseChunk noise) {
        generator.generateCarvers(chunk, Blender.empty(), noise, random, biomes, region,
                generator.generatorSettings().value().materialRule().value());
    }

    /** The structures starting in a chunk, from the level's own generator state. */
    public static void createStarts(ChunkGenerator generator, ServerLevel level, ChunkAccess chunk) {
        generator.createStructures(level.registryAccess(), level.getChunkSource().getGeneratorState(),
                level.structureManager(), chunk, level.getStructureTemplateManager(), level.dimension());
    }

    public static StructureStart start(Holder<Structure> holder, RegistryAccess registries, ChunkGenerator generator,
                                       RandomState random, StructureTemplateManager templates, long seed, ChunkPos pos,
                                       LevelHeightAccessor height) {
        Structure structure = holder.value();
        // The level key only labels the game's profiler.
        return structure.generate(holder, Level.OVERWORLD, registries, generator, generator.getBiomeSource(),
                random.createClimateSampler(SamplerContext.builder().enableCaches().build()), random, templates, seed, pos, 0, height,
                structure.biomes()::contains);
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

    /** {@code seaLevel} is where the temperature drop starts. */
    public static boolean coldEnoughToSnow(Biome biome, BlockPos pos, int seaLevel) {
        return biome.coldEnoughToSnow(pos, seaLevel);
    }

    public static int lightBlock(BlockState state) {
        return state.getLightDampening();
    }

    public static boolean generateStructures(ServerLevel level) {
        return level.getServer().getWorldGenSettings().options().generateStructures();
    }

    /** The surface step of a chunk. No list of possible biomes: it only lets biome conditions answer early. */
    public static void buildSurface(NoiseBasedChunkGenerator generator, ChunkAccess chunk, NoiseChunk noise, RandomState random,
                                    BiomeManager biomes) {
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        generator.buildSurface(chunk, noise, random, biomes, null, settings.materialRule().value());
    }

    /** The block providers of a biome's flowers, the plain block features bone meal can grow. */
    public static List<BlockStateProvider> flowerProviders(Biome biome) {
        return biome.getGenerationSettings().getBoneMealFeatures().stream()
                .filter(SimpleBlockFeature.class::isInstance)
                .map(feature -> ((SimpleBlockFeature) feature).toPlace().value())
                .toList();
    }

    /** Places a feature of a biome's generation step. */
    public static void place(PlacedFeature feature, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        new FeaturePlacer(level, generator).placeWithBiomeCheck(feature, random, origin);
    }

    /**
     * Where a feature would be placed, in the order {@code FeaturePlacer} walks its modifiers, so what {@code place} does
     * with the random between them stays in step with the game.
     */
    public static void placements(PlacedFeature feature, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                  Consumer<BlockPos> place) {
        PlacementContext context = new PlacementContext(level, generator, Optional.of(feature));
        walk(feature.placement(), 0, context, random, origin, place);
    }

    private static void walk(List<PlacementModifier> placement, int index, PlacementContext context, RandomSource random, BlockPos pos,
                             Consumer<BlockPos> place) {
        if (index == placement.size()) {
            place.accept(pos);
            return;
        }
        List<BlockPos> modified = new ArrayList<>();
        placement.get(index).modify(context, random, pos, modified::add);
        for (BlockPos next : modified) {
            walk(placement, index + 1, context, random, next, place);
        }
    }

    /** The check 26.3 replaced with a block tag, which the blocks of other mods may lack. */
    public static boolean blocksMotion(BlockState state) {
        return state.isSolid() && !state.is(Blocks.COBWEB) && !state.is(Blocks.BAMBOO_SAPLING);
    }

    /** A provider's block without a level; the rule based ones read the level, so they throw here. */
    public static BlockState state(BlockStateProvider provider, RandomSource random, BlockPos pos) {
        return provider.getState(null, random, pos);
    }

    /** Sets a block as a world generation region does; {@code flags} are the ones its {@code setBlock} got. */
    public static BlockState setBlock(ChunkAccess chunk, BlockPos pos, BlockState state, int flags) {
        return chunk.setBlockState(pos, state, flags);
    }

    /** As {@code WorldGenRegion.setBlock}: the position the block asks to post process, which can be another one. */
    public static void markPostProcessing(WorldGenRegion region, BlockState state, BlockPos pos, int flags) {
        if ((flags & Block.UPDATE_KNOWN_SHAPE) == 0) {
            BlockPos target = state.getPostProcessPos(region, pos);
            if (target != null) {
                region.getChunk(target).markPosForPostProcessing(target);
            }
        }
    }
}
