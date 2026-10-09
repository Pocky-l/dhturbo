package com.pockyl.dhturbo.surface;

import net.minecraft.core.QuartPos;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * Rewrites a wired density function for single-column sampling.
 * <p>
 * Vanilla only honours the cache markers ({@code flat_cache}, {@code cache_2d}, ...) inside a {@code NoiseChunk}; evaluated
 * point by point they are plain pass-throughs, so every height probe would recompute continentalness, erosion, the
 * terrain splines and so on. This visitor replaces the 2D markers with caches keyed by the column, which makes every
 * probe after the first one in a column pay for the 3D noises only. Other markers and holder indirections are
 * stripped. The result holds mutable caches and must be confined to one thread.
 */
final class ColumnCachingVisitor implements DensityFunction.Visitor {
    private static final KeyDispatchDataCodec<? extends DensityFunction> FLAT_CACHE =
            DensityFunctions.flatCache(DensityFunctions.zero()).codec();
    private static final KeyDispatchDataCodec<? extends DensityFunction> CACHE_2D =
            DensityFunctions.cache2d(DensityFunctions.zero()).codec();

    @Override
    public DensityFunction apply(DensityFunction function) {
        if (function instanceof DensityFunctions.HolderHolder holder) {
            return holder.function().value();
        }
        if (function instanceof DensityFunctions.MarkerOrMarked marker) {
            KeyDispatchDataCodec<? extends DensityFunction> codec = marker.codec();
            if (codec == FLAT_CACHE) {
                return new FlatColumnCache(marker.wrapped());
            }
            if (codec == CACHE_2D) {
                return new ColumnCache(marker.wrapped());
            }
            return marker.wrapped();
        }
        return function;
    }

    /** Caches the value of the last column; the wrapped function must not depend on Y. */
    private static final class ColumnCache implements DensityFunction {
        private final DensityFunction wrapped;
        private int lastX = Integer.MIN_VALUE;
        private int lastZ = Integer.MIN_VALUE;
        private double value;

        ColumnCache(DensityFunction wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public double compute(FunctionContext context) {
            int x = context.blockX();
            int z = context.blockZ();
            if (x != lastX || z != lastZ) {
                value = wrapped.compute(context);
                lastX = x;
                lastZ = z;
            }
            return value;
        }

        @Override
        public void fillArray(double[] array, ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(array, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new ColumnCache(wrapped.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return wrapped.minValue();
        }

        @Override
        public double maxValue() {
            return wrapped.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Column caches are not serializable");
        }
    }

    /**
     * Same as vanilla's {@code flat_cache} inside a noise chunk: the value is sampled at Y=0 on the corner of the
     * 4x4 quart the column belongs to, so results match real generation exactly.
     */
    private static final class FlatColumnCache implements DensityFunction {
        private final DensityFunction wrapped;
        private int lastQuartX = Integer.MIN_VALUE;
        private int lastQuartZ = Integer.MIN_VALUE;
        private double value;

        FlatColumnCache(DensityFunction wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public double compute(FunctionContext context) {
            int quartX = QuartPos.fromBlock(context.blockX());
            int quartZ = QuartPos.fromBlock(context.blockZ());
            if (quartX != lastQuartX || quartZ != lastQuartZ) {
                value = wrapped.compute(new SinglePointContext(QuartPos.toBlock(quartX), 0, QuartPos.toBlock(quartZ)));
                lastQuartX = quartX;
                lastQuartZ = quartZ;
            }
            return value;
        }

        @Override
        public void fillArray(double[] array, ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(array, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FlatColumnCache(wrapped.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return wrapped.minValue();
        }

        @Override
        public double maxValue() {
            return wrapped.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Column caches are not serializable");
        }
    }
}
