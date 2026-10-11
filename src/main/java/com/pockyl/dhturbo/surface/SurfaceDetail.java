package com.pockyl.dhturbo.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Blocks of a land column beyond the biome's default: snow by temperature and height, bare rock on steep mountain
 * slopes, terracotta bands in badlands, and the layers under the top block. Approximates vanilla surface rules from
 * what the LOD tile knows (height, slope).
 * <p>
 * Distant Horizons draws the sides of a column where its neighbour is lower, so the layers matter: a ravine shows a
 * grass rim, a little dirt and stone walls, like the real one.
 */
public final class SurfaceDetail {
    /**
     * Height change per horizontal block from which a slope counts as steep. Vanilla's {@code steep} surface
     * condition fires at 4 blocks over 2 (slope 2); LOD heights are interpolated and smoother, so a bit lower.
     * Vanilla only uses it in snowy mountains (snowy slopes, frozen and jagged peaks): everywhere else a steep slope
     * keeps its grass on top and shows stone only on the walls.
     */
    public static final double STEEP = 1.4;
    /** Blocks of the layer under the top block (vanilla's surface depth is 3 to 6). */
    public static final int SUBSURFACE_DEPTH = 3;
    /** Badlands columns get bands this many blocks down from the top; deeper they are plain terracotta. */
    public static final int BAND_DEPTH = 24;
    /** Badlands keep red sand on low flat ground and show their bands above. */
    private static final int BADLANDS_BANDS_FROM_Y = 80;
    private static final Block[] BANDS = {
            Blocks.TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.TERRACOTTA, Blocks.YELLOW_TERRACOTTA,
            Blocks.TERRACOTTA, Blocks.BROWN_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.RED_TERRACOTTA,
            Blocks.TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.ORANGE_TERRACOTTA
    };

    private SurfaceDetail() {
    }

    public static BlockState top(Holder<Biome> biome, BiomeLook look, long seed, int x, int y, int z, double slope) {
        boolean steep = slope >= STEEP;
        if (biome.is(BiomeTags.IS_BADLANDS) && (steep || y >= BADLANDS_BANDS_FROM_Y)) {
            return band(seed, y).defaultBlockState();
        }
        if (steep && biome.is(Biomes.FROZEN_PEAKS)) {
            return Blocks.PACKED_ICE.defaultBlockState();
        }
        if (steep && (biome.is(Biomes.SNOWY_SLOPES) || biome.is(Biomes.JAGGED_PEAKS))) {
            return Blocks.STONE.defaultBlockState();
        }
        if (biome.value().coldEnoughToSnow(new BlockPos(x, y, z))) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        return look.top();
    }

    /**
     * Block of the layer under {@code top} (the dirt under grass); {@code stone} is the level's default block, used
     * when the top block is rock already.
     */
    public static BlockState subsurface(BlockState top, BiomeLook look, BlockState stone) {
        Block block = top.getBlock();
        if (block == Blocks.SNOW_BLOCK) {
            // Snow lies on the biome's own ground; on snowy peaks that is snow again.
            block = look.top().getBlock();
            if (block == Blocks.SNOW_BLOCK) {
                return top;
            }
        }
        if (block == Blocks.GRASS_BLOCK || block == Blocks.PODZOL || block == Blocks.MYCELIUM
                || block == Blocks.COARSE_DIRT || block == Blocks.DIRT) {
            return Blocks.DIRT.defaultBlockState();
        }
        if (block == Blocks.SAND) {
            return Blocks.SANDSTONE.defaultBlockState();
        }
        if (block == Blocks.RED_SAND) {
            return Blocks.TERRACOTTA.defaultBlockState();
        }
        if (block == Blocks.GRAVEL || block == Blocks.MUD || block == Blocks.SANDSTONE || block == Blocks.RED_SANDSTONE) {
            return top.getBlock() == block ? top : block.defaultBlockState();
        }
        return stone;
    }

    /** Horizontal clay bands: a fixed pattern shifted by the seed, two blocks per band. */
    public static Block band(long seed, int y) {
        int offset = (int) Math.floorMod(seed, BANDS.length);
        return BANDS[Math.floorMod(Math.floorDiv(y, 2) + offset, BANDS.length)];
    }
}
