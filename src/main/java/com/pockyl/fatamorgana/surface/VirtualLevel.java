package com.pockyl.fatamorgana.surface;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.RandomSource;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import com.pockyl.fatamorgana.Fatamorgana;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A pretend world for running vanilla features without chunks: reads come from the approximate terrain (ground,
 * top block, sea), writes are recorded. {@link WorldGenLevel} is a huge interface, so it is a dynamic proxy that
 * implements the handful of methods features and placement modifiers use; default methods run as written on top of
 * them, anything else throws and the feature is skipped for that chunk.
 */
final class VirtualLevel implements InvocationHandler {
    /** Approximate terrain the features are placed on. */
    interface Terrain {
        /** First non-solid Y of the column. */
        int ground(int x, int z);

        /** Block on top of the ground, before snow (vanilla adds snow after vegetation). */
        BlockState top(int x, int z);

        Holder<Biome> biome(int x, int y, int z);
    }

    private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();

    private final Terrain terrain;
    private final long seed;
    private final int minY;
    private final int height;
    private final int seaLevel;
    private final RegistryAccess registries;
    private final DimensionType dimensionType;
    private final Long2ObjectMap<BlockState> writes = new Long2ObjectOpenHashMap<>();
    final WorldGenLevel proxy;

    VirtualLevel(Terrain terrain, long seed, int minY, int height, int seaLevel, RegistryAccess registries,
                 DimensionType dimensionType) {
        this.terrain = terrain;
        this.seed = seed;
        this.minY = minY;
        this.height = height;
        this.seaLevel = seaLevel;
        this.registries = registries;
        this.dimensionType = dimensionType;
        this.proxy = (WorldGenLevel) Proxy.newProxyInstance(VirtualLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, this);
    }

    Long2ObjectMap<BlockState> writes() {
        return writes;
    }

    BlockState blockState(BlockPos pos) {
        BlockState written = writes.get(pos.asLong());
        if (written != null) {
            return written;
        }
        int ground = terrain.ground(pos.getX(), pos.getZ());
        int y = pos.getY();
        if (y < ground - 1) {
            return y < ground - 4 ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        }
        if (y == ground - 1) {
            return ground <= seaLevel ? Blocks.SAND.defaultBlockState() : terrain.top(pos.getX(), pos.getZ());
        }
        return y < seaLevel ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    private int height(Heightmap.Types type, int x, int z) {
        int ground = terrain.ground(x, z);
        return switch (type) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> ground;
            default -> Math.max(ground, seaLevel);
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        int count = args == null ? 0 : args.length;
        switch (name) {
            case "getBlockState":
                return blockState((BlockPos) args[0]);
            case "getFluidState":
                return blockState((BlockPos) args[0]).getFluidState();
            case "isStateAtPosition":
                return ((Predicate<BlockState>) args[1]).test(blockState((BlockPos) args[0]));
            case "isFluidAtPosition":
                return ((Predicate<FluidState>) args[1]).test(blockState((BlockPos) args[0]).getFluidState());
            case "setBlock":
                writes.put(((BlockPos) args[0]).asLong(), (BlockState) args[1]);
                return true;
            case "removeBlock", "destroyBlock":
                writes.put(((BlockPos) args[0]).asLong(), Blocks.AIR.defaultBlockState());
                return true;
            case "getHeight":
                return count == 3 ? height((Heightmap.Types) args[0], (int) args[1], (int) args[2]) : height;
            case "getMinBuildHeight":
                return minY;
            case "getSeaLevel":
                return seaLevel;
            case "getSeed":
                return seed;
            case "getBiome":
                BlockPos pos = (BlockPos) args[0];
                return terrain.biome(pos.getX(), pos.getY(), pos.getZ());
            case "getNoiseBiome", "getUncachedNoiseBiome":
                return terrain.biome(QuartPos.toBlock((int) args[0]), QuartPos.toBlock((int) args[1]),
                        QuartPos.toBlock((int) args[2]));
            case "registryAccess":
                return registries;
            case "enabledFeatures":
                return FeatureFlags.DEFAULT_FLAGS;
            case "dimensionType":
                return dimensionType;
            case "getRandom":
                return RandomSource.create();
            case "isClientSide":
                return false;
            case "getSkyDarken":
                return 0;
            case "nextSubTickCount":
                return 0L;
            case "getBlockEntity":
                return count == 1 ? null : Optional.empty();
            case "getEntities", "players", "getEntitiesOfClass":
                return List.of();
            case "scheduleTick", "blockUpdated", "updateNeighborsAt", "gameEvent", "playSound", "levelEvent",
                 "addParticle", "setCurrentlyGenerating":
                return null;
            case "ensureCanWrite":
                return true;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            case "toString":
                return "VirtualLevel";
            default:
                break;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        if (REPORTED.putIfAbsent(name, Boolean.TRUE) == null) {
            Fatamorgana.LOGGER.debug("A feature called unsupported {} on the virtual level", method);
        }
        throw new UnsupportedOperationException("Virtual level does not support " + name);
    }
}
