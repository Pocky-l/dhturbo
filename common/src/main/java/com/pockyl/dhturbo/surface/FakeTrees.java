package com.pockyl.dhturbo.surface;

import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Deterministic stand-ins for trees: no features are generated, a hash of the position decides where crowns are.
 * <p>
 * Close LOD columns (a few blocks wide) get crowns shaped like the species ({@link BiomeLook.Shape}) with air below,
 * so single trees read as trees. Wide columns get one crown with the biome's canopy coverage as probability, which
 * averages out to the right shade of forest from afar.
 */
public final class FakeTrees {
    /** Columns up to this width get individual tree shapes. */
    public static final int SHAPED_MAX_SPACING = 4;
    /** Columns up to this width show trunks: a trunk fills its whole column, wider ones would make it a wall. */
    public static final int TRUNK_MAX_SPACING = 2;

    private FakeTrees() {
    }

    /** A run of one block over a column, in blocks above the ground ({@code to} is exclusive). */
    public record Span(BlockState block, int from, int to) {
    }

    /** What trees put over a column: runs of blocks, lowest first, with air between them. */
    public record Canopy(List<Span> spans) {
        int top() {
            return spans.getLast().to();
        }

        public boolean higherThan(@Nullable Canopy other) {
            return other == null || top() > other.top();
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
            return crown(tree, height(tree, hash & 0xFFFFFF), 0, tree.radius(), false);
        }
        return shaped(seed, x, z, tree, look.treeCoverage(), spacing <= TRUNK_MAX_SPACING);
    }

    /**
     * The part of a crown above a column {@code distance} blocks from the trunk, or null when the column is outside.
     *
     * @param height height of the tree top above the ground
     * @param trunks whether a column at the middle of the crown shows the trunk
     */
    @Nullable
    private static Canopy crown(BiomeLook.Tree tree, int height, double distance, double radius, boolean trunks) {
        boolean trunk = trunks && distance < 0.75;
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
        Span leaves = new Span(tree.leaves(), bottom, top);
        return new Canopy(trunk ? List.of(new Span(tree.log(), 0, bottom), leaves) : List.of(leaves));
    }

    @Nullable
    private static Canopy shaped(long seed, int x, int z, BiomeLook.Tree tree, double coverage, boolean trunks) {
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
                Canopy canopy = crown(tree, height(tree, (hash >>> 16) & 0xFFFFFF), distance, tree.radius(), trunks);
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
