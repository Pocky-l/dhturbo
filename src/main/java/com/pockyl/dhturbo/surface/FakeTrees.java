package com.pockyl.dhturbo.surface;

import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * Deterministic stand-ins for trees: no features are generated, a hash of the position decides where crowns are.
 * <p>
 * Close LOD columns (a few blocks wide) get crowns shaped like the species ({@link BiomeLook.Shape}) with a trunk and
 * air below, so single trees read as trees. Wide columns only get a leaf block of the right height with the biome's
 * canopy coverage as probability, which averages out to the right shade of forest from afar.
 */
public final class FakeTrees {
    /** Columns up to this width get individual tree shapes. */
    public static final int SHAPED_MAX_SPACING = 4;

    private FakeTrees() {
    }

    /**
     * Leaves over a column, heights in blocks above the ground.
     *
     * @param trunk whether the column holds the trunk ({@code log} under the leaves instead of air)
     */
    public record Canopy(BlockState leaves, BlockState log, boolean trunk, int leavesBottom, int leavesTop) {
        public boolean higherThan(@Nullable Canopy other) {
            return other == null || leavesTop > other.leavesTop;
        }
    }

    @Nullable
    public static Canopy at(long seed, int x, int z, int spacing, BiomeLook look) {
        BiomeLook.Tree tree = look.tree();
        if (tree == null || look.treeCoverage() <= 0) {
            return null;
        }
        if (spacing > SHAPED_MAX_SPACING) {
            long hash = hash(seed, x, z);
            if (unit(hash) >= look.treeCoverage()) {
                return null;
            }
            return new Canopy(tree.leaves(), tree.log(), false, 0, height(tree, hash & 0xFFFFFF));
        }
        return shaped(seed, x, z, tree, look.treeCoverage());
    }

    /**
     * The part of a crown above a column {@code distance} blocks from the trunk, or null when the column is outside.
     *
     * @param height height of the tree top above the ground
     */
    @Nullable
    public static Canopy crown(BiomeLook.Tree tree, int height, double distance, double radius) {
        boolean trunk = distance < 0.75;
        if (distance > radius) {
            return null;
        }
        double edge = Math.sqrt(Math.max(0, 1 - (distance / radius) * (distance / radius)));
        int bottom;
        int top;
        switch (tree.shape()) {
            case CONE -> {
                bottom = Math.max(1, height / 4);
                top = height - (int) Math.round(distance / radius * (height - bottom));
            }
            case UMBRELLA -> {
                bottom = height - 2;
                top = height;
            }
            case DOME -> {
                int crown = Math.max(3, Mth.ceil(radius * 1.2));
                bottom = height - crown;
                top = bottom + Math.max(1, (int) Math.round(crown * edge));
            }
            default -> {
                int crown = Math.max(3, Mth.ceil(radius * 1.6));
                int center = height - crown / 2;
                int half = Math.max(1, (int) Math.round(crown / 2.0 * edge));
                bottom = center - half;
                top = center + half;
            }
        }
        bottom = Math.max(1, bottom);
        if (top <= bottom) {
            if (!trunk) {
                return null;
            }
            top = bottom + 1;
        }
        return new Canopy(tree.leaves(), tree.log(), trunk, bottom, top);
    }

    @Nullable
    private static Canopy shaped(long seed, int x, int z, BiomeLook.Tree tree, double coverage) {
        int cell = Math.max(4, Mth.ceil(tree.radius() * 2) + 1);
        // Probability of a tree per cell that gives the requested canopy coverage.
        double chance = Math.min(1, coverage * cell * cell / (Math.PI * tree.radius() * tree.radius()));
        int cellX = Math.floorDiv(x, cell);
        int cellZ = Math.floorDiv(z, cell);
        Canopy best = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long hash = hash(seed, cellX + dx, cellZ + dz);
                if (unit(hash) >= chance) {
                    continue;
                }
                int centerX = (cellX + dx) * cell + (int) (hash & 0xFF) % cell;
                int centerZ = (cellZ + dz) * cell + (int) ((hash >>> 8) & 0xFF) % cell;
                double distance = Math.sqrt((double) (x - centerX) * (x - centerX) + (double) (z - centerZ) * (z - centerZ));
                Canopy canopy = crown(tree, height(tree, (hash >>> 16) & 0xFFFFFF), distance, tree.radius());
                if (canopy != null && canopy.higherThan(best)) {
                    best = canopy;
                }
            }
        }
        return best;
    }

    private static int height(BiomeLook.Tree tree, long bits) {
        return tree.minHeight() + (int) (Long.remainderUnsigned(bits, tree.maxHeight() - tree.minHeight() + 1));
    }

    private static double unit(long hash) {
        // Top bits: independent from the low bits used for positions and heights.
        return (hash >>> 40) / (double) (1L << 24);
    }

    static long hash(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
