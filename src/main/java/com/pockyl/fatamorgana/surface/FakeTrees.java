package com.pockyl.fatamorgana.surface;

import net.minecraft.util.Mth;

import org.jetbrains.annotations.Nullable;

/**
 * Deterministic stand-ins for trees: no features are generated, a hash of the position decides where canopies are.
 * <p>
 * Close LOD columns (a few blocks wide) get round canopies with a trunk and an air gap below, so single trees read
 * as trees. Wide columns only get a leaf block of the right height with the biome's canopy coverage as probability,
 * which averages out to the right shade of forest from afar.
 */
public final class FakeTrees {
    /** Columns up to this width get individual tree shapes. */
    private static final int SHAPED_MAX_SPACING = 4;

    private FakeTrees() {
    }

    /**
     * Canopy over a column, heights in blocks above the ground.
     *
     * @param trunk whether the column is the trunk of a tree (log under the leaves instead of air)
     */
    public record Canopy(BiomeLook.Tree tree, boolean trunk, int leavesBottom, int leavesTop) {
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
            return new Canopy(tree, false, 0, height(tree, hash & 0xFFFFFF));
        }
        return shaped(seed, x, z, tree, look.treeCoverage());
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
                int distanceSq = (x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ);
                if (distanceSq > tree.radius() * tree.radius()) {
                    continue;
                }
                int top = height(tree, (hash >>> 16) & 0xFFFFFF);
                if (best == null || top > best.leavesTop()) {
                    // Spruce-like tall trees have leaves almost down to the ground, round trees a crown on top.
                    int crown = tree.maxHeight() > 12 ? top * 3 / 4 : Math.min(top - 1, Mth.ceil(tree.radius() * 2));
                    best = new Canopy(tree, distanceSq == 0, top - crown, top);
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

    private static long hash(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
