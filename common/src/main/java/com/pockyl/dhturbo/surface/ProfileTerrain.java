package com.pockyl.dhturbo.surface;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Terrain of worlds that are not a height field: the Nether's caverns under a ceiling, the End's floating islands. Every
 * block of a column is decided like chunk generation decides it: the density is computed on the cell corners at every
 * cell level and interpolated between them (trilinearly, as vanilla does), then finished by the rest of the final
 * density ({@link SurfaceSampler#finalDensity}). The result is a solid / open profile per column; fluids and the
 * surface rules come later.
 * <p>
 * Thread-safe: no state besides the sampler's per-thread caches.
 */
public final class ProfileTerrain {
    private final SurfaceSampler sampler;
    private final int minY;
    private final int height;
    private final int cellWidth;
    private final int cellHeight;
    /** Cell levels from the bottom to the top of the world, both ends included. */
    private final int levels;

    public ProfileTerrain(SurfaceSampler sampler) {
        this.sampler = sampler;
        this.minY = sampler.minY();
        this.height = sampler.maxY() - sampler.minY();
        this.cellWidth = sampler.cellWidth();
        this.cellHeight = sampler.cellHeight();
        this.levels = height / cellHeight + 1;
    }

    public int minY() {
        return minY;
    }

    public int height() {
        return height;
    }

    /** The interpolated value at every cell level of a corner column. */
    double[] corner(int x, int z) {
        double[] values = new double[levels];
        for (int k = 0; k < levels; k++) {
            values[k] = sampler.realDensity(x, minY + k * cellHeight, z);
        }
        return values;
    }

    /**
     * Solid blocks of the column at (x, z), from the bottom of the world up, between four corner profiles
     * ({@code fx}, {@code fz} = position between them).
     */
    boolean[] column(int x, int z, double[] c00, double[] c10, double[] c01, double[] c11, double fx, double fz) {
        boolean[] solid = new boolean[height];
        for (int k = 0; k + 1 < levels; k++) {
            double low = bilinear(c00[k], c10[k], c01[k], c11[k], fx, fz);
            double high = bilinear(c00[k + 1], c10[k + 1], c01[k + 1], c11[k + 1], fx, fz);
            for (int dy = 0; dy < cellHeight; dy++) {
                int y = k * cellHeight + dy;
                if (y >= height) {
                    break;
                }
                double value = low + (high - low) * dy / cellHeight;
                solid[y] = sampler.finalDensity(value, x, minY + y, z) > 0;
            }
        }
        return solid;
    }

    private static double bilinear(double c00, double c10, double c01, double c11, double fx, double fz) {
        double north = c00 + (c10 - c00) * fx;
        double south = c01 + (c11 - c01) * fx;
        return north + (south - north) * fz;
    }

    /**
     * Profiles of a tile of {@code width} x {@code width} columns {@code spacing} blocks apart. Corners are shared
     * between columns; rows run in parallel on a fork-join worker.
     */
    public boolean[][] tile(int minX, int minZ, int spacing, int width) {
        boolean[][] columns = new boolean[width * width][];
        boolean onCorners = spacing % cellWidth == 0 && Math.floorMod(minX, cellWidth) == 0
                && Math.floorMod(minZ, cellWidth) == 0;
        if (onCorners) {
            // Every column stands on a corner: its own profile is all it needs.
            List<Runnable> rows = new ArrayList<>();
            for (int j = 0; j < width; j++) {
                int row = j;
                rows.add(() -> {
                    for (int i = 0; i < width; i++) {
                        int x = minX + i * spacing;
                        int z = minZ + row * spacing;
                        double[] corner = corner(x, z);
                        columns[i + row * width] = column(x, z, corner, corner, corner, corner, 0, 0);
                    }
                });
            }
            SurfaceTile.run(rows);
            return columns;
        }
        int cornerMinX = Math.floorDiv(minX, cellWidth) * cellWidth;
        int cornerMinZ = Math.floorDiv(minZ, cellWidth) * cellWidth;
        int cornersX = (minX + (width - 1) * spacing - cornerMinX) / cellWidth + 2;
        int cornersZ = (minZ + (width - 1) * spacing - cornerMinZ) / cellWidth + 2;
        double[][] corners = new double[cornersX * cornersZ][];
        List<Runnable> cornerRows = new ArrayList<>();
        for (int cz = 0; cz < cornersZ; cz++) {
            int row = cz;
            cornerRows.add(() -> {
                for (int cx = 0; cx < cornersX; cx++) {
                    corners[cx + row * cornersX] = corner(cornerMinX + cx * cellWidth, cornerMinZ + row * cellWidth);
                }
            });
        }
        SurfaceTile.run(cornerRows);
        List<Runnable> rows = new ArrayList<>();
        for (int j = 0; j < width; j++) {
            int row = j;
            rows.add(() -> {
                for (int i = 0; i < width; i++) {
                    int x = minX + i * spacing;
                    int z = minZ + row * spacing;
                    int cx = (x - cornerMinX) / cellWidth;
                    int cz = (z - cornerMinZ) / cellWidth;
                    double fx = (double) (x - cornerMinX - cx * cellWidth) / cellWidth;
                    double fz = (double) (z - cornerMinZ - cz * cellWidth) / cellWidth;
                    columns[i + row * width] = column(x, z, corners[cx + cz * cornersX], corners[cx + 1 + cz * cornersX],
                            corners[cx + (cz + 1) * cornersX], corners[cx + 1 + (cz + 1) * cornersX], fx, fz);
                }
            });
        }
        SurfaceTile.run(rows);
        return columns;
    }

    /** Profiles of single columns on demand (structures), with the corners they share cached. Not thread-safe. */
    public final class Columns {
        private final Long2ObjectOpenHashMap<double[]> corners = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<boolean[]> columns = new Long2ObjectOpenHashMap<>();

        public boolean[] at(int x, int z) {
            return columns.computeIfAbsent(ChunkPos.asLong(x, z), key -> {
                int x0 = Math.floorDiv(x, cellWidth) * cellWidth;
                int z0 = Math.floorDiv(z, cellWidth) * cellWidth;
                double fx = (double) (x - x0) / cellWidth;
                double fz = (double) (z - z0) / cellWidth;
                return column(x, z, cornerAt(x0, z0), cornerAt(x0 + cellWidth, z0), cornerAt(x0, z0 + cellWidth),
                        cornerAt(x0 + cellWidth, z0 + cellWidth), fx, fz);
            });
        }

        private double[] cornerAt(int x, int z) {
            return corners.computeIfAbsent(ChunkPos.asLong(x, z), key -> corner(x, z));
        }

        /** First open Y above the highest solid block of the column, or the bottom of the world. */
        public int top(int x, int z) {
            boolean[] solid = at(x, z);
            for (int y = solid.length - 1; y >= 0; y--) {
                if (solid[y]) {
                    return minY + y + 1;
                }
            }
            return minY;
        }

        public boolean solid(int x, int y, int z) {
            int index = y - minY;
            return index >= 0 && index < height && at(x, z)[index];
        }
    }

    public Columns columns() {
        return new Columns();
    }
}
