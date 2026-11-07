package com.pockyl.dhturbo.surface;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Removes caves from a terrain density function, leaving the solid "shape" of the land.
 * <p>
 * Cave networks are registered density functions under a {@code caves/} path (vanilla: {@code overworld/caves/...};
 * datapacks usually follow it). They only ever cut air into terrain, so replacing each with a large constant turns
 * every {@code min(terrain, cave)} into plain terrain. That is both cheaper (cave noises are the most expensive 3D
 * part) and better looking from afar: cave mouths otherwise show up as random pits in the distant surface.
 */
final class CaveStripper implements DfRewriteRule {
    private static final DensityFunction SOLID = new Solid();
    private static final CaveStripper INSTANCE = new CaveStripper();

    private CaveStripper() {
    }

    /** Returns {@code density} without caves, or {@code density} itself when it uses no registered cave function. */
    static DensityFunction strip(DensityFunction density) {
        return INSTANCE.rewrite(density);
    }

    @Override
    public DensityFunction rewrite(DensityFunction function) {
        if (function instanceof DensityFunctions.HolderHolder(Holder<DensityFunction> holder) && isCave(holder)) {
            return SOLID;
        }
        return function.rewriteChildren(this);
    }

    private static boolean isCave(Holder<DensityFunction> holder) {
        return holder.unwrapKey().filter(key -> key.identifier().getPath().contains("caves/")).isPresent();
    }

    /**
     * Always solid. Declares an unbounded range: with a constant's exact range vanilla logs a warning and drops the
     * other side for every {@code min}/{@code max} whose inputs cannot overlap, which is the whole point here.
     */
    private static final class Solid implements DensityFunction, DensitySampler {
        private static final float VALUE = 64.0F;

        @Override
        public DensitySampler compileSampler(CompileContext context) {
            return this;
        }

        @Override
        public DensityFunction rewriteChildren(DfRewriteRule rule) {
            return this;
        }

        @Override
        public Interval range() {
            return Interval.INFINITE;
        }

        @Override
        public int domainAxes() {
            return NO_AXES;
        }

        @Override
        public MapCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Cave placeholders are not serializable");
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            outputBuffer.fill(VALUE);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            return VALUE;
        }
    }
}
